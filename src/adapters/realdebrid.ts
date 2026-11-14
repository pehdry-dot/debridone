/*
 * Real-Debrid, used from the Mac app.
 *
 * Every request runs in the desktop app's main process (electron/realdebrid.cjs),
 * which holds the API token in the macOS Keychain. This file describes the steps:
 * add a torrent (or reuse one already on the account), pick its main video file,
 * wait for Real-Debrid to finish, unlock the link, and then pick a version the
 * browser can play.
 *
 * Browsers can only play MP4, WebM and HLS. Matroska (.mkv), AVI and similar
 * files are played through Real-Debrid's transcoded stream (GET /streaming/transcode).
 * Endpoints follow Real-Debrid's public REST API (api.real-debrid.com/rest/1.0).
 */

import { desktop, ipc, type RdStatus, type RdUser } from './desktop';
import { magnetLink, type StreamOption } from './stremio';
import { openPlayer } from '../lib/play';

type Method = 'GET' | 'POST' | 'DELETE';

const bridge = () => {
  if (!desktop) throw new Error('Real-Debrid needs the DebridOne Mac app. Open the app to connect your account.');
  return desktop;
};
const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms));

export const rdAvailable = () => !!desktop;
export const rdStatus = (): Promise<RdStatus> => ipc(bridge().rd.status());
export const rdConnect = (token: string): Promise<RdUser> => ipc(bridge().rd.connect(token));
export const rdDisconnect = (): Promise<void> => ipc(bridge().rd.disconnect());
export const rdCall = <T = any>(method: Method, path: string, params?: Record<string, unknown>): Promise<T> =>
  ipc(bridge().rd.request(method, path, params));

export interface RdTorrent {
  id: string; filename: string; hash?: string; bytes: number; host?: string; progress: number; status: string;
  added?: string; links?: string[]; ended?: string; speed?: number; seeders?: number;
}
export interface RdFile { id: number; path: string; bytes: number; selected: number }
export interface RdTorrentInfo extends RdTorrent { files?: RdFile[] }
export interface RdUnrestricted { id: string; filename: string; filesize: number; download: string; streamable?: number; host?: string; link?: string }
export interface RdLink { id: string; filename: string; filesize: number; link: string; host: string; download: string; streamable: number; generated?: string }

/** A file that is ready: `url` is the original download, `playUrl` is a version the browser can play. */
export interface Resolved { id: string; url: string; playUrl: string; filename: string; bytes: number }
export interface ResolveOptions { fileIdx?: number; onStatus?: (text: string) => void; cancelled?: () => boolean }

export const VIDEO_EXT = /\.(mp4|mkv|avi|mov|m4v|webm|ts|mpg|mpeg|wmv)$/i;
export const WEB_FORMAT = /\.(mp4|webm|m4v|mov)$/i;
export const TERMINAL_FAILURES = ['magnet_error', 'error', 'virus', 'dead'];
export const isRdMagnet = (s: string) => /^magnet:\?/i.test(s.trim());

export const listTorrents = () => rdCall<RdTorrent[]>('GET', '/torrents', { limit: 100 });
export const listDownloads = () => rdCall<RdLink[]>('GET', '/downloads', { limit: 100 });
export const torrentInfo = (id: string) => rdCall<RdTorrentInfo>('GET', `/torrents/info/${id}`);
export const deleteTorrent = (id: string) => rdCall<null>('DELETE', `/torrents/delete/${id}`);
export const deleteDownload = (id: string) => rdCall<null>('DELETE', `/downloads/delete/${id}`);
export const unrestrict = (link: string) => rdCall<RdUnrestricted>('POST', '/unrestrict/link', { link });

/** The most likely main file: the largest video, or the largest file if there is no video. */
export function pickVideo(files: RdFile[], hint?: number): RdFile | undefined {
  const videos = files.filter((f) => VIDEO_EXT.test(f.path));
  const pool = videos.length ? videos : files;
  if (!pool.length) return undefined;
  if (hint !== undefined && hint >= 0 && hint < files.length && pool.includes(files[hint])) return files[hint];
  return pool.slice().sort((a, b) => b.bytes - a.bytes)[0];
}

/** The unrestricted link for the file that was selected for download. */
export function chosenLink(info: RdTorrentInfo): string | undefined {
  const links = info.links ?? [];
  const selected = (info.files ?? []).filter((f) => f.selected === 1);
  if (!selected.length) return links[0];
  const pick = pickVideo(selected);
  const index = pick ? selected.indexOf(pick) : 0;
  return links[index] ?? links[0];
}

const HTTP = /^https?:\/\//;
const QUALITY_ORDER = ['original', 'full', '1080p', '720p', '480p', '360p'];
function firstUrl(group: unknown): string | undefined {
  if (typeof group === 'string') return HTTP.test(group) ? group : undefined;
  if (!group || typeof group !== 'object') return undefined;
  const g = group as Record<string, unknown>;
  for (const q of QUALITY_ORDER) { const v = g[q]; if (typeof v === 'string' && HTTP.test(v)) return v; }
  for (const v of Object.values(g)) { if (typeof v === 'string' && HTTP.test(v)) return v; }
  return undefined;
}

/** A URL the browser can play: the original for MP4/WebM, otherwise Real-Debrid's transcoded stream. */
export async function playableUrl(u: { id: string; filename: string; download: string }): Promise<string> {
  if (WEB_FORMAT.test(u.filename)) return u.download;
  try {
    const transcode = await rdCall<Record<string, unknown>>('GET', `/streaming/transcode/${u.id}`);
    for (const key of ['h264WebM', 'liveMP4', 'apple'] as const) {
      const url = firstUrl(transcode?.[key]);
      if (url) return url;
    }
  } catch {
    /* no transcode available: the original is tried below */
  }
  return u.download;
}

/** Open a Real-Debrid file in the player, using a browser-playable version. */
export async function playFile(title: string, u: { id: string; filename: string; download: string }): Promise<void> {
  openPlayer(title, await playableUrl(u), 'Real-Debrid');
}

export const infoHashOf = (magnet: string): string | undefined => {
  const m = magnet.match(/xt=urn:btih:([A-Za-z0-9]{32,40})/i);
  return m ? m[1].toLowerCase() : undefined;
};

/** A torrent already on the account with the same info hash, so it isn't added twice. */
async function findExisting(hash: string): Promise<string | undefined> {
  for (let page = 1; page <= 5; page++) {
    const list = (await rdCall<RdTorrent[]>('GET', '/torrents', { limit: 100, page })) ?? [];
    const hit = list.find((t) => (t.hash ?? '').toLowerCase() === hash && !TERMINAL_FAILURES.includes(t.status));
    if (hit) return hit.id;
    if (list.length < 100) break;
  }
  return undefined;
}

function progressText(info: RdTorrentInfo): string {
  const pct = Math.round(Number(info.progress) || 0);
  switch (info.status) {
    case 'magnet_conversion': return 'Real-Debrid is reading the torrent…';
    case 'queued': return 'Waiting in the Real-Debrid queue…';
    case 'downloading': return `Real-Debrid is downloading… ${pct}%`;
    case 'compressing': return `Real-Debrid is packing the files… ${pct}%`;
    case 'uploading': return `Real-Debrid is preparing the file… ${pct}%`;
    default: return `Real-Debrid: ${info.status.replace(/_/g, ' ')}…`;
  }
}

/** Adds a magnet (or reuses the copy already on the account) and selects the main video file. Returns the torrent id. */
export async function startTorrent(magnet: string, fileIdx?: number): Promise<string> {
  const hash = infoHashOf(magnet);
  if (hash) {
    const existing = await findExisting(hash);
    if (existing) return existing;
  }
  const { id } = await rdCall<{ id: string }>('POST', '/torrents/addMagnet', { magnet });
  // Real-Debrid first converts the magnet into a file list; wait for that.
  for (let i = 0; i < 40; i++) {
    const info = await torrentInfo(id);
    if (info.status === 'waiting_files_selection') {
      const file = pickVideo(info.files ?? [], fileIdx);
      await rdCall('POST', `/torrents/selectFiles/${id}`, { files: file ? String(file.id) : 'all' });
      return id;
    }
    if (TERMINAL_FAILURES.includes(info.status)) throw new Error(`Real-Debrid could not read this torrent (${info.status.replace(/_/g, ' ')}).`);
    if (info.status !== 'magnet_conversion') return id; // already selected, queued or downloaded
    await sleep(1500);
  }
  return id; // still converting: the torrent list shows its progress
}

/** Waits until Real-Debrid has the torrent, then unlocks its main file. */
export async function waitForLink(id: string, opts: ResolveOptions = {}): Promise<Resolved> {
  const say = opts.onStatus ?? (() => {});
  const stop = opts.cancelled ?? (() => false);
  const deadline = Date.now() + 40 * 60_000;
  for (;;) {
    if (stop()) throw new Error('Canceled');
    if (Date.now() > deadline) throw new Error('Real-Debrid is still working on this torrent. Check the Torrents list later.');
    const info = await torrentInfo(id);
    if (info.status === 'downloaded') {
      const link = chosenLink(info);
      if (!link) throw new Error('Real-Debrid finished, but no link came back for the file.');
      say('Preparing the file…');
      return toResolved(await unrestrict(link));
    }
    if (TERMINAL_FAILURES.includes(info.status)) throw new Error(`Real-Debrid could not download this torrent (${info.status.replace(/_/g, ' ')}).`);
    if (info.status === 'waiting_files_selection') {
      const file = pickVideo(info.files ?? [], opts.fileIdx);
      await rdCall('POST', `/torrents/selectFiles/${id}`, { files: file ? String(file.id) : 'all' });
    } else {
      say(progressText(info));
    }
    await sleep(info.status === 'downloading' ? 2500 : 4000);
  }
}

async function toResolved(u: RdUnrestricted): Promise<Resolved> {
  return { id: u.id, url: u.download, playUrl: await playableUrl(u), filename: u.filename, bytes: u.filesize };
}

/** Sends a magnet to Real-Debrid and resolves once the file is ready. */
export async function resolveTorrent(magnet: string, opts: ResolveOptions = {}): Promise<Resolved> {
  (opts.onStatus ?? (() => {}))('Sending the torrent to Real-Debrid…');
  const id = await startTorrent(magnet, opts.fileIdx);
  return waitForLink(id, opts);
}

/** Unlocks a file-host link (or any link Real-Debrid supports). */
export async function resolveLink(link: string, opts: ResolveOptions = {}): Promise<Resolved> {
  (opts.onStatus ?? (() => {}))('Unlocking the link with Real-Debrid…');
  return toResolved(await unrestrict(link));
}

/** For a torrent already on the account that Real-Debrid has finished. */
export async function unrestrictTorrent(id: string): Promise<RdUnrestricted> {
  const info = await torrentInfo(id);
  if (info.status !== 'downloaded') throw new Error('Real-Debrid is still preparing this torrent.');
  const link = chosenLink(info);
  if (!link) throw new Error('Real-Debrid has no link for this torrent yet.');
  return unrestrict(link);
}

/** Resolve a stream option from an addon through Real-Debrid. */
export function resolveOption(o: StreamOption, opts: ResolveOptions = {}): Promise<Resolved> {
  if (o.kind === 'torrent' && o.infoHash) return resolveTorrent(magnetLink(o), { ...opts, fileIdx: o.fileIdx });
  if (o.kind === 'url' && o.url) return resolveLink(o.url, opts);
  return Promise.reject(new Error('Real-Debrid can only use direct links and torrents.'));
}
