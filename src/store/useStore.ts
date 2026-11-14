import { create } from 'zustand';
import { createJSONStorage, persist } from 'zustand/middleware';
import type {
  AppNotification, BookReaderSettings, Bookmark, Collection, CustomFieldDef, GameState, HistoryEntry, HomeLayout, MangaReaderSettings,
  MediaItem, MediaType, MetaFields, MetaRule, Note, Playlist, Progress, Settings, Source, Theme, ThemePreset, WidgetId,
} from '../types';
import { BUILTIN_SOURCES } from '../adapters/sources';
import { DEFAULT_ADDONS, type InstalledAddon } from '../adapters/stremio';
import { storageKey, useAuth } from './auth';
import { kvStorage } from '../lib/kv';
import { DEMO_PLAYLISTS, DEMO_TRACKS } from '../data/music';
import { DEMO_ITEMS } from '../data';
import { uid } from '../lib/utils';

/*
 * Main application store. Everything in here is persisted to localStorage
 * (key "debridone:v1") so the prototype survives refreshes. Large binaries
 * (imports, wallpaper) live in IndexedDB instead — see lib/idb.ts.
 */

export const STORAGE_KEY = 'debridone:v1';
const HOUR = 3_600_000, DAY = 24 * HOUR;

export const DEFAULT_THEME: Theme = {
  mode: 'dark', accent: '#0A84FF', radius: 14, density: 'comfortable', font: 'system', glass: true, glow: true, grain: false,
  animation: 'subtle', wallpaper: false, wallpaperDim: 72, wallpaperBlur: 0, customCss: '', customCssEnabled: false,
  cardSize: 'medium', headerStyle: 'large', shadows: 'soft', sidebarStyle: 'translucent',
};

export const NAV_IDS = ['home', 'discover', 'movies', 'music', 'games', 'manga', 'books', 'downloads', 'library', 'sources', 'addons', 'debrid', 'settings', 'pro'] as const;
export type NavId = (typeof NAV_IDS)[number];

export const WIDGETS: { id: WidgetId; label: string }[] = [
  { id: 'hero', label: 'Featured spotlight' },
  { id: 'continue-watching', label: 'Continue Watching' },
  { id: 'continue-listening', label: 'Continue Listening' },
  { id: 'continue-playing', label: 'Continue Playing' },
  { id: 'continue-reading', label: 'Continue Reading' },
  { id: 'downloads', label: 'Downloads in Progress' },
  { id: 'pinned', label: 'Pinned Collections' },
  { id: 'addons', label: 'From Your Addons' },
  { id: 'recently-added', label: 'Recently Added' },
  { id: 'quick-access', label: 'Quick Access' },
];
const defaultLayout = (): HomeLayout => ({ id: 'default', name: 'Default', widgets: WIDGETS.map((w) => ({ id: w.id, visible: true })), types: [] });

export const DEFAULT_SHORTCUTS: Record<string, string> = {
  search: '/', home: 'Alt+H', library: 'Alt+L', downloads: 'Alt+D', settings: 'Alt+,', playPause: 'Alt+P', nextTrack: 'Alt+ArrowRight', prevTrack: 'Alt+ArrowLeft', sidebar: 'Alt+B',
};
export const SHORTCUT_LABELS: Record<string, string> = {
  search: 'Focus search', home: 'Go to Home', library: 'Go to My Library', downloads: 'Go to Downloads', settings: 'Go to Settings',
  playPause: 'Play / pause music', nextTrack: 'Next track', prevTrack: 'Previous track', sidebar: 'Collapse / expand sidebar',
};

const DEFAULT_SETTINGS: Settings = {
  language: 'en', startPage: '/', timeFormat: '12h', dateFormat: 'locale',
  notify: { toasts: true, downloads: true, library: true, sources: true },
  playback: { quality: 'auto', resume: true, autoplayNext: true, rate: 1, skipSeconds: 10, subtitlesOn: false, subtitleLang: 'en', subtitleSize: 'md', pauseMusicOnVideo: true, musicVolume: 80 },
  downloads: { concurrent: 2, keepHistory: true, autoSave: true, location: 'Browser default (Downloads folder)' },
  privacy: { history: true, searchHistory: true },
  backendUrl: '',
  showDemo: false,
  allowLocalAddons: false,
  streamFilters: {},
};

export { DEFAULT_SETTINGS };
export const DEFAULT_BOOK_READER: BookReaderSettings = { fontSize: 18, font: 'serif', theme: 'dark', lineHeight: 1.65, layout: 'paged', width: 680 };
export const DEFAULT_MANGA_READER: MangaReaderSettings = { mode: 'auto', fit: 'height', zoom: 100, bg: '#0B0B0F' };

interface Data {
  seeded: number;
  profile: { name: string; avatar: number };
  proDemo: boolean;
  settings: Settings;
  theme: Theme;
  userPresets: ThemePreset[];
  layouts: HomeLayout[];
  activeLayout: string;
  nav: { order: NavId[]; hidden: NavId[]; collapsed: boolean };
  saved: Record<string, number>;
  favorites: Record<string, number>;
  completed: Record<string, number>;
  progress: Record<string, Progress>;
  lastAccess: Record<string, number>;
  history: HistoryEntry[];
  collections: Collection[];
  playlists: Playlist[];
  likedTracks: Record<string, number>;
  recentTracks: { id: string; ts: number }[];
  userItems: MediaItem[];
  gameState: Record<string, GameState>;
  bookmarks: Record<string, Bookmark[]>;
  bookNotes: Record<string, Note[]>;
  bookReader: BookReaderSettings;
  mangaReader: MangaReaderSettings;
  overrides: Record<string, Partial<MetaFields>>;
  imported: Record<string, Record<string, Partial<MetaFields>>>;
  metaProviders: { id: string; enabled: boolean }[];
  metaRules: MetaRule[];
  customFieldDefs: CustomFieldDef[];
  sources: Source[];
  sourceSort: 'priority' | 'category' | 'status' | 'name';
  debrid: Record<string, { connected: boolean; since: number }>;
  searchHistory: string[];
  notifications: AppNotification[];
  shortcuts: Record<string, string>;
  /** Installed Stremio-compatible addons, in priority order. */
  addons: InstalledAddon[];
  /** Titles that came from addons or live catalogues. Only the ones you keep (saved, favorited, started…) are persisted. */
  remoteItems: Record<string, MediaItem>;
}

/** Titles that are part of your library (saved, favourited, watched, or in a collection) are kept in the profile file. */
export function isKept(s: Pick<Data, 'saved' | 'favorites' | 'progress' | 'completed' | 'collections'>, id: string): boolean {
  return !!(s.saved[id] || s.favorites[id] || s.progress[id] || s.completed[id]) || s.collections.some((c) => c.itemIds.includes(id));
}

/** A new profile starts empty. Everything shown comes from your library, your addons or the live catalogues. */
function seed(): Data {
  return {
    seeded: Date.now(),
    profile: { name: useAuth.getState().current?.name ?? 'Guest', avatar: 0 },
    proDemo: true,
    settings: DEFAULT_SETTINGS,
    theme: DEFAULT_THEME,
    userPresets: [],
    layouts: [defaultLayout()],
    activeLayout: 'default',
    nav: { order: [...NAV_IDS], hidden: [], collapsed: false },
    saved: {}, favorites: {}, completed: {}, progress: {}, lastAccess: {}, history: [], collections: [], playlists: [],
    likedTracks: {}, recentTracks: [], userItems: [], gameState: {}, bookmarks: {}, bookNotes: {},
    bookReader: DEFAULT_BOOK_READER, mangaReader: DEFAULT_MANGA_READER, overrides: {}, imported: {},
    metaProviders: [{ id: 'demo', enabled: true }, { id: 'openlibrary', enabled: true }, { id: 'tmdb', enabled: false }, { id: 'igdb', enabled: false }, { id: 'musicbrainz', enabled: false }],
    metaRules: [], customFieldDefs: [], sources: BUILTIN_SOURCES, sourceSort: 'priority', debrid: {}, searchHistory: [], notifications: [],
    shortcuts: DEFAULT_SHORTCUTS, addons: DEFAULT_ADDONS, remoteItems: {},
  };
}

/**
 * Profiles saved before version 3 had sample progress, favourites, history,
 * playlists, rules and the sample library switched on. Those were never real,
 * so they are removed once. Your own imports, addons and kept titles are untouched.
 */
function migrateStore(persisted: unknown, version: number): Data {
  const s = (persisted ?? {}) as Partial<Data>;
  if (version >= 3) return s as Data;
  const demo = new Set(DEMO_ITEMS.map((i) => i.id));
  const demoTracks = new Set(DEMO_TRACKS.map((t) => t.id));
  const dropDemo = <T,>(rec?: Record<string, T>) => Object.fromEntries(Object.entries(rec ?? {}).filter(([k]) => !demo.has(k)));
  return {
    ...s,
    saved: dropDemo(s.saved), favorites: dropDemo(s.favorites), completed: dropDemo(s.completed), progress: dropDemo(s.progress),
    lastAccess: dropDemo(s.lastAccess), gameState: dropDemo(s.gameState), overrides: dropDemo(s.overrides), imported: dropDemo(s.imported),
    bookmarks: dropDemo(s.bookmarks), bookNotes: dropDemo(s.bookNotes),
    likedTracks: Object.fromEntries(Object.entries(s.likedTracks ?? {}).filter(([k]) => !demoTracks.has(k))),
    recentTracks: (s.recentTracks ?? []).filter((t) => !demoTracks.has(t.id)),
    history: (s.history ?? []).filter((h) => !demo.has(h.id)),
    collections: (s.collections ?? [])
      .filter((c) => !['col:blender', 'col:rainy-sunday', 'col:retro-night', 'col:classics-shelf'].includes(c.id))
      .map((c) => ({ ...c, itemIds: c.itemIds.filter((id) => !demo.has(id)) })),
    playlists: (s.playlists ?? []).filter((p) => !p.demo),
    settings: { ...(s.settings ?? DEFAULT_SETTINGS), showDemo: false },
    debrid: {},
    metaRules: (s.metaRules ?? []).filter((r) => r.id !== 'rule-demo'),
    customFieldDefs: (s.customFieldDefs ?? []).filter((f) => f.id !== 'cf-demo'),
    notifications: (s.notifications ?? []).filter((n) => !n.id.startsWith('n-welcome') && n.id !== 'n-pro'),
    searchHistory: (s.searchHistory ?? []).filter((q) => !['blender', 'synthwave', 'roguelike'].includes(q)),
  } as Data;
}

interface Actions {
  toggleSaved(id: string): boolean;
  toggleFavorite(id: string): boolean;
  setCompleted(id: string, done: boolean): void;
  touch(id: string): void;
  setProgress(id: string, p: Omit<Progress, 'updatedAt'>): void;
  clearProgress(id: string): void;
  logHistory(e: Omit<HistoryEntry, 'ts'>): void;
  createCollection(name: string, description?: string, kind?: Collection['kind'], itemIds?: string[]): string;
  updateCollection(id: string, patch: Partial<Collection>): void;
  deleteCollection(id: string): void;
  toggleInCollection(colId: string, itemId: string): boolean;
  createPlaylist(name: string, trackIds?: string[]): string;
  updatePlaylist(id: string, patch: Partial<Playlist>): void;
  deletePlaylist(id: string): void;
  toggleLike(trackId: string): boolean;
  logTrack(trackId: string): void;
  addUserItem(item: MediaItem): void;
  updateUserItem(id: string, fn: (item: MediaItem) => MediaItem): void;
  removeUserItem(id: string): void;
  setGameState(id: string, patch: Partial<GameState>): void;
  logPlaySession(id: string, minutes: number): void;
  addBookmark(bookId: string, b: Omit<Bookmark, 'id' | 'ts'>): void;
  removeBookmark(bookId: string, id: string): void;
  addNote(bookId: string, n: Omit<Note, 'id' | 'ts'>): void;
  removeNote(bookId: string, id: string): void;
  setBookReader(patch: Partial<BookReaderSettings>): void;
  setMangaReader(patch: Partial<MangaReaderSettings>): void;
  setOverride(id: string, patch: Partial<MetaFields>): void;
  replaceOverride(id: string, next: Partial<MetaFields>): void;
  clearOverride(id: string, keys?: (keyof MetaFields)[]): void;
  setImported(id: string, providerId: string, fields: Partial<MetaFields> | null): void;
  set<K extends keyof Data>(key: K, value: Data[K] | ((prev: Data[K]) => Data[K])): void;
  setTheme(patch: Partial<Theme>): void;
  setSetting<K extends keyof Settings>(key: K, value: Settings[K]): void;
  updateLayout(id: string, patch: Partial<HomeLayout>): void;
  updateSource(id: string, patch: Partial<Source>): void;
  updateAddon(id: string, patch: Partial<InstalledAddon>): void;
  upsertRemote(item: MediaItem): void;
  pushSearch(q: string): void;
  notify(n: Omit<AppNotification, 'id' | 'ts' | 'read'>): void;
  clearHistory(): void;
  resetAll(): void;
}

export type Store = Data & Actions;

const toggleKey = (map: Record<string, number>, id: string): [Record<string, number>, boolean] => {
  if (map[id]) { const { [id]: _, ...rest } = map; return [rest, false]; }
  return [{ ...map, [id]: Date.now() }, true];
};

export const useStore = create<Store>()(
  persist(
    (set, get) => ({
      ...seed(),

      toggleSaved(id) { const [saved, on] = toggleKey(get().saved, id); set({ saved }); return on; },
      toggleFavorite(id) { const [favorites, on] = toggleKey(get().favorites, id); set({ favorites }); return on; },
      setCompleted(id, done) {
        const { [id]: _, ...rest } = get().completed;
        set({ completed: done ? { ...rest, [id]: Date.now() } : rest });
      },
      touch(id) { set((s) => ({ lastAccess: { ...s.lastAccess, [id]: Date.now() } })); },
      setProgress(id, p) {
        set((s) => ({ progress: { ...s.progress, [id]: { ...p, updatedAt: Date.now() } }, lastAccess: { ...s.lastAccess, [id]: Date.now() } }));
        if (p.percent >= 97 && !get().completed[id]) get().setCompleted(id, true);
      },
      clearProgress(id) { set((s) => { const { [id]: _, ...rest } = s.progress; return { progress: rest }; }); },
      logHistory(e) {
        if (!get().settings.privacy.history) return;
        set((s) => {
          const last = s.history[0];
          const rest = last && last.id === e.id && last.kind === e.kind && Date.now() - last.ts < 30 * 60_000 ? s.history.slice(1) : s.history;
          return { history: [{ ...e, ts: Date.now() }, ...rest].slice(0, 200) };
        });
      },

      createCollection(name, description = '', kind = 'collection', itemIds = []) {
        const id = uid('col:');
        set((s) => ({ collections: [...s.collections, { id, name, description, itemIds, pinned: false, createdAt: Date.now(), kind }] }));
        return id;
      },
      updateCollection(id, patch) { set((s) => ({ collections: s.collections.map((c) => (c.id === id ? { ...c, ...patch } : c)) })); },
      deleteCollection(id) { set((s) => ({ collections: s.collections.filter((c) => c.id !== id) })); },
      toggleInCollection(colId, itemId) {
        let added = false;
        set((s) => ({
          collections: s.collections.map((c) => {
            if (c.id !== colId) return c;
            added = !c.itemIds.includes(itemId);
            return { ...c, itemIds: added ? [...c.itemIds, itemId] : c.itemIds.filter((i) => i !== itemId) };
          }),
        }));
        return added;
      },

      createPlaylist(name, trackIds = []) {
        const id = uid('playlist:');
        set((s) => ({ playlists: [...s.playlists, { id, name, description: '', trackIds, createdAt: Date.now(), palette: s.playlists.length % 16 }] }));
        return id;
      },
      updatePlaylist(id, patch) { set((s) => ({ playlists: s.playlists.map((p) => (p.id === id ? { ...p, ...patch } : p)) })); },
      deletePlaylist(id) { set((s) => ({ playlists: s.playlists.filter((p) => p.id !== id) })); },
      toggleLike(trackId) { const [likedTracks, on] = toggleKey(get().likedTracks, trackId); set({ likedTracks }); return on; },
      logTrack(trackId) {
        if (!get().settings.privacy.history) return;
        set((s) => ({ recentTracks: [{ id: trackId, ts: Date.now() }, ...s.recentTracks.filter((t) => t.id !== trackId)].slice(0, 40) }));
      },

      addUserItem(item) { set((s) => ({ userItems: [item, ...s.userItems.filter((i) => i.id !== item.id)], saved: { ...s.saved, [item.id]: Date.now() } })); },
      updateUserItem(id, fn) { set((s) => ({ userItems: s.userItems.map((i) => (i.id === id ? fn(i) : i)) })); },
      removeUserItem(id) {
        set((s) => {
          const drop = <T,>(m: Record<string, T>) => { const { [id]: _, ...rest } = m; return rest; };
          return {
            userItems: s.userItems.filter((i) => i.id !== id), saved: drop(s.saved), favorites: drop(s.favorites), progress: drop(s.progress),
            overrides: drop(s.overrides), imported: drop(s.imported), completed: drop(s.completed), lastAccess: drop(s.lastAccess),
            collections: s.collections.map((c) => ({ ...c, itemIds: c.itemIds.filter((i) => i !== id) })), history: s.history.filter((h) => h.id !== id),
          };
        });
      },

      setGameState(id, patch) {
        const blank: GameState = { installed: false, playMinutes: 0, sessions: [] };
        set((s) => ({ gameState: { ...s.gameState, [id]: { ...blank, ...(s.gameState[id] as GameState | undefined), ...patch } } }));
      },
      logPlaySession(id, minutes) {
        const prev = get().gameState[id] ?? { installed: false, playMinutes: 0, sessions: [] };
        get().setGameState(id, { playMinutes: Math.max(0, prev.playMinutes + minutes), lastPlayed: Date.now(), sessions: [{ ts: Date.now(), minutes }, ...prev.sessions].slice(0, 30) });
        get().touch(id);
      },

      addBookmark(bookId, b) { set((s) => ({ bookmarks: { ...s.bookmarks, [bookId]: [...(s.bookmarks[bookId] ?? []), { ...b, id: uid('bm-'), ts: Date.now() }] } })); },
      removeBookmark(bookId, id) { set((s) => ({ bookmarks: { ...s.bookmarks, [bookId]: (s.bookmarks[bookId] ?? []).filter((b) => b.id !== id) } })); },
      addNote(bookId, n) { set((s) => ({ bookNotes: { ...s.bookNotes, [bookId]: [...(s.bookNotes[bookId] ?? []), { ...n, id: uid('note-'), ts: Date.now() }] } })); },
      removeNote(bookId, id) { set((s) => ({ bookNotes: { ...s.bookNotes, [bookId]: (s.bookNotes[bookId] ?? []).filter((n) => n.id !== id) } })); },
      setBookReader(patch) { set((s) => ({ bookReader: { ...s.bookReader, ...patch } })); },
      setMangaReader(patch) { set((s) => ({ mangaReader: { ...s.mangaReader, ...patch } })); },

      setOverride(id, patch) { set((s) => ({ overrides: { ...s.overrides, [id]: { ...s.overrides[id], ...patch } } })); },
      replaceOverride(id, next) {
        set((s) => {
          const { [id]: _, ...rest } = s.overrides;
          return { overrides: Object.keys(next).length ? { ...rest, [id]: next } : rest };
        });
      },
      clearOverride(id, keys) {
        set((s) => {
          const { [id]: current, ...rest } = s.overrides;
          if (!current || !keys) return { overrides: rest };
          const next = { ...current };
          keys.forEach((k) => delete next[k]);
          return { overrides: Object.keys(next).length ? { ...rest, [id]: next } : rest };
        });
      },
      setImported(id, providerId, fields) {
        set((s) => {
          const forItem = { ...(s.imported[id] ?? {}) };
          if (fields) forItem[providerId] = fields; else delete forItem[providerId];
          const { [id]: _, ...rest } = s.imported;
          return { imported: Object.keys(forItem).length ? { ...rest, [id]: forItem } : rest };
        });
      },

      set(key, value) { set((s) => ({ [key]: typeof value === 'function' ? (value as (p: unknown) => unknown)(s[key]) : value }) as Partial<Store>); },
      setTheme(patch) { set((s) => ({ theme: { ...s.theme, ...patch } })); },
      setSetting(key, value) { set((s) => ({ settings: { ...s.settings, [key]: value } })); },
      updateLayout(id, patch) { set((s) => ({ layouts: s.layouts.map((l) => (l.id === id ? { ...l, ...patch } : l)) })); },
      updateSource(id, patch) { set((s) => ({ sources: s.sources.map((x) => (x.id === id ? { ...x, ...patch } : x)) })); },
      updateAddon(id, patch) { set((s) => ({ addons: s.addons.map((x) => (x.id === id ? { ...x, ...patch } : x)) })); },
      upsertRemote(item) {
        set((s) => {
          const next = { ...s.remoteItems, [item.id]: { ...item, addedAt: s.remoteItems[item.id]?.addedAt ?? item.addedAt } };
          // Keep the in-memory cache bounded: drop the oldest titles you haven't kept.
          const ids = Object.keys(next);
          if (ids.length > 500) ids.filter((id) => id !== item.id && !isKept(s, id)).sort((a, b) => (s.lastAccess[a] ?? 0) - (s.lastAccess[b] ?? 0)).slice(0, 120).forEach((id) => delete next[id]);
          return { remoteItems: next };
        });
      },
      pushSearch(q) {
        const term = q.trim();
        if (!term || !get().settings.privacy.searchHistory) return;
        set((s) => ({ searchHistory: [term, ...s.searchHistory.filter((h) => h.toLowerCase() !== term.toLowerCase())].slice(0, 12) }));
      },
      notify(n) { set((s) => ({ notifications: [{ ...n, id: uid('n-'), ts: Date.now(), read: false }, ...s.notifications].slice(0, 60) })); },
      clearHistory() { set({ history: [], recentTracks: [], searchHistory: [], lastAccess: {} }); },
      resetAll() { set(seed()); },
    }),
    {
      name: storageKey(STORAGE_KEY),
      version: 3,
      migrate: (persisted, version) => migrateStore(persisted, version),
      storage: createJSONStorage(() => kvStorage),
      // Remote titles are a cache: only the ones you have kept are written to disk.
      partialize: (s) => ({ ...s, remoteItems: Object.fromEntries(Object.entries(s.remoteItems).filter(([id]) => isKept(s, id))) }),
      // Merge persisted data over fresh defaults so new fields added in later versions get sane values.
      merge: (persisted, current) => {
        const p = (persisted ?? {}) as Partial<Data>;
        const account = useAuth.getState().current;
        // Built-in addons added in later versions appear for existing profiles too.
        // Built-in addons always use the current definition (catalogues, copy); only their on/off state is kept.
        const fresh = new Map(current.addons.filter((b) => b.builtin).map((b) => [b.id, b]));
        const addons = (p.addons ?? current.addons).map((a) => (a.builtin && fresh.has(a.id) ? { ...fresh.get(a.id)!, enabled: a.enabled } : a));
        for (const b of current.addons) if (b.builtin && !addons.some((a) => a.id === b.id)) addons.push(b);
        // Bring older saved profiles up to date with features added since they were written.
        const after = <T,>(arr: T[], anchor: (x: T) => boolean, item: T) => { const i = arr.findIndex(anchor); const next = arr.slice(); next.splice(i < 0 ? next.length : i + 1, 0, item); return next; };
        const nav = p.nav ? { ...p.nav, order: p.nav.order.includes('addons') ? p.nav.order : after(p.nav.order, (x) => x === 'sources', 'addons' as NavId) } : current.nav;
        const layouts = (p.layouts ?? current.layouts).map((l) => (l.widgets.some((w) => w.id === 'addons') ? l : { ...l, widgets: after(l.widgets, (w) => w.id === 'pinned', { id: 'addons' as WidgetId, visible: true }) }));
        return {
          ...current, ...p, nav, layouts, addons,
          proDemo: account?.plan === 'pro' ? true : p.proDemo ?? current.proDemo,
          profile: account && (p.profile?.name ?? 'Guest') === 'Guest' ? { ...current.profile, ...p.profile, name: account.name } : p.profile ?? current.profile,
          settings: { ...current.settings, ...p.settings, streamFilters: { ...current.settings.streamFilters, ...p.settings?.streamFilters }, notify: { ...current.settings.notify, ...p.settings?.notify }, playback: { ...current.settings.playback, ...p.settings?.playback }, downloads: { ...current.settings.downloads, ...p.settings?.downloads }, privacy: { ...current.settings.privacy, ...p.settings?.privacy } },
          theme: { ...current.theme, ...p.theme },
          bookReader: { ...current.bookReader, ...p.bookReader },
          mangaReader: { ...current.mangaReader, ...p.mangaReader },
          shortcuts: { ...current.shortcuts, ...p.shortcuts },
        };
      },
    },
  ),
);

export const getState = () => useStore.getState();
export const activeLayout = (s: Store): HomeLayout => s.layouts.find((l) => l.id === s.activeLayout) ?? s.layouts[0];
export const emptyLayout = (name: string, types: MediaType[] = []): HomeLayout => ({ ...defaultLayout(), id: uid('layout-'), name, types });
