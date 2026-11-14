import { Reorder } from 'framer-motion';
import { ArrowDown, ArrowUp, BookOpen, Bookmark, BookmarkCheck, Download, GripVertical, Info, LayoutDashboard, Play, SlidersHorizontal } from 'lucide-react';
import { useEffect, useMemo, useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { CollectionTile } from '../components/collections';
import { AddonShelves } from '../components/addons';
import { browsableCatalogs, getCatalog as fetchAddonCatalog } from '../adapters/stremio';
import { Backdrop, Cover } from '../components/Cover';
import { NAV } from '../components/Layout';
import { ItemShelf, PRIMARY_LABEL, Shelf, Thumb, TYPE_ICON, usePrimaryAction } from '../components/media';
import { Badge, Button, Chip, EmptyState, Modal, ProgressBar, Select, Switch } from '../components/ui';
import { useT } from '../lib/i18n';
import { cn, fmtBytes, fmtRuntime, fmtTime, greeting, moveItem, timeAgo } from '../lib/utils';
import { getItem, getTrack, registerPreviews, useCatalog } from '../store/catalog';
import { isActive, useDownloads } from '../store/downloads';
import { usePlayer } from '../store/player';
import { toast } from '../store/ui';
import { activeLayout, useStore, WIDGETS } from '../store/useStore';
import type { MediaItem, MediaType, WidgetId } from '../types';
import { MEDIA_TYPES, TYPE_LABEL, TYPE_PLURAL } from '../types';

/** Human-readable "where you left off" line for an item. */
export function progressLabel(item: MediaItem, s = useStore.getState()): string {
  const p = s.progress[item.id];
  if (item.type === 'game') { const g = s.gameState[item.id]; return g ? `${fmtRuntime(g.playMinutes)} played` : ''; }
  if (!p) return '';
  if (item.type === 'movie') return p.duration ? `${fmtTime(Math.max(0, p.duration - p.position))} left` : `${Math.round(p.percent)}% watched`;
  if (item.type === 'tv') { const ep = item.tv?.seasons.flatMap((x) => x.episodes.map((e) => ({ e, s: x.number }))).find((x) => x.e.id === p.unit); return ep ? `S${ep.s} · E${ep.e.number} ${ep.e.title}` : 'Continue'; }
  if (item.type === 'manga') { const ch = item.manga?.chapters.find((c) => c.id === p.unit); return ch ? `Ch. ${ch.number} · page ${p.position + 1} of ${ch.pages}` : 'Continue'; }
  return `${Math.round(p.percent)}% read`;
}

export function ContinueCard({ item }: { item: MediaItem }) {
  const act = usePrimaryAction();
  const percent = useStore((s) => s.progress[item.id]?.percent ?? 0);
  const updated = useStore((s) => s.progress[item.id]?.updatedAt ?? s.gameState[item.id]?.lastPlayed);
  const Icon = item.type === 'manga' || item.type === 'book' ? BookOpen : Play;
  return (
    <div className="surface group relative flex w-[19rem] shrink-0 snap-start gap-3.5 p-3 transition-colors hover:bg-line/[0.04]">
      <Link to={`/item/${item.id}`} aria-label={`${item.title} details`} className="shrink-0 rounded-md"><Thumb item={item} className="w-16" /></Link>
      <div className="flex min-w-0 flex-1 flex-col">
        <p className="truncate text-[0.8125rem] font-semibold">{item.title}</p>
        <p className="mt-0.5 line-clamp-2 text-xs text-muted">{progressLabel(item)}</p>
        <div className="mt-auto">
          {item.type !== 'game' && <ProgressBar value={percent} className="mb-2" />}
          <div className="flex items-center justify-between gap-2">
            <span className="truncate text-[0.6875rem] text-muted">{updated ? timeAgo(updated) : ''}</span>
            <Button size="sm" variant="primary" icon={Icon} onClick={() => act(item)} className="h-7">{item.type === 'game' ? 'Open' : 'Resume'}</Button>
          </div>
        </div>
      </div>
    </div>
  );
}

export function Hero({ item }: { item: MediaItem }) {
  const act = usePrimaryAction();
  const navigate = useNavigate();
  const saved = useStore((s) => !!s.saved[item.id]);
  const toggleSaved = useStore((s) => s.toggleSaved);
  const Icon = TYPE_ICON[item.type];
  return (
    <section className="relative mb-9 overflow-hidden rounded-[calc(var(--radius)*1.5)] ring-1 ring-inset ring-white/10" aria-label="Featured">
      <Backdrop item={item} className="absolute inset-0" />
      <div className="absolute inset-0 bg-gradient-to-r from-black/85 via-black/55 to-black/10" />
      <div className="absolute inset-0 bg-gradient-to-t from-black/60 to-transparent" />
      <div className="relative flex min-h-[19rem] items-end gap-6 p-6 text-white sm:min-h-[22rem] sm:p-9">
        <div className="max-w-xl">
          <div className="mb-3 flex flex-wrap items-center gap-2 text-[0.6875rem] font-semibold uppercase tracking-[0.14em] text-white/75">
            <span className="flex items-center gap-1.5 rounded-full bg-white/15 px-2.5 py-1 backdrop-blur-sm"><Icon className="h-3 w-3" />Featured {TYPE_LABEL[item.type]}</span>
            {item.license && <span>{item.license}</span>}
          </div>
          <h1 className="h-display text-3xl leading-[1.05] sm:text-5xl">{item.title}</h1>
          <p className="mt-2 text-sm text-white/70">{[item.creator, item.releaseDate?.slice(0, 4), ...item.genres.slice(0, 2)].filter(Boolean).join(' · ')}</p>
          <p className="mt-3 line-clamp-3 max-w-lg text-sm leading-relaxed text-white/80">{item.description}</p>
          <div className="mt-5 flex flex-wrap gap-2.5">
            <Button variant="primary" size="lg" icon={item.type === 'manga' || item.type === 'book' ? BookOpen : Play} onClick={() => act(item)} className="!bg-white !text-black shadow-none hover:!bg-white/90">{PRIMARY_LABEL[item.type]}</Button>
            <Button size="lg" icon={Info} onClick={() => navigate(`/item/${item.id}`)} className="!bg-white/15 !text-white backdrop-blur-sm hover:!bg-white/25">Details</Button>
            <Button size="lg" icon={saved ? BookmarkCheck : Bookmark} onClick={() => toast(toggleSaved(item.id) ? 'Saved to My Library' : 'Removed from My Library', 'success')} className="!bg-white/15 !text-white backdrop-blur-sm hover:!bg-white/25">{saved ? 'Saved' : 'Save'}</Button>
          </div>
        </div>
        <Cover item={item} className={cn('ml-auto hidden w-40 shrink-0 rotate-2 rounded-card shadow-pop lg:block', item.type === 'album' ? 'aspect-square' : 'aspect-[2/3]')} />
      </div>
    </section>
  );
}

function CustomizeModal({ open, onClose }: { open: boolean; onClose: () => void }) {
  const layout = useStore(activeLayout);
  const layouts = useStore((s) => s.layouts);
  const updateLayout = useStore((s) => s.updateLayout);
  const set = useStore((s) => s.set);
  const pro = useStore((s) => s.proDemo);
  const label = (id: WidgetId) => WIDGETS.find((w) => w.id === id)?.label ?? id;
  const setWidgets = (widgets: typeof layout.widgets) => updateLayout(layout.id, { widgets });
  return (
    <Modal open={open} onClose={onClose} title="Customize Home" subtitle="Show, hide and reorder sections. Changes apply immediately and are saved." size="md"
      footer={<>
        <Button variant="ghost" onClick={() => setWidgets(WIDGETS.map((w) => ({ id: w.id, visible: true })))}>Reset order</Button>
        <Button variant="primary" onClick={onClose}>Done</Button>
      </>}>
      {pro && layouts.length > 1 && (
        <div className="mb-4 flex items-center justify-between gap-3 rounded-btn bg-line/[0.05] px-3 py-2">
          <span className="text-[0.8125rem] text-muted">Editing dashboard</span>
          <Select label="Dashboard" value={layout.id} onChange={(v) => set('activeLayout', v)} options={layouts.map((l) => ({ value: l.id, label: l.name }))} />
        </div>
      )}
      <p className="label mb-2">Preferred categories</p>
      <div className="mb-5 flex flex-wrap gap-2">
        <Chip active={!layout.types.length} onClick={() => updateLayout(layout.id, { types: [] })}>Everything</Chip>
        {MEDIA_TYPES.map((t) => (
          <Chip key={t} active={layout.types.includes(t)} onClick={() => updateLayout(layout.id, { types: layout.types.includes(t) ? layout.types.filter((x) => x !== t) : [...layout.types, t] })}>{TYPE_PLURAL[t]}</Chip>
        ))}
      </div>
      <p className="label mb-2">Sections — drag or use the arrows</p>
      <Reorder.Group axis="y" values={layout.widgets} onReorder={setWidgets} className="space-y-1.5">
        {layout.widgets.map((w, i) => (
          <Reorder.Item key={w.id} value={w} className="flex cursor-grab items-center gap-2 rounded-btn border border-line/[0.08] bg-card px-2.5 py-2 active:cursor-grabbing">
            <GripVertical className="h-4 w-4 shrink-0 text-muted" />
            <span className={cn('min-w-0 flex-1 truncate text-[0.8125rem] font-medium', !w.visible && 'text-muted line-through')}>{label(w.id)}</span>
            <button type="button" aria-label={`Move ${label(w.id)} up`} disabled={i === 0} onClick={() => setWidgets(moveItem(layout.widgets, i, i - 1))} className="rounded p-1 text-muted hover:bg-line/10 hover:text-fg disabled:opacity-30"><ArrowUp className="h-3.5 w-3.5" /></button>
            <button type="button" aria-label={`Move ${label(w.id)} down`} disabled={i === layout.widgets.length - 1} onClick={() => setWidgets(moveItem(layout.widgets, i, i + 1))} className="rounded p-1 text-muted hover:bg-line/10 hover:text-fg disabled:opacity-30"><ArrowDown className="h-3.5 w-3.5" /></button>
            <Switch label={`Show ${label(w.id)}`} checked={w.visible} onChange={(v) => setWidgets(layout.widgets.map((x) => (x.id === w.id ? { ...x, visible: v } : x)))} />
          </Reorder.Item>
        ))}
      </Reorder.Group>
      <p className="mt-4 text-xs text-muted">Want several dashboards (for example “Movie night” and “Focus”)? Create them in <Link to="/pro" className="text-accent hover:underline" onClick={onClose}>DebridOne Pro → Homepage</Link>.</p>
    </Modal>
  );
}

/** The spotlight is the first title of your movie addon's first browsable list (live data), never a picked or made-up title. */
function FeaturedHero() {
  const addons = useStore((s) => s.addons);
  const [item, setItem] = useState<MediaItem | null>(null);
  useEffect(() => {
    const source = browsableCatalogs(addons, ['movie']).find(({ catalog }) => catalog.required.length === 0);
    if (!source) { setItem(null); return; }
    let off = false;
    fetchAddonCatalog(source.addon, source.catalog)
      .then((items) => { if (!off && items[0]) { registerPreviews(items); setItem(items[0]); } else if (!off) setItem(null); })
      .catch(() => { if (!off) setItem(null); });
    return () => { off = true; };
  }, [addons]);
  return item ? <Hero item={item} /> : null;
}

export default function HomePage() {
  const t = useT();
  const navigate = useNavigate();
  const catalog = useCatalog();
  const layout = useStore(activeLayout);
  const layouts = useStore((s) => s.layouts);
  const set = useStore((s) => s.set);
  const pro = useStore((s) => s.proDemo);
  const name = useStore((s) => s.profile.name);
  const progress = useStore((s) => s.progress);
  const gameState = useStore((s) => s.gameState);
  const favorites = useStore((s) => s.favorites);
  const saved = useStore((s) => s.saved);
  const recentTracks = useStore((s) => s.recentTracks);
  const collections = useStore((s) => s.collections);
  const downloads = useDownloads((s) => s.items);
  const [customizing, setCustomizing] = useState(false);

  const pool = useMemo(() => (layout.types.length ? catalog.filter((i) => layout.types.includes(i.type)) : catalog), [catalog, layout.types]);
  const allowed = (type: MediaType) => !layout.types.length || layout.types.includes(type);

  const inProgress = (types: MediaType[]) => pool.filter((i) => types.includes(i.type) && progress[i.id] && progress[i.id].percent < 97)
    .sort((a, b) => progress[b.id].updatedAt - progress[a.id].updatedAt);
  const playing = pool.filter((i) => i.type === 'game' && gameState[i.id]?.lastPlayed).sort((a, b) => (gameState[b.id].lastPlayed ?? 0) - (gameState[a.id].lastPlayed ?? 0));
  const recent = useMemo(() => [...pool].sort((a, b) => b.addedAt - a.addedAt).slice(0, 16), [pool]);
  const activeDownloads = downloads.filter(isActive);
  const tracks = recentTracks.map((r) => getTrack(r.id)).filter(Boolean).slice(0, 12);
  const pinned = collections.filter((c) => c.pinned);

  const sections: Partial<Record<WidgetId, JSX.Element | null>> = {
    hero: <FeaturedHero />,
    'continue-watching': inProgress(['movie', 'tv']).length ? (
      <Shelf title="Continue Watching" to="/movies">{inProgress(['movie', 'tv']).map((i) => <ContinueCard key={i.id} item={i} />)}</Shelf>
    ) : null,
    'continue-listening': allowed('album') && tracks.length ? (
      <Shelf title="Continue Listening" subtitle="Recently played" to="/music">
        {tracks.map((tr, i) => {
          const album = getItem(tr!.albumId);
          return (
            <button key={tr!.id} type="button" onClick={() => usePlayer.getState().play(tracks.map((x) => x!.id), i)} aria-label={`Play ${tr!.title} by ${tr!.artist}`}
              className="surface group flex w-60 shrink-0 snap-start items-center gap-3 p-2.5 text-left transition-colors hover:bg-line/[0.05]">
              <span className="relative shrink-0">
                {album && <Thumb item={album} className="w-12" />}
                <span className="absolute inset-0 flex items-center justify-center rounded-md bg-black/50 opacity-0 transition-opacity group-hover:opacity-100"><Play className="h-4 w-4 fill-current text-white" /></span>
              </span>
              <span className="min-w-0">
                <span className="block truncate text-[0.8125rem] font-medium">{tr!.title}</span>
                <span className="block truncate text-xs text-muted">{tr!.artist}</span>
              </span>
            </button>
          );
        })}
      </Shelf>
    ) : null,
    'continue-playing': playing.length ? <Shelf title="Continue Playing" subtitle="Playtime you log yourself" to="/games">{playing.map((i) => <ContinueCard key={i.id} item={i} />)}</Shelf> : null,
    'continue-reading': inProgress(['manga', 'book']).length ? (
      <Shelf title="Continue Reading" to="/books">{inProgress(['manga', 'book']).map((i) => <ContinueCard key={i.id} item={i} />)}</Shelf>
    ) : null,
    downloads: (
      <section className="mb-9">
        <div className="mb-3 flex items-end justify-between"><h2 className="text-lg font-semibold tracking-tight">Downloads in Progress</h2><Link to="/downloads" className="rounded-full px-2.5 py-1 text-xs font-medium text-muted hover:bg-line/10 hover:text-fg">Open manager</Link></div>
        {activeDownloads.length ? (
          <div className="grid gap-3 sm:grid-cols-2 xl:grid-cols-3">
            {activeDownloads.slice(0, 6).map((d) => (
              <Link key={d.id} to="/downloads" className="surface block p-3.5 transition-colors hover:bg-line/[0.04]">
                <div className="mb-2 flex items-center justify-between gap-3">
                  <p className="truncate text-[0.8125rem] font-medium">{d.title}</p>
                  {d.kind === 'simulated' ? <Badge tone="warn">Simulated</Badge> : <Badge tone="accent">{d.status === 'queued' ? 'Queued' : 'Downloading'}</Badge>}
                </div>
                <ProgressBar value={d.size ? (d.received / d.size) * 100 : 0} />
                <p className="mt-1.5 text-xs tabular-nums text-muted">{fmtBytes(d.received)} of {fmtBytes(d.size)} · {d.source}</p>
              </Link>
            ))}
          </div>
        ) : (
          <EmptyState icon={Download} title="Nothing is downloading" body="Downloads you start appear here with live progress." className="py-8"
            action={<Button size="sm" onClick={() => navigate('/downloads')}>Open downloads</Button>} />
        )}
      </section>
    ),
    pinned: pinned.length ? (
      <Shelf title="Pinned Collections" to="/library?tab=collections">{pinned.map((c) => <CollectionTile key={c.id} collection={c} className="shelf-card snap-start" />)}</Shelf>
    ) : null,
    'recently-added': <ItemShelf title="Recently Added" items={recent} showType to="/discover" />,
    addons: <AddonShelves limit={4} />,
    'quick-access': (
      <section className="mb-9">
        <h2 className="mb-3 text-lg font-semibold tracking-tight">Quick Access</h2>
        <div className="grid grid-cols-2 gap-3 sm:grid-cols-3 xl:grid-cols-6">
          {(['movies', 'music', 'games', 'manga', 'books', 'library'] as const).map((id) => {
            const { to, icon: Icon, key } = NAV[id];
            const types: MediaType[] = id === 'movies' ? ['movie', 'tv'] : id === 'music' ? ['album'] : id === 'games' ? ['game'] : id === 'manga' ? ['manga'] : id === 'books' ? ['book'] : MEDIA_TYPES;
            const count = catalog.filter((i) => types.includes(i.type) && (saved[i.id] || favorites[i.id])).length;
            return (
              <Link key={id} to={to} className="surface group flex items-center gap-3 p-3.5 transition-colors hover:bg-line/[0.05]">
                <span className="flex h-10 w-10 shrink-0 items-center justify-center rounded-btn bg-accent/15 text-accent transition-transform group-hover:scale-105"><Icon className="h-5 w-5" /></span>
                <span className="min-w-0">
                  <span className="block truncate text-[0.8125rem] font-semibold">{t(key)}</span>
                  <span className="block text-xs text-muted">{count} saved</span>
                </span>
              </Link>
            );
          })}
        </div>
      </section>
    ),
  };

  const visible = layout.widgets.filter((w) => w.visible);
  return (
    <div className="page">
      <div className="mb-6 flex flex-wrap items-end justify-between gap-3">
        <div>
          <h1 className="h-display text-[1.75rem] leading-tight sm:text-[2rem]">{t(`greet.${greeting()}`)}, {name}</h1>
          <p className="mt-1 text-sm text-muted">All your entertainment. One platform. Your rules.</p>
        </div>
        <div className="flex items-center gap-2">
          {pro && layouts.length > 1 && <Select label="Dashboard" value={layout.id} onChange={(v) => set('activeLayout', v)} options={layouts.map((l) => ({ value: l.id, label: l.name }))} />}
          <Button icon={SlidersHorizontal} onClick={() => setCustomizing(true)}>Customize</Button>
        </div>
      </div>
      {visible.map((w) => <div key={w.id}>{sections[w.id]}</div>)}
      {!visible.length && <EmptyState icon={LayoutDashboard} title="This dashboard is empty" body="Every section is hidden." action={<Button variant="primary" onClick={() => setCustomizing(true)}>Choose sections</Button>} />}
      <CustomizeModal open={customizing} onClose={() => setCustomizing(false)} />
    </div>
  );
}
