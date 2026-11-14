import type { MediaItem, MediaType, MetaFields, StreamSource } from '../types';
import { MOVIES, SHOWS } from '../data/video';
import { safeImageUrl } from '../lib/utils';
import { desktop } from './desktop';

/*
 * Integration adapters. Each external service sits behind a small interface so
 * a real implementation can replace the demo one without touching the UI.
 *
 * What is real here:
 *   - OpenLibraryProvider calls the public Open Library API (no key needed).
 * What is a demo adapter:
 *   - Real-Debrid / Premiumize: return sample files that point at the same
 *     openly licensed streams as the sample catalogue. No account is contacted.
 *   - DemoMetadataProvider: returns bundled "enriched" metadata.
 * What needs a backend:
 *   - TMDB / IGDB / MusicBrainz and live debrid accounts. Their API secrets
 *     must stay server-side, so the browser prototype only describes them.
 */

/* ------------------------------ debrid ------------------------------ */

export interface DebridAccount { username: string; plan: string; expires: string; demo: boolean }
export interface DebridFile { id: string; name: string; sizeMB: number; added: number; itemId: string; episodeId?: string; source: StreamSource }
export interface StreamLink { url: string; expiresAt: number; provider: string }

export interface DebridAdapter {
  id: string;
  name: string;
  blurb: string;
  website: string;
  /** false = demo adapter that never contacts the provider */
  live: boolean;
  connect(): Promise<DebridAccount>;
  listFiles(): Promise<DebridFile[]>;
  /** Exchange a file id for a short-lived, authorised stream link. */
  getStreamLink(fileId: string): Promise<StreamLink>;
}

const wait = (ms: number) => new Promise((r) => setTimeout(r, ms));

function demoFiles(provider: string, slugs: string[]): DebridFile[] {
  const files: DebridFile[] = [];
  for (const m of MOVIES) {
    const slug = m.id.split(':')[1];
    const source = m.video?.sources[0];
    if (!slugs.includes(slug) || !source) continue;
    const ext = source.url.split('.').pop()?.split('?')[0] ?? 'mp4';
    files.push({ id: `${provider}:${slug}`, name: `${m.title.replace(/[^\w]+/g, '.')}.${m.releaseDate}.${source.quality}.${ext}`, sizeMB: source.sizeMB ?? 0, added: m.addedAt, itemId: m.id, source });
  }
  const cam = SHOWS.find((s) => s.id === 'tv:caminandes');
  if (provider === 'real-debrid' && cam?.tv) for (const e of cam.tv.seasons[0].episodes) {
    files.push({ id: `${provider}:${e.id}`, name: `Caminandes.S01E0${e.number}.${e.title.replace(/[^\w]+/g, '.')}.1080p.mp4`, sizeMB: e.sources[0].sizeMB ?? 0, added: cam.addedAt, itemId: cam.id, episodeId: e.id, source: e.sources[0] });
  }
  return files;
}

function demoDebrid(id: string, name: string, blurb: string, website: string, slugs: string[]): DebridAdapter {
  const files = demoFiles(id, slugs);
  return {
    id, name, blurb, website, live: false,
    async connect() { await wait(700); return { username: 'demo-user', plan: 'Demo adapter (no real account)', expires: 'never — simulated', demo: true }; },
    async listFiles() { await wait(350); return files; },
    async getStreamLink(fileId) {
      await wait(450);
      const f = files.find((x) => x.id === fileId);
      if (!f) throw new Error('That file is no longer in the demo account.');
      return { url: f.source.url, expiresAt: Date.now() + 4 * 3_600_000, provider: name };
    },
  };
}

export const DEBRID_ADAPTERS: DebridAdapter[] = [
  demoDebrid('real-debrid', 'Real-Debrid', 'Personal cloud downloads and unrestricted links.', 'https://real-debrid.com', ['big-buck-bunny', 'sintel', 'tears-of-steel']),
  demoDebrid('premiumize', 'Premiumize', 'Cloud storage, transfers and streaming links.', 'https://www.premiumize.me', ['elephants-dream', 'a-trip-to-the-moon', 'the-general']),
];
export const getDebrid = (id: string) => DEBRID_ADAPTERS.find((a) => a.id === id);

/** Files in connected demo accounts that match an item (and optionally an episode). */
export async function debridMatches(connected: string[], itemId: string, episodeId?: string): Promise<{ adapter: DebridAdapter; file: DebridFile }[]> {
  const out: { adapter: DebridAdapter; file: DebridFile }[] = [];
  for (const id of connected) {
    const adapter = getDebrid(id);
    if (!adapter || (desktop && id === 'real-debrid')) continue;
    for (const file of await adapter.listFiles()) if (file.itemId === itemId && (!episodeId || file.episodeId === episodeId)) out.push({ adapter, file });
  }
  return out;
}

/* ----------------------------- metadata ----------------------------- */

export interface MetadataProvider {
  id: string;
  name: string;
  types: MediaType[];
  /** 'live' = works in the browser now; 'demo' = bundled sample data; 'backend' = needs a server-side key */
  kind: 'live' | 'demo' | 'backend';
  note: string;
  lookup?(item: MediaItem): Promise<Partial<MetaFields> | null>;
}

const DEMO_ENRICHMENT: Record<string, Partial<MetaFields>> = {
  'movie:big-buck-bunny': { altTitle: 'Peach Open Movie', tags: ['open movie', 'blender', 'family', 'short film', 'rabbit'], language: 'No dialogue', rating: 7.6 },
  'movie:sintel': { tags: ['open movie', 'blender', 'dragon', 'fantasy short'], language: 'English', rating: 7.6, collection: 'Blender Open Movies' },
  'movie:tears-of-steel': { tags: ['open movie', 'blender', 'vfx', 'amsterdam'], collection: 'Blender Open Movies' },
  'movie:nosferatu': { description: 'Unauthorised 1922 adaptation of “Dracula”: Count Orlok travels from the Carpathians to the town of Wisborg, bringing plague with him. A cornerstone of German Expressionist cinema.', tags: ['silent film', 'german expressionism', 'vampire', 'weimar cinema'] },
  'game:0ad': { tags: ['open source', 'historical', 'multiplayer', 'ancient warfare', 'moddable'], rating: 8.5 },
  'game:supertuxkart': { tags: ['open source', 'kart', 'multiplayer', 'family', 'split-screen'] },
  'album:sunset-protocol': { tags: ['generated audio', 'demo', 'retro', 'driving'], rating: 8.9 },
  'manga:clockwork-ronin': { altTitle: 'Zenmai Rōnin', tags: ['demo', 'manga', 'samurai', 'clockpunk'] },
  'book:moby-dick': { altTitle: 'The Whale', tags: ['classic', 'public domain', 'whaling', 'american literature'] },
};

export const METADATA_PROVIDERS: MetadataProvider[] = [
  {
    id: 'demo', name: 'DebridOne Demo Metadata', types: ['movie', 'tv', 'album', 'game', 'manga', 'book'], kind: 'demo',
    note: 'Bundled sample enrichment for a handful of demo titles. Works offline.',
    async lookup(item) {
      await wait(500);
      return DEMO_ENRICHMENT[item.id] ?? { tags: [...new Set([...item.tags, ...item.genres.map((g) => g.toLowerCase())])], language: item.language ?? 'English' };
    },
  },
  {
    id: 'openlibrary', name: 'Open Library', types: ['book'], kind: 'live',
    note: 'Live lookup against the public Open Library search API. Sends the book title and author only.',
    async lookup(item) {
      const params = new URLSearchParams({ title: item.title, limit: '1', fields: 'title,author_name,first_publish_year,subject,publisher,cover_i' });
      if (item.creator) params.set('author', item.creator);
      const res = await fetch(`https://openlibrary.org/search.json?${params}`, { credentials: 'omit', referrerPolicy: 'no-referrer' });
      if (!res.ok) throw new Error(`Open Library answered with HTTP ${res.status}.`);
      const doc = (await res.json())?.docs?.[0];
      if (!doc) return null;
      // Everything below is untrusted external data: coerce to plain strings and https-only URLs.
      const text = (v: unknown, max = 200) => (typeof v === 'string' ? v.replace(/<[^>]*>/g, '').slice(0, max) : undefined);
      const out: Partial<MetaFields> = {};
      if (text(doc.title)) out.title = text(doc.title)!;
      if (Array.isArray(doc.author_name) && text(doc.author_name[0])) out.creator = text(doc.author_name[0]);
      if (typeof doc.first_publish_year === 'number') out.releaseDate = String(doc.first_publish_year);
      if (Array.isArray(doc.subject)) out.tags = doc.subject.map((s: unknown) => text(s, 40)).filter(Boolean).slice(0, 8) as string[];
      if (Array.isArray(doc.publisher) && text(doc.publisher[0])) out.publisher = text(doc.publisher[0]);
      if (typeof doc.cover_i === 'number') out.cover = safeImageUrl(`https://covers.openlibrary.org/b/id/${doc.cover_i}-L.jpg`);
      return out;
    },
  },
  { id: 'tmdb', name: 'TMDB', types: ['movie', 'tv'], kind: 'backend', note: 'Requires an API key held by a backend service. Not configured in this prototype.' },
  { id: 'igdb', name: 'IGDB', types: ['game'], kind: 'backend', note: 'Requires OAuth client credentials held by a backend service. Not configured in this prototype.' },
  { id: 'musicbrainz', name: 'MusicBrainz', types: ['album'], kind: 'backend', note: 'Planned adapter. The demo library is generated audio, so there is nothing to look up yet.' },
];
export const getMetaProvider = (id: string) => METADATA_PROVIDERS.find((p) => p.id === id);
