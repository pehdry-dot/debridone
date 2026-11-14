import { create } from 'zustand';
import { createJSONStorage, persist } from 'zustand/middleware';
import { kvStorage } from '../lib/kv';
import { isExecutableName } from '../adapters/sources';
import { saveBlob, uid } from '../lib/utils';
import { storageKey } from './auth';
import { desktop } from '../adapters/desktop';
import { confirmDialog, toast } from './ui';
import { useStore } from './useStore';

/*
 * Download manager.
 *
 * Three kinds of job, each labelled honestly in the UI:
 *   fetch      real network download through fetch() with byte-level progress,
 *              cancel and retry. Only works for hosts that send CORS headers.
 *   generated  a real file built in the browser (WAV from the synth, EPUB,
 *              JSON export…) and handed to the browser's download prompt.
 *   browser    a link handed straight to the browser's own downloader. Used for
 *              addon links, which rarely allow in-page transfers and can be far
 *              too large to hold in memory. No progress is visible in the app.
 *   simulated  a stand-in for things only a desktop app can do (game installs,
 *              writing to a chosen folder). It animates progress and produces
 *              no file — the row always says "Simulated".
 */

export type DlStatus = 'queued' | 'downloading' | 'completed' | 'failed' | 'canceled';
export type DlKind = 'fetch' | 'generated' | 'simulated' | 'browser';

export interface DownloadItem {
  id: string;
  title: string;
  fileName: string;
  source: string;
  kind: DlKind;
  url?: string;
  size?: number;
  received: number;
  speed?: number; // bytes per second
  status: DlStatus;
  error?: string;
  createdAt: number;
  finishedAt?: number;
  itemId?: string;
  /** true while the finished file is still held in memory and can be saved again */
  inMemory?: boolean;
  /** Where the desktop app saved the file (Mac app only). */
  desktopPath?: string;
}

export interface NewDownload {
  title: string; fileName: string; source: string; kind: DlKind; url?: string; size?: number; itemId?: string;
  /** required for kind "generated" */
  generate?: () => Promise<Blob>;
}

interface DlState { items: DownloadItem[]; seeded: boolean }

const now = Date.now();
const SEED: DownloadItem[] = [
  { id: 'dl-seed-sim', title: '0 A.D. — desktop install (demo)', fileName: '0ad-0.27-installer', source: 'Open-source games index', kind: 'simulated', size: 3_500_000_000, received: 0, status: 'queued', createdAt: now - 60_000, itemId: 'game:0ad' },
  { id: 'dl-seed-fail', title: 'Unreachable mirror (sample failure)', fileName: 'sample.bin', source: 'Example community manifest · unreviewed', kind: 'fetch', url: 'https://downloads.invalid/sample.bin', received: 0, status: 'failed', error: 'No response from host. Press retry to try again.', createdAt: now - 3_600_000, finishedAt: now - 3_590_000 },
  { id: 'dl-seed-done', title: 'The DebridOne Field Guide (EPUB)', fileName: 'The DebridOne Field Guide.epub', source: 'Generated in browser', kind: 'generated', size: 6_412, received: 6_412, status: 'completed', createdAt: now - 86_400_000, finishedAt: now - 86_399_000, itemId: 'book:field-guide' },
];

export const useDownloads = create<DlState>()(
  persist(() => ({ items: [], seeded: true }) as DlState, {
    name: storageKey('debridone:downloads:v1'),
    storage: createJSONStorage(() => kvStorage),
    // Network transfers cannot survive a reload; mark them so the user can retry.
    onRehydrateStorage: () => (state) => {
      if (!state) return;
      useDownloads.setState({
        items: state.items.filter((i) => !i.id.startsWith('dl-seed-')).map((i) => {
          if (i.kind === 'simulated' && (i.status === 'downloading' || i.status === 'queued')) return { ...i, status: 'queued' as const, received: 0 };
          if (i.status === 'downloading' || i.status === 'queued') return { ...i, status: 'failed' as const, error: 'Interrupted when the page was reloaded.', inMemory: false };
          return { ...i, inMemory: false };
        }),
      });
    },
  }),
);

const blobs = new Map<string, Blob>();
const generators = new Map<string, () => Promise<Blob>>();
const aborts = new Map<string, AbortController>();

const patch = (id: string, p: Partial<DownloadItem>) => useDownloads.setState((s) => ({ items: s.items.map((i) => (i.id === id ? { ...i, ...p } : i)) }));
const find = (id: string) => useDownloads.getState().items.find((i) => i.id === id);

function finish(id: string, ok: boolean, error?: string) {
  const item = find(id);
  if (!item) return;
  const app = useStore.getState();
  if (ok) {
    if (app.settings.notify.downloads) app.notify({ title: 'Download complete', body: item.title, link: '/downloads', tone: 'success' });
    toast(item.kind === 'simulated' ? `Simulated transfer finished: ${item.title}` : item.kind === 'browser' && !desktop ? `Handed to your browser: ${item.title}` : `Downloaded ${item.fileName}`, 'success');
    if (!desktop && (item.kind === 'fetch' || item.kind === 'generated') && app.settings.downloads.autoSave) saveDownload(id);
    // Settings → Downloads → Keep history: when off, finished rows tidy themselves away.
    if (!app.settings.downloads.keepHistory) setTimeout(() => removeDownload(id), 6000);
  } else if (error) {
    if (app.settings.notify.downloads) app.notify({ title: 'Download failed', body: `${item.title} — ${error}`, link: '/downloads', tone: 'error' });
    toast(`Download failed: ${item.title}`, 'error');
  }
  pump();
}

async function run(item: DownloadItem) {
  const ctrl = new AbortController();
  aborts.set(item.id, ctrl);
  patch(item.id, { status: 'downloading', received: 0, error: undefined, speed: undefined });
  try {
    if (desktop && (item.kind === 'fetch' || item.kind === 'browser')) {
      await runDesktop(item, ctrl);
    } else if (item.kind === 'generated') {
      const gen = generators.get(item.id);
      if (!gen) throw new Error('This file is generated on demand. Start it again from the item’s page.');
      const blob = await gen();
      if (ctrl.signal.aborted) throw new DOMException('Aborted', 'AbortError');
      blobs.set(item.id, blob);
      patch(item.id, { size: blob.size, received: blob.size });
    } else if (item.kind === 'browser') {
      const a = document.createElement('a');
      a.href = item.url!; a.target = '_blank'; a.rel = 'noopener noreferrer'; a.download = item.fileName;
      document.body.appendChild(a); a.click(); a.remove();
    } else if (item.kind === 'fetch') {
      const res = await fetch(item.url!, { signal: ctrl.signal, credentials: 'omit', referrerPolicy: 'no-referrer' });
      if (!res.ok) throw new Error(`Server answered with HTTP ${res.status}.`);
      const total = Number(res.headers.get('content-length')) || item.size;
      if (total) patch(item.id, { size: total });
      const reader = res.body?.getReader();
      if (!reader) throw new Error('This browser cannot stream the response.');
      const chunks: BlobPart[] = [];
      let received = 0, lastPaint = 0, lastBytes = 0, lastTime = performance.now();
      for (;;) {
        const { done, value } = await reader.read();
        if (done) break;
        chunks.push(value);
        received += value.length;
        const t = performance.now();
        if (t - lastPaint > 140) {
          const speed = ((received - lastBytes) / (t - lastTime)) * 1000;
          lastPaint = t; lastBytes = received; lastTime = t;
          patch(item.id, { received, speed });
        }
      }
      blobs.set(item.id, new Blob(chunks, { type: res.headers.get('content-type') ?? 'application/octet-stream' }));
      patch(item.id, { received, size: received });
    } else {
      // Simulated transfer: fixed ~40 s animation scaled to the nominal size.
      const total = item.size ?? 500_000_000;
      const steps = 200;
      for (let i = 1; i <= steps; i++) {
        await new Promise((r) => setTimeout(r, 200));
        if (ctrl.signal.aborted) throw new DOMException('Aborted', 'AbortError');
        patch(item.id, { received: Math.round((total * i) / steps), speed: total / 40 });
      }
    }
    patch(item.id, { status: 'completed', finishedAt: Date.now(), speed: undefined, inMemory: !desktop && (item.kind === 'fetch' || item.kind === 'generated') });
    aborts.delete(item.id);
    finish(item.id, true);
  } catch (e) {
    aborts.delete(item.id);
    const aborted = (e as Error)?.name === 'AbortError';
    if (aborted) { patch(item.id, { status: 'canceled', finishedAt: Date.now(), speed: undefined }); pump(); return; }
    const message = e instanceof TypeError
      ? 'Network or CORS error — the host is unreachable or does not allow browsers to read this file.'
      : (e as Error)?.message || 'Unknown error.';
    patch(item.id, { status: 'failed', error: message, finishedAt: Date.now(), speed: undefined });
    finish(item.id, false, message);
  }
}

/** Start queued jobs up to the "concurrent downloads" setting. */
export function pump() {
  const max = Math.max(1, useStore.getState().settings.downloads.concurrent);
  const items = useDownloads.getState().items;
  let active = items.filter((i) => i.status === 'downloading').length;
  for (const item of [...items].reverse()) {
    if (active >= max) break;
    if (item.status === 'queued' && !aborts.has(item.id)) { active++; void run(item); }
  }
}

/** Queue a download. Executable files always need explicit, per-file consent first. */
export async function startDownload(d: NewDownload): Promise<string | null> {
  if (isExecutableName(d.fileName) || (d.url && isExecutableName(d.url))) {
    const ok = await confirmDialog({
      title: 'Download an executable file?',
      body: `“${d.fileName}” can run code on your computer. DebridOne has not reviewed or scanned it. Only continue if you trust “${d.source}”.`,
      confirmLabel: 'Download anyway', danger: true, requireCheck: 'I understand this file is unreviewed and may be unsafe',
    });
    if (!ok) return null;
  }
  const id = uid('dl-');
  if (d.generate) generators.set(id, d.generate);
  const item: DownloadItem = { id, title: d.title, fileName: d.fileName, source: d.source, kind: d.kind, url: d.url, size: d.size, received: 0, status: 'queued', createdAt: Date.now(), itemId: d.itemId };
  useDownloads.setState((s) => ({ items: [item, ...s.items] }));
  pump();
  return id;
}

export function cancelDownload(id: string) {
  const ctrl = aborts.get(id);
  if (ctrl) ctrl.abort();
  else patch(id, { status: 'canceled', finishedAt: Date.now() });
}

export function retryDownload(id: string) {
  const item = find(id);
  if (!item) return;
  blobs.delete(id);
  patch(id, { status: 'queued', received: 0, error: undefined, finishedAt: undefined, inMemory: false });
  pump();
}

export function removeDownload(id: string) {
  aborts.get(id)?.abort();
  blobs.delete(id);
  generators.delete(id);
  useDownloads.setState((s) => ({ items: s.items.filter((i) => i.id !== id) }));
}

export function clearFinished() {
  useDownloads.getState().items.filter((i) => i.status !== 'downloading' && i.status !== 'queued').forEach((i) => removeDownload(i.id));
}

/** Hand a finished file to the browser's own download prompt. */
export function saveDownload(id: string): boolean {
  const item = find(id), blob = blobs.get(id);
  if (!item || !blob) return false;
  saveBlob(blob, item.fileName);
  return true;
}

export const isActive = (i: DownloadItem) => i.status === 'downloading' || i.status === 'queued';

/* ------------------------------- desktop app -------------------------------- */

const desktopWaiters = new Map<string, { resolve: () => void; reject: (e: Error) => void }>();

/** Downloads through the Mac app: the file is saved to ~/Downloads with live progress. */
async function runDesktop(item: DownloadItem, ctrl: AbortController) {
  const d = desktop!;
  patch(item.id, { status: 'downloading', received: 0, error: undefined });
  await new Promise<void>((resolve, reject) => {
    desktopWaiters.set(item.id, { resolve, reject });
    ctrl.signal.addEventListener('abort', () => { void d.downloads.cancel(item.id); }, { once: true });
    d.downloads.start({ id: item.id, url: item.url!, fileName: item.fileName })
      .then(({ path }) => patch(item.id, { desktopPath: path }))
      .catch((e: unknown) => {
        desktopWaiters.delete(item.id);
        reject(e instanceof Error ? e : new Error(String(e)));
      });
  });
}

if (desktop) {
  desktop.downloads.onEvent((m) => {
    const waiter = desktopWaiters.get(m.id);
    switch (m.type) {
      case 'progress':
        patch(m.id, { received: m.received, size: m.total || undefined, speed: m.speed });
        break;
      case 'done':
        patch(m.id, { received: m.received, size: m.total || m.received, desktopPath: m.path });
        desktopWaiters.delete(m.id);
        waiter?.resolve();
        break;
      case 'error':
        desktopWaiters.delete(m.id);
        waiter?.reject(new Error(m.message));
        break;
      case 'canceled': {
        desktopWaiters.delete(m.id);
        const err = new Error('Aborted');
        err.name = 'AbortError';
        waiter?.reject(err);
        break;
      }
    }
  });
}
