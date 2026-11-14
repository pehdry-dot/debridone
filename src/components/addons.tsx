import { Cloud, Copy, Download, ExternalLink, Magnet, Play, Puzzle, RefreshCw, TriangleAlert, Youtube } from 'lucide-react';
import { useEffect, useMemo, useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { GAMES_ADDON_ID, loadGame, searchAllGames } from '../adapters/games';
import {
  browsableCatalogs, defaultStremioType, fetchManifest, getCatalog, getMeta, getStreams, getSubtitles, loadSubtitleAsVtt, magnetLink, normalizeAddonUrl,
  parseRemoteId, searchableCatalogs, supports, type AddonCatalog, type InstalledAddon, type StreamGroup, type StreamOption,
} from '../adapters/stremio';
import { defaultFilters, sectionFor, type SectionKey } from '../adapters/sections';
import { resolveOption } from '../adapters/realdebrid';
import { cn, fmtBytes } from '../lib/utils';
import { getPreview, registerPreviews } from '../store/catalog';
import { startDownload } from '../store/downloads';
import { useRdReady } from '../store/realdebrid';
import { toast } from '../store/ui';
import { useStore } from '../store/useStore';
import type { MediaItem, MediaType, StreamFilters, SubtitleTrack } from '../types';
import { TYPE_PLURAL } from '../types';
import { MediaCard, MediaGrid, Shelf } from './media';
import { Badge, Button, Callout, Select, Spinner } from './ui';

/* ------------------------------ addon lifecycle ------------------------------ */

/** Install (or update) an addon from whatever the user pasted. Throws a readable Error on failure. */
export async function installAddon(input: string): Promise<InstalledAddon> {
  const s = useStore.getState();
  const transportUrl = normalizeAddonUrl(input, s.settings.allowLocalAddons);
  const { manifest, latency } = await fetchManifest(transportUrl);
  const official = /v3-cinemeta\.strem\.io|opensubtitles-v3\.strem\.io|watchhub\.strem\.io|publicdomainmovies|v3-channels\.strem\.io/.test(transportUrl);
  const existing = s.addons.find((a) => a.id === manifest.id);
  const addon: InstalledAddon = { id: manifest.id, transportUrl, manifest, enabled: true, installedAt: existing?.installedAt ?? Date.now(), status: 'online', latency, lastChecked: Date.now(), official };
  s.set('addons', existing ? s.addons.map((a) => (a.id === manifest.id ? addon : a)) : [...s.addons, addon]);
  return addon;
}

/** Re-read an addon's manifest: confirms it is reachable and picks up new catalogues or versions. Built-in addons are always available. */
export async function checkAddon(addon: InstalledAddon): Promise<boolean> {
  const s = useStore.getState();
  if (addon.builtin) { s.updateAddon(addon.id, { status: 'online', lastChecked: Date.now() }); return true; }
  s.updateAddon(addon.id, { status: 'checking' });
  try {
    const { manifest, latency } = await fetchManifest(addon.transportUrl);
    useStore.getState().updateAddon(addon.id, { manifest: manifest.id === addon.id ? manifest : addon.manifest, status: 'online', latency, lastChecked: Date.now(), stub: false });
    return true;
  } catch {
    useStore.getState().updateAddon(addon.id, { status: 'offline', latency: undefined, lastChecked: Date.now() });
    return false;
  }
}

/** Bundled suggestions start with a minimal manifest; fetch the real one the first time the app runs online. */
export function refreshStubAddons() {
  for (const a of useStore.getState().addons) if (a.stub && a.enabled) void checkAddon(a);
}

/* ------------------------------- remote titles ------------------------------- */

const inflight = new Map<string, Promise<void>>();
const DAY = 86_400_000;

/** Make sure full details for a remote title are in the store (fetching them from an addon or the games catalogue if needed). */
export function ensureRemote(itemId: string): Promise<void> {
  const ref = parseRemoteId(itemId);
  if (!ref) return Promise.resolve();
  const s = useStore.getState();
  const have = s.remoteItems[itemId];
  if (have && !have.remote?.partial && Date.now() - (have.remote?.fetchedAt ?? 0) < DAY) return Promise.resolve();
  const running = inflight.get(itemId);
  if (running) return running;
  const job = (async () => {
    const base = have ?? getPreview(itemId);
    const isGame = ref.type === 'game' && /^(steam|wd|cs):/.test(ref.id);
    const full = isGame ? await loadGame(ref.id, base) : await getMeta(s.addons, ref.type, ref.id);
    const item = full ?? (base ? { ...base, remote: { ...(base.remote ?? { addonId: GAMES_ADDON_ID, type: ref.type, id: ref.id }), partial: false, fetchedAt: Date.now() } } : null);
    if (!item) throw new Error('None of your installed addons can describe this title. It may come from an addon you removed.');
    useStore.getState().upsertRemote(item);
  })().finally(() => inflight.delete(itemId));
  inflight.set(itemId, job);
  return job;
}

/* ------------------------------ catalogue rows ------------------------------ */

function CardSkeletons({ square }: { square?: boolean }) {
  return <>{Array.from({ length: 8 }, (_, i) => <div key={i} className="w-[var(--card-w)] shrink-0"><div className={cn('animate-pulse rounded-card bg-line/[0.07]', square ? 'aspect-square' : 'aspect-[2/3]')} /><div className="mt-2.5 h-3 w-3/4 animate-pulse rounded bg-line/[0.07]" /></div>)}</>;
}

export function CatalogShelf({ addon, catalog }: { addon: InstalledAddon; catalog: AddonCatalog }) {
  const box = useRef<HTMLDivElement>(null);
  const [visible, setVisible] = useState(false);
  const [state, setState] = useState<{ items: MediaItem[]; loading: boolean; error?: string }>({ items: [], loading: true });
  const [attempt, setAttempt] = useState(0);
  useEffect(() => {
    const el = box.current;
    if (!el || visible) return;
    const io = new IntersectionObserver((entries) => { if (entries.some((e) => e.isIntersecting)) { setVisible(true); io.disconnect(); } }, { rootMargin: '300px' });
    io.observe(el);
    return () => io.disconnect();
  }, [visible]);
  useEffect(() => {
    if (!visible) return;
    let off = false;
    setState({ items: [], loading: true });
    getCatalog(addon, catalog).then((items) => { registerPreviews(items); if (!off) setState({ items, loading: false }); }).catch((e) => { if (!off) setState({ items: [], loading: false, error: (e as Error).message }); });
    return () => { off = true; };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [visible, addon.transportUrl, catalog.type, catalog.id, attempt]);

  if (!state.loading && !state.error && !state.items.length) return null;
  const label = `${catalog.name}${/movie|series/.test(catalog.type) ? ` ${catalog.type === 'movie' ? 'Movies' : 'Series'}` : ''}`;
  return (
    <div ref={box}>
      <Shelf title={label} subtitle={`From ${addon.manifest.name}`} to={`/addons/catalog/${encodeURIComponent(addon.id)}/${encodeURIComponent(catalog.type)}/${encodeURIComponent(catalog.id)}`}>
        {state.loading ? <CardSkeletons /> : state.error ? (
          <div className="flex w-full items-center gap-3 rounded-btn border border-dashed border-line/10 px-4 py-6 text-[0.8125rem] text-muted">
            <TriangleAlert className="h-4 w-4 shrink-0 text-amber-400" /><span className="min-w-0 flex-1">{addon.manifest.name} didn’t answer: {state.error}.</span>
            <Button size="sm" icon={RefreshCw} onClick={() => setAttempt((a) => a + 1)}>Retry</Button>
          </div>
        ) : state.items.slice(0, 30).map((it) => <MediaCard key={it.id} item={it} className="w-[var(--card-w)] shrink-0 snap-start" />)}
      </Shelf>
    </div>
  );
}

/** Rows for every browsable catalogue your addons offer for the given media types. */
export function AddonShelves({ media, limit = 8 }: { media?: MediaType[]; limit?: number }) {
  const addons = useStore((s) => s.addons);
  const key = media?.join(',');
  // eslint-disable-next-line react-hooks/exhaustive-deps
  const rows = useMemo(() => browsableCatalogs(addons, media).slice(0, limit), [addons, key, limit]);
  if (!rows.length) {
    const label = media?.length ? media.map((m) => TYPE_PLURAL[m]).join(' or ').toLowerCase() : 'titles';
    return (
      <Callout tone="desktop" className="mb-9" title={`No addon is offering ${label} catalogues right now`} action={<Button size="sm" icon={Puzzle} onClick={() => { window.location.hash = '#/addons'; }}>Open Addons</Button>}>
        Addons supply the rows shown here. Turn on a built-in source or install one from the Addons page, and its rows appear here.
      </Callout>
    );
  }
  return <>{rows.map(({ addon, catalog }) => <CatalogShelf key={`${addon.id}|${catalog.type}|${catalog.id}`} addon={addon} catalog={catalog} />)}</>;
}

/** Search every addon that can search this category, plus the games catalogue. */
export function RemoteSearch({ q, scope }: { q: string; scope: string }) {
  const addons = useStore((s) => s.addons);
  const media: MediaType[] | undefined = scope === 'all' ? undefined : [scope as MediaType];
  const wantsGames = !media || media.includes('game');
  const cats = useMemo(() => searchableCatalogs(addons, media), [addons, scope]); // eslint-disable-line react-hooks/exhaustive-deps
  const [state, setState] = useState<{ items: MediaItem[]; loading: boolean; errors: string[] }>({ items: [], loading: false, errors: [] });
  useEffect(() => {
    if (!q) return;
    let off = false;
    setState({ items: [], loading: true, errors: [] });
    const tasks: Promise<MediaItem[] | string>[] = cats.map(({ addon, catalog }) =>
      getCatalog(addon, catalog, { search: q }).catch((e: Error) => `${addon.manifest.name}: ${e.message}`));
    if (wantsGames) tasks.push(searchAllGames(q).catch((e: Error) => `Games catalogue: ${e.message}`));
    Promise.all(tasks).then((results) => {
      if (off) return;
      const items: MediaItem[] = [], errors: string[] = [];
      for (const r of results) { if (typeof r === 'string') errors.push(r); else items.push(...r); }
      const seen = new Set<string>();
      const unique = items.filter((i) => !seen.has(i.id) && !!seen.add(i.id));
      registerPreviews(unique);
      setState({ items: unique, loading: false, errors });
    });
    return () => { off = true; };
  }, [q, cats, wantsGames]);
  if (!q) return null;
  return (
    <section className="mt-10">
      <h2 className="mb-3 flex items-center gap-2 text-lg font-semibold tracking-tight">From your addons {state.loading && <Spinner className="h-4 w-4" />}</h2>
      {!cats.length && !wantsGames && <Callout tone="desktop" action={<Button size="sm" icon={Puzzle} onClick={() => { window.location.hash = '#/addons'; }}>Open Addons</Button>}>No installed addon can search this category yet.</Callout>}
      {state.errors.map((e) => <p key={e} className="mb-2 text-xs text-amber-400">{e}</p>)}
      {state.items.length > 0 && <MediaGrid items={state.items} showType />}
      {!state.loading && !state.items.length && (cats.length > 0 || wantsGames) && <p className="text-[0.8125rem] text-muted">Nothing matched “{q}” in your addons or the games catalogue.</p>}
    </section>
  );
}

/* ------------------------------ stream selection ------------------------------ */

const selKey = (itemId: string, videoId?: string) => `d1:stream:${itemId}|${videoId ?? ''}`;
export function selectStream(itemId: string, videoId: string | undefined, option: StreamOption) { try { sessionStorage.setItem(selKey(itemId, videoId), JSON.stringify(option)); } catch { /* private mode */ } }
export function getSelectedStream(itemId: string, videoId?: string): StreamOption | null {
  try { const v = sessionStorage.getItem(selKey(itemId, videoId)); return v ? (JSON.parse(v) as StreamOption) : null; } catch { return null; }
}

export function useAddonStreams(item: MediaItem | undefined, videoId?: string, enabled = true) {
  const addons = useStore((s) => s.addons);
  const [groups, setGroups] = useState<StreamGroup[]>([]);
  const [loading, setLoading] = useState(false);
  const [nonce, setNonce] = useState(0);
  const sType = item?.remote?.type ?? (item ? defaultStremioType(item.type) : '');
  const sid = videoId ?? item?.remote?.id;
  const capable = useMemo(() => (sid ? addons.filter((a) => supports(a, 'stream', sType, sid)) : []), [addons, sType, sid]);
  useEffect(() => {
    if (!enabled || !sid || !capable.length) { setGroups([]); setLoading(false); return; }
    let off = false;
    setLoading(true); setGroups([]);
    getStreams(capable, sType, sid, (g) => { if (!off) setGroups((prev) => [...prev, g]); }).finally(() => { if (!off) setLoading(false); });
    return () => { off = true; };
  }, [sType, sid, capable, enabled, nonce]);
  return { groups, loading, capable, reload: () => setNonce((n) => n + 1) };
}

/* ------------------------------- stream filters ------------------------------- */

const QRANK: Record<string, number> = { '480p': 1, '720p': 2, '1080p': 3, '2160p': 4 };
const seedersOf = (o: StreamOption) => { const m = o.description.match(/(?:👤|seeders?:?)\s*(\d+)/i); return m ? Number(m[1]) : 0; };

/** Merge streams from every addon into one list, then filter and sort it (in the spirit of AIOStreams). */
export function aggregateStreams(groups: StreamGroup[], f: StreamFilters): { options: StreamOption[]; hidden: number } {
  const addonRank = new Map(groups.map((g, i) => [g.addon.id, i]));
  const all = groups.flatMap((g) => g.options);
  const text = (o: StreamOption) => `${o.name} ${o.description} ${o.filename ?? ''}`.toLowerCase();
  const words = (s: string) => s.split(',').map((w) => w.trim().toLowerCase()).filter(Boolean);
  const inc = words(f.include), exc = words(f.exclude);
  const minRank = f.quality === 'any' ? 0 : QRANK[f.quality] ?? 0;
  let list = all.filter((o) => f.kinds.includes(o.kind));
  if (minRank) list = list.filter((o) => !o.quality || (QRANK[o.quality] ?? 0) >= minRank);
  if (f.maxSizeGB > 0) list = list.filter((o) => !o.size || o.size <= f.maxSizeGB * 1e9);
  if (inc.length) list = list.filter((o) => inc.some((w) => text(o).includes(w)));
  if (exc.length) list = list.filter((o) => !exc.some((w) => text(o).includes(w)));
  const sorted = list.slice().sort((a, b) => {
    switch (f.sort) {
      case 'quality': return (QRANK[b.quality ?? ''] ?? 0) - (QRANK[a.quality ?? ''] ?? 0);
      case 'size': return (b.size ?? 0) - (a.size ?? 0);
      case 'seeders': return seedersOf(b) - seedersOf(a);
      default: return (addonRank.get(a.addonId) ?? 0) - (addonRank.get(b.addonId) ?? 0);
    }
  });
  return { options: sorted, hidden: all.length - sorted.length };
}

/** The filter controls for a section. Used on each title's streams and on the Addons page. */
export function FilterBar({ section, compact }: { section: SectionKey; compact?: boolean }) {
  const saved = useStore((s) => s.settings.streamFilters[section]);
  const f: StreamFilters = { ...defaultFilters(), ...saved };
  const save = (patch: Partial<StreamFilters>) => {
    const s = useStore.getState();
    s.setSetting('streamFilters', { ...s.settings.streamFilters, [section]: { ...f, ...patch } });
  };
  const kinds: { value: StreamFilters['kinds'][number]; label: string }[] = [
    { value: 'url', label: 'Direct' }, { value: 'torrent', label: 'Torrent' }, { value: 'external', label: 'External' }, { value: 'youtube', label: 'YouTube' },
  ];
  const toggleKind = (k: StreamFilters['kinds'][number]) => save({ kinds: f.kinds.includes(k) ? f.kinds.filter((x) => x !== k) : [...f.kinds, k] });
  return (
    <div className={cn('flex flex-wrap items-center gap-2', compact && 'text-xs')}>
      <Select label="Minimum quality" value={f.quality} onChange={(v) => save({ quality: v })} options={[
        { value: 'any', label: 'Any quality' }, { value: '480p', label: '480p or better' }, { value: '720p', label: '720p or better' }, { value: '1080p', label: '1080p or better' }, { value: '2160p', label: '4K only' },
      ]} />
      <Select label="Largest size" value={String(f.maxSizeGB)} onChange={(v) => save({ maxSizeGB: Number(v) })} options={[
        { value: '0', label: 'Any size' }, { value: '2', label: 'Up to 2 GB' }, { value: '5', label: 'Up to 5 GB' }, { value: '10', label: 'Up to 10 GB' }, { value: '20', label: 'Up to 20 GB' },
      ]} />
      <Select label="Sort by" value={f.sort} onChange={(v) => save({ sort: v })} options={[
        { value: 'addon', label: 'Addon order' }, { value: 'quality', label: 'Best quality' }, { value: 'size', label: 'Largest first' }, { value: 'seeders', label: 'Most seeders' },
      ]} />
      <div className="flex flex-wrap gap-1.5" role="group" aria-label="Source types">
        {kinds.map((k) => (
          <button key={k.value} type="button" aria-pressed={f.kinds.includes(k.value)} onClick={() => toggleKind(k.value)}
            className={cn('h-8 rounded-full border px-3 text-[0.75rem] font-medium transition-colors', f.kinds.includes(k.value) ? 'border-transparent bg-accent text-accent-fg' : 'border-line/15 text-muted hover:text-fg')}>{k.label}</button>
        ))}
      </div>
      <input className="field h-8 w-40 py-0 text-xs" placeholder="Must include…" value={f.include} onChange={(e) => save({ include: e.target.value })} aria-label="Must include words" />
      <input className="field h-8 w-40 py-0 text-xs" placeholder="Hide words…" value={f.exclude} onChange={(e) => save({ exclude: e.target.value })} aria-label="Hide words" />
    </div>
  );
}

/* --------------------------- streams and downloads --------------------------- */

const KIND_LABEL: Record<StreamOption['kind'], string> = { url: 'Direct link', youtube: 'YouTube', external: 'External site', torrent: 'Torrent' };

/** Every way your installed addons offer to play or download a title: filtered, sorted, and with Real-Debrid where it helps. */
export function StreamOptions({ item, videoId, onPlay, dark, label }: { item: MediaItem; videoId?: string; onPlay?: (o: StreamOption) => void; dark?: boolean; label?: string }) {
  const navigate = useNavigate();
  const section = sectionFor(item.type);
  const saved = useStore((s) => s.settings.streamFilters[section]);
  const filters = useMemo<StreamFilters>(() => ({ ...defaultFilters(), ...saved }), [saved]);
  const { groups, loading, capable, reload } = useAddonStreams(item, videoId);
  const { options, hidden } = useMemo(() => aggregateStreams(groups, filters), [groups, filters]);
  const rdReady = useRdReady();
  const playable = item.type === 'movie' || item.type === 'tv';
  const total = groups.reduce((n, g) => n + g.options.length, 0);
  const muted = dark ? 'text-white/55' : 'text-muted';
  const copy = (text: string, what: string) => navigator.clipboard?.writeText(text).then(() => toast(`${what} copied`, 'success'), () => toast('Your browser blocked clipboard access.', 'warn'));
  const play = (o: StreamOption) => {
    if (onPlay) { onPlay(o); return; }
    selectStream(item.id, videoId, o);
    navigate(`/watch/${item.id}?sx=1${videoId ? `&ep=${encodeURIComponent(videoId)}` : ''}`);
  };
  const playRd = (o: StreamOption) => {
    const option: StreamOption = { ...o, via: 'realdebrid' };
    if (onPlay) { onPlay(option); return; }
    selectStream(item.id, videoId, option);
    navigate(`/watch/${item.id}?sx=1${videoId ? `&ep=${encodeURIComponent(videoId)}` : ''}`);
  };
  const download = (o: StreamOption) => {
    const fromUrl = decodeURIComponent(o.url!.split(/[?#]/)[0].split('/').pop() ?? '');
    const fileName = (o.filename || (/\.[a-z0-9]{2,5}$/i.test(fromUrl) ? fromUrl : `${item.title}${label ? ` ${label}` : ''}`)).replace(/[\\/:*?"<>|]/g, '').slice(0, 180);
    void startDownload({ title: `${item.title}${label ? ` · ${label}` : ''}`, fileName, source: `${o.addonName} · addon`, kind: 'browser', url: o.url, size: o.size, itemId: item.id }).then((id) => id && toast('Sent to Downloads', 'success'));
  };
  const downloadRd = (o: StreamOption) => {
    toast('Real-Debrid is preparing the file. It appears in Downloads when it’s ready.', 'info');
    void resolveOption(o)
      .then((r) => startDownload({ title: `${item.title}${label ? ` · ${label}` : ''}`, fileName: r.filename || `${item.title}.mp4`, source: 'Real-Debrid', kind: 'fetch', url: r.url, size: r.bytes, itemId: item.id }))
      .catch((e: Error) => toast(e.message, 'error'));
  };
  const bestPlayable = options.find((o) => o.kind === 'url' && o.notWebReady === false) ?? options.find((o) => o.kind === 'url');

  if (!capable.length) {
    return (
      <Callout tone="desktop" title="No addon offers streams for this yet" action={<Button size="sm" icon={Puzzle} onClick={() => navigate('/addons')}>Addons</Button>}>
        Streams and downloads come from addons you install. Add a stream addon for {TYPE_PLURAL[item.type].toLowerCase()} on the Addons page.
      </Callout>
    );
  }
  return (
    <div className="space-y-3">
      <div className={cn('flex flex-wrap items-center justify-between gap-2 text-xs', muted)}>
        <span>{loading ? `Asking ${capable.length} addon${capable.length === 1 ? '' : 's'}…` : `${options.length} of ${total} option${total === 1 ? '' : 's'}${hidden > 0 ? ` · ${hidden} hidden by filters` : ''}`}</span>
        <div className="flex items-center gap-2">
          {playable && bestPlayable && <Button size="sm" variant="primary" icon={Play} onClick={() => play(bestPlayable)}>Play best match</Button>}
          <button type="button" onClick={reload} className="flex items-center gap-1.5 rounded-full px-2 py-1 hover:underline" aria-label="Ask addons again"><RefreshCw className={cn('h-3 w-3', loading && 'animate-spin')} />Refresh</button>
        </div>
      </div>
      <FilterBar section={section} compact />
      {loading && !groups.length && <div className="flex justify-center py-6"><Spinner className={dark ? 'text-white' : undefined} /></div>}
      <ul className="space-y-1.5">
        {groups.filter((g) => g.error).map((g) => (
          <li key={`${g.addon.id}-err`} className={cn('rounded-btn border border-dashed px-3 py-2 text-xs', dark ? 'border-white/15 text-white/55' : 'border-line/10 text-muted')}>{g.addon.manifest.name}: {g.error}</li>
        ))}
        {options.map((o) => (
          <li key={o.key} className={cn('flex flex-wrap items-center gap-x-3 gap-y-2 rounded-btn border px-3 py-2.5', dark ? 'border-white/10 bg-white/[0.04]' : 'border-line/[0.08] bg-line/[0.02]')}>
            <div className="min-w-0 flex-1 basis-52">
              <p className="flex flex-wrap items-center gap-1.5 text-[0.8125rem] font-medium">
                <span className="truncate">{o.name}</span>
                {o.quality && <Badge tone="accent">{o.quality}</Badge>}
                <Badge tone={o.kind === 'torrent' ? 'warn' : 'default'}>{KIND_LABEL[o.kind]}</Badge>
                {o.size ? <Badge tone="outline">{fmtBytes(o.size)}</Badge> : null}
                <span className={cn('text-[0.6875rem] font-normal', muted)}>{o.addonName}</span>
              </p>
              {o.description && <p className={cn('mt-1 line-clamp-3 whitespace-pre-line text-xs leading-relaxed', muted)}>{o.description}</p>}
              {o.kind === 'url' && o.notWebReady && playable && <p className="mt-1 text-xs text-amber-400/90">May not play in a browser (format or host restrictions). Use Real-Debrid or download it.</p>}
              {o.kind === 'torrent' && <p className="mt-1 text-xs text-amber-400/90">Torrents need Real-Debrid (in the Mac app) or a torrent app. Only use torrents for content you are entitled to.</p>}
            </div>
            <div className="flex shrink-0 flex-wrap items-center gap-1.5">
              {rdReady && (o.kind === 'url' || o.kind === 'torrent') && playable && <Button size="sm" variant="primary" icon={Cloud} onClick={() => playRd(o)}>Play via Real-Debrid</Button>}
              {rdReady && (o.kind === 'url' || o.kind === 'torrent') && <Button size="sm" icon={Cloud} onClick={() => downloadRd(o)}>Download via Real-Debrid</Button>}
              {o.kind === 'url' && playable && <Button size="sm" variant={rdReady ? 'ghost' : 'primary'} icon={Play} onClick={() => play(o)}>Play</Button>}
              {o.kind === 'url' && <Button size="sm" icon={Download} onClick={() => download(o)}>Download</Button>}
              {o.kind === 'url' && <Button size="sm" variant="ghost" icon={Copy} aria-label="Copy link" title="Copy link" onClick={() => copy(o.url!, 'Link')} />}
              {o.kind === 'youtube' && <Button size="sm" icon={Youtube} onClick={() => window.open(o.url, '_blank', 'noopener,noreferrer')}>Open on YouTube</Button>}
              {o.kind === 'external' && <Button size="sm" icon={ExternalLink} onClick={() => window.open(o.url, '_blank', 'noopener,noreferrer')}>Open</Button>}
              {o.kind === 'torrent' && <Button size="sm" icon={Magnet} onClick={() => copy(magnetLink(o), 'Magnet link')}>Copy magnet</Button>}
            </div>
          </li>
        ))}
      </ul>
      {!loading && options.length === 0 && groups.length > 0 && <p className={cn('py-4 text-center text-[0.8125rem]', muted)}>Nothing matches the filters. Loosen them above.</p>}
      <p className={cn('text-[0.6875rem] leading-relaxed', dark ? 'text-white/40' : 'text-muted/80')}>Results come straight from addons you installed and are not reviewed by DebridOne. Only use them with content you are entitled to.</p>
    </div>
  );
}

/* --------------------------------- subtitles -------------------------------- */

const LANG: Record<string, string> = { eng: 'English', spa: 'Español', por: 'Português', pob: 'Português (Brasil)', fre: 'Français', fra: 'Français', ger: 'Deutsch', deu: 'Deutsch', ita: 'Italiano', jpn: '日本語', kor: '한국어', chi: '中文', zho: '中文', rus: 'Русский', ara: 'العربية', tur: 'Türkçe', pol: 'Polski', dut: 'Nederlands', nld: 'Nederlands', swe: 'Svenska' };
const PREF: Record<string, string[]> = { en: ['eng'], es: ['spa'], pt: ['por', 'pob'], fr: ['fre', 'fra'], de: ['ger', 'deu'], ja: ['jpn'] };

/** Subtitle tracks from subtitle addons, converted to WebVTT so the browser can render them. One per language, preferred languages first. */
export function useAddonSubtitles(item: MediaItem | undefined, videoId?: string): SubtitleTrack[] {
  const addons = useStore((s) => s.addons);
  const want = useStore((s) => [...(PREF[s.settings.playback.subtitleLang] ?? []), ...(PREF[s.settings.language] ?? []), 'eng'].join(','));
  const [tracks, setTracks] = useState<SubtitleTrack[]>([]);
  const sType = item?.remote?.type;
  const sid = videoId ?? item?.remote?.id;
  useEffect(() => {
    setTracks([]);
    if (!sType || !sid) return;
    let off = false;
    const made: string[] = [];
    (async () => {
      const all = await getSubtitles(addons, sType, sid);
      const order = want.split(',');
      const byLang = new Map<string, (typeof all)[number]>();
      for (const s of all) if (!byLang.has(s.lang)) byLang.set(s.lang, s);
      const chosen = [...byLang.values()].sort((a, b) => (order.indexOf(a.lang) + 1 || 99) - (order.indexOf(b.lang) + 1 || 99)).slice(0, 8);
      const out: SubtitleTrack[] = [];
      await Promise.all(chosen.map(async (s) => {
        try { const src = await loadSubtitleAsVtt(s); made.push(src); out.push({ id: s.id, label: `${LANG[s.lang] ?? s.lang.toUpperCase()} · ${s.addonName}`, lang: s.lang.slice(0, 2), src }); } catch { /* skip unreadable files */ }
      }));
      if (!off) setTracks(chosen.map((c) => out.find((o) => o.id === c.id)).filter((t): t is SubtitleTrack => !!t));
    })();
    return () => { off = true; made.forEach((u) => URL.revokeObjectURL(u)); };
  }, [addons, sType, sid, want]);
  return tracks;
}
