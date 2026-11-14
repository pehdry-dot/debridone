/*
 * Built-in addons.
 *
 * They need no setup and speak the same resource protocol as Stremio addons
 * (catalog, meta, stream), so the rest of the app treats them like any other
 * addon. Each one only reads public catalogues and metadata:
 *   - MusicBrainz and the Cover Art Archive (music metadata and covers)
 *   - Open Library (book metadata by search, covers)
 *   - Project Gutenberg (public-domain books, free EPUB and text downloads)
 *   - MangaDex (manga metadata only: titles, covers, popularity)
 *   - game catalogues (Steam via CheapShark, Wikidata) handled by the games page
 *
 * DebridOne does not host, index or link to unlicensed copies of music, films,
 * books or manga. Audio, video and manga pages only come from addons you add.
 */

import type { AddonCatalog, AddonManifest, AddonResource, InstalledAddon } from './stremio';
import { fetchText } from '../lib/net';

type Json = Record<string, any>;
type Extra = Record<string, string | number | undefined>;
type Handler = (resource: string, type: string, id: string, extra: Extra) => Promise<Json>;

const UA = 'DebridOne/0.1 (personal use)';
const enc = encodeURIComponent;
const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms));

async function getText(url: string, opts: { headers?: Record<string, string>; maxBytes?: number; timeoutMs?: number; accept?: string } = {}): Promise<string> {
  const r = await fetchText(url, { accept: opts.accept ?? 'application/json', headers: opts.headers, maxBytes: opts.maxBytes ?? 4_000_000, timeoutMs: opts.timeoutMs ?? 15_000 });
  if (!r.ok) throw new Error(`HTTP ${r.status}`);
  return r.text;
}
const getJson = async (url: string, opts?: { headers?: Record<string, string>; timeoutMs?: number }): Promise<Json> => JSON.parse(await getText(url, opts));

/* ------------------------------ MusicBrainz ------------------------------ */

// MusicBrainz asks for at most one request per second, so requests run in a queue.
let mbChain: Promise<unknown> = Promise.resolve();
function mbGet(path: string): Promise<Json> {
  const run = mbChain.then(() => sleep(1100)).then(() => getJson(`https://musicbrainz.org/ws/2${path}${path.includes('?') ? '&' : '?'}fmt=json`, { headers: { 'User-Agent': UA } }));
  mbChain = run.catch(() => undefined);
  return run as Promise<Json>;
}

function mbReleaseMeta(r: Json, full = false): Json {
  const artist = (r['artist-credit'] ?? []).map((a: Json) => a.name ?? a.artist?.name).filter(Boolean).join(', ');
  const tracks = full
    ? (r.media ?? []).flatMap((m: Json) => (m.tracks ?? []).map((t: Json) => ({
      id: `mb:track:${t.recording?.id ?? t.id}`, n: t.position ?? 0, title: t.title, duration: Math.round((Number(t.length) || 0) / 1000),
    })))
    : undefined;
  return {
    id: `mb:release:${r.id}`, type: 'music', name: r.title, releaseInfo: String(r.date ?? '').slice(0, 4),
    description: [artist, r.country, r.status].filter(Boolean).join(' · ') || 'Album',
    poster: `https://coverartarchive.org/release/${r.id}/front-500`, genres: [], artist, tracks,
  };
}

const musicHandler: Handler = async (resource, _type, id, extra) => {
  if (resource === 'catalog') {
    const year = new Date().getFullYear();
    const query = extra.search
      ? `release:"${String(extra.search).replace(/"/g, '')}" AND primarytype:album`
      : `primarytype:album AND status:official AND date:[${year - 1} TO ${year}]`;
    const data = await mbGet(`/release?query=${enc(query)}&limit=40`);
    return { metas: (data.releases ?? []).map((r: Json) => mbReleaseMeta(r)) };
  }
  if (resource === 'meta') {
    const r = await mbGet(`/release/${enc(id.replace(/^mb:release:/, ''))}?inc=artist-credits+recordings`);
    return { meta: mbReleaseMeta(r, true) };
  }
  return {};
};

/* ------------------------------ Open Library ------------------------------ */

const olWorkMeta = (w: Json): Json => ({
  id: `ol:${String(w.key ?? '').replace(/^\/?works\//, '')}`, type: 'book', name: w.title,
  writer: w.author_name ?? [], releaseInfo: w.first_publish_year ? String(w.first_publish_year) : undefined,
  poster: w.cover_i ? `https://covers.openlibrary.org/b/id/${w.cover_i}-L.jpg` : undefined,
  genres: (w.subject ?? []).slice(0, 4),
});

const openLibraryHandler: Handler = async (resource, _type, id, extra) => {
  if (resource === 'catalog') {
    if (extra.search) {
      const d = await getJson(`https://openlibrary.org/search.json?q=${enc(String(extra.search))}&limit=40&fields=key,title,author_name,first_publish_year,cover_i,subject`);
      return { metas: (d.docs ?? []).map(olWorkMeta) };
    }
    // No default list: Open Library is searched, not ranked, so browse returns nothing without a query.
    return { metas: [] };
  }
  if (resource === 'meta') {
    const key = id.replace(/^ol:/, '').replace(/^\/?works\//, '');
    const w = await getJson(`https://openlibrary.org/works/${enc(key)}.json`);
    const description = typeof w.description === 'string' ? w.description : w.description?.value;
    return {
      meta: {
        id, type: 'book', name: w.title, description: description ?? '', genres: (w.subjects ?? []).slice(0, 6),
        poster: w.covers?.[0] ? `https://covers.openlibrary.org/b/id/${w.covers[0]}-L.jpg` : undefined,
        releaseInfo: String(w.first_publish_date ?? '').match(/\d{4}/)?.[0],
      },
    };
  }
  return {};
};

/* ---------------------------- Project Gutenberg ---------------------------- */

const GUT = 'https://www.gutenberg.org';
const FORMAT_LABEL: Record<string, string> = { 'application/epub+zip': 'EPUB', 'text/plain': 'Plain text', 'text/html': 'HTML', 'application/pdf': 'PDF' };

interface GutBook { num: string; title: string; authors: string[]; summary: string; links: { type: string; href: string }[] }

function parseOpds(xml: string): GutBook[] {
  const doc = new DOMParser().parseFromString(xml, 'application/xml');
  const out: GutBook[] = [];
  for (const e of Array.from(doc.getElementsByTagNameNS('*', 'entry'))) {
    const idText = e.getElementsByTagNameNS('*', 'id')[0]?.textContent ?? '';
    const m = idText.match(/ebooks\/(\d+)\/?$/);
    if (!m) continue; // navigation entries (subjects, bookshelves) are skipped
    const title = e.getElementsByTagNameNS('*', 'title')[0]?.textContent?.trim() ?? `Book ${m[1]}`;
    const authors = Array.from(e.getElementsByTagNameNS('*', 'author'))
      .map((a) => a.getElementsByTagNameNS('*', 'name')[0]?.textContent?.trim() ?? '').filter(Boolean);
    const summary = (e.getElementsByTagNameNS('*', 'summary')[0]?.textContent ?? e.getElementsByTagNameNS('*', 'content')[0]?.textContent ?? '').replace(/\s+/g, ' ').trim();
    const links = Array.from(e.getElementsByTagNameNS('*', 'link'))
      .filter((l) => /acquisition/.test(l.getAttribute('rel') ?? ''))
      .map((l) => ({ type: (l.getAttribute('type') ?? '').split(';')[0].trim(), href: new URL(l.getAttribute('href') ?? '', GUT).href }));
    out.push({ num: m[1], title, authors, summary: summary.slice(0, 1500), links });
  }
  return out;
}

const gutMeta = (b: GutBook): Json => {
  const text = b.links.find((l) => l.type === 'text/plain');
  return {
    id: `gut:${b.num}`, type: 'book', name: b.title, writer: b.authors, description: b.summary,
    poster: `${GUT}/cache/epub/${b.num}/pg${b.num}.cover.medium.jpg`,
    contentKey: text ? `url:${text.href}` : '',
  };
};

const gutenbergHandler: Handler = async (resource, _type, id, extra) => {
  if (resource === 'catalog') {
    const q = extra.search ? `query=${enc(String(extra.search))}` : 'sort_order=downloads';
    const xml = await getText(`${GUT}/ebooks/search.opds/?${q}`, { accept: 'application/atom+xml, application/xml, */*' });
    return { metas: parseOpds(xml).slice(0, 60).map(gutMeta) };
  }
  if (resource === 'meta' || resource === 'stream') {
    const num = id.replace(/^gut:/, '');
    if (!/^\d+$/.test(num)) return {};
    const xml = await getText(`${GUT}/ebooks/${num}.opds`, { accept: 'application/atom+xml, application/xml, */*' });
    const [book] = parseOpds(xml);
    if (!book) return {};
    if (resource === 'meta') return { meta: gutMeta(book) };
    return {
      streams: book.links.map((l) => {
        const ext = l.type === 'application/epub+zip' ? 'epub' : l.type === 'application/pdf' ? 'pdf' : 'txt';
        return {
          name: FORMAT_LABEL[l.type] ?? l.type, description: 'Public domain · Project Gutenberg', url: l.href,
          behaviorHints: { filename: `${book.title.replace(/[\\/:*?"<>|]/g, '').slice(0, 120)}.${ext}` },
        };
      }),
    };
  }
  return {};
};

/* -------------------------------- MangaDex -------------------------------- */

const MD = 'https://api.mangadex.org';

function mdMeta(m: Json): Json {
  const titles = m.attributes?.title ?? {};
  const rels: Json[] = m.relationships ?? [];
  const cover = rels.find((r) => r.type === 'cover_art')?.attributes?.fileName;
  const author = rels.find((r) => r.type === 'author')?.attributes?.name;
  return {
    id: `md:${m.id}`, type: 'manga', name: String(titles.en ?? Object.values(titles)[0] ?? 'Untitled'),
    description: String(m.attributes?.description?.en ?? ''),
    releaseInfo: m.attributes?.year ? String(m.attributes.year) : undefined,
    genres: (m.attributes?.tags ?? []).map((t: Json) => t.attributes?.name?.en).filter(Boolean).slice(0, 6),
    poster: cover ? `https://uploads.mangadex.org/covers/${m.id}/${cover}.256.jpg` : undefined,
    writer: author ? [author] : [],
  };
}

const mangadexHandler: Handler = async (resource, _type, id, extra) => {
  if (resource === 'catalog') {
    const base = `${MD}/manga?limit=40&includes[]=cover_art&includes[]=author&contentRating[]=safe&contentRating[]=suggestive&availableTranslatedLanguage[]=en`;
    const url = extra.search ? `${base}&title=${enc(String(extra.search))}` : `${base}&order[followedCount]=desc`;
    const d = await getJson(url);
    return { metas: (d.data ?? []).map(mdMeta) };
  }
  if (resource === 'meta') {
    const d = await getJson(`${MD}/manga/${enc(id.replace(/^md:/, ''))}?includes[]=cover_art&includes[]=author`);
    return { meta: mdMeta(d.data) };
  }
  return {};
};

/* --------------------------------- manifests -------------------------------- */

const HANDLERS: Record<string, Handler> = {
  'builtin.musicbrainz': musicHandler,
  'builtin.openlibrary': openLibraryHandler,
  'builtin.gutenberg': gutenbergHandler,
  'builtin.mangadex': mangadexHandler,
};

const res = (name: string, types: string[], idPrefixes?: string[]): AddonResource => ({ name, types, idPrefixes });
const cat = (type: string, id: string, name: string): AddonCatalog => ({ type, id, name, genres: [], search: true, skip: false, required: [] });
const manifestOf = (id: string, name: string, description: string, types: string[], resources: AddonResource[], catalogs: AddonCatalog[]): AddonManifest => ({
  id, name, version: '1.0.0', description, types, resources, catalogs, adult: false, p2p: false, configurable: false, configurationRequired: false,
});
const builtin = (id: string, key: string, manifest: AddonManifest): InstalledAddon => ({
  id, transportUrl: `builtin:${key}`, enabled: true, installedAt: 0, status: 'online', official: true, builtin: true, manifest,
});

export const BUILTIN_ADDONS: InstalledAddon[] = [
  builtin('builtin.musicbrainz', 'musicbrainz', manifestOf('builtin.musicbrainz', 'MusicBrainz',
    'Open music database: albums and tracks, with covers from the Cover Art Archive. Metadata only; it does not provide audio.',
    ['music'], [res('catalog', ['music']), res('meta', ['music'], ['mb:'])], [cat('music', 'recent', 'Recent albums')])),
  builtin('builtin.openlibrary', 'openlibrary', manifestOf('builtin.openlibrary', 'Open Library',
    'Open book catalogue: search, descriptions and covers. Metadata only.',
    ['book'], [res('catalog', ['book']), res('meta', ['book'], ['ol:'])], [{ ...cat('book', 'search', 'Search Open Library'), required: ['search'] }])),
  builtin('builtin.gutenberg', 'gutenberg', manifestOf('builtin.gutenberg', 'Project Gutenberg',
    'Public-domain books with free EPUB and plain-text downloads. Everything here is in the public domain.',
    ['book'], [res('catalog', ['book']), res('meta', ['book'], ['gut:']), res('stream', ['book'], ['gut:'])], [cat('book', 'popular', 'Most downloaded')])),
  builtin('builtin.mangadex', 'mangadex', manifestOf('builtin.mangadex', 'MangaDex (metadata)',
    'Manga metadata: titles, covers, descriptions and popularity. Reading chapters is not included.',
    ['manga'], [res('catalog', ['manga']), res('meta', ['manga'], ['md:'])], [cat('manga', 'popular', 'Most followed')])),
  builtin('builtin.games', 'games', manifestOf('builtin.games', 'Game catalogues',
    'Steam titles and live store prices (CheapShark), plus every platform through Wikidata.',
    ['game'], [res('meta', ['game'], ['steam:', 'wd:', 'cs:'])], [])),
];

/* --------------------------------- dispatch --------------------------------- */

const memo = new Map<string, { at: number; data: Json }>();
const TTL = 10 * 60_000;

/** Answer a resource request for a built-in addon. Results are cached for ten minutes. */
export async function callBuiltin(addonId: string, resource: string, type: string, id: string, extra?: Extra): Promise<Json> {
  const handler = HANDLERS[addonId];
  if (!handler) return {};
  const key = JSON.stringify([addonId, resource, type, id, extra ?? {}]);
  const hit = memo.get(key);
  if (hit && Date.now() - hit.at < TTL) return hit.data;
  const data = await handler(resource, type, id, extra ?? {});
  if (memo.size > 300) memo.delete(memo.keys().next().value as string);
  memo.set(key, { at: Date.now(), data });
  return data;
}
