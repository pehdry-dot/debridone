import type { Credit, Episode, MediaItem, MediaType, Season } from '../types';
import { hash, safeImageUrl } from '../lib/utils';
import { fetchText, isLocalUrl } from '../lib/net';
import { BUILTIN_ADDONS, callBuiltin } from './builtin';
import { checkSourceUrl } from './sources';

/*
 * Stremio addon protocol client.
 *
 * An addon is a web server that answers plain JSON over HTTP:
 *   {base}/manifest.json
 *   {base}/catalog/{type}/{id}[/{extra}].json    lists of titles
 *   {base}/meta/{type}/{id}.json                 full details (and episodes)
 *   {base}/stream/{type}/{id}.json               ways to play or download a title
 *   {base}/subtitles/{type}/{id}.json            subtitle files
 *
 * Nuvio uses the same addon manifests, so anything installable there by
 * manifest URL works here. What is deliberately NOT supported: plugin or
 * scraper packages that ship JavaScript. DebridOne only ever reads JSON from
 * an addon; it never downloads and runs addon code.
 *
 * Everything an addon returns is untrusted: strings are stripped and capped,
 * image URLs must be https, links must be http(s).
 */

export interface AddonCatalog { type: string; id: string; name: string; genres: string[]; search: boolean; skip: boolean; required: string[] }
export interface AddonResource { name: string; types: string[]; idPrefixes?: string[] }
export interface AddonManifest {
  id: string; name: string; version: string; description: string; logo?: string;
  types: string[]; resources: AddonResource[]; catalogs: AddonCatalog[];
  adult: boolean; p2p: boolean; configurable: boolean; configurationRequired: boolean;
}
export interface InstalledAddon {
  id: string;
  /** manifest URL without the trailing /manifest.json */
  transportUrl: string;
  manifest: AddonManifest;
  enabled: boolean;
  installedAt: number;
  status: 'unknown' | 'online' | 'offline' | 'checking';
  latency?: number;
  lastChecked?: number;
  /** published by Stremio itself and bundled as a suggestion */
  official?: boolean;
  /** placeholder manifest that should be refreshed from the network on first use */
  stub?: boolean;
  /** Built into the app: works without setup and can't be removed. */
  builtin?: boolean;
}

export type StreamKind = 'url' | 'youtube' | 'external' | 'torrent';
export interface StreamOption {
  key: string; addonId: string; addonName: string; kind: StreamKind;
  name: string; description: string; quality?: string;
  url?: string; ytId?: string; infoHash?: string; fileIdx?: number;
  notWebReady: boolean; filename?: string; size?: number;
  /** Set when the user chose to play or download this option through Real-Debrid. */
  via?: 'realdebrid';
}
export interface AddonSubtitle { id: string; lang: string; url: string; addonName: string }

/* ------------------------------ type mapping ------------------------------ */

const TYPE_MAP: Record<string, MediaType> = {
  movie: 'movie', channel: 'movie', tv: 'movie', other: 'movie', series: 'tv', anime: 'tv',
  music: 'album', album: 'album', podcast: 'album', audiobook: 'album',
  game: 'game', games: 'game', manga: 'manga', comic: 'manga', comics: 'manga', book: 'book', books: 'book', ebook: 'book',
};
export const mediaTypeOf = (stremioType: string): MediaType | undefined => TYPE_MAP[stremioType.toLowerCase()];
export const stremioTypesFor = (media: MediaType): string[] => Object.keys(TYPE_MAP).filter((k) => TYPE_MAP[k] === media);
/** The type string to send to stream/subtitle addons for an item. */
export const defaultStremioType = (media: MediaType): string => ({ movie: 'movie', tv: 'series', album: 'music', game: 'game', manga: 'manga', book: 'book' })[media];

const enc = (id: string) => encodeURIComponent(id).replace(/%/g, '~');
const dec = (id: string) => { try { return decodeURIComponent(id.replace(/~/g, '%')); } catch { return id; } };
/** Library id for a remote title. Route-safe, and independent of which addon listed it. */
export const remoteId = (type: string, id: string) => `sx:${enc(type)}:${enc(id)}`;
export const isRemoteId = (itemId: string) => itemId.startsWith('sx:');
export function parseRemoteId(itemId: string): { type: string; id: string } | null {
  const m = itemId.match(/^sx:([^:]+):(.+)$/);
  return m ? { type: dec(m[1]), id: dec(m[2]) } : null;
}

/* ------------------------------- sanitising ------------------------------- */

const str = (v: unknown, max = 300): string => (typeof v === 'string' || typeof v === 'number' ? String(v).replace(/<[^>]*>/g, '').replace(/[\u0000-\u0008\u000b-\u001f]/g, ' ').trim().slice(0, max) : '');
const list = (v: unknown, max = 40, each = 80): string[] => (Array.isArray(v) ? v.map((x) => str(x, each)).filter(Boolean).slice(0, max) : typeof v === 'string' && v ? [str(v, each)] : []);
const httpUrl = (v: unknown): string | undefined => {
  if (typeof v !== 'string' || v.length > 4000) return undefined;
  try { const u = new URL(v); return u.protocol === 'https:' || u.protocol === 'http:' ? u.href : undefined; } catch { return undefined; }
};

export function parseManifest(raw: unknown): AddonManifest {
  const m = raw as Record<string, unknown>;
  if (!m || typeof m !== 'object') throw new Error('The manifest is not a JSON object.');
  const id = str(m.id, 120), name = str(m.name, 80);
  if (!id || !name) throw new Error('The manifest is missing an id or a name.');
  if (!Array.isArray(m.resources)) throw new Error('The manifest does not list any resources.');
  const types = list(m.types, 20, 30);
  const idPrefixes = Array.isArray(m.idPrefixes) ? list(m.idPrefixes, 40, 40) : undefined;
  const resources: AddonResource[] = [];
  for (const r of m.resources.slice(0, 20)) {
    if (typeof r === 'string') resources.push({ name: str(r, 30), types, idPrefixes });
    else if (r && typeof r === 'object') {
      const o = r as Record<string, unknown>;
      if (str(o.name, 30)) resources.push({ name: str(o.name, 30), types: Array.isArray(o.types) ? list(o.types, 20, 30) : types, idPrefixes: Array.isArray(o.idPrefixes) ? list(o.idPrefixes, 40, 40) : idPrefixes });
    }
  }
  const catalogs: AddonCatalog[] = [];
  for (const c of Array.isArray(m.catalogs) ? m.catalogs.slice(0, 80) : []) {
    const o = c as Record<string, unknown>;
    const type = str(o?.type, 30), cid = str(o?.id, 120);
    if (!type || !cid) continue;
    const extra = (Array.isArray(o.extra) ? o.extra : []) as Record<string, unknown>[];
    const supported = new Set<string>([...extra.map((e) => str(e?.name, 40)), ...list(o.extraSupported, 20, 40)]);
    const required = [...new Set([...extra.filter((e) => e?.isRequired).map((e) => str(e.name, 40)), ...list(o.extraRequired, 20, 40)])];
    const genreExtra = extra.find((e) => e?.name === 'genre');
    catalogs.push({ type, id: cid, name: str(o.name, 80) || cid, genres: list(genreExtra?.options ?? o.genres, 150, 40), search: supported.has('search'), skip: supported.has('skip'), required });
  }
  const hints = (m.behaviorHints ?? {}) as Record<string, unknown>;
  return {
    id, name, version: str(m.version, 20) || '0.0.0', description: str(m.description, 400), logo: safeImageUrl(typeof m.logo === 'string' ? m.logo : undefined),
    types, resources, catalogs, adult: !!hints.adult, p2p: !!hints.p2p, configurable: !!hints.configurable, configurationRequired: !!hints.configurationRequired,
  };
}

const isLocalHost = (host: string) => /^(localhost|127\.\d+\.\d+\.\d+|10\.\d+\.\d+\.\d+|192\.168\.\d+\.\d+|172\.(1[6-9]|2\d|3[01])\.\d+\.\d+|\[::1\])$/.test(host) || /\.(local|lan|home|internal)$/.test(host);

/** Turn whatever the user pasted (https://…/manifest.json, stremio://…, or a base URL) into a transport URL. */
export function normalizeAddonUrl(input: string, allowLocal: boolean): string {
  let raw = input.trim();
  if (!raw) throw new Error('Paste an addon manifest URL.');
  raw = raw.replace(/^stremio:\/\//i, 'https://');
  if (!/^https?:\/\//i.test(raw)) raw = `https://${raw}`;
  let u: URL;
  try { u = new URL(raw); } catch { throw new Error('That is not a valid URL.'); }
  if (u.username || u.password) throw new Error('Addon URLs must not contain embedded credentials.');
  const local = isLocalHost(u.hostname);
  if (local && !allowLocal) throw new Error('Addons on localhost or a private network are blocked. Turn on “Allow local-network addons” in Settings → Integrations if this is your own self-hosted addon.');
  if (!local) { const err = checkSourceUrl(u.href); if (err) throw new Error(err); }
  u.hash = ''; u.search = '';
  return u.href.replace(/\/manifest\.json$/i, '').replace(/\/+$/, '');
}

/* -------------------------------- transport ------------------------------- */

const cache = new Map<string, { at: number; data: unknown }>();
const TTL = 5 * 60_000;

async function getJson(url: string, timeoutMs = 14_000, useCache = true): Promise<unknown> {
  const hit = cache.get(url);
  if (useCache && hit && Date.now() - hit.at < TTL) return hit.data;
  let r;
  try {
    r = await fetchText(url, { accept: 'application/json', timeoutMs, maxBytes: 6_000_000, allowLocal: isLocalUrl(url) });
  } catch (e) {
    const err = e as Error;
    if (/abort|timeout|timed out|too long/i.test(`${err.name} ${err.message}`)) throw new Error('Timed out');
    if (err instanceof TypeError) throw new Error('Unreachable (offline, blocked, or the addon does not allow browser access)');
    throw err;
  }
  if (!r.ok) throw new Error(`HTTP ${r.status}`);
  const data = JSON.parse(r.text);
  if (cache.size > 300) cache.delete(cache.keys().next().value as string);
  cache.set(url, { at: Date.now(), data });
  return data;
}

export async function fetchManifest(transportUrl: string): Promise<{ manifest: AddonManifest; latency: number }> {
  const t = performance.now();
  const raw = await getJson(`${transportUrl}/manifest.json`, 12_000, false);
  return { manifest: parseManifest(raw), latency: Math.round(performance.now() - t) };
}

export function supports(addon: InstalledAddon, resource: string, type: string, id?: string): boolean {
  if (!addon.enabled) return false;
  return addon.manifest.resources.some((r) => r.name === resource && r.types.includes(type) && (!id || !r.idPrefixes?.length || r.idPrefixes.some((p) => id.startsWith(p))));
}

function resourceUrl(addon: InstalledAddon, resource: string, type: string, id: string, extra?: Record<string, string | number | undefined>): string {
  const args = Object.entries(extra ?? {}).filter(([, v]) => v !== undefined && v !== '').map(([k, v]) => `${encodeURIComponent(k)}=${encodeURIComponent(String(v))}`).join('&');
  return `${addon.transportUrl}/${resource}/${encodeURIComponent(type)}/${encodeURIComponent(id)}${args ? `/${args}` : ''}.json`;
}

/* --------------------------------- mapping -------------------------------- */

function buildSeasons(videos: unknown, runtime: number): Season[] {
  if (!Array.isArray(videos)) return [];
  const bySeason = new Map<number, Episode[]>();
  for (const raw of videos.slice(0, 3000)) {
    const v = raw as Record<string, unknown>;
    const id = str(v?.id, 300);
    if (!id) continue;
    const season = Number(v.season ?? 1) || 0;
    const number = Number(v.episode ?? v.number ?? 0) || (bySeason.get(season)?.length ?? 0) + 1;
    const ep: Episode = { id, number, title: str(v.name ?? v.title, 160) || `Episode ${number}`, description: str(v.overview ?? v.description, 220), runtime, sources: [] };
    bySeason.set(season, [...(bySeason.get(season) ?? []), ep]);
  }
  return [...bySeason.entries()].sort((a, b) => (a[0] === 0 ? 1 : b[0] === 0 ? -1 : a[0] - b[0]))
    .map(([number, episodes]) => ({ number, title: number === 0 ? 'Specials' : undefined, episodes: episodes.sort((a, b) => a.number - b.number) }));
}

/** Convert an addon "meta" object (preview or full) into a library item. Returns null for unusable or unsupported entries. */
export function metaToItem(raw: unknown, fallbackType: string, addonId: string, partial: boolean): MediaItem | null {
  const m = raw as Record<string, unknown>;
  if (!m || typeof m !== 'object') return null;
  const sid = str(m.id, 300), title = str(m.name, 200);
  const sType = str(m.type, 30) || fallbackType;
  const type = mediaTypeOf(sType);
  if (!sid || !title || !type) return null;
  const released = str(m.released, 30), info = str(m.releaseInfo ?? m.year, 20);
  const rating = parseFloat(str(m.imdbRating, 6));
  const runtime = parseInt(str(m.runtime, 12), 10) || 0;
  const directors = list(m.director, 6), writers = list(m.writer, 6);
  const cast: Credit[] = list(m.cast, 16).map((name) => ({ name, role: 'Cast' }));
  const crew: Credit[] = [...directors.map((name) => ({ name, role: 'Director' })), ...writers.map((name) => ({ name, role: 'Writer' }))];
  const item: MediaItem = {
    id: remoteId(sType, sid), type, demo: false, title, description: str(m.description, 4000),
    releaseDate: /^\d{4}-\d{2}-\d{2}/.test(released) ? released.slice(0, 10) : info.match(/\d{4}/)?.[0],
    genres: list(m.genres ?? m.genre, 8, 40), tags: [], creator: directors[0] ?? writers[0], rating: rating >= 0 && rating <= 10 ? rating : undefined,
    language: str(m.language, 40) || undefined, cover: safeImageUrl(typeof m.poster === 'string' ? m.poster : undefined), banner: safeImageUrl(typeof m.background === 'string' ? m.background : undefined),
    addedAt: Date.now(), popularity: 50, palette: hash(sid) % 16, remote: { addonId, type: sType, id: sid, partial, fetchedAt: Date.now() },
  };
  if (type === 'movie') item.video = { runtime, cast, crew, sources: [] };
  else if (type === 'tv') item.tv = { seasons: buildSeasons(m.videos, runtime), cast, crew };
  else if (type === 'album') {
    const artist = str(m.artist, 120);
    const tracks = (Array.isArray(m.tracks) ? m.tracks : []).slice(0, 300).map((t: unknown, i: number) => {
      const tr = t as Record<string, unknown>;
      return { id: str(tr.id, 200) || `${sid}:${i}`, n: Number(tr.n) || i + 1, title: str(tr.title, 160) || `Track ${i + 1}`, artist: artist || 'Unknown artist', albumId: item.id, duration: Number(tr.duration) || 0, src: '' };
    });
    item.album = { kind: 'Album', tracks };
  }
  else if (type === 'game') item.game = { platforms: [], website: httpUrl(m.website) };
  else if (type === 'manga') item.manga = { chapters: [], status: 'Ongoing', direction: 'rtl' };
  else item.book = { format: 'text', contentKey: typeof m.contentKey === 'string' ? m.contentKey : '' };
  return item;
}

/* -------------------------------- resources ------------------------------- */

export const catalogKey = (addon: InstalledAddon, c: AddonCatalog) => `${addon.id}|${c.type}|${c.id}`;

/** Catalogs that can be shown as a browsable row (search-only and special-purpose catalogs are skipped). */
export function browsableCatalogs(addons: InstalledAddon[], media?: MediaType[]): { addon: InstalledAddon; catalog: AddonCatalog }[] {
  const out: { addon: InstalledAddon; catalog: AddonCatalog }[] = [];
  for (const addon of addons) {
    if (!addon.enabled) continue;
    for (const catalog of addon.manifest.catalogs) {
      const mt = mediaTypeOf(catalog.type);
      if (!mt || (media && !media.includes(mt))) continue;
      if (catalog.required.some((r) => r !== 'genre') || (catalog.required.includes('genre') && !catalog.genres.length)) continue;
      out.push({ addon, catalog });
    }
  }
  return out;
}
export const searchableCatalogs = (addons: InstalledAddon[], media?: MediaType[]) =>
  addons.filter((a) => a.enabled).flatMap((addon) => addon.manifest.catalogs.filter((c) => c.search && mediaTypeOf(c.type) && (!media || media.includes(mediaTypeOf(c.type)!))).map((catalog) => ({ addon, catalog })));

export async function getCatalog(addon: InstalledAddon, c: AddonCatalog, opts: { genre?: string; search?: string; skip?: number } = {}): Promise<MediaItem[]> {
  const genre = opts.genre ?? (c.required.includes('genre') ? c.genres[0] : undefined);
  const data = (await callResource(addon, 'catalog', c.type, c.id, { genre, search: opts.search, skip: opts.skip || undefined })) as { metas?: unknown[] };
  const seen = new Set<string>();
  return (Array.isArray(data?.metas) ? data.metas : []).slice(0, 200).map((m) => metaToItem(m, c.type, addon.id, true)).filter((i): i is MediaItem => !!i && !seen.has(i.id) && !!seen.add(i.id));
}

/** Full details from the first enabled addon that can describe this id. */
export async function getMeta(addons: InstalledAddon[], type: string, id: string): Promise<MediaItem | null> {
  for (const addon of addons.filter((a) => supports(a, 'meta', type, id))) {
    try {
      const data = (await callResource(addon, 'meta', type, id)) as { meta?: unknown };
      const item = metaToItem(data?.meta, type, addon.id, false);
      if (item) return item;
    } catch { /* try the next addon */ }
  }
  return null;
}

const QUALITY = /\b(2160p|4k|1440p|1080p|720p|480p|360p)\b/i;

function toOption(raw: unknown, addon: InstalledAddon, index: number): StreamOption | null {
  const s = raw as Record<string, unknown>;
  if (!s || typeof s !== 'object') return null;
  const hints = (s.behaviorHints ?? {}) as Record<string, unknown>;
  const name = str(s.name, 120), description = str(s.description ?? s.title, 600);
  const base = { key: `${addon.id}#${index}`, addonId: addon.id, addonName: addon.manifest.name, name: name || addon.manifest.name, description, quality: (`${name} ${description}`.match(QUALITY)?.[1] ?? undefined)?.toLowerCase().replace('4k', '2160p'), notWebReady: !!hints.notWebReady, filename: str(hints.filename, 200) || undefined, size: typeof hints.videoSize === 'number' ? hints.videoSize : undefined };
  const url = httpUrl(s.url);
  if (url) return { ...base, kind: 'url', url };
  const ytId = str(s.ytId, 20);
  if (/^[\w-]{6,20}$/.test(ytId)) return { ...base, kind: 'youtube', ytId, url: `https://www.youtube.com/watch?v=${ytId}` };
  const external = httpUrl(s.externalUrl);
  if (external) return { ...base, kind: 'external', url: external };
  const infoHash = str(s.infoHash, 64).toLowerCase();
  if (/^[a-f0-9]{40}$/.test(infoHash)) return { ...base, kind: 'torrent', infoHash, fileIdx: typeof s.fileIdx === 'number' ? s.fileIdx : undefined, notWebReady: true };
  return null;
}

export interface StreamGroup { addon: InstalledAddon; options: StreamOption[]; error?: string }

/** Resource calls go to built-in handlers for built-in addons, and to the addon's web server for everything else. */
async function callResource(addon: InstalledAddon, resource: string, type: string, id: string, extra?: Record<string, string | number | undefined>): Promise<any> {
  if (addon.transportUrl.startsWith('builtin:')) return callBuiltin(addon.id, resource, type, id, extra);
  return getJson(resourceUrl(addon, resource, type, id, extra));
}

/** Ask every enabled, capable addon how this title can be played or downloaded. Results arrive per addon via `onGroup`. */
export async function getStreams(addons: InstalledAddon[], type: string, id: string, onGroup?: (g: StreamGroup) => void): Promise<StreamGroup[]> {
  const capable = addons.filter((a) => supports(a, 'stream', type, id));
  return Promise.all(capable.map(async (addon) => {
    let group: StreamGroup;
    try {
      const data = (await callResource(addon, 'stream', type, id)) as { streams?: unknown[] };
      group = { addon, options: (Array.isArray(data?.streams) ? data.streams : []).slice(0, 120).map((s, i) => toOption(s, addon, i)).filter((o): o is StreamOption => !!o) };
    } catch (e) { group = { addon, options: [], error: (e as Error).message }; }
    onGroup?.(group);
    return group;
  }));
}

export async function getSubtitles(addons: InstalledAddon[], type: string, id: string): Promise<AddonSubtitle[]> {
  const out: AddonSubtitle[] = [];
  await Promise.all(addons.filter((a) => supports(a, 'subtitles', type, id)).map(async (addon) => {
    try {
      const data = (await callResource(addon, 'subtitles', type, id)) as { subtitles?: Record<string, unknown>[] };
      for (const s of (Array.isArray(data?.subtitles) ? data.subtitles : []).slice(0, 80)) {
        const url = httpUrl(s?.url);
        if (url && url.startsWith('https:')) out.push({ id: `${addon.id}:${str(s.id, 60) || out.length}`, lang: str(s.lang, 12) || 'und', url, addonName: addon.manifest.name });
      }
    } catch { /* subtitles are optional */ }
  }));
  return out;
}

/** SubRip → WebVTT, so the browser's native text-track engine can show addon subtitles. */
export function srtToVtt(text: string): string {
  if (/^﻿?WEBVTT/.test(text)) return text;
  return `WEBVTT\n\n${text.replace(/\r\n?/g, '\n').replace(/^﻿/, '').replace(/(\d{2}:\d{2}:\d{2}),(\d{3})/g, '$1.$2').replace(/<\/?font[^>]*>/gi, '').trim()}\n`;
}
export async function loadSubtitleAsVtt(sub: AddonSubtitle): Promise<string> {
  const r = await fetchText(sub.url, { accept: 'text/plain, */*', maxBytes: 2_000_000 });
  if (!r.ok) throw new Error(`HTTP ${r.status}`);
  return URL.createObjectURL(new Blob([srtToVtt(r.text)], { type: 'text/vtt' }));
}

export const magnetLink = (o: StreamOption) => `magnet:?xt=urn:btih:${o.infoHash}&dn=${encodeURIComponent(o.filename || o.name)}`;

/* ------------------------------- suggestions ------------------------------ */

export interface FeaturedAddon { url: string; id: string; name: string; description: string; provides: string[]; types: string[] }

/** Official addons published by Stremio. Listed as suggestions; any other addon can be installed by pasting its manifest URL. */
export const FEATURED_ADDONS: FeaturedAddon[] = [
  { url: 'https://v3-cinemeta.strem.io', id: 'com.linvo.cinemeta', name: 'Cinemeta', description: 'The official catalogue and metadata for films and series: popular, new and top-rated lists, search, cast, artwork and episode guides.', provides: ['Catalogues', 'Details', 'Search'], types: ['Movies', 'TV'] },
  { url: 'https://opensubtitles-v3.strem.io', id: 'org.stremio.opensubtitlesv3', name: 'OpenSubtitles v3', description: 'Subtitles in many languages for films and episodes, matched by title id.', provides: ['Subtitles'], types: ['Movies', 'TV'] },
  { url: 'https://watchhub.strem.io', id: 'org.stremio.watchhub', name: 'WatchHub', description: 'Shows which licensed streaming services carry a film or series and links straight to them.', provides: ['Where to watch'], types: ['Movies', 'TV'] },
  { url: 'https://caching.stremio.net/publicdomainmovies.now.sh', id: 'org.stremio.pubdomainmovies', name: 'Public Domain Movies', description: 'A catalogue of several hundred public-domain films. Its streams are torrents, which this browser app can list but not play.', provides: ['Catalogue', 'Torrent links'], types: ['Movies'] },
  { url: 'https://v3-channels.strem.io', id: 'com.linvo.stremiochannels', name: 'YouTube Channels', description: 'Browse YouTube channels and their uploads as catalogues.', provides: ['Catalogues', 'Details'], types: ['Channels'] },
];

const stub = (f: FeaturedAddon, resources: AddonResource[], catalogs: AddonCatalog[], types: string[]): InstalledAddon => ({
  id: f.id, transportUrl: f.url, enabled: true, installedAt: Date.now(), status: 'unknown', official: true, stub: true,
  manifest: { id: f.id, name: f.name, version: '', description: f.description, types, resources, catalogs, adult: false, p2p: false, configurable: false, configurationRequired: false },
});
const std = (name: string): AddonResource => ({ name, types: ['movie', 'series'], idPrefixes: ['tt'] });
const cinemetaCatalogs: AddonCatalog[] = (['movie', 'series'] as const).flatMap((type) => [
  { type, id: 'top', name: 'Popular', genres: [], search: true, skip: true, required: [] },
  { type, id: 'imdbRating', name: 'Featured', genres: [], search: false, skip: true, required: [] },
]);

/** Installed on first launch so the app has a real film and series catalogue straight away. Manifests refresh from the network on first use. */
// (built-in addons: see builtin.ts)
export const DEFAULT_ADDONS: InstalledAddon[] = [
  ...BUILTIN_ADDONS,
  stub(FEATURED_ADDONS[0], [std('catalog'), std('meta')], cinemetaCatalogs, ['movie', 'series']),
  stub(FEATURED_ADDONS[1], [std('subtitles')], [], ['movie', 'series']),
  stub(FEATURED_ADDONS[2], [std('stream')], [], ['movie', 'series']),
];
