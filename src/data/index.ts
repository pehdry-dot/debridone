import type { MediaItem } from '../types';
import { MOVIES, SHOWS } from './video';
import { ALBUMS } from './music';
import { GAMES } from './games';
import { MANGA, BOOKS } from './reading';

/** Every bundled sample item. All of it is demo content (`demo: true`). */
export const DEMO_ITEMS: MediaItem[] = [...MOVIES, ...SHOWS, ...ALBUMS, ...GAMES, ...MANGA, ...BOOKS];

export { MOVIES, SHOWS, ALBUMS, GAMES, MANGA, BOOKS };
export { ARTISTS, DEMO_PLAYLISTS, DEMO_TRACKS } from './music';
export { BOOK_TEXTS } from './bookTexts';
