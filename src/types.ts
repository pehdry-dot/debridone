/* Shared domain types for DebridOne. */

export type MediaType = 'movie' | 'tv' | 'album' | 'game' | 'manga' | 'book';

export const MEDIA_TYPES: MediaType[] = ['movie', 'tv', 'album', 'game', 'manga', 'book'];

export const TYPE_LABEL: Record<MediaType, string> = {
  movie: 'Movie', tv: 'TV', album: 'Music', game: 'Game', manga: 'Manga', book: 'Book',
};
export const TYPE_PLURAL: Record<MediaType, string> = {
  movie: 'Movies', tv: 'TV', album: 'Music', game: 'Games', manga: 'Manga', book: 'Books',
};

export interface Credit { name: string; role: string }

export interface StreamSource {
  id: string;
  label: string;
  url: string;
  quality: string;
  provider: string;
  license: string;
  sizeMB?: number;
  /** true when the file's host sends CORS headers, so the download manager can show real progress */
  cors?: boolean;
}

export interface SubtitleTrack { id: string; label: string; lang: string; src: string }

export interface Episode {
  id: string;
  number: number;
  title: string;
  description: string;
  runtime: number; // minutes
  sources: StreamSource[];
}
export interface Season { number: number; title?: string; episodes: Episode[] }

export interface SongSpec {
  seed: number; bpm: number; bars: number; root: number; minor: boolean;
  style: 'ambient' | 'synthwave' | 'lofi' | 'chip' | 'house';
}

export interface Track {
  id: string;
  n: number;
  title: string;
  artist: string;
  albumId: string;
  duration: number; // seconds
  /** `synth:` tracks are rendered in the browser from `spec`; `idb:<key>` are user imports; otherwise a URL. */
  src: string;
  spec?: SongSpec;
}

export interface Chapter {
  id: string;
  number: number;
  title: string;
  pages: number;
  /** generated = original procedural artwork; imported = user file stored in IndexedDB */
  source: 'generated' | 'imported';
}

/** Fields the universal metadata editor can change on any item. */
export interface MetaFields {
  title: string;
  altTitle?: string;
  description: string;
  releaseDate?: string; // ISO date or year
  genres: string[];
  tags: string[];
  cover?: string;
  banner?: string;
  creator?: string;
  publisher?: string;
  rating?: number; // 0–10
  language?: string;
  collection?: string;
  notes?: string;
  custom?: Record<string, string>;
}
export const META_KEYS: (keyof MetaFields)[] = [
  'title', 'altTitle', 'description', 'releaseDate', 'genres', 'tags', 'cover', 'banner',
  'creator', 'publisher', 'rating', 'language', 'collection', 'notes', 'custom',
];

/** Where an item that came from an addon or a live catalogue lives, so it can be re-fetched and matched to streams. */
export interface RemoteRef { addonId: string; type: string; id: string; partial?: boolean; fetchedAt?: number }

export interface MediaItem extends MetaFields {
  id: string;
  type: MediaType;
  remote?: RemoteRef;
  /** true for bundled sample content, false for things the user added */
  demo: boolean;
  /** fictional placeholder title (as opposed to a real open-licensed / public-domain work) */
  fictional?: boolean;
  license?: string;
  addedAt: number;
  popularity: number; // 0–100
  palette: number;
  video?: { runtime: number; cast: Credit[]; crew: Credit[]; sources: StreamSource[]; subtitles?: SubtitleTrack[] };
  tv?: { seasons: Season[]; cast: Credit[]; crew: Credit[]; subtitles?: SubtitleTrack[] };
  album?: { kind: 'Album' | 'EP' | 'Single' | 'Imports'; tracks: Track[] };
  game?: { platforms: string[]; website?: string; license?: string; sizeMB?: number };
  manga?: { chapters: Chapter[]; status: 'Ongoing' | 'Completed'; direction: 'rtl' | 'ltr' | 'webtoon'; color?: boolean };
  book?: { format: 'text' | 'epub' | 'pdf'; contentKey: string; pages?: number; gutenberg?: number; excerpt?: boolean };
}

export interface Progress {
  percent: number; // 0–100
  updatedAt: number;
  /** movie: seconds; tv: seconds in current episode; manga: page index; book: fraction within chapter */
  position: number;
  duration?: number;
  /** tv episode id, manga chapter id, book chapter index (as string) */
  unit?: string;
  /** per-unit completion for tv episodes / manga chapters */
  units?: Record<string, { position: number; done: boolean }>;
}

export interface Collection {
  id: string; name: string; description: string; itemIds: string[]; pinned: boolean; createdAt: number; kind?: 'collection' | 'shelf';
}
export interface Playlist { id: string; name: string; description: string; trackIds: string[]; createdAt: number; demo?: boolean; palette: number; cover?: string }

export interface GameState { installed: boolean; location?: string; version?: string; playMinutes: number; lastPlayed?: number; sessions: { ts: number; minutes: number }[] }
export interface Bookmark { id: string; chapter: number; fraction: number; label: string; ts: number }
export interface Note { id: string; chapter: number; text: string; ts: number }
export interface HistoryEntry { id: string; ts: number; kind: 'watch' | 'listen' | 'play' | 'read'; detail?: string }
export interface AppNotification { id: string; title: string; body?: string; ts: number; read: boolean; link?: string; tone?: 'info' | 'success' | 'warn' | 'error' }

export type SourceCategory = 'movies' | 'tv' | 'music' | 'games' | 'manga' | 'books';
export const SOURCE_CATEGORIES: SourceCategory[] = ['movies', 'tv', 'music', 'games', 'manga', 'books'];
export type SourceCapability = 'catalog' | 'search' | 'metadata' | 'stream' | 'download';
export const SOURCE_CAPABILITIES: SourceCapability[] = ['catalog', 'search', 'metadata', 'stream', 'download'];
export type SourceStatus = 'unknown' | 'online' | 'restricted' | 'offline' | 'checking';

export interface ManifestItem { title: string; type: SourceCategory; url: string; sizeBytes?: number; license?: string; executable?: boolean }
export interface Source {
  id: string;
  name: string;
  url: string;
  description: string;
  categories: SourceCategory[];
  capabilities: SourceCapability[];
  auth: { type: 'none' | 'apiKey' | 'bearer' | 'basic' };
  enabled: boolean;
  status: SourceStatus;
  statusNote?: string;
  lastChecked?: number;
  /** builtin = ships with the prototype and points at known legal content; unreviewed = added by the user */
  trust: 'builtin' | 'unreviewed';
  items?: ManifestItem[];
}

export interface MetaRule {
  id: string; enabled: boolean; name: string;
  when: { type: MediaType | 'any'; field: 'title' | 'genres' | 'tags' | 'creator' | 'publisher'; contains: string };
  then: { action: 'addTag' | 'setCollection' | 'setLanguage' | 'addGenre'; value: string };
}
export interface CustomFieldDef { id: string; name: string; types: MediaType[] }

export type ThemeMode = 'dark' | 'light' | 'oled';
export interface Theme {
  mode: ThemeMode;
  accent: string;
  radius: number;
  density: 'compact' | 'comfortable' | 'spacious';
  font: 'system' | 'rounded' | 'humanist' | 'serif' | 'mono';
  glass: boolean;
  glow: boolean;
  grain: boolean;
  animation: 'off' | 'subtle' | 'full';
  wallpaper: boolean;
  wallpaperDim: number; // 0–95
  wallpaperBlur: number; // px
  customCss: string;
  customCssEnabled: boolean;
  /** Width of posters and covers on shelves. */
  cardSize: 'small' | 'medium' | 'large';
  /** Large Apple-style page titles, or compact ones. */
  headerStyle: 'large' | 'compact';
  /** Depth of cards and panels. */
  shadows: 'none' | 'soft' | 'deep';
  /** Translucent sidebar (over the page) or solid. */
  sidebarStyle: 'translucent' | 'solid';
}
export interface ThemePreset { id: string; name: string; theme: Partial<Theme>; builtin?: boolean }

export type WidgetId =
  | 'hero' | 'continue-watching' | 'continue-listening' | 'continue-playing' | 'continue-reading'
  | 'recently-added' | 'downloads' | 'quick-access' | 'pinned' | 'addons';
export interface HomeLayout { id: string; name: string; widgets: { id: WidgetId; visible: boolean }[]; types: MediaType[] }

export interface BookReaderSettings { fontSize: number; font: 'serif' | 'sans' | 'mono' | 'rounded'; theme: 'light' | 'sepia' | 'dark'; lineHeight: number; layout: 'paged' | 'scroll'; width: number }
export interface MangaReaderSettings { mode: 'auto' | 'ltr' | 'rtl' | 'vertical' | 'webtoon'; fit: 'width' | 'height' | 'original'; zoom: number; bg: string }

/** AIOStreams-style filters applied to every list of streams in a section. */
export type StreamKindName = 'url' | 'torrent' | 'external' | 'youtube';
export interface StreamFilters {
  quality: 'any' | '480p' | '720p' | '1080p' | '2160p';
  kinds: StreamKindName[];
  maxSizeGB: number;
  include: string;
  exclude: string;
  sort: 'addon' | 'quality' | 'size' | 'seeders';
}

export interface Settings {
  language: 'en' | 'es' | 'pt' | 'fr' | 'de' | 'ja';
  startPage: string;
  timeFormat: '12h' | '24h';
  dateFormat: 'locale' | 'iso' | 'long';
  notify: { toasts: boolean; downloads: boolean; library: boolean; sources: boolean };
  playback: {
    quality: 'auto' | '1080p' | '720p' | '480p';
    resume: boolean; autoplayNext: boolean; rate: number; skipSeconds: number;
    subtitlesOn: boolean; subtitleLang: string; subtitleSize: 'sm' | 'md' | 'lg';
    pauseMusicOnVideo: boolean; musicVolume: number;
  };
  downloads: { concurrent: number; keepHistory: boolean; autoSave: boolean; location: string };
  privacy: { history: boolean; searchHistory: boolean };
  /** Base URL of an optional DebridOne backend. Empty in the browser-only prototype. */
  backendUrl: string;
  /** Show the bundled sample library alongside your own content. */
  showDemo: boolean;
  /** Permit addons served from localhost / private-network addresses (self-hosted addons). Off by default. */
  allowLocalAddons: boolean;
  /** Stream filters per section (movies, music, games, books). */
  streamFilters: Partial<Record<'movies' | 'music' | 'games' | 'books', Partial<StreamFilters>>>;
}
