import type { MediaItem, Playlist, SongSpec, Track } from '../types';
import { songDuration } from '../lib/synth';

/*
 * Music sample library.
 *
 * Every artist here is fictional and every track is ORIGINAL audio generated
 * in the browser by src/lib/synth.ts from the numeric spec below — nothing is
 * streamed from a third-party music service.
 */

const DAY = 86_400_000;
const NOW = Date.now();

export interface Artist { id: string; name: string; bio: string; genres: string[]; palette: number }

export const ARTISTS: Artist[] = [
  { id: 'artist:halcyon-drift', name: 'Halcyon Drift', bio: 'Slow-moving ambient pieces built from soft pads and distant bells. (Fictional demo artist — audio is generated in your browser.)', genres: ['Ambient'], palette: 1 },
  { id: 'artist:neon-arcade', name: 'Neon Arcade', bio: 'Arpeggios, gated drums and a permanent 1986 sunset. (Fictional demo artist — audio is generated in your browser.)', genres: ['Synthwave'], palette: 11 },
  { id: 'artist:paper-lanterns', name: 'Paper Lanterns', bio: 'Dusty, unhurried beats for reading and rainy windows. (Fictional demo artist — audio is generated in your browser.)', genres: ['Lo-fi'], palette: 15 },
  { id: 'artist:bitwise', name: 'Bitwise', bio: 'Square waves with something to prove. (Fictional demo artist — audio is generated in your browser.)', genres: ['Chiptune'], palette: 12 },
  { id: 'artist:velvet-circuit', name: 'Velvet Circuit', bio: 'Four-on-the-floor grooves for very late evenings. (Fictional demo artist — audio is generated in your browser.)', genres: ['House'], palette: 4 },
  { id: 'artist:north-atlas', name: 'North Atlas', bio: 'Electronic sketches of cold coastlines. (Fictional demo artist — audio is generated in your browser.)', genres: ['Ambient', 'Electronic'], palette: 7 },
];

type Style = SongSpec['style'];
const BPM: Record<Style, [number, number]> = { ambient: [62, 74], synthwave: [104, 118], lofi: [72, 84], chip: [128, 148], house: [120, 126] };
const BARS: Record<Style, number> = { ambient: 16, synthwave: 32, lofi: 24, chip: 36, house: 36 };

let seedCounter = 1000;
function album(
  slug: string, artist: Artist, title: string, year: number, kind: 'Album' | 'EP', style: Style, palette: number,
  popularity: number, added: number, description: string, titles: string[], minor = true,
): MediaItem {
  const id = `album:${slug}`;
  const tracks: Track[] = titles.map((t, i) => {
    const seed = ++seedCounter * 7919;
    const [lo, hi] = BPM[style];
    const spec: SongSpec = {
      seed, style, minor: style === 'chip' ? i % 2 === 0 : minor,
      bpm: lo + ((seed >> 3) % (hi - lo + 1)),
      bars: BARS[style] + (i % 3) * 4,
      root: 45 + ((seed >> 5) % 10),
    };
    return { id: `track:${slug}:${i + 1}`, n: i + 1, title: t, artist: artist.name, albumId: id, duration: songDuration(spec), src: 'synth:', spec };
  });
  return {
    id, type: 'album', demo: true, fictional: true, title, description, releaseDate: String(year), genres: artist.genres,
    tags: ['generated audio', 'demo'], creator: artist.name, publisher: 'DebridOne Demo Records', rating: 6.8 + (popularity % 20) / 10,
    language: 'Instrumental', license: 'Original generated audio', palette, popularity, addedAt: NOW - added * DAY,
    album: { kind, tracks },
  };
}

const [halcyon, neon, lanterns, bitwise, velvet, atlas] = ARTISTS;

export const ALBUMS: MediaItem[] = [
  album('tidal-memory', halcyon, 'Tidal Memory', 2024, 'Album', 'ambient', 1, 86, 6, 'Five long exhalations recorded (procedurally) at the edge of an imaginary sea.', ['Low Tide', 'Glass Harbor', 'Salt Air', 'Afterglow', 'Undertow'], false),
  album('night-garden', halcyon, 'Night Garden', 2025, 'EP', 'ambient', 3, 72, 1, 'A short suite for late hours and open windows.', ['Moonflower', 'Moth Light', 'Dew Point'], true),
  album('sunset-protocol', neon, 'Sunset Protocol', 2023, 'Album', 'synthwave', 11, 94, 12, 'Driving music for a coastline that only exists on VHS.', ['Chrome Horizon', 'Midnight Drive', 'Laser Palms', 'Turbo Heart', 'VHS Dreams']),
  album('overdrive-city', neon, 'Overdrive City', 2025, 'Album', 'synthwave', 4, 89, 3, 'Faster, brighter, and with the top down.', ['Skyline Run', 'Arcade Rain', 'Neon Exit', 'Afterburner']),
  album('rainy-day-tapes', lanterns, 'Rainy Day Tapes', 2024, 'Album', 'lofi', 15, 91, 8, 'Soft drums, softer chords. Best enjoyed with a book.', ['Window Seat', 'Tea Steam', 'Dog-Eared Pages', 'Slow Sunday', 'Umbrella Walk'], false),
  album('second-cup', lanterns, 'Second Cup', 2025, 'EP', 'lofi', 5, 68, 2, 'Three more for the road, or for the couch.', ['Refill', 'Crumbs', 'Half Past'], true),
  album('8-bit-odyssey', bitwise, '8-Bit Odyssey', 2022, 'Album', 'chip', 12, 83, 22, 'A soundtrack for a platformer nobody made.', ['Press Start', 'Pixel Plains', 'Boss Rush', 'Save Point', 'Credits Roll']),
  album('continue', bitwise, 'Continue?', 2025, 'EP', 'chip', 8, 70, 4, 'Insert coin. Three bonus stages.', ['Extra Life', 'Warp Zone', 'Game Over (Not Really)']),
  album('afterhours', velvet, 'Afterhours', 2024, 'Album', 'house', 6, 88, 10, 'Warm stabs and a kick drum that never gets tired.', ['Doorway', 'Basement Light', 'Kinetic', 'Last Train']),
  album('cartography', atlas, 'Cartography', 2023, 'Album', 'ambient', 7, 77, 17, 'Six coordinates, four of them sonic.', ['Sixty Degrees North', 'Fjord', 'Lighthouse Interval', 'Ice Shelf'], true),
];

export const DEMO_TRACKS: Track[] = ALBUMS.flatMap((a) => a.album!.tracks);

const byStyle = (style: Style) => DEMO_TRACKS.filter((t) => t.spec?.style === style).map((t) => t.id);
const pick = (ids: string[], every: number, offset = 0) => ids.filter((_, i) => (i + offset) % every === 0);

export const DEMO_PLAYLISTS: Playlist[] = [
  { id: 'playlist:focus-flow', name: 'Focus Flow', description: 'Ambient and lo-fi with no surprises. Demo playlist.', trackIds: [...pick(byStyle('ambient'), 2), ...pick(byStyle('lofi'), 2)], createdAt: NOW - 20 * DAY, demo: true, palette: 1 },
  { id: 'playlist:night-drive', name: 'Night Drive', description: 'Synthwave and house for empty roads. Demo playlist.', trackIds: [...pick(byStyle('synthwave'), 2), ...pick(byStyle('house'), 2)], createdAt: NOW - 15 * DAY, demo: true, palette: 11 },
  { id: 'playlist:arcade-energy', name: 'Arcade Energy', description: 'Chiptune at full tilt. Demo playlist.', trackIds: byStyle('chip'), createdAt: NOW - 9 * DAY, demo: true, palette: 12 },
  { id: 'playlist:sunday-reset', name: 'Sunday Reset', description: 'A bit of everything, gently. Demo playlist.', trackIds: [...pick(byStyle('lofi'), 2, 1), ...pick(byStyle('ambient'), 3, 1), ...pick(byStyle('house'), 3, 1)], createdAt: NOW - 3 * DAY, demo: true, palette: 15 },
];
