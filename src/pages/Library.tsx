import { Check, Download, FolderHeart, FolderPlus, Library, Pencil, Pin, PinOff, Plus, Search, Trash2, X } from 'lucide-react';
import { useMemo, useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { CollectionMosaic, CollectionTile } from '../components/collections';
import { MediaCard, MediaGrid, Thumb, TYPE_ICON } from '../components/media';
import { Badge, Button, Chip, EmptyState, Field, Menu, Modal, PageHeader, Segmented } from '../components/ui';
import { useTab } from '../lib/hooks';
import { cn } from '../lib/utils';
import { getItem, useCatalog } from '../store/catalog';
import { useDownloads } from '../store/downloads';
import { confirmDialog, toast } from '../store/ui';
import { useStore } from '../store/useStore';
import type { Collection, MediaItem, MediaType } from '../types';
import { MEDIA_TYPES, TYPE_LABEL, TYPE_PLURAL } from '../types';

type Tab = 'all' | 'favorites' | 'added' | 'accessed' | 'watched' | 'collections' | 'downloads' | 'completed' | 'progress';

function CollectionModal({ open, onClose, collection }: { open: boolean; onClose: () => void; collection?: Collection }) {
  const navigate = useNavigate();
  const [name, setName] = useState(collection?.name ?? '');
  const [description, setDescription] = useState(collection?.description ?? '');
  const save = () => {
    if (!name.trim()) return;
    const s = useStore.getState();
    if (collection) { s.updateCollection(collection.id, { name: name.trim(), description: description.trim() }); toast('Collection updated', 'success'); onClose(); }
    else { const id = s.createCollection(name.trim(), description.trim()); toast(`Created “${name.trim()}”`, 'success'); onClose(); setName(''); setDescription(''); navigate(`/collection/${id}`); }
  };
  return (
    <Modal open={open} onClose={onClose} title={collection ? 'Edit collection' : 'New collection'} subtitle="Collections can hold any mix of movies, shows, albums, games, manga and books." size="sm"
      footer={<><Button variant="ghost" onClick={onClose}>Cancel</Button><Button variant="primary" disabled={!name.trim()} onClick={save}>{collection ? 'Save' : 'Create'}</Button></>}>
      <form className="space-y-3" onSubmit={(e) => { e.preventDefault(); save(); }}>
        <Field label="Name"><input autoFocus className="field" value={name} onChange={(e) => setName(e.target.value)} maxLength={60} placeholder="e.g. Cosy mysteries" /></Field>
        <Field label="Description"><textarea className="field min-h-[4.5rem]" value={description} onChange={(e) => setDescription(e.target.value)} maxLength={240} /></Field>
      </form>
    </Modal>
  );
}

export default function LibraryPage() {
  const catalog = useCatalog();
  const navigate = useNavigate();
  const [tab, setTab] = useTab<Tab>('all');
  const [type, setType] = useState<MediaType | 'all'>('all');
  const [creating, setCreating] = useState(false);
  const saved = useStore((s) => s.saved);
  const favorites = useStore((s) => s.favorites);
  const completed = useStore((s) => s.completed);
  const progress = useStore((s) => s.progress);
  const lastAccess = useStore((s) => s.lastAccess);
  const history = useStore((s) => s.history);
  const gameState = useStore((s) => s.gameState);
  const collections = useStore((s) => s.collections);
  const downloads = useDownloads((s) => s.items);

  const mine = useMemo(() => catalog.filter((i) => saved[i.id] || favorites[i.id] || !i.demo), [catalog, saved, favorites]);
  const downloadedIds = new Set(downloads.map((d) => d.itemId).filter(Boolean));
  const watchedIds = new Set(history.filter((h) => h.kind === 'watch').map((h) => h.id));
  const watchedAt = (id: string) => Math.max(lastAccess[id] ?? 0, progress[id]?.updatedAt ?? 0);
  const watchedList = catalog
    .filter((i) => (i.type === 'movie' || i.type === 'tv') && (completed[i.id] || (progress[i.id]?.percent ?? 0) >= 90 || watchedIds.has(i.id)))
    .sort((a, b) => watchedAt(b.id) - watchedAt(a.id));
  const lists: Record<Exclude<Tab, 'collections'>, MediaItem[]> = {
    all: [...mine].sort((a, b) => a.title.localeCompare(b.title)),
    favorites: catalog.filter((i) => favorites[i.id]).sort((a, b) => favorites[b.id] - favorites[a.id]),
    added: [...mine].sort((a, b) => Math.max(saved[b.id] ?? 0, b.demo ? 0 : b.addedAt) - Math.max(saved[a.id] ?? 0, a.demo ? 0 : a.addedAt)),
    accessed: catalog.filter((i) => lastAccess[i.id]).sort((a, b) => lastAccess[b.id] - lastAccess[a.id]),
    downloads: catalog.filter((i) => downloadedIds.has(i.id)),
    completed: catalog.filter((i) => completed[i.id]).sort((a, b) => completed[b.id] - completed[a.id]),
    watched: watchedList,
    progress: catalog.filter((i) => !completed[i.id] && ((progress[i.id] && progress[i.id].percent > 0 && progress[i.id].percent < 97) || (i.type === 'game' && (gameState[i.id]?.playMinutes ?? 0) > 0))),
  };
  const list = tab === 'collections' ? [] : lists[tab].filter((i) => type === 'all' || i.type === type);
  const counts = (t: MediaType) => (tab === 'collections' ? 0 : lists[tab].filter((i) => i.type === t).length);
  const empty: Record<Exclude<Tab, 'collections'>, [string, string]> = {
    all: ['Your library is empty', 'Save or favorite anything, or import your own files, and it will appear here.'],
    favorites: ['No favorites yet', 'Tap the heart on any card.'],
    added: ['Nothing added yet', 'Items you save or import show up here, newest first.'],
    accessed: ['Nothing opened recently', 'Items you open, play or read show up here.'],
    downloads: ['No downloaded items', 'Items you download through the download manager are listed here.'],
    completed: ['Nothing completed yet', 'Finish a film, book or series — or mark one as completed from its page.'],
    progress: ['Nothing in progress', 'Start watching, reading or playing something.'],
    watched: ['Nothing watched yet', 'Films and episodes you finish, or start watching, appear here, most recent first.'],
  };

  return (
    <div className="page">
      <PageHeader title="My Library" icon={Library} subtitle="Everything you’ve saved, favorited, started, finished, downloaded or imported — across every kind of media."
        actions={<Button icon={FolderPlus} onClick={() => setCreating(true)}>New collection</Button>}>
        <div className="mt-5">
          <Segmented value={tab} onChange={setTab} options={[
            { value: 'all', label: `All Media (${lists.all.length})` }, { value: 'favorites', label: 'Favorites' }, { value: 'added', label: 'Recently Added' }, { value: 'accessed', label: 'Recent' }, { value: 'watched', label: 'Watched' },
            { value: 'collections', label: `Collections (${collections.length})` }, { value: 'downloads', label: 'Downloads' }, { value: 'completed', label: 'Completed' }, { value: 'progress', label: 'In Progress' },
          ]} />
        </div>
      </PageHeader>

      {tab === 'collections' ? (
        collections.length ? (
          <div className="grid grid-cols-[repeat(auto-fill,minmax(9.5rem,1fr))] gap-x-4 gap-y-6 sm:grid-cols-[repeat(auto-fill,minmax(11rem,1fr))]">
            <button type="button" onClick={() => setCreating(true)} className="group block text-left">
              <div className="lift flex aspect-square items-center justify-center rounded-card border border-dashed border-line/20 text-muted transition-colors group-hover:border-accent group-hover:text-accent"><Plus className="h-8 w-8" /></div>
              <p className="mt-2.5 text-[0.8125rem] font-medium">New collection</p><p className="text-xs text-muted">Mix any media types</p>
            </button>
            {collections.map((c) => <CollectionTile key={c.id} collection={c} />)}
          </div>
        ) : <EmptyState icon={FolderHeart} title="No collections yet" body="Group a game with its soundtrack, the book it’s based on and the film adaptation." action={<Button variant="primary" icon={FolderPlus} onClick={() => setCreating(true)}>New collection</Button>} />
      ) : (
        <>
          <div className="mb-5 flex flex-wrap gap-2">
            <Chip active={type === 'all'} onClick={() => setType('all')}>All ({lists[tab].length})</Chip>
            {MEDIA_TYPES.map((t) => { const Icon = TYPE_ICON[t]; return <Chip key={t} active={type === t} onClick={() => setType(type === t ? 'all' : t)}><Icon className="h-3.5 w-3.5" />{TYPE_PLURAL[t]} ({counts(t)})</Chip>; })}
          </div>
          {tab === 'downloads' && <p className="mb-4 text-xs text-muted">Showing library items with a record in the <Link to="/downloads" className="text-accent hover:underline">download manager</Link>.</p>}
          {list.length ? <MediaGrid items={list} showType /> : <EmptyState icon={tab === 'downloads' ? Download : Library} title={empty[tab][0]} body={type === 'all' ? empty[tab][1] : `No ${TYPE_PLURAL[type].toLowerCase()} in this view.`} action={<Button variant="primary" onClick={() => navigate('/discover')}>Discover something</Button>} />}
        </>
      )}
      <CollectionModal open={creating} onClose={() => setCreating(false)} />
    </div>
  );
}

function AddItemsModal({ open, onClose, collection }: { open: boolean; onClose: () => void; collection: Collection }) {
  const catalog = useCatalog();
  const toggle = useStore((s) => s.toggleInCollection);
  const [q, setQ] = useState('');
  const [type, setType] = useState<MediaType | 'all'>('all');
  const list = useMemo(() => {
    const needle = q.trim().toLowerCase();
    return catalog.filter((i) => (type === 'all' || i.type === type) && (!needle || i.title.toLowerCase().includes(needle) || i.creator?.toLowerCase().includes(needle)))
      .sort((a, b) => a.title.localeCompare(b.title)).slice(0, 80);
  }, [catalog, q, type]);
  return (
    <Modal open={open} onClose={onClose} title={`Add to “${collection.name}”`} subtitle={`${collection.itemIds.length} item${collection.itemIds.length === 1 ? '' : 's'} so far`} size="md" footer={<Button variant="primary" onClick={onClose}>Done</Button>}>
      <div className="relative mb-3">
        <Search className="pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-muted" />
        <input autoFocus className="field pl-9" placeholder="Search your whole catalogue" value={q} onChange={(e) => setQ(e.target.value)} aria-label="Search catalogue" />
      </div>
      <div className="mb-3 flex flex-wrap gap-1.5">
        <Chip active={type === 'all'} onClick={() => setType('all')}>All</Chip>
        {MEDIA_TYPES.map((t) => <Chip key={t} active={type === t} onClick={() => setType(t)}>{TYPE_PLURAL[t]}</Chip>)}
      </div>
      <ul className="max-h-[46vh] space-y-0.5 overflow-y-auto">
        {list.map((i) => {
          const inIt = collection.itemIds.includes(i.id);
          return (
            <li key={i.id}>
              <button type="button" aria-pressed={inIt} onClick={() => toggle(collection.id, i.id)} className="flex w-full items-center gap-3 rounded-btn px-2 py-1.5 text-left hover:bg-line/[0.07]">
                <Thumb item={i} className="w-8" />
                <span className="min-w-0 flex-1"><span className="block truncate text-[0.8125rem] font-medium">{i.title}</span><span className="block truncate text-xs text-muted">{TYPE_LABEL[i.type]}{i.creator ? ` · ${i.creator}` : ''}</span></span>
                <span className={cn('flex h-6 w-6 items-center justify-center rounded-full border', inIt ? 'border-accent bg-accent text-accent-fg' : 'border-line/20 text-transparent')}><Check className="h-3.5 w-3.5" /></span>
              </button>
            </li>
          );
        })}
        {!list.length && <li className="py-8 text-center text-[0.8125rem] text-muted">Nothing matches.</li>}
      </ul>
    </Modal>
  );
}

export function CollectionPage() {
  const { id = '' } = useParams();
  const navigate = useNavigate();
  const collection = useStore((s) => s.collections.find((c) => c.id === id));
  const update = useStore((s) => s.updateCollection);
  const toggle = useStore((s) => s.toggleInCollection);
  useCatalog(); // re-render when metadata changes
  const [editing, setEditing] = useState(false);
  const [adding, setAdding] = useState(false);
  if (!collection) return <div className="page"><EmptyState icon={FolderHeart} title="Collection not found" action={<Button variant="primary" onClick={() => navigate('/library?tab=collections')}>All collections</Button>} /></div>;
  const state = useStore.getState();
  const items = collection.itemIds.map((i) => getItem(i, state)).filter((i): i is MediaItem => !!i);
  const byType = MEDIA_TYPES.map((t) => [t, items.filter((i) => i.type === t).length] as const).filter(([, n]) => n > 0);
  const remove = async () => {
    if (!(await confirmDialog({ title: `Delete “${collection.name}”?`, body: 'The items themselves stay in your library.', confirmLabel: 'Delete collection', danger: true }))) return;
    useStore.getState().deleteCollection(collection.id);
    toast('Collection deleted', 'success');
    navigate('/library?tab=collections');
  };
  return (
    <div className="page">
      <div className="mb-8 flex flex-col items-center gap-6 text-center sm:flex-row sm:items-end sm:text-left">
        <CollectionMosaic collection={collection} className="w-44 shrink-0 shadow-pop sm:w-52" />
        <div className="min-w-0 flex-1">
          <div className="flex flex-wrap justify-center gap-1.5 sm:justify-start"><Badge tone="accent" icon={FolderHeart}>{collection.kind === 'shelf' ? 'Book shelf' : 'Collection'}</Badge>{collection.pinned && <Badge icon={Pin}>Pinned to sidebar & Home</Badge>}</div>
          <h1 className="h-display mt-2 text-3xl sm:text-5xl">{collection.name}</h1>
          {collection.description && <p className="mt-2 max-w-xl text-sm text-muted">{collection.description}</p>}
          <p className="mt-1 text-xs text-muted">{items.length} item{items.length === 1 ? '' : 's'}{byType.length ? ` · ${byType.map(([t, n]) => `${n} ${n === 1 ? TYPE_LABEL[t].toLowerCase() : TYPE_PLURAL[t].toLowerCase()}`).join(', ')}` : ''}</p>
          <div className="mt-4 flex flex-wrap items-center justify-center gap-2 sm:justify-start">
            <Button variant="primary" size="lg" icon={Plus} onClick={() => setAdding(true)}>Add items</Button>
            <Button size="lg" icon={collection.pinned ? PinOff : Pin} onClick={() => { update(collection.id, { pinned: !collection.pinned }); toast(collection.pinned ? 'Unpinned' : 'Pinned to the sidebar and Home', 'success'); }}>{collection.pinned ? 'Unpin' : 'Pin'}</Button>
            <Menu label="Collection actions" className="inline-flex h-11 w-11 items-center justify-center rounded-full bg-line/10 text-fg hover:bg-line/20" items={[
              { label: 'Edit name & description', icon: Pencil, onClick: () => setEditing(true) },
              { label: collection.kind === 'shelf' ? 'Convert to general collection' : 'Mark as book shelf', icon: FolderHeart, onClick: () => update(collection.id, { kind: collection.kind === 'shelf' ? 'collection' : 'shelf' }) },
              'divider',
              { label: 'Delete collection…', icon: Trash2, danger: true, onClick: remove },
            ]} />
          </div>
        </div>
      </div>
      {items.length ? (
        <div className="grid grid-cols-[repeat(auto-fill,minmax(8.25rem,1fr))] gap-x-4 gap-y-6 sm:grid-cols-[repeat(auto-fill,minmax(10rem,1fr))]">
          {items.map((it) => (
            <div key={it.id} className="group/row relative">
              <MediaCard item={it} showType />
              <button type="button" aria-label={`Remove ${it.title} from this collection`} title="Remove from collection" onClick={() => { toggle(collection.id, it.id); toast(`Removed “${it.title}”`); }}
                className="absolute -left-2 -top-2 z-10 flex h-7 w-7 items-center justify-center rounded-full border border-line/10 bg-card text-muted opacity-0 shadow-lift transition-opacity hover:text-red-400 focus-visible:opacity-100 group-hover/row:opacity-100"><X className="h-3.5 w-3.5" /></button>
            </div>
          ))}
        </div>
      ) : <EmptyState icon={FolderPlus} title="This collection is empty" body="Add anything from your catalogue — different media types are welcome side by side." action={<Button variant="primary" icon={Plus} onClick={() => setAdding(true)}>Add items</Button>} />}
      <CollectionModal key={`${collection.id}-${editing}`} open={editing} onClose={() => setEditing(false)} collection={collection} />
      <AddItemsModal open={adding} onClose={() => setAdding(false)} collection={collection} />
    </div>
  );
}
