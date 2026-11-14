import { Check, FolderPlus, Pin, Plus } from 'lucide-react';
import { useState } from 'react';
import { Link } from 'react-router-dom';
import { cn } from '../lib/utils';
import { getItem, useItem } from '../store/catalog';
import { toast, useUi } from '../store/ui';
import { useStore } from '../store/useStore';
import type { Collection } from '../types';
import { TYPE_PLURAL } from '../types';
import { Art, Cover } from './Cover';
import { Button, Modal } from './ui';

/** 2×2 mosaic of the first four covers in a collection. */
export function CollectionMosaic({ collection, className }: { collection: Collection; className?: string }) {
  const state = useStore.getState();
  const items = collection.itemIds.map((id) => getItem(id, state)).filter(Boolean).slice(0, 4);
  return (
    <div className={cn('relative grid aspect-square grid-cols-2 grid-rows-2 gap-px overflow-hidden rounded-card bg-card ring-1 ring-inset ring-line/10', className)}>
      {items.length === 0 && <Art seed={collection.id} palette={collection.name.length} />}
      {items.map((it, i) => <Cover key={it!.id} item={it!} text={false} className={cn('h-full w-full', items.length === 1 && 'col-span-2 row-span-2', items.length === 2 && 'row-span-2', items.length === 3 && i === 0 && 'row-span-2')} />)}
    </div>
  );
}

export function CollectionTile({ collection, className }: { collection: Collection; className?: string }) {
  const state = useStore.getState();
  const types = [...new Set(collection.itemIds.map((id) => getItem(id, state)?.type).filter(Boolean))] as (keyof typeof TYPE_PLURAL)[];
  return (
    <Link to={`/collection/${collection.id}`} className={cn('group block min-w-0', className)}>
      <div className="lift relative">
        <CollectionMosaic collection={collection} className="shadow-lift" />
        {collection.pinned && <span className="absolute right-2 top-2 flex h-6 w-6 items-center justify-center rounded-full bg-black/60 text-white backdrop-blur-sm"><Pin className="h-3 w-3" /></span>}
      </div>
      <p className="mt-2.5 truncate text-[0.8125rem] font-medium">{collection.name}</p>
      <p className="truncate text-xs text-muted">{collection.itemIds.length} item{collection.itemIds.length === 1 ? '' : 's'}{types.length > 1 ? ` · ${types.map((t) => TYPE_PLURAL[t]).join(', ')}` : ''}</p>
    </Link>
  );
}

/** Global "Add to collection" dialog. Open it with openCollector(itemId). */
export function CollectionPicker() {
  const itemId = useUi((s) => s.collectItemId);
  const item = useItem(itemId ?? undefined);
  const collections = useStore((s) => s.collections);
  const toggle = useStore((s) => s.toggleInCollection);
  const create = useStore((s) => s.createCollection);
  const [name, setName] = useState('');
  const close = () => { useUi.setState({ collectItemId: null }); setName(''); };
  const add = () => {
    if (!name.trim() || !itemId) return;
    create(name.trim(), '', 'collection', [itemId]);
    toast(`Created “${name.trim()}” and added ${item?.title ?? 'item'}`, 'success');
    setName('');
  };
  return (
    <Modal open={!!itemId && !!item} onClose={close} title="Add to collection" subtitle={item ? `${item.title} — collections can mix movies, music, games, manga and books.` : undefined} size="sm"
      footer={<Button variant="primary" onClick={close}>Done</Button>}>
      <form className="mb-4 flex gap-2" onSubmit={(e) => { e.preventDefault(); add(); }}>
        <input className="field" placeholder="New collection name" value={name} onChange={(e) => setName(e.target.value)} aria-label="New collection name" maxLength={60} />
        <Button type="submit" icon={Plus} disabled={!name.trim()}>Create</Button>
      </form>
      {collections.length === 0 && <p className="py-6 text-center text-[0.8125rem] text-muted">No collections yet. Create your first one above.</p>}
      <ul className="space-y-1">
        {collections.map((c) => {
          const inIt = !!itemId && c.itemIds.includes(itemId);
          return (
            <li key={c.id}>
              <button type="button" aria-pressed={inIt} onClick={() => itemId && toast(toggle(c.id, itemId) ? `Added to “${c.name}”` : `Removed from “${c.name}”`, 'success')}
                className="flex w-full items-center gap-3 rounded-btn px-2 py-2 text-left hover:bg-line/[0.07]">
                <CollectionMosaic collection={c} className="w-10 !rounded-md" />
                <span className="min-w-0 flex-1">
                  <span className="block truncate text-[0.8125rem] font-medium">{c.name}</span>
                  <span className="block text-xs text-muted">{c.itemIds.length} item{c.itemIds.length === 1 ? '' : 's'}{c.kind === 'shelf' ? ' · Book shelf' : ''}</span>
                </span>
                <span className={cn('flex h-6 w-6 items-center justify-center rounded-full border', inIt ? 'border-accent bg-accent text-accent-fg' : 'border-line/20 text-transparent')}>
                  {inIt ? <Check className="h-3.5 w-3.5" /> : <FolderPlus className="h-3.5 w-3.5" />}
                </span>
              </button>
            </li>
          );
        })}
      </ul>
    </Modal>
  );
}
