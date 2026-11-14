// Saves files to the Downloads folder with live progress, cancel and a
// "Show in Finder" action. Files are written to a .part file first, so an
// interrupted transfer never leaves a half-written file under the real name.

const { app, shell } = require('electron');
const fs = require('node:fs');
const path = require('node:path');

const jobs = new Map(); // id -> { controller, path }

function safeName(name) {
  const cleaned = String(name || 'download')
    .replace(/[\\/:*?"<>|\u0000-\u001f]/g, '_')
    .replace(/^\.+/, '')
    .trim()
    .slice(0, 200);
  return cleaned || 'download';
}

function uniquePath(dir, name) {
  const safe = safeName(name);
  const ext = path.extname(safe);
  const base = safe.slice(0, safe.length - ext.length);
  let candidate = path.join(dir, safe);
  let n = 1;
  while (fs.existsSync(candidate) || fs.existsSync(candidate + '.part')) {
    candidate = path.join(dir, `${base} (${n++})${ext}`);
  }
  return candidate;
}

function send(sender, message) {
  if (sender && !sender.isDestroyed()) sender.send('dl:event', message);
}

async function start(sender, { id, url, fileName }) {
  const parsed = new URL(String(url));
  if (parsed.protocol !== 'http:' && parsed.protocol !== 'https:') throw new Error('Only http and https links can be downloaded.');
  if (parsed.username || parsed.password) throw new Error('Links with embedded credentials are refused.');

  const dir = app.getPath('downloads');
  const dest = uniquePath(dir, fileName);
  const part = dest + '.part';
  const controller = new AbortController();
  jobs.set(id, { controller, path: dest });

  (async () => {
    try {
      const res = await fetch(parsed, { signal: controller.signal, redirect: 'follow', headers: { Accept: '*/*' } });
      if (!res.ok) throw new Error(`The server answered HTTP ${res.status}.`);
      const total = Number(res.headers.get('content-length')) || 0;
      const out = fs.createWriteStream(part);
      const reader = res.body.getReader();
      let received = 0;
      let lastTick = Date.now();
      let lastBytes = 0;
      let speed = 0;
      for (;;) {
        const { done, value } = await reader.read();
        if (done) break;
        if (!out.write(value)) await new Promise((resolve) => out.once('drain', resolve));
        received += value.length;
        const now = Date.now();
        if (now - lastTick >= 250) {
          speed = ((received - lastBytes) * 1000) / (now - lastTick);
          lastTick = now;
          lastBytes = received;
          send(sender, { id, type: 'progress', received, total, speed });
        }
      }
      await new Promise((resolve, reject) => out.end((err) => (err ? reject(err) : resolve())));
      fs.renameSync(part, dest);
      send(sender, { id, type: 'done', path: dest, received, total: total || received });
    } catch (e) {
      fs.rmSync(part, { force: true });
      if (controller.signal.aborted) send(sender, { id, type: 'canceled' });
      else send(sender, { id, type: 'error', message: e.message || 'The download failed.' });
    } finally {
      jobs.delete(id);
    }
  })();

  return { id, path: dest };
}

function cancel(id) {
  jobs.get(id)?.controller.abort();
}

function reveal(filePath) {
  const dir = path.resolve(app.getPath('downloads'));
  const target = path.resolve(String(filePath));
  if (!target.startsWith(dir + path.sep)) throw new Error('Only files in your Downloads folder can be shown.');
  shell.showItemInFolder(target);
}

module.exports = { start, cancel, reveal };
