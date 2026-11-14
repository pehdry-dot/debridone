import type { Credit, Episode, MediaItem, StreamSource, SubtitleTrack } from '../types';

/*
 * Movies & TV sample catalogue.
 *
 * Real entries are either Blender Foundation open movies (Creative Commons) or
 * public-domain films, streamed from the Internet Archive. Entries marked
 * `fictional` are invented placeholder titles that play a CC0 test clip.
 */

const DAY = 86_400_000;
const NOW = Date.now();
const ia = (identifier: string, file: string) => `https://archive.org/download/${identifier}/${encodeURIComponent(file)}`;

export const DEMO_SUBTITLES: SubtitleTrack[] = [
  { id: 'en', label: 'English (demo captions)', lang: 'en', src: './captions/demo-en.vtt' },
  { id: 'es', label: 'Español (demo captions)', lang: 'es', src: './captions/demo-es.vtt' },
];

const MDN = 'https://interactive-examples.mdn.mozilla.net/media/cc0-videos';
export const CLIP_FLOWER: StreamSource = { id: 'clip-flower', label: 'CC0 test clip “Flower” (MP4)', url: `${MDN}/flower.mp4`, quality: '540p', provider: 'MDN sample media', license: 'CC0 1.0', sizeMB: 1.1, cors: true };
export const CLIP_FLOWER_WEBM: StreamSource = { id: 'clip-flower-webm', label: 'CC0 test clip “Flower” (WebM)', url: `${MDN}/flower.webm`, quality: '540p', provider: 'MDN sample media', license: 'CC0 1.0', sizeMB: 0.6, cors: true };
export const CLIP_FRIDAY: StreamSource = { id: 'clip-friday', label: 'CC0 test clip “Friday” (MP4)', url: `${MDN}/friday.mp4`, quality: '360p', provider: 'MDN sample media', license: 'CC0 1.0', sizeMB: 0.5, cors: true };

const src = (id: string, identifier: string, file: string, quality: string, sizeMB: number, license: string, label?: string): StreamSource => ({
  id, label: label ?? `Internet Archive · ${quality}`, url: ia(identifier, file), quality, provider: 'Internet Archive', license, sizeMB,
});

const blenderCrew = (director: string, extra: Credit[] = []): Credit[] => [
  { name: director, role: 'Director' },
  { name: 'Ton Roosendaal', role: 'Producer' },
  ...extra,
];

interface VideoSeed {
  slug: string; title: string; year: number; runtime: number; genres: string[]; description: string;
  creator: string; publisher: string; rating: number; license: string; palette: number; popularity: number; added: number;
  cast?: Credit[]; crew?: Credit[]; sources: StreamSource[]; tags?: string[]; language?: string; fictional?: boolean; altTitle?: string;
}

const movie = (v: VideoSeed): MediaItem => ({
  id: `movie:${v.slug}`, type: 'movie', demo: true, fictional: v.fictional, title: v.title, altTitle: v.altTitle,
  description: v.description, releaseDate: String(v.year), genres: v.genres, tags: v.tags ?? [], creator: v.creator,
  publisher: v.publisher, rating: v.rating, language: v.language ?? 'English', license: v.license, palette: v.palette,
  popularity: v.popularity, addedAt: NOW - v.added * DAY,
  video: { runtime: v.runtime, cast: v.cast ?? [], crew: v.crew ?? [{ name: v.creator, role: 'Director' }], sources: v.sources, subtitles: DEMO_SUBTITLES },
});

const fictionalFilm = (slug: string, title: string, year: number, runtime: number, genres: string[], description: string, director: string, palette: number, popularity: number, added: number, clip: StreamSource): MediaItem =>
  movie({
    slug, title, year, runtime, genres, description, creator: director, publisher: 'DebridOne Demo Studio', rating: 6 + (popularity % 30) / 10,
    license: 'Fictional demo title', palette, popularity, added, fictional: true, tags: ['demo'],
    cast: [{ name: 'Demo Cast A', role: 'Lead' }, { name: 'Demo Cast B', role: 'Supporting' }],
    sources: [{ ...clip, label: `Sample stream · ${clip.label}` }],
  });

export const MOVIES: MediaItem[] = [
  movie({
    slug: 'big-buck-bunny', title: 'Big Buck Bunny', year: 2008, runtime: 10, genres: ['Animation', 'Comedy', 'Short'],
    description: 'A gentle giant of a rabbit has his peaceful morning ruined by three bullying rodents — and decides, for once, to get even. The Blender Foundation’s second open movie, made entirely with open-source tools.',
    creator: 'Sacha Goedegebure', publisher: 'Blender Foundation', rating: 7.4, license: 'CC BY 3.0', palette: 3, popularity: 98, added: 2,
    tags: ['open movie', 'blender', 'family'], altTitle: 'Project Peach',
    crew: blenderCrew('Sacha Goedegebure', [{ name: 'Jan Morgenstern', role: 'Music' }]),
    sources: [src('ia-720', 'BigBuckBunny_124', 'Content/big_buck_bunny_720p_surround.mp4', '720p', 62, 'CC BY 3.0')],
  }),
  movie({
    slug: 'sintel', title: 'Sintel', year: 2010, runtime: 15, genres: ['Animation', 'Fantasy', 'Short'],
    description: 'A lone traveller searches a harsh world for the baby dragon she once rescued and named Scales. A bittersweet fantasy from the Blender Foundation’s third open movie project.',
    creator: 'Colin Levy', publisher: 'Blender Foundation', rating: 7.5, license: 'CC BY 3.0', palette: 10, popularity: 95, added: 5,
    tags: ['open movie', 'blender', 'dragon'], altTitle: 'Project Durian',
    cast: [{ name: 'Halina Reijn', role: 'Sintel (voice)' }, { name: 'Thom Hoffman', role: 'Shaman (voice)' }],
    crew: blenderCrew('Colin Levy', [{ name: 'Esther Wouda', role: 'Writer' }, { name: 'Jan Morgenstern', role: 'Music' }]),
    sources: [src('ia-512', 'Sintel', 'sintel-2048-stereo_512kb.mp4', '480p', 77, 'CC BY 3.0')],
  }),
  movie({
    slug: 'tears-of-steel', title: 'Tears of Steel', year: 2012, runtime: 12, genres: ['Sci-Fi', 'Action', 'Short'],
    description: 'In a future Amsterdam, a group of scientists and soldiers stage a desperate re-enactment in the Oude Kerk to stop a robot uprising rooted in one bad breakup. Blender’s first live-action/VFX open movie.',
    creator: 'Ian Hubert', publisher: 'Blender Foundation', rating: 6.9, license: 'CC BY 3.0', palette: 1, popularity: 90, added: 9,
    tags: ['open movie', 'blender', 'vfx'], altTitle: 'Project Mango',
    cast: [
      { name: 'Derek de Lint', role: 'Old Thom' }, { name: 'Sergio Hasselbaink', role: 'Barley' }, { name: 'Rogier Schippers', role: 'Young Thom' },
      { name: 'Vanja Rukavina', role: 'Young Thom (teen)' }, { name: 'Denise Rebergen', role: 'Celia' }, { name: 'Jody Bhe', role: 'Captain' }, { name: 'Chris Haley', role: 'Tech' },
    ],
    crew: blenderCrew('Ian Hubert', [{ name: 'Joram Letwory', role: 'Music' }]),
    sources: [src('ia-720', 'Tears-of-Steel', 'tears_of_steel_720p.mp4', '720p', 76, 'CC BY 3.0'), src('ia-1080', 'Tears-of-Steel', 'tears_of_steel_1080p.mp4', '1080p', 76, 'CC BY 3.0')],
  }),
  movie({
    slug: 'elephants-dream', title: 'Elephants Dream', year: 2006, runtime: 11, genres: ['Animation', 'Sci-Fi', 'Short'],
    description: 'Two men wander a vast, shifting machine. The older one insists it is wonderful; the younger one is not so sure. The world’s first open movie, released with all of its production files.',
    creator: 'Bassam Kurdali', publisher: 'Blender Foundation', rating: 6.6, license: 'CC BY 2.5', palette: 7, popularity: 76, added: 14,
    tags: ['open movie', 'blender', 'surreal'], altTitle: 'Project Orange',
    cast: [{ name: 'Tygo Gernandt', role: 'Proog (voice)' }, { name: 'Cas Jansen', role: 'Emo (voice)' }],
    crew: blenderCrew('Bassam Kurdali', [{ name: 'Jan Morgenstern', role: 'Music' }]),
    sources: [src('ia-512', 'ElephantsDream', 'ed_1024_512kb.mp4', '480p', 47, 'CC BY 2.5'), src('ia-hd', 'ElephantsDream', 'ed_hd.mp4', '720p', 67, 'CC BY 2.5')],
  }),
  movie({
    slug: 'nosferatu', title: 'Nosferatu', year: 1922, runtime: 93, genres: ['Horror', 'Silent', 'Classic'],
    description: 'An estate agent travels to a remote castle to close a sale with the unnervingly thin Count Orlok — and brings a plague of shadows back home with him. The silent film that defined how vampires look on screen.',
    creator: 'F. W. Murnau', publisher: 'Prana Film', rating: 7.9, license: 'Public domain', palette: 9, popularity: 88, added: 20,
    tags: ['silent film', 'german expressionism', 'vampire'], altTitle: 'Nosferatu, eine Symphonie des Grauens', language: 'Silent (German intertitles)',
    cast: [{ name: 'Max Schreck', role: 'Count Orlok' }, { name: 'Gustav von Wangenheim', role: 'Thomas Hutter' }, { name: 'Greta Schröder', role: 'Ellen Hutter' }],
    sources: [src('ia-512', 'Nosferatu_most_complete_version_93_mins.', 'Nosferatu_1922_Symphony_of_Horror_512kb.mp4', '480p', 395, 'Public domain')],
  }),
  movie({
    slug: 'the-general', title: 'The General', year: 1926, runtime: 67, genres: ['Comedy', 'Action', 'Silent', 'Classic'],
    description: 'When his beloved locomotive is stolen, a railway engineer gives chase alone — on foot, by handcar, by bicycle and finally by train. Buster Keaton performs every stunt himself in one of cinema’s great chase films.',
    creator: 'Buster Keaton', publisher: 'United Artists', rating: 8.1, license: 'Public domain', palette: 15, popularity: 82, added: 26,
    tags: ['silent film', 'trains', 'stunts'], language: 'Silent (English intertitles)',
    cast: [{ name: 'Buster Keaton', role: 'Johnnie Gray' }, { name: 'Marion Mack', role: 'Annabelle Lee' }],
    crew: [{ name: 'Buster Keaton', role: 'Director' }, { name: 'Clyde Bruckman', role: 'Director' }],
    sources: [src('ia-512', 'TheGeneral', 'The_General_512kb.mp4', '480p', 293, 'Public domain')],
  }),
  movie({
    slug: 'his-girl-friday', title: 'His Girl Friday', year: 1940, runtime: 92, genres: ['Comedy', 'Romance', 'Classic'],
    description: 'A newspaper editor will do anything to stop his star reporter — who is also his ex-wife — from quitting to remarry, including dangling the story of the year in front of her. Famous for its overlapping, rapid-fire dialogue.',
    creator: 'Howard Hawks', publisher: 'Columbia Pictures', rating: 7.8, license: 'Public domain', palette: 5, popularity: 80, added: 32,
    tags: ['screwball', 'newspaper'],
    cast: [{ name: 'Cary Grant', role: 'Walter Burns' }, { name: 'Rosalind Russell', role: 'Hildy Johnson' }, { name: 'Ralph Bellamy', role: 'Bruce Baldwin' }],
    sources: [src('ia-512', 'his_girl_friday', 'his_girl_friday_512kb.mp4', '480p', 401, 'Public domain')],
  }),
  movie({
    slug: 'night-of-the-living-dead', title: 'Night of the Living Dead', year: 1968, runtime: 96, genres: ['Horror', 'Thriller', 'Classic'],
    description: 'Seven strangers barricade themselves in a Pennsylvania farmhouse as the recently dead begin to walk. George A. Romero’s independent landmark, in the public domain in the United States.',
    creator: 'George A. Romero', publisher: 'Image Ten', rating: 7.8, license: 'Public domain (US)', palette: 8, popularity: 91, added: 12,
    tags: ['zombies', 'independent film'],
    cast: [{ name: 'Duane Jones', role: 'Ben' }, { name: 'Judith O’Dea', role: 'Barbra' }, { name: 'Karl Hardman', role: 'Harry Cooper' }, { name: 'Marilyn Eastman', role: 'Helen Cooper' }],
    sources: [src('ia-720', 'Night.Of.The.Living.Dead_1080p', 'NightOfTheLivingDead_720p_512kb.mp4', '720p', 406, 'Public domain (US)')],
  }),
  movie({
    slug: 'a-trip-to-the-moon', title: 'A Trip to the Moon', year: 1902, runtime: 16, genres: ['Sci-Fi', 'Silent', 'Short', 'Classic'],
    description: 'A club of astronomers fire themselves at the Moon in a cannon-launched capsule, landing squarely in its eye. Georges Méliès’ hand-crafted fantasy is among the earliest science-fiction films.',
    creator: 'Georges Méliès', publisher: 'Star Film Company', rating: 8.2, license: 'Public domain', palette: 6, popularity: 74, added: 40,
    tags: ['silent film', 'early cinema'], altTitle: 'Le Voyage dans la Lune', language: 'Silent',
    cast: [{ name: 'Georges Méliès', role: 'Professor Barbenfouillis' }],
    sources: [src('ia-mp4', 'ATripToTheMoon1902', 'A Trip to the Moon (1902).mp4', '480p', 241, 'Public domain')],
  }),
  movie({
    slug: 'cc0-test-clips', title: 'CC0 Test Clips', year: 2019, runtime: 0.1, genres: ['Short', 'Test media'],
    description: 'Two tiny public-domain-dedicated (CC0) clips used by web developers for testing. They load instantly, which makes them ideal for trying the player controls and the download manager’s live progress.',
    creator: 'MDN contributors', publisher: 'MDN sample media', rating: 5, license: 'CC0 1.0', palette: 4, popularity: 40, added: 1,
    tags: ['test clip', 'tiny file'],
    crew: [{ name: 'MDN contributors', role: 'Source' }],
    sources: [CLIP_FLOWER, CLIP_FLOWER_WEBM, CLIP_FRIDAY],
  }),
  fictionalFilm('paper-satellites', 'Paper Satellites', 2024, 104, ['Sci-Fi', 'Drama'], 'A retired radio operator starts receiving weather reports from a satellite that was never launched. (Fictional demo title — plays a CC0 sample clip.)', 'Imani Okafor', 14, 71, 3, CLIP_FLOWER),
  fictionalFilm('the-long-tide', 'The Long Tide', 2023, 118, ['Drama'], 'Three siblings return to a shrinking island to decide what to do with the family ferry. (Fictional demo title — plays a CC0 sample clip.)', 'Marit Solheim', 1, 64, 7, CLIP_FRIDAY),
  fictionalFilm('neon-orchard', 'Neon Orchard', 2025, 97, ['Thriller', 'Mystery'], 'A night-shift botanist discovers that someone is harvesting the city’s rooftop gardens before dawn. (Fictional demo title — plays a CC0 sample clip.)', 'Dae-ho Kwon', 11, 85, 1, CLIP_FLOWER),
  fictionalFilm('small-hours', 'Small Hours', 2022, 89, ['Romance', 'Comedy'], 'Two insomniacs keep meeting at the only 24-hour laundromat in town. (Fictional demo title — plays a CC0 sample clip.)', 'Lucía Ferrante', 4, 58, 18, CLIP_FRIDAY),
  fictionalFilm('cartographers', 'The Cartographers', 2021, 126, ['Adventure', 'Fantasy'], 'An apprentice map-maker is sent to chart a valley that moves every winter. (Fictional demo title — plays a CC0 sample clip.)', 'Tomasz Wrona', 12, 67, 30, CLIP_FLOWER),
  fictionalFilm('static-bloom', 'Static Bloom', 2024, 76, ['Documentary'], 'A portrait of the hobbyists who restore dead television transmitters for fun. (Fictional demo title — plays a CC0 sample clip.)', 'Asha Verma', 13, 49, 11, CLIP_FRIDAY),
];

/* ------------------------------ TV ------------------------------ */

const ep = (showSlug: string, s: number, n: number, title: string, description: string, runtime: number, sources: StreamSource[]): Episode => ({
  id: `${showSlug}:s${s}e${n}`, number: n, title, description, runtime, sources,
});
const sample = (clip: StreamSource): StreamSource[] => [{ ...clip, label: `Sample stream · ${clip.label}` }];

const fictionalShow = (slug: string, title: string, year: number, genres: string[], description: string, creator: string, palette: number, popularity: number, added: number, seasons: string[][]): MediaItem => ({
  id: `tv:${slug}`, type: 'tv', demo: true, fictional: true, title, description: `${description} (Fictional demo series — episodes play CC0 sample clips.)`,
  releaseDate: String(year), genres, tags: ['demo'], creator, publisher: 'DebridOne Demo Studio', rating: 6.5 + (popularity % 25) / 10,
  language: 'English', license: 'Fictional demo title', palette, popularity, addedAt: NOW - added * DAY,
  tv: {
    cast: [{ name: 'Demo Cast A', role: 'Lead' }, { name: 'Demo Cast B', role: 'Lead' }, { name: 'Demo Cast C', role: 'Recurring' }],
    crew: [{ name: creator, role: 'Creator' }],
    subtitles: DEMO_SUBTITLES,
    seasons: seasons.map((titles, si) => ({
      number: si + 1,
      episodes: titles.map((t, ei) => ep(slug, si + 1, ei + 1, t, `Episode ${ei + 1} of season ${si + 1}. Placeholder synopsis for the demo catalogue.`, 42 + ((ei * 7 + si * 3) % 14), sample((ei + si) % 2 ? CLIP_FRIDAY : CLIP_FLOWER))),
    })),
  },
});

export const SHOWS: MediaItem[] = [
  {
    id: 'tv:caminandes', type: 'tv', demo: true, title: 'Caminandes',
    description: 'Koro the llama has simple goals — cross the road, reach the berries, share the fence — and the Patagonian wilderness has other ideas. Three slapstick shorts from the Blender Institute, each released with its production files.',
    releaseDate: '2013', genres: ['Animation', 'Comedy', 'Family'], tags: ['open movie', 'blender', 'llama'], creator: 'Pablo Vazquez',
    publisher: 'Blender Institute', rating: 7.6, language: 'No dialogue', license: 'CC BY 3.0', palette: 2, popularity: 87, addedAt: NOW - 4 * DAY,
    tv: {
      cast: [],
      crew: [{ name: 'Pablo Vazquez', role: 'Director' }, { name: 'Ton Roosendaal', role: 'Producer' }],
      subtitles: DEMO_SUBTITLES,
      seasons: [{
        number: 1,
        episodes: [
          ep('caminandes', 1, 1, 'Llama Drama', 'Koro just wants to get to the other side of a very busy road.', 1.5, [src('ia-1080', 'Caminandes1LlamaDrama', '01_llama_drama_1080p.mp4', '1080p', 35, 'CC BY 3.0')]),
          ep('caminandes', 1, 2, 'Gran Dillama', 'An electric fence stands between Koro and a patch of fresh grass.', 2.5, [src('ia-1080', 'Caminandes2GranDillama', '02_gran_dillama_1080p.mp4', '1080p', 125, 'CC BY 3.0')]),
          ep('caminandes', 1, 3, 'Llamigos', 'Winter is coming, and Koro has to compete with a penguin for the last berries.', 2.5, [
            src('ia-1080', 'CaminandesLlamigos', 'Caminandes_ Llamigos-1080p.mp4', '1080p', 138, 'CC BY 3.0'),
            src('ia-webm', 'CaminandesLlamigos', 'Caminandes_ Llamigos-1080p.webm', '1080p WebM', 38, 'CC BY 3.0'),
          ]),
        ],
      }],
    },
  },
  fictionalShow('signal-lost', 'Signal Lost', 2024, ['Sci-Fi', 'Mystery'], 'The crew of a deep-space relay station wake to find eleven days missing from every log.', 'Noor Haddad', 6, 89, 2, [
    ['Dead Air', 'Eleven Days', 'The Quiet Channel', 'Carrier Wave', 'Handshake'],
  ]),
  fictionalShow('harbor-lights', 'Harbor Lights', 2022, ['Drama'], 'A small-town harbour master juggles smugglers, storms and a town council that wants to sell the docks.', 'Eleanor Whitcombe', 1, 73, 16, [
    ['Slack Water', 'Dredging', 'Fog Signal', 'Springs and Neaps'],
    ['The Breakwater', 'Salvage Rights', 'Low Pressure'],
  ]),
  fictionalShow('kitchen-table-science', 'Kitchen Table Science', 2023, ['Documentary', 'Family'], 'Big ideas explained with whatever happens to be in the cupboard.', 'Okonkwo & Reyes', 5, 61, 9, [
    ['Why Bread Rises', 'The Physics of Spaghetti', 'Ice That Sinks', 'A Short History of Soap'],
  ]),
];
