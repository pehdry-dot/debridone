import type { ManifestItem, Source, SourceCapability, SourceCategory, SourceStatus } from '../types';
import { SOURCE_CAPABILITIES, SOURCE_CATEGORIES } from '../types';

/*
 * Source management: built-in sources, manifest validation and connection tests.
 *
 * Safety model for user-added sources (nobody reviews them, so the app must
 * defend itself):
 *   - manifests are plain data, validated field by field; unknown or
 *     code-like fields are rejected and nothing is ever executed
 *   - only https endpoints are accepted, never localhost / private ranges
 *   - there is no server-side fetching at all in this prototype; connection
 *     tests run in the browser sandbox and are subject to CORS
 *   - executable downloads require explicit consent (see store/downloads.ts)
 *   - every user-added source is permanently labelled "unreviewed"
 */

const MDN = 'https://interactive-examples.mdn.mozilla.net/media';

export const BUILTIN_SOURCES: Source[] = [
  {
    id: 'src:internet-archive', name: 'Internet Archive — open & public-domain film', url: 'https://archive.org/metadata/Sintel/metadata/title',
    description: 'Streams for the Blender open movies and public-domain classics in the sample catalogue.',
    categories: ['movies', 'tv'], capabilities: ['catalog', 'stream', 'download'], auth: { type: 'none' }, enabled: true, status: 'unknown', trust: 'builtin',
  },
  {
    id: 'src:mdn-cc0', name: 'MDN CC0 sample media', url: `${MDN}/cc0-videos/flower.mp4`,
    description: 'Tiny CC0 test files. The host sends CORS headers, so downloads show real byte-level progress.',
    categories: ['movies', 'music'], capabilities: ['catalog', 'stream', 'download'], auth: { type: 'none' }, enabled: true, status: 'unknown', trust: 'builtin',
    items: [
      { title: 'Flower (MP4, CC0)', type: 'movies', url: `${MDN}/cc0-videos/flower.mp4`, sizeBytes: 1128375, license: 'CC0 1.0' },
      { title: 'Flower (WebM, CC0)', type: 'movies', url: `${MDN}/cc0-videos/flower.webm`, license: 'CC0 1.0' },
      { title: 'Friday (MP4, CC0)', type: 'movies', url: `${MDN}/cc0-videos/friday.mp4`, sizeBytes: 515198, license: 'CC0 1.0' },
      { title: 'T-Rex roar (MP3, CC0)', type: 'music', url: `${MDN}/cc0-audio/t-rex-roar.mp3`, sizeBytes: 39868, license: 'CC0 1.0' },
    ],
  },
  {
    id: 'src:generated-audio', name: 'DebridOne generated audio', url: 'builtin://synth',
    description: 'Original music synthesised in your browser. No network, no third-party catalogue.',
    categories: ['music'], capabilities: ['catalog', 'stream', 'download'], auth: { type: 'none' }, enabled: true, status: 'unknown', trust: 'builtin',
  },
  {
    id: 'src:open-games', name: 'Open-source games index', url: 'builtin://open-games',
    description: 'Metadata for free and open-source games. Downloads always go to each project’s official website.',
    categories: ['games'], capabilities: ['catalog', 'metadata'], auth: { type: 'none' }, enabled: true, status: 'unknown', trust: 'builtin',
  },
  {
    id: 'src:demo-manga', name: 'DebridOne placeholder manga', url: 'builtin://manga',
    description: 'Fictional series with procedurally drawn pages, for testing the reader.',
    categories: ['manga'], capabilities: ['catalog', 'stream'], auth: { type: 'none' }, enabled: true, status: 'unknown', trust: 'builtin',
  },
  {
    id: 'src:open-library', name: 'Open Library', url: 'https://openlibrary.org/search.json?q=moby+dick&limit=1&fields=title',
    description: 'Public book metadata API from the Internet Archive. Used by the metadata editor for books.',
    categories: ['books'], capabilities: ['search', 'metadata'], auth: { type: 'none' }, enabled: true, status: 'unknown', trust: 'builtin',
  },
  {
    id: 'src:gutenberg', name: 'Project Gutenberg', url: 'https://www.gutenberg.org/ebooks/1342',
    description: 'Full texts of the public-domain classics excerpted in the Books module. Opens in a new tab; download a file there and import it.',
    categories: ['books'], capabilities: ['catalog', 'download'], auth: { type: 'none' }, enabled: true, status: 'unknown', trust: 'builtin',
  },
  {
    id: 'src:local', name: 'This device', url: 'local://files',
    description: 'Import your own audio, video, EPUB, PDF, text and CBZ files. Stored in this browser only.',
    categories: [...SOURCE_CATEGORIES], capabilities: ['catalog', 'stream'], auth: { type: 'none' }, enabled: true, status: 'unknown', trust: 'builtin',
  },
  {
    id: 'src:example-user', name: 'Example community manifest', url: 'https://example.com/debridone/manifest.json',
    description: 'A sample of what a user-added source looks like. It points at a placeholder address and is disabled.',
    categories: ['books', 'music'], capabilities: ['catalog', 'download'], auth: { type: 'none' }, enabled: false, status: 'unknown', trust: 'unreviewed',
  },
];

/* ----------------------------- validation ----------------------------- */

const EXECUTABLE_EXT = /\.(exe|msi|bat|cmd|com|scr|ps1|vbs|js|jar|sh|bash|run|bin|app|dmg|pkg|deb|rpm|appimage|apk|ipa)(\?|#|$)/i;
export const isExecutableName = (nameOrUrl: string) => EXECUTABLE_EXT.test(nameOrUrl.trim());

const FORBIDDEN_KEY = /^(script|scripts|code|eval|exec|execute|command|cmd|shell|run|onload|oninstall|preinstall|postinstall|hook|hooks|binary|plugin|wasm)$/i;

function isPrivateHost(host: string): boolean {
  const h = host.toLowerCase().replace(/\.$/, '');
  if (h === 'localhost' || /\.(localhost|local|internal|intranet|lan|home|corp|home\.arpa)$/.test(h)) return true;
  if (h.startsWith('[')) return true; // IPv6 literals are refused outright
  if (/^(0x[0-9a-f]+|\d+)$/.test(h)) return true; // integer / hex encoded IPs
  const m = h.match(/^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$/);
  if (m) {
    const [a, b] = [Number(m[1]), Number(m[2])];
    if (a === 10 || a === 127 || a === 0 || a >= 224) return true;
    if (a === 172 && b >= 16 && b <= 31) return true;
    if (a === 192 && b === 168) return true;
    if (a === 169 && b === 254) return true;
    if (a === 100 && b >= 64 && b <= 127) return true;
  }
  return !h.includes('.');
}

/** Returns an error message, or null when the URL is acceptable for a user-added source. */
export function checkSourceUrl(raw: string): string | null {
  if (!raw || raw.length > 2048) return 'URL is missing or longer than 2048 characters.';
  let u: URL;
  try { u = new URL(raw); } catch { return 'That is not a valid URL.'; }
  if (u.protocol !== 'https:') return 'Only https:// endpoints are accepted.';
  if (u.username || u.password) return 'URLs must not contain embedded credentials.';
  if (isPrivateHost(u.hostname)) return 'Local and private-network addresses are not allowed.';
  return null;
}

const cleanText = (v: unknown, max: number) => String(v ?? '').replace(/<[^>]*>/g, '').replace(/[\u0000-\u001f]/g, ' ').trim().slice(0, max);

function findForbiddenKey(value: unknown, path = ''): string | null {
  if (Array.isArray(value)) {
    for (let i = 0; i < value.length; i++) { const hit = findForbiddenKey(value[i], `${path}[${i}]`); if (hit) return hit; }
  } else if (value && typeof value === 'object') {
    for (const [k, v] of Object.entries(value)) {
      if (FORBIDDEN_KEY.test(k)) return `${path}.${k}`.replace(/^\./, '');
      const hit = findForbiddenKey(v, `${path}.${k}`);
      if (hit) return hit;
    }
  }
  return null;
}

export interface ManifestResult { ok: boolean; errors: string[]; warnings: string[]; source?: Omit<Source, 'id' | 'enabled' | 'status' | 'trust'> }

const TOP_KEYS = ['manifestVersion', 'name', 'url', 'description', 'categories', 'capabilities', 'auth', 'items'];
const ITEM_KEYS = ['title', 'type', 'url', 'sizeBytes', 'license'];

/** Strictly validate an untrusted source manifest. Never throws; never executes anything. */
export function validateManifest(input: unknown): ManifestResult {
  const errors: string[] = [], warnings: string[] = [];
  if (!input || typeof input !== 'object' || Array.isArray(input)) return { ok: false, errors: ['Manifest must be a JSON object.'], warnings };
  if (JSON.stringify(input).length > 256 * 1024) return { ok: false, errors: ['Manifest is larger than 256 KB.'], warnings };
  const forbidden = findForbiddenKey(input);
  if (forbidden) return { ok: false, errors: [`Field “${forbidden}” looks like executable content. DebridOne never runs code from a manifest.`], warnings };

  const m = input as Record<string, unknown>;
  for (const k of Object.keys(m)) if (!TOP_KEYS.includes(k)) errors.push(`Unknown field “${cleanText(k, 40)}”.`);
  if (m.manifestVersion !== 1) errors.push('manifestVersion must be 1.');
  const name = cleanText(m.name, 80);
  if (name.length < 2) errors.push('name is required (2–80 characters).');
  const urlError = checkSourceUrl(String(m.url ?? ''));
  if (urlError) errors.push(`url: ${urlError}`);

  const categories = Array.isArray(m.categories) ? m.categories.filter((c): c is SourceCategory => SOURCE_CATEGORIES.includes(c as SourceCategory)) : [];
  if (!categories.length) errors.push(`categories must list at least one of: ${SOURCE_CATEGORIES.join(', ')}.`);
  else if (Array.isArray(m.categories) && categories.length !== m.categories.length) errors.push('categories contains unsupported values.');

  const capabilities = Array.isArray(m.capabilities) ? m.capabilities.filter((c): c is SourceCapability => SOURCE_CAPABILITIES.includes(c as SourceCapability)) : [];
  if (m.capabilities !== undefined && (!Array.isArray(m.capabilities) || capabilities.length !== m.capabilities.length)) errors.push(`capabilities may only contain: ${SOURCE_CAPABILITIES.join(', ')}.`);

  let authType: Source['auth']['type'] = 'none';
  if (m.auth !== undefined) {
    const a = m.auth as Record<string, unknown>;
    if (!a || typeof a !== 'object' || !['none', 'apiKey', 'bearer', 'basic'].includes(String(a.type))) errors.push('auth.type must be none, apiKey, bearer or basic.');
    else {
      authType = a.type as Source['auth']['type'];
      if (Object.keys(a).some((k) => k !== 'type')) errors.push('auth may only declare a type. Secrets are never stored in manifests.');
    }
  }

  const items: ManifestItem[] = [];
  if (m.items !== undefined) {
    if (!Array.isArray(m.items)) errors.push('items must be an array.');
    else if (m.items.length > 500) errors.push('items is limited to 500 entries.');
    else m.items.forEach((raw, i) => {
      if (!raw || typeof raw !== 'object') { errors.push(`items[${i}] must be an object.`); return; }
      const it = raw as Record<string, unknown>;
      const extra = Object.keys(it).find((k) => !ITEM_KEYS.includes(k));
      if (extra) { errors.push(`items[${i}]: unknown field “${cleanText(extra, 40)}”.`); return; }
      const title = cleanText(it.title, 160);
      const itemUrlError = checkSourceUrl(String(it.url ?? ''));
      if (!title) errors.push(`items[${i}]: title is required.`);
      if (itemUrlError) errors.push(`items[${i}].url: ${itemUrlError}`);
      if (!SOURCE_CATEGORIES.includes(it.type as SourceCategory)) errors.push(`items[${i}].type is not a supported category.`);
      if (it.sizeBytes !== undefined && (typeof it.sizeBytes !== 'number' || it.sizeBytes < 0 || !isFinite(it.sizeBytes))) errors.push(`items[${i}].sizeBytes must be a positive number.`);
      if (!title || itemUrlError) return;
      const executable = isExecutableName(String(it.url));
      if (executable) warnings.push(`“${title}” is an executable file. It will require explicit consent before download.`);
      items.push({ title, type: it.type as SourceCategory, url: String(it.url), sizeBytes: it.sizeBytes as number | undefined, license: it.license ? cleanText(it.license, 80) : undefined, executable });
    });
  }

  if (errors.length) return { ok: false, errors, warnings };
  return {
    ok: true, errors, warnings,
    source: { name, url: String(m.url), description: cleanText(m.description, 400), categories, capabilities: capabilities.length ? capabilities : ['catalog'], auth: { type: authType }, items: items.length ? items : undefined },
  };
}

/** Serialise a source back to the manifest format (no status, no secrets). */
export const toManifest = (s: Source) => ({
  manifestVersion: 1, name: s.name, url: s.url, description: s.description, categories: s.categories, capabilities: s.capabilities, auth: { type: s.auth.type },
  ...(s.items?.length ? { items: s.items.map(({ title, type, url, sizeBytes, license }) => ({ title, type, url, ...(sizeBytes ? { sizeBytes } : {}), ...(license ? { license } : {}) })) } : {}),
});

export const SAMPLE_MANIFEST = {
  manifestVersion: 1,
  name: 'My public-domain shelf',
  url: 'https://interactive-examples.mdn.mozilla.net/media/cc0-audio/t-rex-roar.mp3',
  description: 'Example manifest. Replace the URLs with content you are authorised to use.',
  categories: ['music', 'movies'],
  capabilities: ['catalog', 'download'],
  auth: { type: 'none' },
  items: [
    { title: 'T-Rex roar (CC0)', type: 'music', url: 'https://interactive-examples.mdn.mozilla.net/media/cc0-audio/t-rex-roar.mp3', sizeBytes: 39868, license: 'CC0 1.0' },
    { title: 'Friday clip (CC0)', type: 'movies', url: 'https://interactive-examples.mdn.mozilla.net/media/cc0-videos/friday.mp4', sizeBytes: 515198, license: 'CC0 1.0' },
  ],
};

/* --------------------------- connection test --------------------------- */

export interface TestResult { status: SourceStatus; note: string }

/**
 * Browser-side reachability test. There is no backend in this prototype, so a
 * cross-origin endpoint without CORS headers can only be reported as
 * "restricted": the host answered, but the app cannot read the response.
 */
export async function testConnection(source: Pick<Source, 'url' | 'trust'>): Promise<TestResult> {
  if (source.url.startsWith('builtin://')) return { status: 'online', note: 'Built into the app — always available.' };
  if (source.url.startsWith('local://')) return { status: 'online', note: 'File import is supported by this browser.' };
  const invalid = checkSourceUrl(source.url);
  if (invalid) return { status: 'offline', note: invalid };
  if (!navigator.onLine) return { status: 'offline', note: 'This device appears to be offline.' };

  const timeout = (ms: number) => { const c = new AbortController(); const t = setTimeout(() => c.abort(), ms); return { signal: c.signal, done: () => clearTimeout(t) }; };
  const started = performance.now();
  const a = timeout(7000);
  try {
    const res = await fetch(source.url, { method: 'GET', mode: 'cors', credentials: 'omit', redirect: 'follow', referrerPolicy: 'no-referrer', signal: a.signal });
    a.done();
    res.body?.cancel().catch(() => {});
    const ms = Math.round(performance.now() - started);
    if (res.ok || res.status === 206) return { status: 'online', note: `Responded ${res.status} in ${ms} ms with CORS enabled.` };
    return { status: 'offline', note: `Endpoint answered with HTTP ${res.status}.` };
  } catch {
    a.done();
  }
  const b = timeout(7000);
  try {
    await fetch(source.url, { method: 'GET', mode: 'no-cors', credentials: 'omit', referrerPolicy: 'no-referrer', signal: b.signal });
    b.done();
    return { status: 'restricted', note: 'Host is reachable, but it does not allow browser (CORS) access. A backend proxy or the desktop app would be needed to read it.' };
  } catch {
    b.done();
    return { status: 'offline', note: 'No response. The address may be wrong, offline, or blocked.' };
  }
}
