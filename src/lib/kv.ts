import { desktop } from '../adapters/desktop';

/*
 * Storage for everything DebridOne remembers: accounts, libraries, settings,
 * progress and download records.
 *
 * In the browser this is localStorage. In the Mac app every write is also
 * mirrored into a profile file in the PC's app-data folder, which is loaded
 * before the app starts. So nothing is lost on logout, a cache clear or a
 * reinstall.
 */

let warnedFull = false;

export const kv = {
  get(key: string): string | null {
    try { return localStorage.getItem(key); } catch { return null; }
  },
  set(key: string, value: string): void {
    try {
      localStorage.setItem(key, value);
    } catch {
      if (!warnedFull) { warnedFull = true; console.warn('DebridOne: browser storage is full; recent changes may not be kept.'); }
    }
    desktop?.storage.set(key, value).catch(() => { /* the browser copy still has it */ });
  },
  remove(key: string): void {
    try { localStorage.removeItem(key); } catch { /* ignore */ }
    desktop?.storage.remove(key).catch(() => { /* ignore */ });
  },
};

/** Storage adapter for zustand's persist middleware. */
export const kvStorage = {
  getItem: (key: string) => kv.get(key),
  setItem: (key: string, value: string) => kv.set(key, value),
  removeItem: (key: string) => kv.remove(key),
};

/**
 * Desktop: copy the saved profile file into localStorage before the stores
 * read it. Keys that only exist in localStorage (from earlier versions) are
 * copied into the file, so nothing from before this version is lost.
 */
export async function bootstrapProfileStore(): Promise<void> {
  if (!desktop) return;
  const saved = await desktop.storage.loadAll();
  for (const [key, value] of Object.entries(saved)) {
    try { localStorage.setItem(key, value); } catch { /* ignore */ }
  }
  for (let i = 0; i < localStorage.length; i++) {
    const key = localStorage.key(i);
    if (!key || !key.startsWith('debridone') || key in saved) continue;
    const value = localStorage.getItem(key);
    if (value !== null) desktop.storage.set(key, value).catch(() => {});
  }
}
