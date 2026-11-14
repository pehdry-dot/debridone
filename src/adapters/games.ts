import type { MediaItem } from '../types';
import { hash } from '../lib/utils';
import { remoteId } from './stremio';

/*
 * Live games catalogue.
 *
 * Two public, key-less, browser-readable services are combined:
 *   CheapShark  the Steam store catalogue (browse, sort, search) plus current
 *               prices at licensed stores (Steam, GOG, Humble, …)
 *   Wikidata    ~180,000 video games on every platform (search and details),
 *               with Wikipedia supplying descriptions
 *
 * DebridOne does not host or link game files. "Where to get it" always points
 * at stores. Other download options only appear if an addon you installed
 * answers for the game.
 */

export const GAMES_ADDON_ID = 'builtin.games';
const CS = 'https://www.cheapshark.com/api/1.0';
const steamImg = (appid: string, file: string) => `https://shared.steamstatic.com/store_item_assets/steam/apps/${appid}/${file}`;

export type GameSort = 'Reviews' | 'Metacritic' | 'Release' | 'recent' | 'Title' | 'Price';
export const GAME_SORTS: { value: GameSort; label: string }[] = [
  { value: 'Reviews', label: 'Best reviewed' }, { value: 'Metacritic', label: 'Metacritic score' }, { value: 'Release', label: 'Newest releases' },
  { value: 'recent', label: 'Recently updated' }, { value: 'Title', label: 'Title A–Z' }, { value: 'Price', label: 'Lowest price' },
];

async function json<T>(url: string, timeoutMs = 20_000): Promise<{ data: T; headers: Headers }> {
  const ctrl = new AbortController();
  const timer = setTimeout(() => ctrl.abort(), timeoutMs);
  try {
    const res = await fetch(url, { signal: ctrl.signal, credentials: 'omit', referrerPolicy: 'no-referrer' });
    if (res.status === 429) throw new Error('The games service is rate-limiting requests. Wait a minute and try again.');
    if (!res.ok) throw new Error(`The games service answered with HTTP ${res.status}.`);
    return { data: (await res.json()) as T, headers: res.headers };
  } catch (e) {
    if ((e as Error).name === 'AbortError') throw new Error('The games service took too long to answer.');
    if (e instanceof TypeError) throw new Error('The games catalogue could not be reached. Check your connection.');
    throw e;
  } finally { clearTimeout(timer); }
}

const clean = (v: unknown, max = 200) => String(v ?? '').replace(/<[^>]*>/g, '').trim().slice(0, max);

interface CsDeal { title: string; steamAppID: string | null; gameID: string; salePrice: string; normalPrice: string; steamRatingPercent: string; steamRatingCount: string; metacriticScore: string; releaseDate: number; thumb: string; dealID: string; storeID: string; savings: string }

function gameItem(sid: string, title: string, extra: Partial<MediaItem> = {}): MediaItem {
  return {
    id: remoteId('game', sid), type: 'game', demo: false, title, description: '', genres: [], tags: [], addedAt: Date.now(), popularity: 50, palette: hash(sid) % 16,
    remote: { addonId: GAMES_ADDON_ID, type: 'game', id: sid, partial: true, fetchedAt: Date.now() }, game: { platforms: [] }, ...extra,
  };
}

function fromDeal(d: CsDeal): MediaItem {
  const appid = d.steamAppID && /^\d+$/.test(d.steamAppID) ? d.steamAppID : null;
  const percent = Number(d.steamRatingPercent) || 0, meta = Number(d.metacriticScore) || 0;
  const thumb = typeof d.thumb === 'string' && d.thumb.startsWith('https://') ? d.thumb : undefined;
  return gameItem(appid ? `steam:${appid}` : `cs:${clean(d.gameID, 12)}`, clean(d.title, 160), {
    releaseDate: d.releaseDate > 0 ? new Date(d.releaseDate * 1000).toISOString().slice(0, 10) : undefined,
    rating: percent ? percent / 10 : meta ? meta / 10 : undefined,
    popularity: Math.min(100, Math.round(Math.log10((Number(d.steamRatingCount) || 1) + 1) * 18)),
    cover: appid ? steamImg(appid, 'library_600x900.jpg') : thumb,
    banner: thumb?.replace(/capsule_\d+x\d+/, 'header'),
    game: { platforms: appid ? ['Windows'] : [], website: appid ? `https://store.steampowered.com/app/${appid}/` : undefined },
  });
}

/** One page (60 titles) of the Steam catalogue, optionally filtered by title. */
export async function listGames(opts: { page: number; sort: GameSort; title?: string }): Promise<{ items: MediaItem[]; pages: number }> {
  const params = new URLSearchParams({ storeID: '1', pageSize: '60', pageNumber: String(opts.page), sortBy: opts.sort });
  if (opts.sort === 'Release' || opts.sort === 'Metacritic' || opts.sort === 'Reviews') params.set('desc', '0');
  if (opts.title?.trim()) { params.set('title', opts.title.trim()); params.delete('sortBy'); }
  const { data, headers } = await json<CsDeal[]>(`${CS}/deals?${params}`);
  const seen = new Set<string>();
  const items = (Array.isArray(data) ? data : []).map(fromDeal).filter((i) => !seen.has(i.id) && !!seen.add(i.id));
  return { items, pages: Number(headers.get('x-total-page-count')) || (items.length === 60 ? opts.page + 2 : opts.page + 1) };
}

/* -------------------------------- Wikidata -------------------------------- */

async function sparql(query: string): Promise<Record<string, { value: string }>[]> {
  const { data } = await json<{ results: { bindings: Record<string, { value: string }>[] } }>(`https://query.wikidata.org/sparql?format=json&query=${encodeURIComponent(query)}`, 25_000);
  return data.results?.bindings ?? [];
}
const lit = (s: string) => `"${s.replace(/[\\"\n\r]/g, ' ')}"`;

/** Search every game Wikidata knows about (all platforms, all eras). */
export async function searchAllGames(q: string): Promise<MediaItem[]> {
  const rows = await sparql(`SELECT ?g ?label ?steam ?links (MIN(?d) AS ?date) WHERE {
    SERVICE wikibase:mwapi { bd:serviceParam wikibase:endpoint "www.wikidata.org"; wikibase:api "EntitySearch"; mwapi:search ${lit(q.trim().slice(0, 80))}; mwapi:language "en"; mwapi:limit "50". ?g wikibase:apiOutputItem mwapi:item. }
    ?g wdt:P31 wd:Q7889; wikibase:sitelinks ?links; rdfs:label ?label FILTER(LANG(?label)="en")
    OPTIONAL { ?g wdt:P1733 ?steam } OPTIONAL { ?g wdt:P577 ?d }
  } GROUP BY ?g ?label ?steam ?links ORDER BY DESC(?links) LIMIT 40`);
  const seen = new Set<string>();
  return rows.map((r) => {
    const appid = r.steam?.value && /^\d+$/.test(r.steam.value) ? r.steam.value : null;
    const qid = r.g.value.split('/').pop()!;
    return gameItem(appid ? `steam:${appid}` : `wd:${qid}`, clean(r.label.value, 160), {
      releaseDate: r.date?.value.slice(0, 10), popularity: Math.min(100, Number(r.links?.value) || 0),
      cover: appid ? steamImg(appid, 'library_600x900.jpg') : undefined, banner: appid ? steamImg(appid, 'header.jpg') : undefined,
      game: { platforms: [], website: appid ? `https://store.steampowered.com/app/${appid}/` : undefined },
    });
  }).filter((i) => !seen.has(i.id) && !!seen.add(i.id));
}

/** Fill in developer, publisher, genres, platforms, release date and a description for a game. */
export async function loadGame(sid: string, base?: MediaItem): Promise<MediaItem | null> {
  const [scheme, value] = [sid.slice(0, sid.indexOf(':')), sid.slice(sid.indexOf(':') + 1)];
  const item = base ? { ...base } : gameItem(sid, 'Game');
  const pattern = scheme === 'steam' && /^\d+$/.test(value) ? `?g wdt:P1733 ${lit(value)}.` : scheme === 'wd' && /^Q\d+$/.test(value) ? `BIND(wd:${value} AS ?g)` : null;
  if (pattern) {
    try {
      const [row] = await sparql(`SELECT ?g ?label ?site ?article ?steam (GROUP_CONCAT(DISTINCT ?devL;separator="|") AS ?devs) (GROUP_CONCAT(DISTINCT ?pubL;separator="|") AS ?pubs) (GROUP_CONCAT(DISTINCT ?genL;separator="|") AS ?genres) (GROUP_CONCAT(DISTINCT ?platL;separator="|") AS ?plats) (MIN(?d) AS ?date) WHERE {
        ${pattern} OPTIONAL{?g rdfs:label ?label FILTER(LANG(?label)="en")} OPTIONAL{?g wdt:P1733 ?steam} OPTIONAL{?g wdt:P856 ?site} OPTIONAL{?g wdt:P577 ?d}
        OPTIONAL{?g wdt:P178 ?dev. ?dev rdfs:label ?devL FILTER(LANG(?devL)="en")} OPTIONAL{?g wdt:P123 ?pub. ?pub rdfs:label ?pubL FILTER(LANG(?pubL)="en")}
        OPTIONAL{?g wdt:P136 ?gen. ?gen rdfs:label ?genL FILTER(LANG(?genL)="en")} OPTIONAL{?g wdt:P400 ?plat. ?plat rdfs:label ?platL FILTER(LANG(?platL)="en")}
        OPTIONAL{?article schema:about ?g; schema:isPartOf <https://en.wikipedia.org/>}
      } GROUP BY ?g ?label ?site ?article ?steam LIMIT 1`);
      if (row) {
        const parts = (k: string, max: number) => (row[k]?.value ? row[k].value.split('|').map((x) => clean(x, 50)).filter(Boolean).slice(0, max) : []);
        const cap = (s: string) => s.charAt(0).toUpperCase() + s.slice(1);
        if (row.label?.value && (!base || base.title === 'Game')) item.title = clean(row.label.value, 160);
        item.creator = parts('devs', 3).join(', ') || item.creator;
        item.publisher = parts('pubs', 3).join(', ') || item.publisher;
        item.genres = parts('genres', 6).map((g) => cap(g.replace(/ ?(video )?game$/i, ''))).filter(Boolean);
        item.releaseDate = row.date?.value.slice(0, 10) || item.releaseDate;
        const appid = row.steam?.value && /^\d+$/.test(row.steam.value) ? row.steam.value : null;
        const site = row.site?.value.startsWith('http') ? row.site.value.replace(/^http:/, 'https:') : undefined;
        item.game = { platforms: parts('plats', 10), website: item.game?.website ?? (appid ? `https://store.steampowered.com/app/${appid}/` : site) };
        if (site) item.custom = { ...item.custom, 'Official site': site };
        if (appid && !item.cover) { item.cover = steamImg(appid, 'library_600x900.jpg'); item.banner = steamImg(appid, 'header.jpg'); }
        const title = row.article?.value.split('/wiki/')[1];
        if (title) {
          try {
            const { data } = await json<{ extract?: string; description?: string }>(`https://en.wikipedia.org/api/rest_v1/page/summary/${title}`, 12_000);
            if (data.extract) { item.description = clean(data.extract, 1500); item.tags = ['description from Wikipedia']; }
          } catch { /* description is optional */ }
        }
      }
    } catch { /* keep the catalogue data we already have */ }
  }
  if (!item.description) item.description = 'No description is available from the open catalogue for this title yet.';
  item.remote = { addonId: GAMES_ADDON_ID, type: 'game', id: sid, partial: false, fetchedAt: Date.now() };
  return item.title === 'Game' && !base ? null : item;
}

export interface GameDeal { store: string; price: number; retail: number; savings: number; url: string }
let storeNames: Promise<Record<string, string>> | null = null;

/** Current prices at licensed stores. Links go through CheapShark's redirect, as its API terms require. */
export async function gameDeals(sid: string): Promise<GameDeal[]> {
  const [scheme, value] = sid.split(':');
  const query = scheme === 'steam' ? `steamAppID=${encodeURIComponent(value)}` : null;
  let deals: { storeID: string; dealID: string; salePrice?: string; price?: string; normalPrice?: string; retailPrice?: string; savings: string }[] = [];
  if (query) deals = (await json<typeof deals>(`${CS}/deals?${query}&pageSize=30`)).data;
  else if (scheme === 'cs') deals = (await json<{ deals: typeof deals }>(`${CS}/games?id=${encodeURIComponent(value)}`)).data.deals ?? [];
  else return [];
  storeNames ??= json<{ storeID: string; storeName: string }[]>(`${CS}/stores`).then((r) => Object.fromEntries(r.data.map((s) => [s.storeID, clean(s.storeName, 40)]))).catch(() => ({}));
  const names = await storeNames;
  return (Array.isArray(deals) ? deals : []).map((d) => ({
    store: names[d.storeID] ?? `Store ${d.storeID}`, price: Number(d.salePrice ?? d.price) || 0, retail: Number(d.normalPrice ?? d.retailPrice) || 0,
    savings: Math.round(Number(d.savings) || 0), url: `https://www.cheapshark.com/redirect?dealID=${d.dealID}`,
  })).sort((a, b) => a.price - b.price);
}
