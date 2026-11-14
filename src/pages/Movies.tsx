import { Bookmark, Clapperboard, FolderPlus, Heart, Upload } from 'lucide-react';
import { useMemo, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { AddonShelves } from '../components/addons';
import { CollectionTile } from '../components/collections';
import { HistoryList } from '../components/HistoryList';
import { CatalogBrowser, ItemShelf, MediaGrid, Shelf } from '../components/media';
import { Button, Callout, EmptyState, PageHeader, Segmented } from '../components/ui';
import { useTab } from '../lib/hooks';
import { importVideo, runImport } from '../lib/importers';
import { byType, useCatalog } from '../store/catalog';
import { toast } from '../store/ui';
import { useStore } from '../store/useStore';
import { ContinueCard } from './Home';

type Tab = 'browse' | 'movies' | 'tv' | 'watchlist' | 'favorites' | 'history' | 'collections';

/** Movies & TV: real catalogues from your addons, plus what you have saved, watched and imported. */
export default function MoviesPage() {
  const navigate = useNavigate();
  const catalog = useCatalog();
  const [tab, setTab] = useTab<Tab>('browse');
  const [importing, setImporting] = useState(false);
  const saved = useStore((s) => s.saved);
  const favorites = useStore((s) => s.favorites);
  const progress = useStore((s) => s.progress);
  const collections = useStore((s) => s.collections);
  const createCollection = useStore((s) => s.createCollection);

  const all = useMemo(() => byType(catalog, 'movie', 'tv'), [catalog]);
  const movies = useMemo(() => byType(catalog, 'movie'), [catalog]);
  const shows = useMemo(() => byType(catalog, 'tv'), [catalog]);
  const recent = useMemo(() => [...all].sort((a, b) => b.addedAt - a.addedAt).slice(0, 14), [all]);
  const continuing = all.filter((i) => progress[i.id] && progress[i.id].percent < 97).sort((a, b) => progress[b.id].updatedAt - progress[a.id].updatedAt);
  const videoIds = new Set(all.map((i) => i.id));
  const mine = collections.filter((c) => c.itemIds.some((id) => videoIds.has(id)));

  const doImport = async () => {
    setImporting(true);
    const id = await runImport(importVideo);
    setImporting(false);
    if (id) navigate(`/item/${id}`);
  };

  return (
    <div className="page">
      <PageHeader title="Movies & TV" icon={Clapperboard} subtitle="Catalogues come from your movie and series addons. Saved, watched and imported titles live in your library."
        actions={<Button icon={Upload} loading={importing} onClick={doImport}>Import video file</Button>}>
        <div className="mt-5 max-w-full overflow-x-auto">
          <Segmented value={tab} onChange={setTab} options={[
            { value: 'browse', label: 'Browse' }, { value: 'movies', label: `Movies (${movies.length})` }, { value: 'tv', label: `TV (${shows.length})` },
            { value: 'watchlist', label: 'Watchlist' }, { value: 'favorites', label: 'Favorites' }, { value: 'history', label: 'History' }, { value: 'collections', label: 'Collections' },
          ]} />
        </div>
      </PageHeader>

      {tab === 'browse' && (
        <>
          {continuing.length > 0 && <Shelf title="Continue Watching">{continuing.map((i) => <ContinueCard key={i.id} item={i} />)}</Shelf>}
          <AddonShelves media={['movie', 'tv']} limit={10} />
          <ItemShelf title="Recently Added to Your Library" items={recent} showType />
        </>
      )}
      {tab === 'movies' && <CatalogBrowser items={movies} emptyAction={<Button onClick={() => { window.location.hash = '#/addons'; }}>Open Addons</Button>} />}
      {tab === 'tv' && <CatalogBrowser items={shows} emptyAction={<Button onClick={() => { window.location.hash = '#/addons'; }}>Open Addons</Button>} />}
      {tab === 'watchlist' && (all.some((i) => saved[i.id])
        ? <MediaGrid items={all.filter((i) => saved[i.id]).sort((a, b) => saved[b.id] - saved[a.id])} />
        : <EmptyState icon={Bookmark} title="Your watchlist is empty" body="Open any film or series and press Watchlist to save it for later." action={<Button variant="primary" onClick={() => setTab('browse')}>Browse titles</Button>} />)}
      {tab === 'favorites' && (all.some((i) => favorites[i.id])
        ? <MediaGrid items={all.filter((i) => favorites[i.id]).sort((a, b) => favorites[b.id] - favorites[a.id])} />
        : <EmptyState icon={Heart} title="No favorite films or series yet" body="Tap the heart on any card to add it here." />)}
      {tab === 'history' && <HistoryList types={['movie', 'tv']} />}
      {tab === 'collections' && (
        <>
          <Callout tone="info" className="mb-5" action={<Button size="sm" icon={FolderPlus} onClick={() => { const id = createCollection('New film collection'); toast('Collection created', 'success'); navigate(`/collection/${id}`); }}>New collection</Button>}>
            Personal collections that hold films or series. Collections can also mix in music, games, manga and books.
          </Callout>
          {mine.length
            ? <div className="grid grid-cols-[repeat(auto-fill,minmax(9.5rem,1fr))] gap-x-4 gap-y-6">{mine.map((c) => <CollectionTile key={c.id} collection={c} />)}</div>
            : <EmptyState icon={FolderPlus} title="No film collections yet" body="Use “Add to collection” on any title." />}
        </>
      )}
    </div>
  );
}
