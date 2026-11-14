import { Search, SearchX } from 'lucide-react';
import { useEffect, useState } from 'react';
import { GAME_SORTS, listGames, searchAllGames, type GameSort } from '../adapters/games';
import { registerPreviews } from '../store/catalog';
import type { MediaItem } from '../types';
import { MediaGrid } from './media';
import { Button, Callout, EmptyState, Select, Spinner } from './ui';

/*
 * Every game the open catalogues expose.
 *   Browse  the Steam catalogue, paged and sortable, via CheapShark
 *   Search  any game in Wikidata, on every platform, plus the Steam catalogue
 * Opening a title loads its developers, genres, platforms, summary and where to buy.
 */
export function AllGamesBrowser() {
  const [sort, setSort] = useState<GameSort>('Reviews');
  const [query, setQuery] = useState('');
  const [q, setQ] = useState('');
  const [items, setItems] = useState<MediaItem[]>([]);
  const [page, setPage] = useState(0);
  const [pages, setPages] = useState(1);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [nonce, setNonce] = useState(0);

  useEffect(() => { const t = setTimeout(() => setQ(query.trim()), 350); return () => clearTimeout(t); }, [query]);

  useEffect(() => {
    let off = false;
    setLoading(true); setError(null); setItems([]); setPage(0);
    const job: Promise<{ items: MediaItem[]; pages: number }> = q
      ? Promise.all([
          listGames({ page: 0, sort, title: q }).then((r) => r.items),
          searchAllGames(q).catch(() => [] as MediaItem[]),
        ]).then(([a, b]) => {
          const seen = new Set<string>();
          return { items: [...a, ...b].filter((i) => !seen.has(i.id) && !!seen.add(i.id)), pages: 1 };
        })
      : listGames({ page: 0, sort });
    job
      .then((r) => { if (off) return; registerPreviews(r.items); setItems(r.items); setPages(r.pages); })
      .catch((e: Error) => { if (!off) setError(e.message); })
      .finally(() => { if (!off) setLoading(false); });
    return () => { off = true; };
  }, [sort, q, nonce]);

  const more = async () => {
    const next = page + 1;
    setLoading(true);
    try {
      const r = await listGames({ page: next, sort });
      registerPreviews(r.items);
      setItems((prev) => { const seen = new Set(prev.map((i) => i.id)); return [...prev, ...r.items.filter((i) => !seen.has(i.id))]; });
      setPage(next);
      setPages(r.pages);
    } catch (e) { setError((e as Error).message); }
    setLoading(false);
  };

  return (
    <div>
      <div className="mb-5 flex flex-wrap items-center gap-2">
        <div className="relative min-w-[12rem] flex-1 sm:max-w-sm">
          <Search className="pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-muted" />
          <input className="field h-9 py-0 pl-9" placeholder="Search every game, any platform" value={query} onChange={(e) => setQuery(e.target.value)} aria-label="Search every game" />
        </div>
        {!q && <Select label="Sort games" value={sort} onChange={setSort} options={GAME_SORTS} />}
        <span className="ml-auto text-xs tabular-nums text-muted">{items.length} shown{!q && pages > 1 ? ` · page ${page + 1} of ${pages}` : ''}</span>
      </div>
      <Callout tone="info" className="mb-5">
        Browsing lists Steam titles from the open CheapShark index. Searching also checks every game in Wikidata across all platforms. Open a title for developers, genres, platforms, a summary and where to buy.
      </Callout>
      {error && <Callout tone="error" className="mb-5" action={<Button size="sm" onClick={() => setNonce((n) => n + 1)}>Retry</Button>}>{error}</Callout>}
      {items.length > 0 && <MediaGrid items={items} />}
      {loading && <div className="flex justify-center py-10"><Spinner /></div>}
      {!loading && !items.length && !error && <EmptyState icon={SearchX} title="No games found" body={q ? `Nothing matched “${q}”.` : 'The catalogue returned no titles.'} />}
      {!loading && !q && page + 1 < pages && <div className="mt-8 flex justify-center"><Button size="lg" onClick={more}>Load more games</Button></div>}
    </div>
  );
}
