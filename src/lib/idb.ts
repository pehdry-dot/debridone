import { clear, del, get, keys, set } from 'idb-keyval';

/*
 * IndexedDB holds everything too large for localStorage: imported audio/video,
 * e-books, comic pages and the custom wallpaper. Nothing here leaves the browser.
 */

const urls = new Map<string, string>();

export async function putBlob(key: string, blob: Blob): Promise<void> {
  await set(key, blob);
  const old = urls.get(key);
  if (old) { URL.revokeObjectURL(old); urls.delete(key); }
}
export const getBlob = (key: string) => get<Blob>(key);

export async function blobUrl(key: string): Promise<string | undefined> {
  const hit = urls.get(key);
  if (hit) return hit;
  const blob = await get<Blob>(key);
  if (!blob) return undefined;
  const url = URL.createObjectURL(blob);
  urls.set(key, url);
  return url;
}

export async function delBlobs(prefix: string): Promise<void> {
  for (const k of await keys()) {
    if (typeof k === 'string' && k.startsWith(prefix)) {
      await del(k);
      const u = urls.get(k);
      if (u) { URL.revokeObjectURL(u); urls.delete(k); }
    }
  }
}

export async function clearBlobs(): Promise<void> {
  await clear();
  urls.forEach((u) => URL.revokeObjectURL(u));
  urls.clear();
}

export async function storageEstimate(): Promise<{ usage: number; quota: number } | null> {
  if (!navigator.storage?.estimate) return null;
  const e = await navigator.storage.estimate();
  return { usage: e.usage ?? 0, quota: e.quota ?? 0 };
}
