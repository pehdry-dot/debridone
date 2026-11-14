import { BookImage, Bookmark, Heart, Upload } from 'lucide-react';
import { useMemo, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { AddonShelves } from '../components/addons';
import { HistoryList } from '../components/HistoryList';
import { CatalogBrowser, MediaGrid, Shelf } from '../components/media';
import { Button, Callout, EmptyState, PageHeader, Segmented } from '../components/ui';
import { useTab } from '../lib/hooks';
import { importManga, runImport } from '../lib/importers';
import { byType, useCatalog } from '../store/catalog';
import { useStore } from '../store/useStore';
import { ContinueCard } from './Home';

type Tab = 'discover' | 'library' | 'reading' | 'favorites' | 'history';

/** Manga: metadata from your addons (MangaDex by default) and the comics you import. */
export default function MangaPage() {
  const navigate = useNavigate();
  const catalog = useCatalog();
  const [tab, setTab] = useTab<Tab>('discover');
  const [importing, setImporting] = useState(false);
  const saved = useStore((s) => s.saved);
  const favorites = useStore((s) => s.favorites);
  const progress = useStore((s) => s.progress);
  const all = useMemo(() => byType(catalog, 'manga'), [catalog]);
  const continuing = all.filter((i) => progress[i.id] && progress[i.id].percent < 97).sort((a, b) => progress[b.id].updatedAt - progress[a.id].updatedAt);

  const doImport = async () => {
    setImporting(true);
    const id = await runImport(importManga);
    setImporting(false);
    if (id) navigate(`/item/${id}`);
  };

  return (
    <div className="page">
      <PageHeader title="Manga" icon={BookImage} subtitle="Titles, covers and popularity come from your manga addons. Import a CBZ archive or page images to read your own comics."
        actions={<Button icon={Upload} loading={importing} onClick={doImport}>Import CBZ / images</Button>}>
        <div className="mt-5 max-w-full overflow-x-auto">
          <Segmented value={tab} onChange={setTab} options={[
            { value: 'discover', label: 'Discover' }, { value: 'library', label: `Library (${all.length})` }, { value: 'reading', label: 'Reading list' },
            { value: 'favorites', label: 'Favorites' }, { value: 'history', label: 'History' },
          ]} />
        </div>
      </PageHeader>

      {tab === 'discover' && (
        <>
          {continuing.length > 0 && <Shelf title="Continue Reading">{continuing.map((i) => <ContinueCard key={i.id} item={i} />)}</Shelf>}
          <AddonShelves media={['manga']} limit={8} />
          <Callout tone="info" title="About manga sources">
            DebridOne shows manga metadata from addons. Reading chapters is not included: a manga addon that provides pages can add them, and imported comics read here.
          </Callout>
        </>
      )}
      {tab === 'library' && <CatalogBrowser items={all} emptyAction={<Button icon={Upload} onClick={doImport}>Import a comic</Button>} />}
      {tab === 'reading' && (all.some((i) => saved[i.id])
        ? <MediaGrid items={all.filter((i) => saved[i.id]).sort((a, b) => saved[b.id] - saved[a.id])} />
        : <EmptyState icon={Bookmark} title="Your reading list is empty" body="Open a series and press Reading list to keep it here." action={<Button variant="primary" onClick={() => setTab('discover')}>Discover manga</Button>} />)}
      {tab === 'favorites' && (all.some((i) => favorites[i.id])
        ? <MediaGrid items={all.filter((i) => favorites[i.id])} />
        : <EmptyState icon={Heart} title="No favorite series yet" body="Tap the heart on any cover." />)}
      {tab === 'history' && <HistoryList types={['manga']} />}
    </div>
  );
}
