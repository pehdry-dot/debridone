import type { MediaType, StreamFilters } from '../types';
import type { InstalledAddon } from './stremio';

/** The four addon sections on the Addons page. Each has its own metadata, catalogue, stream and filter settings. */
export type SectionKey = 'movies' | 'music' | 'games' | 'books';

export const SECTIONS: { key: SectionKey; label: string; media: MediaType[]; blurb: string }[] = [
  { key: 'movies', label: 'Movies & Series', media: ['movie', 'tv'], blurb: 'Films and series: catalogues, details, streams and subtitles.' },
  { key: 'music', label: 'Music', media: ['album'], blurb: 'Albums and tracks: metadata and cover art, plus any audio sources you add.' },
  { key: 'games', label: 'Games', media: ['game'], blurb: 'Game catalogues, store prices and details.' },
  { key: 'books', label: 'Books & Manga', media: ['book', 'manga'], blurb: 'Books, e-books and manga: metadata, public-domain downloads and streams.' },
];

export const sectionFor = (media: MediaType): SectionKey =>
  media === 'album' ? 'music' : media === 'game' ? 'games' : media === 'book' || media === 'manga' ? 'books' : 'movies';

/** Which section an addon belongs to, from the content types it declares. */
export function sectionOf(addon: InstalledAddon): SectionKey | null {
  const t = addon.manifest.types;
  if (t.some((x) => ['movie', 'series', 'tv', 'channel', 'anime', 'other'].includes(x))) return 'movies';
  if (t.some((x) => ['music', 'album', 'podcast', 'audiobook'].includes(x))) return 'music';
  if (t.some((x) => ['game', 'games'].includes(x))) return 'games';
  if (t.some((x) => ['book', 'books', 'manga', 'comic', 'comics', 'ebook'].includes(x))) return 'books';
  return null;
}

export const defaultFilters = (): StreamFilters => ({
  quality: 'any', kinds: ['url', 'torrent', 'external', 'youtube'], maxSizeGB: 0, include: '', exclude: 'cam, telesync, ts', sort: 'quality',
});
