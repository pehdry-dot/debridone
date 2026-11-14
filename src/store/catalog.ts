import type { MediaItem, MediaType, MetaFields, MetaRule, Track } from '../types';
import { DEMO_ITEMS } from '../data';
import { ARTISTS, type Artist } from '../data/music';
import { hash } from '../lib/utils';
import { isKept, useStore, type Store } from './useStore';

/*
 * The catalogue the UI sees is always *resolved* metadata:
 *
 *   original record  →  provider imports (lowest priority first)  →  metadata rules (Pro)  →  your manual edits
 *
 * Originals are never mutated, so "Restore original" is just dropping a layer.
 */

const strip = <T extends object>(o: T): Partial<T> => Object.fromEntries(Object.entries(o).filter(([, v]) => v !== undefined)) as Partial<T>;

function ruleMatches(rule: MetaRule, item: MediaItem): boolean {
  if (rule.when.type !== 'any' && rule.when.type !== item.type) return false;
  const needle = rule.when.contains.trim().toLowerCase();
  if (!needle) return false;
  const v = item[rule.when.field];
  const hay = Array.isArray(v) ? v.join(' ') : v ?? '';
  return hay.toLowerCase().includes(needle);
}
function applyRule(rule: MetaRule, item: MediaItem): MediaItem {
  const value = rule.then.value.trim();
  if (!value) return item;
  switch (rule.then.action) {
    case 'addTag': return item.tags.includes(value) ? item : { ...item, tags: [...item.tags, value] };
    case 'addGenre': return item.genres.includes(value) ? item : { ...item, genres: [...item.genres, value] };
    case 'setCollection': return { ...item, collection: value };
    case 'setLanguage': return { ...item, language: value };
  }
}

export function resolveItem(base: MediaItem, s: Pick<Store, 'imported' | 'metaProviders' | 'metaRules' | 'overrides' | 'proDemo'>): MediaItem {
  let out = base;
  const imp = s.imported[base.id];
  if (imp) for (const p of [...s.metaProviders].reverse()) if (p.enabled && imp[p.id]) out = { ...out, ...strip(imp[p.id]) };
  if (s.proDemo) for (const r of s.metaRules) if (r.enabled && ruleMatches(r, out)) out = applyRule(r, out);
  const ov = s.overrides[base.id];
  if (ov) out = { ...out, ...strip(ov), custom: { ...out.custom, ...ov.custom } };
  return out;
}

/*
 * Remote titles (from addons and the live games catalogue).
 *
 *   previews      everything a catalogue row has shown this session (memory only)
 *   remoteItems   titles you opened or kept (in the store; kept ones persist)
 *
 * Browse lists only include remote titles you have kept, so opening something
 * to look at it does not add it to your library. Lookups by id see everything.
 */
const previews = new Map<string, MediaItem>();
export function registerPreviews(items: MediaItem[]) {
  for (const i of items) if (!previews.has(i.id)) previews.set(i.id, i);
  if (previews.size > 4000) for (const k of [...previews.keys()].slice(0, 1000)) previews.delete(k);
}
export const getPreview = (id: string) => previews.get(id);

let deps: unknown[] = [];
let cache: { list: MediaItem[]; byId: Map<string, MediaItem>; tracks: Map<string, Track> } = { list: [], byId: new Map(), tracks: new Map() };

function build(s: Store) {
  const keptKey = Object.keys(s.remoteItems).filter((id) => isKept(s, id)).join('|');
  const next = [s.userItems, s.overrides, s.imported, s.metaProviders, s.metaRules, s.proDemo, s.remoteItems, s.settings.showDemo, keptKey];
  if (next.length === deps.length && next.every((d, i) => d === deps[i])) return cache;
  deps = next;
  const kept = new Set(keptKey ? keptKey.split('|') : []);
  const remote = Object.values(s.remoteItems).map((i) => resolveItem(i, s));
  const list = [...[...s.userItems, ...(s.settings.showDemo ? DEMO_ITEMS : [])].map((i) => resolveItem(i, s)), ...remote.filter((i) => kept.has(i.id))];
  const tracks = new Map<string, Track>();
  for (const it of list) it.album?.tracks.forEach((t) => tracks.set(t.id, t));
  const byId = new Map([...remote, ...list].map((i) => [i.id, i]));
  // Hidden sample items stay reachable by id so old links, history and queues don't break.
  if (!s.settings.showDemo) for (const d of DEMO_ITEMS) if (!byId.has(d.id)) byId.set(d.id, resolveItem(d, s));
  cache = { list, byId, tracks };
  return cache;
}

export const getCatalog = (s: Store = useStore.getState()) => build(s).list;
export const getItem = (id: string, s: Store = useStore.getState()) => build(s).byId.get(id) ?? previews.get(id);
export const getTrack = (id: string, s: Store = useStore.getState()) => build(s).tracks.get(id);
export const getAllTracks = (s: Store = useStore.getState()) => [...build(s).tracks.values()];

export const useCatalog = () => useStore(getCatalog);
export const useItem = (id?: string) => useStore((s) => (id ? build(s).byId.get(id) : undefined)) ?? (id ? previews.get(id) : undefined);
export const useTracks = () => useStore((s) => build(s).tracks);

/** The untouched record (bundled, as first imported, or as the addon supplied it), before any metadata layers. */
export function originalItem(id: string): MediaItem | undefined {
  const s = useStore.getState();
  return s.userItems.find((i) => i.id === id) ?? s.remoteItems[id] ?? DEMO_ITEMS.find((i) => i.id === id) ?? previews.get(id);
}

// When a previewed title becomes part of the library (saved, favorited, started, added to a collection),
// copy it into the store so it is persisted and shows up in library views.
useStore.subscribe((s, prev) => {
  if (s.saved === prev.saved && s.favorites === prev.favorites && s.progress === prev.progress && s.completed === prev.completed && s.collections === prev.collections) return;
  const ids = new Set([...Object.keys(s.saved), ...Object.keys(s.favorites), ...Object.keys(s.progress), ...Object.keys(s.completed), ...s.collections.flatMap((c) => c.itemIds)]);
  for (const id of ids) if (id.startsWith('sx:') && !s.remoteItems[id] && previews.has(id)) s.upsertRemote(previews.get(id)!);
});

export const byType = (items: MediaItem[], ...types: MediaType[]) => items.filter((i) => types.includes(i.type));

/* ------------------------------ artists ------------------------------ */

export function artistId(name: string) { return `artist:${name.toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-|-$/g, '')}`; }

export function listArtists(items: MediaItem[]): (Artist & { albums: MediaItem[] })[] {
  const map = new Map<string, Artist & { albums: MediaItem[] }>();
  for (const it of items) {
    if (it.type !== 'album' || !it.creator) continue;
    const id = artistId(it.creator);
    const known = ARTISTS.find((a) => a.id === id);
    const entry = map.get(id) ?? { id, name: it.creator, bio: known?.bio ?? 'Artist from your imported library.', genres: known?.genres ?? it.genres, palette: known?.palette ?? hash(id) % 16, albums: [] };
    entry.albums.push(it);
    map.set(id, entry);
  }
  return [...map.values()];
}

/* ------------------------------- search ------------------------------- */

export type SearchScope = 'all' | 'movie' | 'tv' | 'album' | 'game' | 'manga' | 'book';
export interface SearchHit { item: MediaItem; score: number; track?: Track; why: string }

/** Instant local search over the real catalogue (titles, creators, genres, tags, descriptions and track names). */
export function searchCatalog(items: MediaItem[], query: string, scope: SearchScope = 'all'): SearchHit[] {
  const terms = query.toLowerCase().split(/\s+/).filter(Boolean);
  if (!terms.length) return [];
  const hits: SearchHit[] = [];
  const scoreText = (text: string | undefined, weight: number) => {
    if (!text) return 0;
    const t = text.toLowerCase();
    let total = 0;
    for (const term of terms) {
      const at = t.indexOf(term);
      if (at < 0) return 0;
      total += weight * (at === 0 ? 2 : /\s/.test(t[at - 1] ?? ' ') ? 1.4 : 1);
    }
    return total;
  };
  for (const item of items) {
    if (scope !== 'all' && item.type !== scope) continue;
    const fields: [string | undefined, number, string][] = [
      [item.title, 10, 'Title'], [item.altTitle, 8, 'Alternate title'], [item.creator, 6, 'Creator'], [item.publisher, 3, 'Publisher'],
      [item.genres.join(' '), 4, 'Genre'], [item.tags.join(' '), 4, 'Tag'], [item.collection, 3, 'Collection'], [item.description, 1, 'Description'],
      [Object.values(item.custom ?? {}).join(' '), 2, 'Custom field'],
    ];
    let best = 0, why = '';
    for (const [text, weight, label] of fields) { const sc = scoreText(text, weight); if (sc > best) { best = sc; why = label; } }
    if (best > 0) hits.push({ item, score: best + item.popularity / 100, why });
    item.album?.tracks.forEach((track) => {
      const sc = scoreText(track.title, 7);
      if (sc > 0) hits.push({ item, track, score: sc, why: 'Song' });
    });
  }
  return hits.sort((a, b) => b.score - a.score);
}

export const metaDiff = (a: Partial<MetaFields>, b: Partial<MetaFields>, key: keyof MetaFields) => JSON.stringify(a[key] ?? null) !== JSON.stringify(b[key] ?? null);
