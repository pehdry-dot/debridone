import { BookOpen, Bookmark, Heart, LibraryBig, Plus, Upload } from 'lucide-react';
import { useMemo, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { AddonShelves } from '../components/addons';
import { CollectionTile } from '../components/collections';
import { HistoryList } from '../components/HistoryList';
import { CatalogBrowser, MediaGrid, Shelf } from '../components/media';
import { Button, Callout, EmptyState, Field, Modal, PageHeader, Segmented } from '../components/ui';
import { useTab } from '../lib/hooks';
import { importBook, runImport } from '../lib/importers';
import { byType, useCatalog } from '../store/catalog';
import { toast } from '../store/ui';
import { useStore } from '../store/useStore';
import { ContinueCard } from './Home';

type Tab = 'library' | 'reading' | 'shelves' | 'favorites' | 'history';

/** Books and e-books: catalogues from your addons (Open Library, Project Gutenberg) plus the books you import. */
export default function BooksPage() {
  const navigate = useNavigate();
  const catalog = useCatalog();
  const [tab, setTab] = useTab<Tab>('library');
  const [importing, setImporting] = useState(false);
  const [shelfOpen, setShelfOpen] = useState(false);
  const [shelfName, setShelfName] = useState('');
  const saved = useStore((s) => s.saved);
  const favorites = useStore((s) => s.favorites);
  const progress = useStore((s) => s.progress);
  const collections = useStore((s) => s.collections);

  const books = useMemo(() => byType(catalog, 'book'), [catalog]);
  const continuing = books.filter((i) => progress[i.id] && progress[i.id].percent < 97).sort((a, b) => progress[b.id].updatedAt - progress[a.id].updatedAt);
  const ids = new Set(books.map((b) => b.id));
  const shelves = collections.filter((c) => c.kind === 'shelf' || (c.itemIds.length > 0 && c.itemIds.every((id) => ids.has(id))));
  const authors = useMemo(() => [...new Set(books.map((b) => b.creator).filter(Boolean))] as string[], [books]);

  const doImport = async () => {
    setImporting(true);
    const id = await runImport(importBook);
    setImporting(false);
    if (id) navigate(`/item/${id}`);
  };
  const createShelf = () => {
    if (!shelfName.trim()) return;
    const id = useStore.getState().createCollection(shelfName.trim(), '', 'shelf');
    toast(`Shelf “${shelfName.trim()}” created`, 'success');
    setShelfName(''); setShelfOpen(false);
    navigate(`/collection/${id}`);
  };

  return (
    <div className="page">
      <PageHeader title="Books & E-books" icon={BookOpen} subtitle="Catalogues come from your book addons, including public-domain downloads from Project Gutenberg. Your own EPUB, PDF and text files live here too."
        actions={<><Button icon={Plus} onClick={() => setShelfOpen(true)}>New shelf</Button><Button icon={Upload} loading={importing} onClick={doImport}>Import e-book</Button></>}>
        <div className="mt-5 max-w-full overflow-x-auto">
          <Segmented value={tab} onChange={setTab} options={[
            { value: 'library', label: `Library (${books.length})` }, { value: 'reading', label: 'Reading list' }, { value: 'shelves', label: `Shelves (${shelves.length})` },
            { value: 'favorites', label: 'Favorites' }, { value: 'history', label: 'History' },
          ]} />
        </div>
      </PageHeader>

      {tab === 'library' && (
        <>
          {continuing.length > 0 && <Shelf title="Continue Reading">{continuing.map((i) => <ContinueCard key={i.id} item={i} />)}</Shelf>}
          <AddonShelves media={['book']} limit={8} />
          <section className="mb-9">
            <h2 className="mb-3 text-lg font-semibold tracking-tight">Your books</h2>
            <CatalogBrowser items={books} emptyAction={<Button icon={Upload} onClick={doImport}>Import e-book</Button>} />
          </section>
          {authors.length > 0 && (
            <section className="mb-9">
              <h2 className="mb-3 text-lg font-semibold tracking-tight">Authors in your library</h2>
              <div className="flex flex-wrap gap-2">
                {authors.map((a) => <button key={a} type="button" onClick={() => navigate(`/search?q=${encodeURIComponent(a)}&type=book`)} className="rounded-full border border-line/10 bg-line/[0.04] px-3.5 py-1.5 text-[0.8125rem] text-muted transition-colors hover:bg-line/10 hover:text-fg">{a}</button>)}
              </div>
            </section>
          )}
          <Callout tone="info" title="Supported formats">EPUB (unpacked and cleaned in the app), PDF (your browser’s viewer) and plain text. Imported books are stored on this PC.</Callout>
        </>
      )}
      {tab === 'reading' && (books.some((i) => saved[i.id])
        ? <MediaGrid items={books.filter((i) => saved[i.id]).sort((a, b) => saved[b.id] - saved[a.id])} />
        : <EmptyState icon={Bookmark} title="Your reading list is empty" body="Open a book and press Reading list." action={<Button variant="primary" onClick={() => setTab('library')}>Browse books</Button>} />)}
      {tab === 'shelves' && (shelves.length
        ? <div className="grid grid-cols-[repeat(auto-fill,minmax(9.5rem,1fr))] gap-x-4 gap-y-6">{shelves.map((c) => <CollectionTile key={c.id} collection={c} />)}</div>
        : <EmptyState icon={LibraryBig} title="No shelves yet" body="Shelves are collections for books. Create one, then use “Add to collection” on any book." action={<Button variant="primary" icon={Plus} onClick={() => setShelfOpen(true)}>New shelf</Button>} />)}
      {tab === 'favorites' && (books.some((i) => favorites[i.id])
        ? <MediaGrid items={books.filter((i) => favorites[i.id])} />
        : <EmptyState icon={Heart} title="No favorite books yet" body="Tap the heart on any cover." />)}
      {tab === 'history' && <HistoryList types={['book']} />}

      <Modal open={shelfOpen} onClose={() => setShelfOpen(false)} title="New shelf" size="sm" footer={<><Button variant="ghost" onClick={() => setShelfOpen(false)}>Cancel</Button><Button variant="primary" disabled={!shelfName.trim()} onClick={createShelf}>Create shelf</Button></>}>
        <form onSubmit={(e) => { e.preventDefault(); createShelf(); }}><Field label="Shelf name"><input autoFocus className="field" value={shelfName} onChange={(e) => setShelfName(e.target.value)} maxLength={60} placeholder="e.g. To read this winter" /></Field></form>
      </Modal>
    </div>
  );
}
