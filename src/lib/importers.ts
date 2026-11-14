import JSZip from 'jszip';
import { toast } from '../store/ui';
import { useStore } from '../store/useStore';
import type { MediaItem, Track } from '../types';
import { parseEpub } from './books';
import { delBlobs, putBlob } from './idb';
import { hash, imageToDataUrl, pickFiles, uid } from './utils';

/*
 * Importing personal files. Everything stays on this device: the file's bytes
 * go into IndexedDB and a library entry pointing at them goes into the store.
 * Browsers cap how much a site may store, so very large files can be refused —
 * that case is reported to the user instead of failing silently.
 */

const cleanName = (name: string) => name.replace(/\.[^.]+$/, '').replace(/[_.]+/g, ' ').replace(/\s+/g, ' ').trim() || 'Untitled';

async function store(key: string, blob: Blob) {
  try { await putBlob(key, blob); }
  catch { throw new Error('Browser storage refused this file (it may be too large, or storage is full or disabled).'); }
}

function probeDuration(file: Blob, kind: 'audio' | 'video'): Promise<number> {
  return new Promise((resolve) => {
    const el = document.createElement(kind);
    const url = URL.createObjectURL(file);
    const done = (v: number) => { URL.revokeObjectURL(url); resolve(isFinite(v) ? v : 0); };
    el.preload = 'metadata';
    el.onloadedmetadata = () => done(el.duration);
    el.onerror = () => done(0);
    setTimeout(() => done(0), 8000);
    el.src = url;
  });
}

const base = (id: string, type: MediaItem['type'], title: string): MediaItem => ({
  id, type, demo: false, title, description: 'Imported from this device. Stored only in this browser.', genres: [], tags: ['imported'],
  license: 'Your file', addedAt: Date.now(), popularity: 50, palette: hash(id) % 16, language: undefined,
});

export const IMPORTS_ALBUM_ID = 'album:u-imports';

export async function importAudio(): Promise<number> {
  const files = (await pickFiles('audio/*,.mp3,.m4a,.aac,.ogg,.oga,.opus,.wav,.flac', true)).filter((f) => f.type.startsWith('audio/') || /\.(mp3|m4a|aac|og[ga]|opus|wav|flac)$/i.test(f.name));
  if (!files.length) return 0;
  const s = useStore.getState();
  const tracks: Track[] = [];
  for (const file of files) {
    const id = uid('track:u-');
    await store(`audio:${id}`, file);
    const name = cleanName(file.name);
    const [artist, title] = name.includes(' - ') ? name.split(/ - (.+)/) : ['Unknown artist', name];
    tracks.push({ id, n: 0, title: title.trim(), artist: artist.trim(), albumId: IMPORTS_ALBUM_ID, duration: Math.round(await probeDuration(file, 'audio')), src: `idb:audio:${id}` });
  }
  const existing = s.userItems.find((i) => i.id === IMPORTS_ALBUM_ID);
  if (existing) {
    s.updateUserItem(IMPORTS_ALBUM_ID, (it) => ({ ...it, album: { kind: 'Imports', tracks: [...(it.album?.tracks ?? []), ...tracks].map((t, i) => ({ ...t, n: i + 1 })) } }));
  } else {
    s.addUserItem({ ...base(IMPORTS_ALBUM_ID, 'album', 'My Imported Audio'), creator: 'Various artists', description: 'Audio files you imported from this device. Stored only in this browser.', album: { kind: 'Imports', tracks: tracks.map((t, i) => ({ ...t, n: i + 1 })) } });
  }
  toast(`Imported ${tracks.length} audio file${tracks.length === 1 ? '' : 's'}`, 'success');
  return tracks.length;
}

export async function importVideo(): Promise<string | null> {
  const [file] = await pickFiles('video/*,.mp4,.webm,.mov,.m4v,.ogv');
  if (!file) return null;
  const id = uid('movie:u-');
  await store(`video:${id}`, file);
  const minutes = (await probeDuration(file, 'video')) / 60;
  useStore.getState().addUserItem({
    ...base(id, 'movie', cleanName(file.name)), releaseDate: String(new Date().getFullYear()),
    video: { runtime: Math.max(0.1, Math.round(minutes * 10) / 10), cast: [], crew: [], sources: [{ id: 'local', label: `Imported file · ${file.name}`, url: `idb:video:${id}`, quality: 'Original', provider: 'This device', license: 'Your file', sizeMB: Math.round(file.size / 1e5) / 10 }] },
  });
  toast(`Imported “${cleanName(file.name)}”`, 'success');
  return id;
}

export async function importBook(): Promise<string | null> {
  const [file] = await pickFiles('.epub,.pdf,.txt,application/epub+zip,application/pdf,text/plain');
  if (!file) return null;
  const ext = file.name.split('.').pop()?.toLowerCase();
  const format = ext === 'epub' ? 'epub' : ext === 'pdf' ? 'pdf' : ext === 'txt' ? 'text' : null;
  if (!format) throw new Error('Unsupported format. DebridOne reads EPUB, PDF and plain-text files.');
  const id = uid('book:u-');
  const item: MediaItem = { ...base(id, 'book', cleanName(file.name)), book: { format, contentKey: `idb:book:${id}` } };
  if (format === 'epub') {
    const parsed = await parseEpub(file); // validates the file before anything is stored
    if (parsed.title) item.title = parsed.title;
    if (parsed.author) item.creator = parsed.author;
    if (parsed.cover) item.cover = await imageToDataUrl(parsed.cover, 500).catch(() => undefined);
  }
  await store(`book:${id}`, file);
  useStore.getState().addUserItem(item);
  toast(`Imported “${item.title}”`, 'success');
  return id;
}

export async function importManga(): Promise<string | null> {
  const files = await pickFiles('.cbz,.zip,image/*', true);
  if (!files.length) return null;
  const isImage = (name: string) => /\.(jpe?g|png|gif|webp|avif)$/i.test(name);
  let pages: { name: string; blob: Blob }[] = [];
  let title = cleanName(files[0].name);
  const archive = files.find((f) => /\.(cbz|zip)$/i.test(f.name));
  if (archive) {
    const zip = await JSZip.loadAsync(archive);
    const entries = Object.values(zip.files).filter((f) => !f.dir && isImage(f.name) && !f.name.startsWith('__MACOSX'));
    if (!entries.length) throw new Error('That archive does not contain any images.');
    if (entries.length > 600) throw new Error('That archive has more than 600 pages, which is too large for browser storage.');
    title = cleanName(archive.name);
    for (const e of entries) {
      const ext = e.name.split('.').pop()!.toLowerCase();
      pages.push({ name: e.name, blob: new Blob([await e.async('blob')], { type: `image/${ext === 'jpg' ? 'jpeg' : ext}` }) });
    }
  } else {
    pages = files.filter((f) => f.type.startsWith('image/') || isImage(f.name)).map((f) => ({ name: f.name, blob: f }));
    if (!pages.length) throw new Error('Choose a .cbz/.zip comic archive or a set of page images.');
    title = `Imported comic (${pages.length} pages)`;
  }
  pages.sort((a, b) => a.name.localeCompare(b.name, undefined, { numeric: true }));
  const id = uid('manga:u-');
  const slug = id.split(':')[1];
  for (let i = 0; i < pages.length; i++) await store(`manga:${slug}:ch1:${i}`, pages[i].blob);
  useStore.getState().addUserItem({
    ...base(id, 'manga', title), cover: await imageToDataUrl(pages[0].blob, 500).catch(() => undefined),
    manga: { status: 'Completed', direction: 'rtl', chapters: [{ id: `${slug}:ch1`, number: 1, title: 'Imported pages', pages: pages.length, source: 'imported' }] },
  });
  toast(`Imported “${title}” (${pages.length} pages)`, 'success');
  return id;
}

/** Wrap an importer so failures become a toast instead of an unhandled rejection. */
export async function runImport<T>(fn: () => Promise<T>): Promise<T | null> {
  try { return await fn(); }
  catch (e) { toast((e as Error).message || 'Import failed.', 'error'); return null; }
}

/** Remove a user-added item and every file it stored in IndexedDB. Bundled demo items cannot be deleted. */
export async function deleteUserItem(item: MediaItem): Promise<void> {
  if (item.type === 'album') for (const t of item.album?.tracks ?? []) await delBlobs(`audio:${t.id}`);
  else if (item.type === 'manga') await delBlobs(`manga:${item.id.split(':')[1]}:`);
  else if (item.type === 'book') await delBlobs(`book:${item.id}`);
  else await delBlobs(`video:${item.id}`);
  useStore.getState().removeUserItem(item.id);
}
