// Fetches addon and subtitle files for the web page. CORS doesn't apply in the
// main process, but the same safety rules as the web app do: http(s) only, no
// credentials in URLs, private-network hosts refused unless the caller allows
// them (self-hosted addons), redirects re-checked, and a hard size limit.

const net = require('node:net');

const MAX_REDIRECTS = 4;
const SAFE_HEADERS = new Set(['accept', 'accept-language', 'user-agent']);

function hostBlocked(hostname) {
  const h = String(hostname).toLowerCase().replace(/\.$/, '');
  const bare = h.replace(/^\[|\]$/g, '');
  if (net.isIPv6(bare)) return true;
  if (net.isIPv4(bare)) {
    const [a, b] = bare.split('.').map(Number);
    return a === 10 || a === 127 || a === 0 || a >= 224 ||
      (a === 172 && b >= 16 && b <= 31) || (a === 192 && b === 168) ||
      (a === 169 && b === 254) || (a === 100 && b >= 64 && b <= 127);
  }
  return h === 'localhost' || /\.(localhost|local|lan|home|internal|intranet)$/.test(h) || !h.includes('.');
}

function pickHeaders(input) {
  const out = {};
  for (const [k, v] of Object.entries(input || {})) {
    const key = k.toLowerCase();
    if (SAFE_HEADERS.has(key) && typeof v === 'string' && v.length < 200) out[key] = v;
  }
  return out;
}

async function fetchText({ url, headers, timeoutMs = 14_000, maxBytes = 6_000_000, allowLocal = false }) {
  let current = new URL(String(url));
  for (let hop = 0; hop <= MAX_REDIRECTS; hop++) {
    if (current.protocol !== 'http:' && current.protocol !== 'https:') {
      throw new Error('Only http and https addresses can be requested.');
    }
    if (current.username || current.password) throw new Error('Addresses with embedded credentials are refused.');
    if (!allowLocal && hostBlocked(current.hostname)) {
      throw new Error('Local and private-network addresses are not allowed.');
    }
    const res = await fetch(current, {
      redirect: 'manual',
      headers: { Accept: 'application/json, text/plain, */*', ...pickHeaders(headers) },
      signal: AbortSignal.timeout(timeoutMs),
    });
    const location = res.headers.get('location');
    if (res.status >= 300 && res.status < 400 && location) {
      await res.body?.cancel();
      current = new URL(location, current);
      continue;
    }
    const declared = Number(res.headers.get('content-length')) || 0;
    if (declared > maxBytes) {
      await res.body?.cancel();
      throw new Error('The response is too large.');
    }
    const chunks = [];
    let total = 0;
    const reader = res.body.getReader();
    for (;;) {
      const { done, value } = await reader.read();
      if (done) break;
      total += value.length;
      if (total > maxBytes) {
        await reader.cancel();
        throw new Error('The response is too large.');
      }
      chunks.push(value);
    }
    return {
      ok: res.ok,
      status: res.status,
      contentType: res.headers.get('content-type') || '',
      text: Buffer.concat(chunks).toString('utf8'),
    };
  }
  throw new Error('Too many redirects.');
}

module.exports = { fetchText, hostBlocked };
