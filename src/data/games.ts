import type { MediaItem } from '../types';

/*
 * Gaming sample catalogue: real free / open-source games. DebridOne does not
 * host or mirror any of them — "Get" always opens the project's official site.
 */

const DAY = 86_400_000;
const NOW = Date.now();

interface G {
  slug: string; title: string; year: number; dev: string; pub?: string; genres: string[]; platforms: string[]; license: string;
  site: string; description: string; palette: number; popularity: number; added: number; rating: number; sizeMB?: number; tags?: string[];
}
const PC = ['Windows', 'macOS', 'Linux'];

const game = (g: G): MediaItem => ({
  id: `game:${g.slug}`, type: 'game', demo: true, title: g.title, description: g.description, releaseDate: String(g.year),
  genres: g.genres, tags: ['open source', ...(g.tags ?? [])], creator: g.dev, publisher: g.pub ?? g.dev, rating: g.rating,
  language: 'Multi-language', license: g.license, palette: g.palette, popularity: g.popularity, addedAt: NOW - g.added * DAY,
  game: { platforms: g.platforms, website: g.site, license: g.license, sizeMB: g.sizeMB },
});

export const GAMES: MediaItem[] = [
  game({ slug: '0ad', title: '0 A.D.', year: 2010, dev: 'Wildfire Games', genres: ['Strategy', 'RTS'], platforms: PC, license: 'GPL-2.0+ / CC BY-SA', site: 'https://play0ad.com', palette: 15, popularity: 93, added: 3, rating: 8.4, sizeMB: 3500, tags: ['historical', 'multiplayer'],
    description: 'A free, open-source real-time strategy game of ancient warfare. Lead Romans, Persians, Mauryas and more, gather resources, raise armies and besiege your rivals’ cities.' }),
  game({ slug: 'supertuxkart', title: 'SuperTuxKart', year: 2007, dev: 'SuperTuxKart Team', genres: ['Racing', 'Arcade'], platforms: [...PC, 'Android'], license: 'GPL-3.0', site: 'https://supertuxkart.net', palette: 3, popularity: 90, added: 6, rating: 8.0, sizeMB: 700, tags: ['kart', 'multiplayer', 'family'],
    description: 'A 3D arcade kart racer starring the mascots of open-source projects. Story mode, time trials, battle arenas and online multiplayer across a wide variety of tracks.' }),
  game({ slug: 'wesnoth', title: 'The Battle for Wesnoth', year: 2003, dev: 'The Battle for Wesnoth Project', genres: ['Strategy', 'Turn-based'], platforms: PC, license: 'GPL-2.0+', site: 'https://www.wesnoth.org', palette: 5, popularity: 88, added: 9, rating: 8.6, sizeMB: 500, tags: ['fantasy', 'hex', 'campaigns'],
    description: 'A turn-based tactical strategy game in a high-fantasy setting. Build an army from veterans who carry over between scenarios, across more than a dozen campaigns and countless add-ons.' }),
  game({ slug: 'openttd', title: 'OpenTTD', year: 2004, dev: 'OpenTTD Team', genres: ['Simulation', 'Management'], platforms: PC, license: 'GPL-2.0', site: 'https://www.openttd.org', palette: 13, popularity: 91, added: 13, rating: 8.7, sizeMB: 40, tags: ['transport', 'tycoon'],
    description: 'An open-source business simulation in which you build a transport empire of trains, buses, ships and aircraft. Based on a 1990s classic, with larger maps, multiplayer and extensive modding.' }),
  game({ slug: 'xonotic', title: 'Xonotic', year: 2011, dev: 'Team Xonotic', genres: ['Shooter', 'Arena FPS'], platforms: PC, license: 'GPL-3.0+', site: 'https://xonotic.org', palette: 11, popularity: 79, added: 17, rating: 7.7, sizeMB: 1200, tags: ['fast-paced', 'multiplayer'],
    description: 'A fast arena first-person shooter with crisp movement, a wide arsenal and many game modes, from capture-the-flag to freeze tag.' }),
  game({ slug: 'veloren', title: 'Veloren', year: 2018, dev: 'Veloren Team', genres: ['RPG', 'Open World'], platforms: PC, license: 'GPL-3.0', site: 'https://veloren.net', palette: 7, popularity: 86, added: 2, rating: 8.1, sizeMB: 900, tags: ['voxel', 'multiplayer', 'exploration'],
    description: 'A multiplayer voxel RPG written in Rust. Explore a vast procedurally generated world of mountains, caves and towns, craft gear, tame creatures and fight alongside friends.' }),
  game({ slug: 'mindustry', title: 'Mindustry', year: 2017, dev: 'Anuken', genres: ['Strategy', 'Tower Defense'], platforms: [...PC, 'Android', 'iOS'], license: 'GPL-3.0', site: 'https://mindustrygame.github.io', palette: 2, popularity: 89, added: 5, rating: 8.5, sizeMB: 120, tags: ['factory', 'automation'],
    description: 'A hybrid of tower defence and factory building. Lay conveyor belts to feed turrets, design production chains and defend your core through a campaign or co-op multiplayer.' }),
  game({ slug: 'luanti', title: 'Luanti', year: 2010, dev: 'Luanti Team', genres: ['Sandbox', 'Open World'], platforms: [...PC, 'Android'], license: 'LGPL-2.1+', site: 'https://www.luanti.org', palette: 12, popularity: 84, added: 21, rating: 7.9, sizeMB: 60, tags: ['voxel', 'moddable', 'formerly Minetest'],
    description: 'An open-source voxel game engine and platform (formerly Minetest). Pick from hundreds of community-made games and mods, and build or survive alone or on servers.' }),
  game({ slug: 'cataclysm-dda', title: 'Cataclysm: Dark Days Ahead', year: 2013, dev: 'CleverRaven', genres: ['Roguelike', 'Survival'], platforms: [...PC, 'Android'], license: 'CC BY-SA 3.0', site: 'https://cataclysmdda.org', palette: 8, popularity: 81, added: 28, rating: 8.3, sizeMB: 400, tags: ['post-apocalyptic', 'deep simulation'],
    description: 'A turn-based survival roguelike set in a post-apocalyptic New England. Scavenge, craft, build vehicles and bases, and survive a world simulated in extraordinary detail.' }),
  game({ slug: 'endless-sky', title: 'Endless Sky', year: 2015, dev: 'Michael Zahniser', genres: ['Simulation', 'Space'], platforms: PC, license: 'GPL-3.0', site: 'https://endless-sky.github.io', palette: 6, popularity: 75, added: 34, rating: 7.8, sizeMB: 300, tags: ['trading', 'exploration'],
    description: 'A 2D space trading and combat game. Start with a single shuttle, then haul cargo, run missions, upgrade your fleet and follow a branching story across hundreds of star systems.' }),
  game({ slug: 'shattered-pixel-dungeon', title: 'Shattered Pixel Dungeon', year: 2014, dev: 'Shattered Pixel', genres: ['Roguelike', 'RPG'], platforms: [...PC, 'Android', 'iOS'], license: 'GPL-3.0', site: 'https://shatteredpixel.com', palette: 10, popularity: 87, added: 7, rating: 8.8, sizeMB: 60, tags: ['pixel art', 'dungeon crawler'],
    description: 'A traditional roguelike dungeon crawler with modern polish. Six hero classes, randomised levels and items, and runs that are easy to start and very hard to finish.' }),
  game({ slug: 'openra', title: 'OpenRA', year: 2007, dev: 'OpenRA Developers', genres: ['Strategy', 'RTS'], platforms: PC, license: 'GPL-3.0', site: 'https://www.openra.net', palette: 8, popularity: 78, added: 24, rating: 8.0, sizeMB: 100, tags: ['classic RTS engine', 'multiplayer'],
    description: 'An open-source engine that recreates and modernises classic 1990s real-time strategy games, with online play, replays, a map editor and rebalanced mods.' }),
  game({ slug: 'warzone-2100', title: 'Warzone 2100', year: 1999, dev: 'Warzone 2100 Project', pub: 'Pumpkin Studios (original)', genres: ['Strategy', 'RTS'], platforms: PC, license: 'GPL-2.0+', site: 'https://wz2100.net', palette: 9, popularity: 72, added: 38, rating: 7.6, sizeMB: 300, tags: ['unit design', 'sci-fi'],
    description: 'A 3D real-time strategy game released commercially in 1999 and open-sourced in 2004. Research a huge tech tree and design your own units from chassis, propulsion and weapon parts.' }),
  game({ slug: 'hedgewars', title: 'Hedgewars', year: 2006, dev: 'Hedgewars Team', genres: ['Strategy', 'Artillery'], platforms: PC, license: 'GPL-2.0', site: 'https://hedgewars.org', palette: 4, popularity: 70, added: 44, rating: 7.4, sizeMB: 200, tags: ['turn-based', 'party', 'multiplayer'],
    description: 'A turn-based artillery game starring heavily armed pink hedgehogs. Destructible terrain, dozens of absurd weapons and local or online multiplayer.' }),
  game({ slug: 'freedoom', title: 'Freedoom', year: 2003, dev: 'Freedoom Project', genres: ['Shooter', 'Retro FPS'], platforms: PC, license: 'BSD-3-Clause', site: 'https://freedoom.github.io', palette: 8, popularity: 69, added: 50, rating: 7.2, sizeMB: 30, tags: ['retro', 'moddable'],
    description: 'A complete set of free game data for classic-shooter source ports: levels, textures, sounds and music, all original, so the thousands of community-made mods can run on entirely free content.' }),
  game({ slug: 'freeciv', title: 'Freeciv', year: 1996, dev: 'The Freeciv Project', genres: ['Strategy', '4X', 'Turn-based'], platforms: PC, license: 'GPL-2.0+', site: 'https://www.freeciv.org', palette: 0, popularity: 74, added: 58, rating: 7.7, sizeMB: 120, tags: ['empire building', 'multiplayer'],
    description: 'A free empire-building strategy game. Guide a civilisation from the stone age to the space age through exploration, diplomacy, research and conquest.' }),
];
