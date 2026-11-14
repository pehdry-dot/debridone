// Real-Debrid client for the desktop app.
//
// Runs in Electron's main process, so the API token never reaches the web page
// and the browser's CORS rules don't apply (api.real-debrid.com does not allow
// browser requests). The token is encrypted with the macOS Keychain via
// safeStorage and is never written to disk in plain text.

const { app, safeStorage } = require('electron');
const fs = require('node:fs');
const path = require('node:path');

const API = 'https://api.real-debrid.com/rest/1.0';
const TOKEN_FILE = () => path.join(app.getPath('userData'), 'realdebrid.bin');

// The only endpoints the web page may reach through this client. Anything else is refused.
const ALLOWED = [
  ['GET', /^\/user$/],
  ['GET', /^\/time$/],
  ['GET', /^\/torrents$/],
  ['GET', /^\/torrents\/info\/[A-Za-z0-9]+$/],
  ['POST', /^\/torrents\/addMagnet$/],
  ['POST', /^\/torrents\/selectFiles\/[A-Za-z0-9]+$/],
  ['DELETE', /^\/torrents\/delete\/[A-Za-z0-9]+$/],
  ['GET', /^\/downloads$/],
  ['DELETE', /^\/downloads\/delete\/[A-Za-z0-9]+$/],
  ['POST', /^\/unrestrict\/link$/],
  ['GET', /^\/streaming\/transcode\/[A-Za-z0-9]+$/],
];

let memoryToken = null;

function readToken() {
  if (memoryToken !== null) return memoryToken;
  try {
    memoryToken = safeStorage.decryptString(fs.readFileSync(TOKEN_FILE()));
  } catch {
    memoryToken = '';
  }
  return memoryToken;
}

function writeToken(token) {
  if (!safeStorage.isEncryptionAvailable()) {
    throw new Error('The macOS Keychain is unavailable, so DebridOne will not store your Real-Debrid token.');
  }
  fs.mkdirSync(path.dirname(TOKEN_FILE()), { recursive: true });
  fs.writeFileSync(TOKEN_FILE(), safeStorage.encryptString(token), { mode: 0o600 });
  memoryToken = token;
}

function clearToken() {
  memoryToken = '';
  fs.rmSync(TOKEN_FILE(), { force: true });
}

function explain(status, data) {
  const code = data && typeof data === 'object' ? data.error : undefined;
  if (status === 401 || code === 'bad_token') return 'Real-Debrid rejected your token. Copy it again from real-debrid.com/apitoken.';
  if (status === 429) return 'Real-Debrid is limiting requests (250 a minute). Wait a minute and try again.';
  if (status === 403 || code === 'permission_denied') return 'Real-Debrid says this needs a premium account.';
  if (status === 404) return 'Real-Debrid could not find that item. It may have been removed.';
  if (code) return `Real-Debrid: ${String(code).replace(/_/g, ' ')} (HTTP ${status}).`;
  return `Real-Debrid answered HTTP ${status}.`;
}

async function request(method, apiPath, params = {}) {
  if (!ALLOWED.some(([m, re]) => m === method && re.test(apiPath))) {
    throw new Error('That Real-Debrid request is not allowed.');
  }
  const token = readToken();
  if (!token) throw new Error('Connect your Real-Debrid account first.');

  const entries = Object.entries(params || {})
    .filter(([, v]) => v !== undefined && v !== null && v !== '')
    .map(([k, v]) => [k, String(v)]);
  const url = new URL(API + apiPath);
  const init = {
    method,
    headers: { Authorization: `Bearer ${token}`, Accept: 'application/json' },
    signal: AbortSignal.timeout(25_000),
  };
  if (method === 'GET') {
    for (const [k, v] of entries) url.searchParams.set(k, v);
  } else if (method === 'POST') {
    init.headers['Content-Type'] = 'application/x-www-form-urlencoded';
    init.body = new URLSearchParams(entries).toString();
  }

  let res;
  try {
    res = await fetch(url, init);
  } catch (e) {
    throw new Error(e && e.name === 'TimeoutError'
      ? 'Real-Debrid took too long to answer.'
      : 'Could not reach Real-Debrid. Check your internet connection.');
  }
  if (res.status === 204) return null;
  const text = await res.text();
  let data = null;
  try { data = text ? JSON.parse(text) : null; } catch { data = null; }
  if (!res.ok) throw new Error(explain(res.status, data));
  return data;
}

function summarise(u) {
  const seconds = Number(u.premium) || 0;
  return {
    username: String(u.username || ''),
    type: String(u.type || 'free'),
    premiumDays: Math.floor(seconds / 86400),
    expires: u.expiration || undefined,
    points: Number(u.points) || 0,
    email: u.email || undefined,
  };
}

async function connect(token) {
  const t = String(token || '').trim();
  if (!/^[A-Za-z0-9_-]{16,128}$/.test(t)) {
    throw new Error('That does not look like a Real-Debrid API token. Copy the long string of letters and numbers from real-debrid.com/apitoken.');
  }
  const previous = memoryToken;
  memoryToken = t;
  try {
    const user = await request('GET', '/user');
    writeToken(t);
    return summarise(user);
  } catch (e) {
    memoryToken = previous;
    throw e;
  }
}

async function status() {
  if (!readToken()) return { connected: false };
  try {
    return { connected: true, user: summarise(await request('GET', '/user')) };
  } catch (e) {
    return { connected: true, error: e.message };
  }
}

function disconnect() {
  clearToken();
}

module.exports = { request, connect, status, disconnect };
