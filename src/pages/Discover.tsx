import { Clock, Compass, Dices, Search as SearchIcon, SearchX, X } from 'lucide-react';
import { useEffect, useMemo, useState } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { CatalogBrowser, ItemShelf, MediaGrid, sortItems, TYPE_ICON, type SortKey } from '../components/media';
import { TrackList } from '../components/TrackList';
import { AddonShelves, RemoteSearch } from '../components/addons';
import { Button, Chip, EmptyState, PageHeader, Segmented, Select } from '../components/ui';
import { searchCatalog, useCatalog, type SearchScope } from '../store/catalog';
import { useStore } from '../store/useStore';
import type { MediaItem, MediaType, Track } from '../types';
import { MEDIA_TYPES, TYPE_PLURAL } from '../types';

export default function DiscoverPage() {
  const catalog = useCatalog();
  const navigate = useNavigate();
  const [type, setType] = useState<MediaType | 'all'>('all');
  const [genre, setGenre] = useState<string | null>(null);

  const pool = useMemo(() => catalog.filter((i) => type === 'all' || i.type === type), [catalog, type]);
  const genres = useMemo(() => {
    const count = new Map<string, number>();
    pool.forEach((i) => i.genres.forEach((g) => count.set(g, (count.get(g) ?? 0) + 1)));
    return [...count.entries()].sort((a, b) => b[1] - a[1]).slice(0, 24);
  }, [pool]);
  const added = useMemo(() => sortItems(pool, 'recent').slice(0, 16), [pool]);
  const pickAtRandom = () => { const pick = pool[Math.floor(Math.random() * pool.length)]; if (pick) navigate(`/item/${pick.id}`); };
  useEffect(() => setGenre(null), [type]);

  return (
    <div className="page">
      <PageHeader title="Discover" icon={Compass} subtitle="Titles from your addons and your library. Nothing is ranked or recommended for you."
        actions={<Button icon={Dices} onClick={pickAtRandom} disabled={!pool.length}>Pick at random</Button>}>
        <div className="mt-5 flex flex-wrap gap-2">
          <Chip active={type === 'all'} onClick={() => setType('all')}>Everything</Chip>
          {MEDIA_TYPES.map((t) => { const Icon = TYPE_ICON[t]; return <Chip key={t} active={type === t} onClick={() => setType(t)}><Icon className="h-3.5 w-3.5" />{TYPE_PLURAL[t]}</Chip>; })}
        </div>
      </PageHeader>

      <AddonShelves media={type === 'all' ? undefined : [type]} limit={8} />
      {added.length > 0 && <ItemShelf title="Recently added to your library" items={added} showType={type === 'all'} />}

      {genres.length > 0 && (
        <section className="mb-9">
          <h2 className="mb-3 text-lg font-semibold tracking-tight">Browse by genre</h2>
          <div className="mb-5 flex flex-wrap gap-2">
            {genres.map(([g, n]) => <Chip key={g} active={genre === g} onClick={() => setGenre(genre === g ? null : g)}>{g}<span className="opacity-60">{n}</span></Chip>)}
          </div>
          {genre && <MediaGrid items={pool.filter((i) => i.genres.includes(genre))} showType={type === 'all'} />}
        </section>
      )}

      <h2 className="mb-3 text-lg font-semibold tracking-tight">All titles</h2>
      <CatalogBrowser items={pool} showType={type === 'all'} />
    </div>
  );
}

const SCOPES: { value: SearchScope; label: string }[] = [
  { value: 'all', label: 'All' }, { value: 'movie', label: 'Movies' }, { value: 'tv', label: 'TV' }, { value: 'album', label: 'Music' },
  { value: 'game', label: 'Games' }, { value: 'manga', label: 'Manga' }, { value: 'book', label: 'Books' },
];

export function SearchPage() {
  const catalog = useCatalog();
  const navigate = useNavigate();
  const [params, setParams] = useSearchParams();
  const history = useStore((s) => s.searchHistory);
  const tracking = useStore((s) => s.settings.privacy.searchHistory);
  const set = useStore((s) => s.set);
  const pushSearch = useStore((s) => s.pushSearch);
  const q = params.get('q') ?? '';
  const scope = (params.get('type') as SearchScope) ?? 'all';
  const [draft, setDraft] = useState(q);
  const [sort, setSort] = useState<'relevance' | SortKey>('relevance');
  useEffect(() => setDraft(q), [q]);

  const update = (patch: Record<string, string>) => { const p = new URLSearchParams(params); Object.entries(patch).forEach(([k, v]) => (v && v !== 'all' ? p.set(k, v) : p.delete(k))); setParams(p, { replace: true }); };
  const hits = useMemo(() => searchCatalog(catalog, q, scope), [catalog, q, scope]);
  const all = useMemo(() => searchCatalog(catalog, q, 'all'), [catalog, q]);
  const items = useMemo(() => {
    const seen = new Set<string>();
    const list = hits.filter((h) => !h.track && !seen.has(h.item.id) && seen.add(h.item.id)).map((h) => h.item);
    return sort === 'relevance' ? list : sortItems(list as MediaItem[], sort);
  }, [hits, sort]);
  const songs = hits.filter((h) => h.track).map((h) => h.track as Track);
  const count = (s: SearchScope) => new Set(all.filter((h) => s === 'all' || h.item.type === s).map((h) => h.track?.id ?? h.item.id)).size;

  return (
    <div className="page">
      <PageHeader title="Search" icon={SearchIcon} subtitle="Searches titles, alternate titles, creators, genres, tags, descriptions, custom fields and song names in your catalogue.">
        <form className="mt-5 flex max-w-2xl gap-2" onSubmit={(e) => { e.preventDefault(); pushSearch(draft); update({ q: draft.trim() }); }}>
          <div className="relative flex-1">
            <SearchIcon className="pointer-events-none absolute left-3.5 top-1/2 h-4 w-4 -translate-y-1/2 text-muted" />
            <input className="field h-11 pl-10 pr-10 text-[0.9375rem]" placeholder="What are you looking for?" value={draft} onChange={(e) => { setDraft(e.target.value); update({ q: e.target.value }); }} aria-label="Search query" autoFocus />
            {draft && <button type="button" aria-label="Clear search" onClick={() => { setDraft(''); update({ q: '' }); }} className="absolute right-2 top-1/2 flex h-7 w-7 -translate-y-1/2 items-center justify-center rounded-full text-muted hover:bg-line/10 hover:text-fg"><X className="h-4 w-4" /></button>}
          </div>
          <Button type="submit" variant="primary" size="lg">Search</Button>
        </form>
      </PageHeader>

      {!q.trim() ? (
        <>
          <div className="mb-3 flex items-center justify-between"><h2 className="text-lg font-semibold tracking-tight">Recent searches</h2>{history.length > 0 && <Button size="sm" variant="ghost" onClick={() => set('searchHistory', [])}>Clear history</Button>}</div>
          {history.length ? (
            <div className="mb-9 flex flex-wrap gap-2">{history.map((h) => <Chip key={h} onClick={() => { setDraft(h); update({ q: h }); }}><Clock className="h-3.5 w-3.5" />{h}</Chip>)}</div>
          ) : <p className="mb-9 text-[0.8125rem] text-muted">{tracking ? 'Your recent searches will appear here.' : 'Search history is turned off in Settings → Privacy.'}</p>}
          <h2 className="mb-3 text-lg font-semibold tracking-tight">Try searching for</h2>
          <div className="flex flex-wrap gap-2">{['blender', 'silent film', 'synthwave', 'roguelike', 'Jane Austen', 'webtoon', 'strategy', 'public domain'].map((s) => <Chip key={s} onClick={() => { setDraft(s); pushSearch(s); update({ q: s }); }}>{s}</Chip>)}</div>
        </>
      ) : (
        <>
          <div className="mb-5 flex flex-wrap items-center gap-2">
            <Segmented value={scope} onChange={(v) => update({ type: v })} options={SCOPES.map((s) => ({ value: s.value, label: `${s.label} (${count(s.value)})` }))} />
            <Select label="Sort results" className="ml-auto" value={sort} onChange={setSort} options={[{ value: 'relevance', label: 'Best match' }, { value: 'title', label: 'Title A–Z' }, { value: 'year', label: 'Newest release' }, { value: 'rating', label: 'Highest rated' }, { value: 'recent', label: 'Recently added' }]} />
          </div>
          {!items.length && !songs.length ? (
            <EmptyState icon={SearchX} title={`No results for “${q.trim()}”`} body={scope === 'all' ? 'Check the spelling, or try a creator, genre or tag.' : 'Nothing in this category. Try “All”.'} action={scope !== 'all' ? <Button onClick={() => update({ type: 'all' })}>Search all categories</Button> : <Button onClick={() => navigate('/discover')}>Browse Discover</Button>} />
          ) : (
            <>
              {items.length > 0 && <section className="mb-9"><h2 className="mb-3 text-lg font-semibold tracking-tight">{items.length} title{items.length === 1 ? '' : 's'}</h2><MediaGrid items={items} showType={scope === 'all'} /></section>}
              {songs.length > 0 && <section><h2 className="mb-3 text-lg font-semibold tracking-tight">{songs.length} song{songs.length === 1 ? '' : 's'}</h2><div className="surface p-2"><TrackList tracks={songs} showAlbum showArt /></div></section>}
            </>
          )}
          <RemoteSearch q={q.trim()} scope={scope} />
        </>
      )}
    </div>
  );
}
