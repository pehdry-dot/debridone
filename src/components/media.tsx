import { BookImage, BookOpen, Check, ChevronLeft, ChevronRight, Disc3, Film, Gamepad2, Heart, Play, Search, SearchX, Tv, type LucideIcon } from 'lucide-react';
import { memo, useMemo, useRef, useState, type ReactNode } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { cn, fmtRuntime, yearOf } from '../lib/utils';
import { usePlayer } from '../store/player';
import { toast } from '../store/ui';
import { useStore } from '../store/useStore';
import type { MediaItem, MediaType } from '../types';
import { TYPE_LABEL } from '../types';
import { Cover } from './Cover';
import { EmptyState, IconButton, ProgressBar, Select } from './ui';

export const TYPE_ICON: Record<MediaType, LucideIcon> = { movie: Film, tv: Tv, album: Disc3, game: Gamepad2, manga: BookImage, book: BookOpen };
export const PRIMARY_LABEL: Record<MediaType, string> = { movie: 'Play', tv: 'Play', album: 'Play', game: 'Open', manga: 'Read', book: 'Read' };
export const SAVE_LABEL: Record<MediaType, string> = { movie: 'Watchlist', tv: 'Watchlist', album: 'Library', game: 'My Games', manga: 'Reading list', book: 'Reading list' };

/** The main thing you do with an item: play it, read it, or open its page. */
export function usePrimaryAction() {
  const navigate = useNavigate();
  return (item: MediaItem) => {
    // Remote music, games, manga and books have no in-app content: their page lists what your addons offer.
    if (item.remote && item.type !== 'movie' && item.type !== 'tv') { navigate(`/item/${item.id}`); return; }
    switch (item.type) {
      case 'movie': case 'tv': navigate(`/watch/${item.id}`); break;
      case 'album':
        if (item.album?.tracks.length) usePlayer.getState().play(item.album.tracks.map((t) => t.id));
        else toast('This album has no tracks yet.', 'warn');
        break;
      case 'manga': navigate(`/read/manga/${item.id}`); break;
      case 'book': navigate(`/read/book/${item.id}`); break;
      default: navigate(`/item/${item.id}`);
    }
  };
}

export function itemSubtitle(item: MediaItem): string {
  const year = yearOf(item.releaseDate);
  switch (item.type) {
    case 'movie': return [year, item.video ? fmtRuntime(item.video.runtime) : ''].filter(Boolean).join(' · ');
    case 'tv': { const n = item.tv?.seasons.length ?? 0; return [year, `${n} season${n === 1 ? '' : 's'}`].filter(Boolean).join(' · '); }
    case 'game': return [item.genres[0], item.creator].filter(Boolean).join(' · ') || year;
    default: return item.creator ?? year;
  }
}

export const MediaCard = memo(function MediaCard({ item, className, subtitle, showType }: { item: MediaItem; className?: string; subtitle?: string; showType?: boolean }) {
  const fav = useStore((s) => !!s.favorites[item.id]);
  const progress = useStore((s) => s.progress[item.id]?.percent);
  const done = useStore((s) => !!s.completed[item.id]);
  const toggleFavorite = useStore((s) => s.toggleFavorite);
  const act = usePrimaryAction();
  const Icon = TYPE_ICON[item.type];
  return (
    <div className={cn('group relative min-w-0', className)}>
      <div className="lift relative">
        <Link to={`/item/${item.id}`} className="block rounded-card" aria-label={`${item.title} · ${TYPE_LABEL[item.type]}`}>
          <Cover item={item} className={cn('w-full rounded-card shadow-lift ring-1 ring-inset ring-line/10', item.type === 'album' ? 'aspect-square' : 'aspect-[2/3]')} />
        </Link>
        {showType && (
          <span className="pointer-events-none absolute left-2 top-2 flex items-center gap-1 rounded-full bg-black/55 px-2 py-0.5 text-[0.625rem] font-semibold uppercase tracking-wider text-white backdrop-blur-sm">
            <Icon className="h-3 w-3" />{TYPE_LABEL[item.type]}
          </span>
        )}
        {done && !progress && <span className="pointer-events-none absolute left-2 bottom-2 flex h-5 w-5 items-center justify-center rounded-full bg-emerald-500 text-white"><Check className="h-3 w-3" /></span>}
        <button type="button" aria-label={fav ? `Remove ${item.title} from favorites` : `Add ${item.title} to favorites`} aria-pressed={fav}
          onClick={() => toast(toggleFavorite(item.id) ? 'Added to Favorites' : 'Removed from Favorites', 'success')}
          className={cn('absolute right-2 top-2 flex h-8 w-8 items-center justify-center rounded-full bg-black/55 text-white backdrop-blur-sm transition-all hover:scale-110 focus-visible:opacity-100',
            fav ? 'opacity-100' : 'opacity-0 group-hover:opacity-100')}>
          <Heart className={cn('h-4 w-4', fav && 'fill-current text-rose-400')} />
        </button>
        {item.type !== 'game' && (
          <button type="button" aria-label={`${PRIMARY_LABEL[item.type]} ${item.title}`} onClick={() => act(item)}
            className="absolute bottom-2.5 right-2.5 flex h-10 w-10 translate-y-1 items-center justify-center rounded-full bg-accent text-accent-fg opacity-0 shadow-lift transition-all hover:scale-110 focus-visible:translate-y-0 focus-visible:opacity-100 group-hover:translate-y-0 group-hover:opacity-100">
            {item.type === 'manga' || item.type === 'book' ? <BookOpen className="h-[1.05rem] w-[1.05rem]" /> : <Play className="ml-0.5 h-[1.05rem] w-[1.05rem] fill-current" />}
          </button>
        )}
        {!!progress && progress < 97 && <ProgressBar value={progress} className="pointer-events-none absolute inset-x-2.5 bottom-2 w-auto bg-black/50" />}
      </div>
      <Link to={`/item/${item.id}`} tabIndex={-1} aria-hidden className="mt-2.5 block">
        <p className="truncate text-[0.8125rem] font-medium leading-tight">{item.title}</p>
        <p className="mt-0.5 truncate text-xs text-muted">{subtitle ?? itemSubtitle(item)}</p>
      </Link>
    </div>
  );
});

/** Horizontally scrolling row with a heading. */
export function Shelf({ title, subtitle, to, action, children, className }: { title: string; subtitle?: string; to?: string; action?: ReactNode; children: ReactNode; className?: string }) {
  const ref = useRef<HTMLDivElement>(null);
  const scroll = (dir: number) => ref.current?.scrollBy({ left: dir * ref.current.clientWidth * 0.85, behavior: 'smooth' });
  return (
    <section className={cn('mb-9', className)}>
      <div className="mb-3 flex items-end justify-between gap-4">
        <div className="min-w-0">
          <h2 className="truncate text-lg font-semibold tracking-tight">{title}</h2>
          {subtitle && <p className="truncate text-xs text-muted">{subtitle}</p>}
        </div>
        <div className="flex shrink-0 items-center gap-1">
          {action}
          {to && <Link to={to} className="mr-1 rounded-full px-2.5 py-1 text-xs font-medium text-muted transition-colors hover:bg-line/10 hover:text-fg">See all</Link>}
          <IconButton label={`Scroll ${title} left`} icon={ChevronLeft} size="sm" tone="solid" onClick={() => scroll(-1)} className="hidden md:inline-flex" />
          <IconButton label={`Scroll ${title} right`} icon={ChevronRight} size="sm" tone="solid" onClick={() => scroll(1)} className="hidden md:inline-flex" />
        </div>
      </div>
      <div ref={ref} className="bleed flex snap-x gap-4 overflow-x-auto pb-3 pt-1 no-scrollbar">{children}</div>
    </section>
  );
}

export function ItemShelf({ title, subtitle, to, items, showType, empty }: { title: string; subtitle?: string; to?: string; items: MediaItem[]; showType?: boolean; empty?: ReactNode }) {
  if (!items.length && !empty) return null;
  return (
    <Shelf title={title} subtitle={subtitle} to={to}>
      {items.length ? items.map((it) => <MediaCard key={it.id} item={it} showType={showType} className="shelf-card snap-start" />) : <div className="w-full">{empty}</div>}
    </Shelf>
  );
}

export function MediaGrid({ items, showType, className }: { items: MediaItem[]; showType?: boolean; className?: string }) {
  return (
    <div className={cn('grid grid-cols-[repeat(auto-fill,minmax(8.25rem,1fr))] gap-x-4 gap-y-6 sm:grid-cols-[repeat(auto-fill,minmax(10rem,1fr))]', className)}>
      {items.map((it) => <MediaCard key={it.id} item={it} showType={showType} />)}
    </div>
  );
}

export type SortKey = 'popular' | 'recent' | 'title' | 'year' | 'rating';
const SORTS: { value: SortKey; label: string }[] = [
  { value: 'popular', label: 'Most popular' }, { value: 'recent', label: 'Recently added' }, { value: 'title', label: 'Title A–Z' },
  { value: 'year', label: 'Newest release' }, { value: 'rating', label: 'Highest rated' },
];
export function sortItems(items: MediaItem[], sort: SortKey): MediaItem[] {
  const out = items.slice();
  switch (sort) {
    case 'popular': return out.sort((a, b) => b.popularity - a.popularity);
    case 'recent': return out.sort((a, b) => b.addedAt - a.addedAt);
    case 'title': return out.sort((a, b) => a.title.localeCompare(b.title));
    case 'year': return out.sort((a, b) => (b.releaseDate ?? '').localeCompare(a.releaseDate ?? ''));
    case 'rating': return out.sort((a, b) => (b.rating ?? 0) - (a.rating ?? 0));
  }
}

/** Search + genre + sort over a list of items, with a responsive grid. Used by every module's catalogue view. */
export function CatalogBrowser({ items, showType, extra, emptyAction, defaultSort = 'popular' }: { items: MediaItem[]; showType?: boolean; extra?: ReactNode; emptyAction?: ReactNode; defaultSort?: SortKey }) {
  const [q, setQ] = useState('');
  const [genre, setGenre] = useState('all');
  const [sort, setSort] = useState<SortKey>(defaultSort);
  const genres = useMemo(() => [...new Set(items.flatMap((i) => i.genres))].sort(), [items]);
  const list = useMemo(() => {
    const needle = q.trim().toLowerCase();
    return sortItems(items.filter((i) =>
      (genre === 'all' || i.genres.includes(genre)) &&
      (!needle || [i.title, i.altTitle, i.creator, i.publisher, ...i.tags, ...i.genres].some((v) => v?.toLowerCase().includes(needle)))), sort);
  }, [items, q, genre, sort]);
  return (
    <div>
      <div className="mb-5 flex flex-wrap items-center gap-2">
        <div className="relative min-w-[11rem] flex-1 sm:max-w-xs">
          <Search className="pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-muted" />
          <input className="field h-9 py-0 pl-9" placeholder="Filter by title, creator or tag" value={q} onChange={(e) => setQ(e.target.value)} aria-label="Filter this list" />
        </div>
        <Select label="Genre" value={genres.includes(genre) ? genre : 'all'} onChange={setGenre} options={[{ value: 'all', label: 'All genres' }, ...genres.map((g) => ({ value: g, label: g }))]} />
        <Select label="Sort by" value={sort} onChange={setSort} options={SORTS} />
        {extra}
        <span className="ml-auto text-xs tabular-nums text-muted">{list.length} of {items.length}</span>
      </div>
      {list.length ? <MediaGrid items={list} showType={showType} /> : (
        <EmptyState icon={SearchX} title={items.length ? 'Nothing matches those filters' : 'Nothing here yet'}
          body={items.length ? 'Try a different search term or genre.' : undefined} action={emptyAction} />
      )}
    </div>
  );
}

/** Small rounded artwork thumbnail. */
export function Thumb({ item, className }: { item: MediaItem; className?: string }) {
  return <Cover item={item} text={false} className={cn('shrink-0 rounded-[calc(var(--radius)*0.45)] ring-1 ring-inset ring-line/10', item.type === 'album' ? 'aspect-square' : 'aspect-[2/3]', className)} />;
}
