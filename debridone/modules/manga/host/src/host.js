'use strict';
/*
 * DebridOne Manga extension host.
 *
 * Runs the unmodified Tiyo package (https://github.com/xgi/tiyo, MIT) — the extension library the
 * Houdoku manga reader (https://github.com/xgi/houdoku, MIT) loads — and serves its sources to the
 * DebridOne app.
 *
 * It mirrors what Houdoku's main process does (apps/desktop/src/main/services/extension.ts):
 *   aki.list(pluginsDir)  ->  aki.load(pluginsDir, '@tiyo/core', require)  ->  new TiyoClient(window)
 * with two differences that matter outside Electron:
 *   - `window` is a small stand-in for Electron's BrowserWindow (see electron-shim.js);
 *   - errors are returned to the caller instead of being swallowed into empty results.
 *
 * Transport: one JSON document per line on stdin (requests) and stdout (answers, each prefixed with
 * PREFIX). No socket is opened. Anything an extension prints goes to stderr, never into the protocol.
 * The process exits when stdin closes, so it cannot outlive the app.
 */

const fs = require('fs');
const path = require('path');
const readline = require('readline');

const PREFIX = '@@DO1@@ ';
const HOST_VERSION = '1.0.0';
const TIYO_PACKAGE = '@tiyo/core';
const LEGACY_PACKAGE = /^@houdoku\/extension-[a-z0-9-]+$/;

// ---- stdout belongs to the protocol -------------------------------------------------------------

const protocolWrite = process.stdout.write.bind(process.stdout);
process.stdout.write = (chunk, encoding, callback) => process.stderr.write(chunk, encoding, callback);

function formatArg(value) {
  if (typeof value === 'string') return value;
  if (value instanceof Error) return value.stack || value.message;
  try {
    return JSON.stringify(value);
  } catch (_err) {
    return String(value);
  }
}
for (const level of ['log', 'info', 'debug', 'warn', 'error', 'trace']) {
  console[level] = (...args) => {
    process.stderr.write(`[${level}] ${args.map(formatArg).join(' ')}\n`);
  };
}

function send(message) {
  protocolWrite(PREFIX + JSON.stringify(message) + '\n');
}

class HostError extends Error {
  constructor(code, message, details) {
    super(message);
    this.code = code;
    this.details = details;
  }
}

// ---- arguments ------------------------------------------------------------------------------------

function argument(name) {
  const index = process.argv.indexOf(`--${name}`);
  return index >= 0 && index + 1 < process.argv.length ? process.argv[index + 1] : null;
}

const pluginsDir = argument('plugins');
const spoolDir = argument('spool');
if (!pluginsDir || !spoolDir) {
  process.stderr.write('usage: host.js --plugins <dir> --spool <dir>\n');
  process.exit(64);
}

const aki = require('aki-plugin-manager');
const akiVersion = require('aki-plugin-manager/package.json').version;
const { createSpoofWindow } = require('./electron-shim');
const { resolveInstallPlan } = require('./resolver');
const { loadLegacyExtension } = require('./legacy-adapter');

// ---- loaded state ---------------------------------------------------------------------------------

/** @type {null | {packageVersion: string, version: string, client: any, extensions: Map<string, any>, window: any}} */
let tiyo = null;
/** Legacy per-source packages, keyed by extension id. */
const legacy = new Map();
/** Everything aki finds in the plugin folder, with what this host makes of it. */
let packages = [];

function listPackages() {
  let installed = [];
  try {
    installed = aki.list(pluginsDir);
  } catch (err) {
    console.warn('Could not list the plugin folder:', err);
  }
  return installed.map(([name, version]) => ({
    name,
    version,
    kind: name === TIYO_PACKAGE ? 'tiyo' : LEGACY_PACKAGE.test(name) ? 'legacy' : 'unsupported',
  }));
}

function unloadAll() {
  if (tiyo) {
    try {
      aki.unload(pluginsDir, TIYO_PACKAGE, require);
    } catch (err) {
      console.warn('Could not unload Tiyo:', err);
    }
    tiyo = null;
  }
  for (const entry of legacy.values()) {
    try {
      aki.unload(pluginsDir, entry.packageName, require);
    } catch (err) {
      console.warn(`Could not unload ${entry.packageName}:`, err);
    }
  }
  legacy.clear();
}

/** Which of Tiyo's sources ask for a browser window. Read from the package's own files; null when unknown. */
function browserWindowUse(packageDir) {
  const result = new Map();
  try {
    const extensionsDir = path.join(packageDir, 'src', 'extensions');
    const genericDir = path.join(packageDir, 'src', 'generic');
    const usesWindow = (file) => /\bwebviewFn\(|\bwebviewFunc\(/.test(fs.readFileSync(file, 'utf8'));
    for (const name of fs.readdirSync(extensionsDir)) {
      const index = path.join(extensionsDir, name, 'index.js');
      if (!fs.existsSync(index)) continue;
      const text = fs.readFileSync(index, 'utf8');
      let uses = /\bwebviewFn\(|\bwebviewFunc\(/.test(text);
      for (const match of text.matchAll(/require\("\.\.\/\.\.\/generic\/([^"]+)"\)/g)) {
        const base = path.join(genericDir, match[1]);
        const candidates = [`${base}.js`, path.join(base, 'index.js'), path.join(base, `${path.basename(base)}.js`)];
        for (const candidate of candidates) {
          if (fs.existsSync(candidate) && usesWindow(candidate)) uses = true;
        }
      }
      const idMatch = fs.existsSync(path.join(extensionsDir, name, 'metadata.js'))
        ? /id:\s*['"]([^'"]+)['"]/.exec(fs.readFileSync(path.join(extensionsDir, name, 'metadata.js'), 'utf8'))
        : null;
      if (idMatch) result.set(idMatch[1], uses);
    }
  } catch (err) {
    console.warn('Could not inspect the package for browser-window use:', err);
  }
  return result;
}

function describeExtension(id, entry, origin, usesBrowserWindow) {
  const { metadata, client } = entry;
  let settingTypes = {};
  let settings = {};
  let filterCount = 0;
  const problems = [];
  try {
    settingTypes = client.getSettingTypes() || {};
  } catch (err) {
    problems.push(`getSettingTypes: ${err.message}`);
  }
  try {
    settings = client.getSettings() || {};
  } catch (err) {
    problems.push(`getSettings: ${err.message}`);
  }
  try {
    filterCount = (client.getFilterOptions() || []).length;
  } catch (err) {
    problems.push(`getFilterOptions: ${err.message}`);
  }
  const missing = REQUIRED_FUNCTIONS.filter((fn) => typeof client[fn] !== 'function');
  return {
    id,
    name: String(metadata.name || id),
    url: String(metadata.url || ''),
    translatedLanguage: metadata.translatedLanguage || null,
    notice: metadata.notice || null,
    origin,
    settingTypes,
    settings,
    filterCount,
    usesBrowserWindow: usesBrowserWindow === undefined ? null : usesBrowserWindow,
    missingFunctions: missing,
    problems,
  };
}

const REQUIRED_FUNCTIONS = [
  'getSeries',
  'getChapters',
  'getPageRequesterData',
  'getPageUrls',
  'getImage',
  'getSearch',
  'getDirectory',
  'getSettingTypes',
  'getSettings',
  'setSettings',
  'getFilterOptions',
];

function loadPlugins(disabled = []) {
  unloadAll();
  packages = listPackages();
  const errors = [];
  // A package the person has switched off is not loaded at all: none of its code runs.
  for (const pkg of packages) {
    if (disabled.includes(pkg.name)) pkg.disabled = true;
  }

  const tiyoPackage = packages.find((pkg) => pkg.kind === 'tiyo' && !pkg.disabled);
  if (tiyoPackage) {
    try {
      const packageDir = path.join(pluginsDir, TIYO_PACKAGE);
      const mod = aki.load(pluginsDir, TIYO_PACKAGE, require);
      if (!mod || typeof mod.TiyoClient !== 'function') {
        throw new HostError('INCOMPATIBLE', 'This package does not export TiyoClient, so it is not a Tiyo extension package.');
      }
      const window = createSpoofWindow({ packageDir });
      const client = new mod.TiyoClient(window);
      // Tiyo hands this function to every source. Wrapping it here lets one page load finish before
      // the next starts (there is one window) and lets a failed load reject instead of hanging.
      if (typeof client._webviewFn === 'function') {
        const original = client._webviewFn;
        client._webviewFn = (url, options) => window.guardedLoad(() => original(url, options));
      }
      // getExtensions() builds new source clients every time it is called; keep one set.
      const extensionMap = client.getExtensions();
      const windowUse = browserWindowUse(packageDir);
      const extensions = new Map();
      for (const [id, entry] of Object.entries(extensionMap)) {
        if (!entry || !entry.metadata || !entry.client) continue;
        extensions.set(id, { ...entry, origin: TIYO_PACKAGE, usesBrowserWindow: windowUse.get(id) });
      }
      if (extensions.size === 0) {
        throw new HostError('INCOMPATIBLE', 'The package loaded but registered no sources.');
      }
      tiyo = { packageVersion: tiyoPackage.version, version: String(client.getVersion()), client, extensions, window };
      tiyoPackage.loaded = true;
      tiyoPackage.sourceCount = extensions.size;
    } catch (err) {
      tiyoPackage.loaded = false;
      tiyoPackage.error = err.message;
      errors.push({ package: TIYO_PACKAGE, code: err.code || 'LOAD_FAILED', message: err.message });
      console.error('Tiyo failed to load:', err);
      tiyo = null;
    }
  }

  for (const pkg of packages.filter((candidate) => candidate.kind === 'legacy' && !candidate.disabled)) {
    try {
      const entry = loadLegacyExtension({ aki, pluginsDir, packageName: pkg.name, requireFn: require, tiyoDir: path.join(pluginsDir, TIYO_PACKAGE) });
      legacy.set(entry.metadata.id, { ...entry, origin: pkg.name, packageName: pkg.name, usesBrowserWindow: null });
      pkg.loaded = true;
      pkg.sourceCount = 1;
      if (tiyo && tiyo.extensions.has(entry.metadata.id)) pkg.shadowedBy = TIYO_PACKAGE;
    } catch (err) {
      pkg.loaded = false;
      pkg.error = err.message;
      errors.push({ package: pkg.name, code: err.code || 'LOAD_FAILED', message: err.message });
      console.error(`${pkg.name} failed to load:`, err);
    }
  }

  return describeState(errors);
}

function allExtensions() {
  const all = new Map();
  if (tiyo) for (const [id, entry] of tiyo.extensions) all.set(id, entry);
  // A source that Tiyo also provides is taken from Tiyo; the legacy copy stays loaded but unused.
  for (const [id, entry] of legacy) if (!all.has(id)) all.set(id, entry);
  return all;
}

function describeState(errors = []) {
  const extensions = [];
  for (const [id, entry] of allExtensions()) {
    extensions.push(describeExtension(id, entry, entry.origin, entry.usesBrowserWindow));
  }
  return {
    packages,
    tiyoVersion: tiyo ? tiyo.version : null,
    extensions,
    errors,
  };
}

function extension(extensionId) {
  const entry = allExtensions().get(extensionId);
  if (!entry) {
    throw new HostError('UNKNOWN_SOURCE', `No loaded source has the id ${extensionId}.`);
  }
  return entry;
}

// ---- calls ----------------------------------------------------------------------------------------

const CALLABLE = new Set([
  'getSeries',
  'getChapters',
  'getPageRequesterData',
  'getPageUrls',
  'getSearch',
  'getDirectory',
  'getSettingTypes',
  'getSettings',
  'setSettings',
  'getFilterOptions',
]);

let spoolCounter = 0;

const methods = {
  async hello() {
    return {
      host: HOST_VERSION,
      node: process.version,
      aki: akiVersion,
      platform: process.platform,
      arch: process.arch,
      restricted: Boolean(process.permission),
      pid: process.pid,
    };
  },

  async ping() {
    return { ok: true };
  },

  async 'plugins.list'() {
    return listPackages();
  },

  async 'plugins.load'({ disabled }) {
    return loadPlugins(Array.isArray(disabled) ? disabled.map(String) : []);
  },

  async 'plugins.unload'() {
    unloadAll();
    packages = listPackages();
    return describeState();
  },

  async 'plugins.state'() {
    return describeState();
  },

  async 'plugins.resolve'({ name, version, registry }) {
    return resolveInstallPlan({ name, version, registry });
  },

  async 'ext.call'({ extensionId, fn, args }) {
    if (!CALLABLE.has(fn)) throw new HostError('BAD_REQUEST', `${fn} is not a source operation.`);
    const entry = extension(extensionId);
    if (typeof entry.client[fn] !== 'function') {
      throw new HostError('UNSUPPORTED', `This source does not implement ${fn}.`);
    }
    const result = await entry.client[fn](...(Array.isArray(args) ? args : []));
    return result === undefined ? null : result;
  },

  /** Page list of one chapter: Tiyo's two steps (requester data, then addresses) in one round trip. */
  async 'ext.pages'({ extensionId, seriesSourceId, chapterSourceId }) {
    const entry = extension(extensionId);
    const requesterData = await entry.client.getPageRequesterData(seriesSourceId, chapterSourceId);
    if (!requesterData) throw new HostError('NO_PAGES', 'The source returned no page information for this chapter.');
    const urls = entry.client.getPageUrls(requesterData) || [];
    return { requesterData, urls: urls.map(String) };
  },

  /**
   * One image. Tiyo sources answer either with an address the app should fetch itself, or with the
   * bytes (when the source needs its own headers or login). Bytes are handed over as a file in the
   * spool folder, the only place this process may write.
   */
  async 'ext.image'({ extensionId, series, url }) {
    const entry = extension(extensionId);
    const data = await entry.client.getImage(series, url);
    if (typeof data === 'string') {
      if (!data) throw new HostError('NO_IMAGE', 'The source returned no image for this page.');
      return { kind: 'url', url: data };
    }
    if (data === null || data === undefined) throw new HostError('NO_IMAGE', 'The source returned no image for this page.');
    const bytes = Buffer.isBuffer(data) ? data : Buffer.from(data);
    if (bytes.length === 0) throw new HostError('NO_IMAGE', 'The source returned an empty image.');
    spoolCounter += 1;
    const file = path.join(spoolDir, `image-${process.pid}-${Date.now()}-${spoolCounter}.bin`);
    fs.writeFileSync(file, bytes);
    return { kind: 'file', path: file, size: bytes.length };
  },

  async shutdown() {
    setImmediate(() => process.exit(0));
    return { ok: true };
  },

  // Test hooks: only reachable when the app starts the host with DEBRIDONE_MANGA_HOST_TEST=1.
  async 'test.crash'() {
    requireTestMode();
    setImmediate(() => process.exit(3));
    return { ok: true };
  },
  async 'test.hang'() {
    requireTestMode();
    return new Promise(() => {});
  },
  /** Loads one page through the browser-window stand-in, exactly as a Tiyo source would. */
  async 'test.webview'({ url, options }) {
    requireTestMode();
    if (!tiyo) throw new HostError('NOT_INSTALLED', 'Tiyo is not loaded.');
    return tiyo.client._webviewFn(url, options);
  },
  async 'test.noise'() {
    requireTestMode();
    process.stdout.write('this line is not part of the protocol\n');
    console.log('neither is this one');
    return { ok: true };
  },
};

function requireTestMode() {
  if (process.env.DEBRIDONE_MANGA_HOST_TEST !== '1') throw new HostError('BAD_REQUEST', 'Not available.');
}

const SYSTEM_CODE = /^(E[A-Z_]+|UND_[A-Z_]+)$/;

/**
 * Turns whatever an extension threw into something the app can explain:
 *   NETWORK            the site could not be reached
 *   TIMEOUT            it did not answer in time
 *   UNEXPECTED_ANSWER  it answered, but not with what the extension expects (the site changed)
 *   BROWSER_CHECK      it wants a real browser window
 *   SOURCE_ERROR       anything else raised by the extension
 */
function describeError(err) {
  const cause = err && err.cause ? err.cause : null;
  const systemCode = [err && err.code, cause && cause.code].find((value) => typeof value === 'string' && SYSTEM_CODE.test(value)) || null;
  let code = err && typeof err.code === 'string' && !SYSTEM_CODE.test(err.code) ? err.code : 'SOURCE_ERROR';
  let message = String((err && err.message) || err || 'Unknown error');
  if (code === 'SOURCE_ERROR') {
    const name = err && err.name;
    if (name === 'AbortError' || name === 'TimeoutError') code = 'TIMEOUT';
    else if (name === 'SyntaxError' || (err && err.type === 'invalid-json')) code = 'UNEXPECTED_ANSWER';
    else if (systemCode || name === 'FetchError' || /fetch failed|socket hang up|network/i.test(message)) code = 'NETWORK';
    else if (name === 'TypeError') code = 'UNEXPECTED_ANSWER';
  }
  if (cause && cause.message && !message.includes(cause.message)) message += `: ${cause.message}`;
  return {
    code,
    message,
    network: systemCode,
    stack: err && err.stack ? String(err.stack).split('\n').slice(0, 8).join('\n') : null,
  };
}

async function handle(line) {
  let request;
  try {
    request = JSON.parse(line);
  } catch (_err) {
    console.warn('Ignored a request that is not JSON.');
    return;
  }
  const { id, method, params } = request;
  const fn = Object.prototype.hasOwnProperty.call(methods, method) ? methods[method] : null;
  if (!fn) {
    send({ id, ok: false, error: { code: 'BAD_REQUEST', message: `Unknown method ${method}` } });
    return;
  }
  try {
    const result = await fn(params || {});
    send({ id, ok: true, result: result === undefined ? null : result });
  } catch (err) {
    send({ id, ok: false, error: describeError(err) });
  }
}

process.on('uncaughtException', (err) => {
  console.error('Uncaught error in an extension:', err);
});
process.on('unhandledRejection', (reason) => {
  console.error('Unhandled rejection in an extension:', reason);
});

const input = readline.createInterface({ input: process.stdin, crlfDelay: Infinity });
input.on('line', (line) => {
  if (line.trim()) handle(line);
});
input.on('close', () => process.exit(0));

send({ event: 'ready', host: HOST_VERSION, node: process.version, restricted: Boolean(process.permission) });
