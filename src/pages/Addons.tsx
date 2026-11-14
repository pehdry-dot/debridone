import { ArrowDown, ArrowUp, Copy, Link2, Plus, Puzzle, RefreshCw, Search, Settings2, ShieldAlert, ShieldCheck, Trash2 } from 'lucide-react';
import { useEffect, useMemo, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { FEATURED_ADDONS, getCatalog, mediaTypeOf, type InstalledAddon } from '../adapters/stremio';
import { SECTIONS, sectionOf, type SectionKey } from '../adapters/sections';
import { checkAddon, FilterBar, installAddon } from '../components/addons';
import { MediaGrid } from '../components/media';
import { Badge, Button, Callout, Card, EmptyState, Menu, PageHeader, Segmented, Select, Spinner, Switch } from '../components/ui';
import { useTab } from '../lib/hooks';
import { cn, moveItem, timeAgo } from '../lib/utils';
import { registerPreviews } from '../store/catalog';
import { confirmDialog, toast } from '../store/ui';
import { useStore } from '../store/useStore';
import type { MediaItem } from '../types';
import { TYPE_PLURAL } from '../types';

const RES: Record<string, string> = { meta: 'Metadata', catalog: 'Catalogues', stream: 'Streams & downloads', subtitles: 'Subtitles' };
const STATUS = {
  unknown: ['bg-line/30', 'Not checked'], checking: ['bg-sky-400 animate-pulse', 'Checking…'], online: ['bg-emerald-400', 'Available'], offline: ['bg-red-400', 'Unavailable'],
} as const;

function AddonLogo({ addon }: { addon: Pick<InstalledAddon, 'manifest'> }) {
  const [failed, setFailed] = useState(false);
  return addon.manifest.logo && !failed
    ? <img src={addon.manifest.logo} alt="" referrerPolicy="no-referrer" onError={() => setFailed(true)} className="h-11 w-11 shrink-0 rounded-[10px] bg-line/10 object-contain p-1" />
    : <span className="flex h-11 w-11 shrink-0 items-center justify-center rounded-[10px] bg-accent/15 text-accent"><Puzzle className="h-5 w-5" /></span>;
}

function InstallBox({ section }: { section: SectionKey }) {
  const [url, setUrl] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const label = SECTIONS.find((s) => s.key === section)?.label ?? '';
  const install = async () => {
    setBusy(true); setError(null);
    try {
      const a = await installAddon(url);
      const filed = sectionOf(a);
      toast(`Installed ${a.manifest.name}${filed ? ` under ${SECTIONS.find((s) => s.key === filed)?.label}` : ''}`, 'success');
      setUrl('');
    } catch (e) { setError((e as Error).message); }
    setBusy(false);
  };
  return (
    <Card title="Add an addon" description={`Paste a manifest URL. It is filed under the right section by the content types it declares (you are on ${label}).`}>
      <form className="flex flex-wrap gap-2" onSubmit={(e) => { e.preventDefault(); void install(); }}>
        <div className="relative min-w-[14rem] flex-1">
          <Link2 className="pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-muted" />
          <input className="field pl-9 font-mono text-xs" placeholder="https://example-addon.com/manifest.json" value={url} onChange={(e) => { setUrl(e.target.value); setError(null); }} spellCheck={false} aria-label="Addon manifest URL" />
        </div>
        <Button type="submit" variant="primary" icon={Plus} loading={busy} disabled={!url.trim()}>Install</Button>
      </form>
      {error && <p className="mt-2 text-xs text-red-400">{error}</p>}
    </Card>
  );
}

function AddonRow({ addon }: { addon: InstalledAddon }) {
  const addons = useStore((s) => s.addons);
  const set = useStore((s) => s.set);
  const updateAddon = useStore((s) => s.updateAddon);
  const navigate = useNavigate();
  const index = addons.indexOf(addon);
  const [dot, label] = STATUS[addon.status];
  const resources = [...new Set(addon.manifest.resources.map((r) => RES[r.name] ?? r.name))];
  const firstCatalog = addon.manifest.catalogs.find((c) => mediaTypeOf(c.type) && !c.required.some((r) => r !== 'genre'));
  const remove = async () => {
    if (!(await confirmDialog({ title: `Remove ${addon.manifest.name}?`, body: 'Its catalogues, streams and subtitles disappear from the app. Titles you already saved stay in your library.', confirmLabel: 'Remove addon', danger: true }))) return;
    set('addons', (list) => list.filter((x) => x.id !== addon.id));
    toast('Addon removed', 'success');
  };
  return (
    <li className={cn('flex flex-wrap items-start gap-x-4 gap-y-3 py-4', !addon.enabled && 'opacity-60')}>
      <AddonLogo addon={addon} />
      <div className="min-w-0 flex-1 basis-64">
        <p className="flex flex-wrap items-center gap-2 text-sm font-semibold">
          {addon.manifest.name}{addon.manifest.version && !addon.builtin && <span className="text-xs font-normal text-muted">v{addon.manifest.version}</span>}
          {addon.builtin ? <Badge tone="accent" icon={ShieldCheck}>Built in</Badge> : addon.official ? <Badge tone="success" icon={ShieldCheck}>Official</Badge> : <Badge tone="warn" icon={ShieldAlert}>Community · unreviewed</Badge>}
          {addon.manifest.p2p && <Badge tone="warn">Uses torrents</Badge>}
        </p>
        {!addon.builtin && <p className="mt-0.5 truncate font-mono text-[0.6875rem] text-muted">{addon.transportUrl}/manifest.json</p>}
        {addon.manifest.description && <p className="mt-1.5 text-xs leading-relaxed text-muted">{addon.manifest.description}</p>}
        <div className="mt-2 flex flex-wrap gap-1.5">
          {resources.map((r) => <Badge key={r} tone="outline">{r}</Badge>)}
          {addon.manifest.catalogs.length > 0 && <Badge>{addon.manifest.catalogs.length} catalogue{addon.manifest.catalogs.length === 1 ? '' : 's'}</Badge>}
        </div>
        <p className="mt-2 flex flex-wrap items-center gap-x-2 text-xs text-muted">
          <span className={cn('h-2 w-2 rounded-full', dot)} /><span className="font-medium text-fg">{label}</span>
          {addon.latency != null && <span>· {addon.latency} ms</span>}{addon.lastChecked && <span>· checked {timeAgo(addon.lastChecked)}</span>}
          {addon.manifest.configurationRequired && <span className="text-amber-400">· needs configuration before it returns results</span>}
        </p>
      </div>
      <div className="flex shrink-0 items-center gap-1.5">
        {firstCatalog && <Button size="sm" onClick={() => navigate(`/addons/catalog/${encodeURIComponent(addon.id)}/${encodeURIComponent(firstCatalog.type)}/${encodeURIComponent(firstCatalog.id)}`)}>Browse</Button>}
        <Button size="sm" icon={RefreshCw} loading={addon.status === 'checking'} onClick={() => checkAddon(addon).then((ok) => toast(`${addon.manifest.name} is ${ok ? 'available' : 'unavailable right now'}`, ok ? 'success' : 'warn'))}>Check</Button>
        <Switch label={`${addon.enabled ? 'Disable' : 'Enable'} ${addon.manifest.name}`} checked={addon.enabled} onChange={(v) => updateAddon(addon.id, { enabled: v })} />
        <Menu label={`Actions for ${addon.manifest.name}`} items={[
          { label: 'Raise priority', icon: ArrowUp, disabled: index <= 0, onClick: () => set('addons', moveItem(addons, index, index - 1)) },
          { label: 'Lower priority', icon: ArrowDown, disabled: index >= addons.length - 1, onClick: () => set('addons', moveItem(addons, index, index + 1)) },
          'divider',
          (addon.manifest.configurable || addon.manifest.configurationRequired) && !addon.builtin && { label: 'Configure on addon site', icon: Settings2, onClick: () => window.open(`${addon.transportUrl}/configure`, '_blank', 'noopener,noreferrer') },
          !addon.builtin && { label: 'Copy manifest URL', icon: Copy, onClick: () => navigator.clipboard?.writeText(`${addon.transportUrl}/manifest.json`).then(() => toast('Manifest URL copied', 'success'), () => toast('Clipboard access was blocked.', 'warn')) },
          !addon.builtin && 'divider',
          !addon.builtin && { label: 'Remove addon…', icon: Trash2, danger: true, onClick: remove },
        ]} />
      </div>
    </li>
  );
}

function SectionView({ section }: { section: SectionKey }) {
  const addons = useStore((s) => s.addons);
  const meta = SECTIONS.find((s) => s.key === section)!;
  const list = addons.filter((a) => sectionOf(a) === section);
  const counts = {
    metadata: list.filter((a) => a.enabled && a.manifest.resources.some((r) => r.name === 'meta')).length,
    catalogs: list.filter((a) => a.enabled).reduce((n, a) => n + a.manifest.catalogs.length, 0),
    streams: list.filter((a) => a.enabled && a.manifest.resources.some((r) => r.name === 'stream')).length,
  };
  const catalogs = list.flatMap((a) => (a.enabled ? a.manifest.catalogs.filter((c) => mediaTypeOf(c.type)).map((c) => ({ addon: a, catalog: c })) : []));
  const official = section === 'movies' ? FEATURED_ADDONS.filter((f) => !addons.some((a) => a.id === f.id)) : [];
  const hasStreams = counts.streams > 0 || section === 'books';
  return (
    <div className="space-y-6">
      <div className="grid grid-cols-3 gap-3">
        {[['Metadata', counts.metadata], ['Catalogues', counts.catalogs], ['Stream sources', counts.streams]].map(([label, n]) => (
          <div key={label as string} className="rounded-[14px] border border-line/[0.08] bg-card/60 p-4">
            <p className="text-2xl font-semibold tabular-nums">{n as number}</p>
            <p className="text-xs text-muted">{label as string}</p>
          </div>
        ))}
      </div>
      <Card title="Installed" description={meta.blurb}>
        {list.length ? <ul className="divide-y divide-line/[0.06]">{list.map((a) => <AddonRow key={a.id} addon={a} />)}</ul>
          : <EmptyState icon={Puzzle} title={`No ${meta.label.toLowerCase()} addons yet`} body="Install one below, or turn on a built-in source." />}
      </Card>
      {catalogs.length > 0 && (
        <Card title="Catalogues" description="Rows that show on Home and in this section. Select one to browse it.">
          <div className="flex flex-wrap gap-2">
            {catalogs.map(({ addon, catalog }) => (
              <a key={`${addon.id}|${catalog.type}|${catalog.id}`} href={`#/addons/catalog/${encodeURIComponent(addon.id)}/${encodeURIComponent(catalog.type)}/${encodeURIComponent(catalog.id)}`}
                className="rounded-full border border-line/10 bg-line/[0.04] px-3.5 py-1.5 text-[0.8125rem] text-muted transition-colors hover:bg-line/10 hover:text-fg">
                {catalog.name} <span className="text-muted/70">· {addon.manifest.name}</span>
              </a>
            ))}
          </div>
        </Card>
      )}
      {official.length > 0 && (
        <Card title="Official addons" description="Published by Stremio. Availability is checked when you install them.">
          <ul className="divide-y divide-line/[0.06]">
            {official.map((f) => (
              <li key={f.id} className="flex flex-wrap items-center gap-3 py-3">
                <div className="min-w-0 flex-1 basis-56">
                  <p className="text-[0.8125rem] font-medium">{f.name}</p>
                  <p className="mt-0.5 text-xs leading-relaxed text-muted">{f.description}</p>
                </div>
                <Button size="sm" variant="primary" icon={Plus} onClick={async () => { try { await installAddon(f.url); toast(`Installed ${f.name}`, 'success'); } catch (e) { toast((e as Error).message, 'error'); } }}>Install</Button>
              </li>
            ))}
          </ul>
        </Card>
      )}
      <InstallBox section={section} />
      {hasStreams && (
        <Card title="Stream filters" description="Applied to every list of streams in this section, the way AIOStreams filters its sources: quality, size, source type, sorting, and words to include or hide.">
          <FilterBar section={section} />
        </Card>
      )}
    </div>
  );
}

export default function AddonsPage() {
  const [section, setSection] = useTab<SectionKey>('movies', 'section');
  const addons = useStore((s) => s.addons);
  const counts = useMemo(() => Object.fromEntries(SECTIONS.map((s) => [s.key, addons.filter((a) => sectionOf(a) === s.key).length])), [addons]);
  return (
    <div className="page">
      <PageHeader title="Addons" icon={Puzzle} subtitle="Each section has its own metadata, catalogues and stream sources. Built-in sources need no setup; any Stremio-compatible addon can be added by URL.">
        <div className="mt-5 max-w-full overflow-x-auto">
          <Segmented value={section} onChange={setSection} options={SECTIONS.map((s) => ({ value: s.key, label: `${s.label} (${counts[s.key] ?? 0})` }))} />
        </div>
      </PageHeader>
      <div className="max-w-5xl">
        <SectionView section={section} />
      </div>
    </div>
  );
}

/** Full-page view of one addon catalogue, with genre filter, search and paging where the addon supports them. */
export function CatalogPage() {
  const { addonId = '', type = '', catalogId = '' } = useParams();
  const navigate = useNavigate();
  const addon = useStore((s) => s.addons.find((a) => a.id === addonId));
  const catalog = addon?.manifest.catalogs.find((c) => c.type === type && c.id === catalogId);
  const [genre, setGenre] = useState('');
  const [query, setQuery] = useState('');
  const [search, setSearch] = useState('');
  const [items, setItems] = useState<MediaItem[]>([]);
  const [loading, setLoading] = useState(true);
  const [more, setMore] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const genres = useMemo(() => catalog?.genres ?? [], [catalog]);
  const activeGenre = genre || (catalog?.required.includes('genre') ? genres[0] : '');

  useEffect(() => { const t = setTimeout(() => setSearch(query.trim()), 400); return () => clearTimeout(t); }, [query]);

  const load = async (skip: number) => {
    if (!addon || !catalog) return;
    setLoading(true); setError(null);
    try {
      const page = await getCatalog(addon, catalog, { genre: activeGenre || undefined, search: search || undefined, skip });
      registerPreviews(page);
      setItems((prev) => { const seen = new Set(skip ? prev.map((i) => i.id) : []); return [...(skip ? prev : []), ...page.filter((i) => !seen.has(i.id))]; });
      setMore(catalog.skip && !search && page.length >= 20);
    } catch (e) { setError((e as Error).message); if (!skip) setItems([]); }
    setLoading(false);
  };
  // eslint-disable-next-line react-hooks/exhaustive-deps
  useEffect(() => { void load(0); }, [addon?.transportUrl, catalog?.id, catalog?.type, activeGenre, search]);

  if (!addon || !catalog) return <div className="page"><EmptyState icon={Puzzle} title="Catalogue not found" body="The addon may have been removed or changed its catalogues." action={<Button variant="primary" onClick={() => navigate('/addons')}>Open Addons</Button>} /></div>;
  const media = mediaTypeOf(catalog.type);
  return (
    <div className="page">
      <PageHeader title={`${catalog.name}${media ? ` · ${TYPE_PLURAL[media]}` : ''}`} icon={Puzzle} subtitle={`From ${addon.manifest.name}`}>
        <div className="mt-5 flex flex-wrap items-center gap-2">
          {catalog.search && (
            <div className="relative min-w-[12rem] flex-1 sm:max-w-sm">
              <Search className="pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-muted" />
              <input className="field h-9 py-0 pl-9" placeholder={`Search ${addon.manifest.name}`} value={query} onChange={(e) => setQuery(e.target.value)} aria-label="Search this catalogue" />
            </div>
          )}
          {genres.length > 0 && <Select label="Genre" value={activeGenre} onChange={setGenre} options={[...(catalog.required.includes('genre') ? [] : [{ value: '', label: 'All genres' }]), ...genres.map((g) => ({ value: g, label: g }))]} />}
          <span className="ml-auto text-xs tabular-nums text-muted">{items.length} loaded</span>
        </div>
      </PageHeader>
      {error && <Callout tone="error" className="mb-5" action={<Button size="sm" onClick={() => load(0)}>Retry</Button>}>{addon.manifest.name} didn’t answer: {error}.</Callout>}
      {items.length > 0 && <MediaGrid items={items} />}
      {loading && <div className="flex justify-center py-10"><Spinner /></div>}
      {!loading && !items.length && !error && <EmptyState icon={Search} title="Nothing here" body={search ? `No results for “${search}”.` : 'This catalogue returned no titles.'} />}
      {!loading && more && <div className="mt-8 flex justify-center"><Button size="lg" onClick={() => load(items.length)}>Load more</Button></div>}
    </div>
  );
}
