// DebridOne desktop app (macOS). Hosts the built web app from a private app://
// origin, routes Real-Debrid, addon and download requests through safe native
// code, and keeps the window and menus behaving like a Mac app.

const { app, BrowserWindow, Menu, protocol, session, shell, ipcMain } = require('electron');
const fs = require('node:fs/promises');
const path = require('node:path');
const realdebrid = require('./realdebrid.cjs');
const netproxy = require('./netproxy.cjs');
const downloads = require('./downloads.cjs');
const store = require('./store.cjs');

const SCHEME = 'app';
const ORIGIN = `${SCHEME}://debridone`;
const DIST = path.join(__dirname, '..', 'dist');

const MIME = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.json': 'application/json; charset=utf-8',
  '.webmanifest': 'application/manifest+json; charset=utf-8',
  '.svg': 'image/svg+xml',
  '.png': 'image/png',
  '.jpg': 'image/jpeg',
  '.jpeg': 'image/jpeg',
  '.webp': 'image/webp',
  '.woff2': 'font/woff2',
  '.vtt': 'text/vtt; charset=utf-8',
  '.ico': 'image/x-icon',
};

// Content-Security-Policy for the web app. Media, images and addon/API requests
// may come from https; scripts and the app itself only from the bundled files.
const CSP = [
  `default-src 'self' ${SCHEME}:`,
  `script-src 'self'`,
  `style-src 'self' 'unsafe-inline' https://fonts.googleapis.com`,
  `font-src 'self' data: https://fonts.gstatic.com`,
  `img-src 'self' data: blob: https: http:`,
  `media-src 'self' blob: https: http:`,
  `connect-src 'self' https: http:`,
  `frame-src 'none'`,
  `object-src 'none'`,
  `base-uri 'none'`,
].join('; ');

protocol.registerSchemesAsPrivileged([
  { scheme: SCHEME, privileges: { standard: true, secure: true, supportFetchAPI: true, corsEnabled: true, stream: true } },
]);

app.setName('DebridOne');

async function serveDist(request) {
  const url = new URL(request.url);
  const rel = decodeURIComponent(url.pathname).replace(/^\/+/, '') || 'index.html';
  let file = path.resolve(DIST, rel);
  if (file !== DIST && !file.startsWith(DIST + path.sep)) return new Response('Forbidden', { status: 403 });
  let data;
  try {
    data = await fs.readFile(file);
  } catch {
    file = path.join(DIST, 'index.html');
    data = await fs.readFile(file);
  }
  const ext = path.extname(file).toLowerCase();
  const headers = { 'content-type': MIME[ext] || 'application/octet-stream' };
  if (ext === '.html') headers['content-security-policy'] = CSP;
  return new Response(data, { headers });
}

function isAppUrl(url) {
  return typeof url === 'string' && url.startsWith(`${ORIGIN}/`);
}

function openExternalSafely(url) {
  try {
    const parsed = new URL(String(url));
    if (parsed.protocol === 'https:' || parsed.protocol === 'http:') shell.openExternal(parsed.toString());
  } catch { /* ignore malformed links */ }
}

// Only the app's own pages may call the bridge.
function trusted(event) {
  return isAppUrl(event.senderFrame?.url || event.sender?.getURL?.() || '');
}
function guard(channel, handler) {
  ipcMain.handle(channel, async (event, ...args) => {
    if (!trusted(event)) throw new Error('Not allowed.');
    return handler(event, ...args);
  });
}

function registerIpc() {
  guard('rd:status', () => realdebrid.status());
  guard('rd:connect', (_e, token) => realdebrid.connect(token));
  guard('rd:disconnect', () => realdebrid.disconnect());
  guard('rd:request', (_e, { method, path: apiPath, params }) => realdebrid.request(String(method), String(apiPath), params || {}));
  guard('net:fetchText', (_e, options) => netproxy.fetchText({
    url: options.url,
    headers: options.headers,
    timeoutMs: Math.min(Number(options.timeoutMs) || 14_000, 30_000),
    maxBytes: Math.min(Number(options.maxBytes) || 6_000_000, 12_000_000),
    allowLocal: !!options.allowLocal,
  }));
  guard('dl:start', (event, options) => downloads.start(event.sender, {
    id: String(options.id),
    url: options.url,
    fileName: options.fileName,
  }));
  guard('dl:cancel', (_e, id) => downloads.cancel(String(id)));
  guard('dl:reveal', (_e, filePath) => downloads.reveal(filePath));
  guard('app:openExternal', (_e, url) => openExternalSafely(url));
  guard('store:loadAll', () => store.loadAll());
  guard('store:set', (_e, { key, value }) => store.set(String(key), String(value)));
  guard('store:remove', (_e, key) => store.remove(String(key)));
}

function buildMenu() {
  const template = [
    ...(process.platform === 'darwin' ? [{ role: 'appMenu' }] : []),
    {
      label: 'File',
      submenu: [
        { label: 'Show Downloads', click: () => shell.openPath(app.getPath('downloads')) },
        { type: 'separator' },
        process.platform === 'darwin' ? { role: 'close' } : { role: 'quit' },
      ],
    },
    { role: 'editMenu' },
    { role: 'viewMenu' },
    { role: 'windowMenu' },
    {
      role: 'help',
      submenu: [{ label: 'Open DebridOne data folder', click: () => shell.openPath(app.getPath('userData')) }],
    },
  ];
  Menu.setApplicationMenu(Menu.buildFromTemplate(template));
}

let mainWindow = null;

function createWindow() {
  mainWindow = new BrowserWindow({
    width: 1360,
    height: 880,
    minWidth: 980,
    minHeight: 620,
    title: 'DebridOne',
    backgroundColor: '#0B0B0F',
    show: false,
    titleBarStyle: 'hiddenInset',
    trafficLightPosition: { x: 18, y: 18 },
    webPreferences: {
      preload: path.join(__dirname, 'preload.cjs'),
      contextIsolation: true,
      sandbox: true,
      nodeIntegration: false,
      webSecurity: true,
      spellcheck: false,
    },
  });

  mainWindow.once('ready-to-show', () => mainWindow.show());
  mainWindow.webContents.setWindowOpenHandler(({ url }) => {
    openExternalSafely(url);
    return { action: 'deny' };
  });
  mainWindow.webContents.on('will-navigate', (event, url) => {
    if (!isAppUrl(url)) {
      event.preventDefault();
      openExternalSafely(url);
    }
  });
  mainWindow.on('closed', () => { mainWindow = null; });
  mainWindow.loadURL(`${ORIGIN}/index.html`);
}

const gotLock = app.requestSingleInstanceLock();
if (!gotLock) {
  app.quit();
} else {
  app.on('second-instance', () => {
    if (mainWindow) {
      if (mainWindow.isMinimized()) mainWindow.restore();
      mainWindow.focus();
    }
  });

  app.whenReady().then(() => {
    protocol.handle(SCHEME, serveDist);

    // No camera, microphone, location or notification prompts from the web app.
    session.defaultSession.setPermissionRequestHandler((_wc, _permission, callback) => callback(false));
    session.defaultSession.setPermissionCheckHandler(() => false);

    registerIpc();
    buildMenu();
    createWindow();

    app.on('activate', () => {
      if (BrowserWindow.getAllWindows().length === 0) createWindow();
    });
  });

  app.on('before-quit', () => store.flush());

  app.on('window-all-closed', () => {
    if (process.platform !== 'darwin') app.quit();
  });
}
