// Profile store. Every DebridOne account, library, setting and download record
// is kept in one JSON file in the app's data folder on this PC. It survives
// logging out, clearing the web cache and reinstalling the app.

const { app } = require('electron');
const fs = require('node:fs');
const path = require('node:path');

const FILE = () => path.join(app.getPath('userData'), 'profile-store.json');
let data = null;
let timer = null;

function load() {
  if (data) return data;
  try { data = JSON.parse(fs.readFileSync(FILE(), 'utf8')); } catch { data = {}; }
  if (!data || typeof data !== 'object' || Array.isArray(data)) data = {};
  return data;
}

function save() {
  if (!data) return;
  clearTimeout(timer);
  timer = null;
  fs.mkdirSync(path.dirname(FILE()), { recursive: true });
  const tmp = FILE() + '.tmp';
  fs.writeFileSync(tmp, JSON.stringify(data));
  fs.renameSync(tmp, FILE());
}

function schedule() {
  clearTimeout(timer);
  timer = setTimeout(() => {
    try { save(); } catch (e) { console.error('profile save failed', e); }
  }, 250);
}

const keyOk = (key) => typeof key === 'string' && key.startsWith('debridone') && key.length < 200;

module.exports = {
  loadAll: () => ({ ...load() }),
  set(key, value) {
    if (!keyOk(key)) throw new Error('Not a DebridOne storage key.');
    const text = String(value);
    if (text.length > 20_000_000) throw new Error('That is too large to store.');
    load()[key] = text;
    schedule();
  },
  remove(key) {
    if (!keyOk(key)) return;
    delete load()[key];
    schedule();
  },
  flush() {
    try { if (timer) save(); } catch (e) { console.error('profile save failed', e); }
  },
};
