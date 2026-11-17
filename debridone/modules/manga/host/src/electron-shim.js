'use strict';
/*
 * A stand-in for the one Electron object Tiyo touches.
 *
 * Houdoku gives Tiyo a hidden Electron BrowserWindow ("spoof window"). Tiyo's
 * src/util/webview.js uses exactly this much of it:
 *
 *   window.webContents.removeAllListeners('did-finish-load')
 *   window.webContents.on('did-finish-load', handler)
 *   window.webContents.setBackgroundThrottling(bool)
 *   window.loadURL(url, { httpReferrer, userAgent, extraHeaders, postData })
 *   window.webContents.session.cookies.get({})
 *   window.webContents.getTitle() / getURL()
 *   window.webContents.executeJavaScript('document.body.innerHTML')
 *   window.webContents.executeJavaScript('document.body.innerHTML = ""')
 *   window.webContents.stop()
 *
 * This object offers the same surface, but a page "load" is an ordinary HTTP request: the answer is
 * parsed (with the jsdom copy that ships inside the Tiyo package) and no page script runs. That is
 * enough for sources whose pages are plain HTML. It is not a browser: a site that answers with a
 * browser check ("Just a moment...") is reported as such — nothing here tries to get past it.
 */

const { EventEmitter } = require('events');
const path = require('path');

const USER_AGENT = 'Mozilla/5.0 (compatible; DebridOne manga reader; no page scripts)';
const LOAD_TIMEOUT_MS = 30000;
const MAX_PAGE_BYTES = 16 * 1024 * 1024;

class ShimError extends Error {
  constructor(code, message) {
    super(message);
    this.code = code;
  }
}

function parseExtraHeaders(text) {
  const headers = {};
  if (!text) return headers;
  for (const line of String(text).split(/\r?\n/)) {
    const index = line.indexOf(':');
    if (index > 0) headers[line.slice(0, index).trim()] = line.slice(index + 1).trim();
  }
  return headers;
}

function createSpoofWindow({ packageDir }) {
  const events = new EventEmitter();
  let jsdom = null;
  const cookies = [];
  let page = { url: 'about:blank', title: '', body: '' };
  /** Rejects the load that is running now, so a failed request ends the caller's wait. */
  let rejectCurrent = null;
  let queue = Promise.resolve();

  function parser() {
    if (!jsdom) {
      // Tiyo depends on jsdom, so the installed package always has it.
      jsdom = require(path.join(packageDir, 'node_modules', 'jsdom'));
    }
    return jsdom;
  }

  function cookieHeader(url) {
    const host = new URL(url).hostname;
    return cookies
      .filter((cookie) => host === cookie.domain || host.endsWith(`.${cookie.domain}`))
      .map((cookie) => `${cookie.name}=${cookie.value}`)
      .join('; ');
  }

  function rememberCookies(url, response) {
    const host = new URL(url).hostname;
    const lines = typeof response.headers.getSetCookie === 'function' ? response.headers.getSetCookie() : [];
    for (const line of lines) {
      const first = line.split(';')[0];
      const index = first.indexOf('=');
      if (index <= 0) continue;
      const name = first.slice(0, index).trim();
      const value = first.slice(index + 1).trim();
      const existing = cookies.findIndex((cookie) => cookie.name === name && cookie.domain === host);
      if (existing >= 0) cookies.splice(existing, 1);
      cookies.push({ name, value, domain: host });
    }
  }

  async function loadURL(url, options = {}) {
    const reject = rejectCurrent;
    try {
      const target = new URL(url);
      if (target.protocol !== 'http:' && target.protocol !== 'https:') {
        throw new ShimError('BAD_ADDRESS', `Only web addresses can be loaded, not ${target.protocol}`);
      }
      const headers = { 'User-Agent': options.userAgent || USER_AGENT, Accept: 'text/html,application/xhtml+xml,*/*;q=0.8' };
      if (options.httpReferrer) {
        headers.Referer = typeof options.httpReferrer === 'string' ? options.httpReferrer : options.httpReferrer.url;
      }
      Object.assign(headers, parseExtraHeaders(options.extraHeaders));
      const cookie = cookieHeader(url);
      if (cookie) headers.Cookie = cookie;

      const request = { method: 'GET', headers, redirect: 'follow', signal: AbortSignal.timeout(LOAD_TIMEOUT_MS) };
      const raw = (options.postData || []).filter((part) => part && part.type === 'rawData' && part.bytes);
      if (raw.length > 0) {
        request.method = 'POST';
        request.body = Buffer.concat(raw.map((part) => Buffer.from(part.bytes)));
      } else if ((options.postData || []).length > 0) {
        throw new ShimError('UNSUPPORTED', 'This source uploads a file through the browser window, which is not supported.');
      }

      const response = await fetch(url, request);
      rememberCookies(response.url || url, response);
      const buffer = Buffer.from(await response.arrayBuffer());
      if (buffer.length > MAX_PAGE_BYTES) throw new ShimError('TOO_LARGE', 'The page is too large to load.');
      const html = buffer.toString('utf8');
      const { JSDOM } = parser();
      const document = new JSDOM(html, { url: response.url || url }).window.document;
      const title = document.title || '';
      const challenged =
        response.headers.get('cf-mitigated') === 'challenge' ||
        /just a moment\.\.\./i.test(title) ||
        (response.status === 403 && /challenge-platform|cf-chl|captcha/i.test(html));
      if (challenged) {
        throw new ShimError(
          'BROWSER_CHECK',
          `${target.hostname} answered with a browser check. This source needs a real browser window, which DebridOne does not provide.`,
        );
      }
      if (!response.ok) {
        throw new ShimError('HTTP_ERROR', `${target.hostname} answered ${response.status} ${response.statusText}`.trim());
      }
      page = { url: response.url || url, title, body: document.body ? document.body.innerHTML : '' };
      events.emit('did-finish-load');
    } catch (err) {
      let failure = err;
      if (err && (err.name === 'TimeoutError' || err.name === 'AbortError')) {
        failure = new ShimError('TIMEOUT', `The page did not answer within ${LOAD_TIMEOUT_MS / 1000} seconds.`);
      } else if (!(err instanceof ShimError)) {
        const cause = err && err.cause ? err.cause : null;
        failure = new ShimError('NETWORK', `Could not load the page: ${(cause && (cause.code || cause.message)) || err.message}`);
      }
      // Tiyo does not look at loadURL's result, so tell the waiting caller directly.
      if (reject) reject(failure);
      else throw failure;
    }
  }

  const webContents = {
    on: (event, listener) => {
      events.on(event, listener);
      return webContents;
    },
    once: (event, listener) => {
      events.once(event, listener);
      return webContents;
    },
    removeListener: (event, listener) => {
      events.removeListener(event, listener);
      return webContents;
    },
    removeAllListeners: (event) => {
      events.removeAllListeners(event);
      return webContents;
    },
    setBackgroundThrottling: () => {},
    stop: () => {},
    getURL: () => page.url,
    getTitle: () => page.title,
    isLoading: () => false,
    session: {
      cookies: {
        get: async () => cookies.map((cookie) => ({ ...cookie })),
      },
    },
    executeJavaScript: async (code) => {
      const source = String(code).trim().replace(/;+$/, '');
      if (source === 'document.body.innerHTML') return page.body;
      if (/^document\.body\.innerHTML\s*=\s*(""|'')$/.test(source)) {
        page = { ...page, body: '' };
        return '';
      }
      if (source === 'document.title') return page.title;
      if (source === 'location.href' || source === 'window.location.href') return page.url;
      throw new ShimError('UNSUPPORTED', 'This source runs a script in the browser window, which is not supported.');
    },
  };

  return {
    webContents,
    loadURL,
    isDestroyed: () => false,
    close: () => {},
    destroy: () => {},
    show: () => {},
    hide: () => {},

    /** Runs one page load at a time and lets [loadURL] end it early with an error. */
    guardedLoad(start) {
      const run = () =>
        new Promise((resolve, reject) => {
          rejectCurrent = reject;
          Promise.resolve()
            .then(start)
            .then(resolve, reject);
        }).finally(() => {
          rejectCurrent = null;
        });
      const result = queue.then(run, run);
      queue = result.catch(() => {});
      return result;
    },
  };
}

module.exports = { createSpoofWindow };
