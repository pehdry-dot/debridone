import {
  BookOpen, Bookmark, BookmarkCheck, Check, CheckCircle2, Clock, Download, ExternalLink, FileJson, FolderPlus, Heart, HardDrive, Monitor, Pencil, Play, RotateCcw,
  SearchX, Shuffle, Star, StickyNote, Timer, Trash2, Undo2, Puzzle,
} from 'lucide-react';
import { useEffect, useMemo, useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { Backdrop, Cover } from '../components/Cover';
import { ItemShelf, PRIMARY_LABEL, SAVE_LABEL, TYPE_ICON, usePrimaryAction } from '../components/media';
import { TrackList } from '../components/TrackList';
import { Badge, Button, Callout, Card, EmptyState, IconButton, Menu, ProgressBar, Segmented, Select, Spinner, Switch } from '../components/ui';
import { bookPlainText, buildEpub, loadBook, type BookChapter } from '../lib/books';
import { useDateFormat } from '../lib/i18n';
import { deleteUserItem } from '../lib/importers';
import { cn, fmtRuntime, fmtTime, saveJson, timeAgo, yearOf } from '../lib/utils';
import { originalItem, useCatalog, useItem } from '../store/catalog';
import { startDownload } from '../store/downloads';
import { usePlayer } from '../store/player';
import { confirmDialog, openCollector, openEditor, toast } from '../store/ui';
import { useStore } from '../store/useStore';
import type { Credit, MediaItem, MediaType, StreamSource } from '../types';
import { TYPE_LABEL } from '../types';
import { progressLabel } from './Home';
import { ensureRemote, selectStream, StreamOptions } from '../components/addons';
import { isRemoteId } from '../adapters/stremio';
import { gameDeals, type GameDeal } from '../adapters/games';

export const CREATOR_LABEL: Record<MediaType, string> = { movie: 'Director', tv: 'Creator', album: 'Artist', game: 'Developer', manga: 'Author / studio', book: 'Author' };
export const PUBLISHER_LABEL: Record<MediaType, string> = { movie: 'Studio', tv: 'Studio', album: 'Label', game: 'Publisher', manga: 'Publisher', book: 'Publisher' };

/** Real download when the host allows it; otherwise hand the link to the browser and say so. */
export function downloadSource(item: MediaItem, source: StreamSource, label?: string) {
  if (source.url.startsWith('idb:')) { toast('This file is already stored on your device.', 'info'); return; }
  const ext = source.url.split('?')[0].split('.').pop() ?? 'mp4';
  if (source.cors) {
    void startDownload({ title: label ?? item.title, fileName: `${(label ?? item.title).replace(/[\\/:*?"<>|]/g, '')}.${ext}`, source: source.provider, kind: 'fetch', url: source.url, size: source.sizeMB ? source.sizeMB * 1e6 : undefined, itemId: item.id });
    toast('Added to Downloads', 'success');
  } else {
    window.open(source.url, '_blank', 'noopener,noreferrer');
    toast(`Opened at ${source.provider}. That host doesn’t allow in-app transfers, so your browser handles this download.`, 'info');
  }
}

function Credits({ cast, crew }: { cast: Credit[]; crew: Credit[] }) {
  if (!cast.length && !crew.length) return null;
  const Person = ({ c }: { c: Credit }) => (
    <div className="flex items-center gap-3 rounded-btn bg-line/[0.04] p-2.5">
      <span className="flex h-9 w-9 shrink-0 items-center justify-center rounded-full bg-line/10 text-xs font-semibold uppercase text-muted">{c.name.split(' ').map((w) => w[0]).slice(0, 2).join('')}</span>
      <span className="min-w-0"><span className="block truncate text-[0.8125rem] font-medium">{c.name}</span><span className="block truncate text-xs text-muted">{c.role}</span></span>
    </div>
  );
  return (
    <Card title="Cast & crew">
      {cast.length > 0 && <><p className="label mb-2">Cast</p><div className="mb-4 grid gap-2 sm:grid-cols-2">{cast.map((c) => <Person key={c.name + c.role} c={c} />)}</div></>}
      {crew.length > 0 && <><p className="label mb-2">Crew</p><div className="grid gap-2 sm:grid-cols-2">{crew.map((c) => <Person key={c.name + c.role} c={c} />)}</div></>}
    </Card>
  );
}

function SourceRows({ item, sources, onPlay }: { item: MediaItem; sources: StreamSource[]; onPlay: (s: StreamSource) => void }) {
  if (!sources.length) return <p className="text-[0.8125rem] text-muted">No source is attached to this entry yet.</p>;
  return (
    <ul className="divide-y divide-line/[0.06]">
      {sources.map((s) => (
        <li key={s.id} className="flex flex-wrap items-center gap-3 py-2.5">
          <div className="min-w-0 flex-1 basis-48">
            <p className="truncate text-[0.8125rem] font-medium">{s.label}</p>
            <p className="truncate text-xs text-muted">{s.provider} · {s.quality} · {s.license}{s.sizeMB ? ` · ${s.sizeMB} MB` : ''}</p>
          </div>
          <Button size="sm" icon={Play} onClick={() => onPlay(s)}>Play</Button>
          <Button size="sm" variant="ghost" icon={s.cors ? Download : ExternalLink} onClick={() => downloadSource(item, s)}>{s.cors ? 'Download' : 'Get file'}</Button>
        </li>
      ))}
    </ul>
  );
}

function VideoSection({ item }: { item: MediaItem }) {
  const navigate = useNavigate();
  const progress = useStore((s) => s.progress[item.id]);
  const [season, setSeason] = useState(String(item.tv?.seasons[0]?.number ?? 1));
  if (item.remote) {
    return (
      <>
        {item.type === 'movie'
          ? <Card title="Streams & downloads" description="Every option your installed addons offer for this film. Direct links play in the player or download through your browser."><StreamOptions item={item} /></Card>
          : <EpisodeStreams item={item} />}
        {(item.video || item.tv) && <Credits cast={item.video?.cast ?? item.tv?.cast ?? []} crew={item.video?.crew ?? item.tv?.crew ?? []} />}
      </>
    );
  }
  if (item.type === 'movie' && item.video) {
    return (
      <>
        <Card title="Sources" description="Pick where to stream from. Debrid files from connected providers appear inside the player.">
          <SourceRows item={item} sources={item.video.sources} onPlay={(s) => navigate(`/watch/${item.id}?src=${s.id}`)} />
        </Card>
        <Credits cast={item.video.cast} crew={item.video.crew} />
      </>
    );
  }
  if (!item.tv) return null;
  const current = item.tv.seasons.find((s) => String(s.number) === season) ?? item.tv.seasons[0];
  return (
    <>
      <Card title="Episodes" action={item.tv.seasons.length > 1 ? <Segmented size="sm" value={season} onChange={setSeason} options={item.tv.seasons.map((s) => ({ value: String(s.number), label: `Season ${s.number}` }))} /> : <Badge>Season {current.number}</Badge>}>
        <ul className="divide-y divide-line/[0.06]">
          {current.episodes.map((e) => {
            const unit = progress?.units?.[e.id];
            const isCurrent = progress?.unit === e.id && !unit?.done;
            return (
              <li key={e.id} className="group flex items-center gap-3 py-3">
                <button type="button" onClick={() => navigate(`/watch/${item.id}?ep=${e.id}`)} aria-label={`Play episode ${e.number}: ${e.title}`}
                  className="relative flex h-10 w-10 shrink-0 items-center justify-center rounded-full bg-line/[0.07] text-sm font-semibold tabular-nums text-muted transition-colors hover:bg-accent hover:text-accent-fg">
                  <span className="group-hover:hidden">{e.number}</span><Play className="hidden h-4 w-4 fill-current group-hover:block" />
                </button>
                <div className="min-w-0 flex-1">
                  <p className="flex items-center gap-2 text-[0.8125rem] font-medium"><span className="truncate">{e.title}</span>{unit?.done && <CheckCircle2 className="h-3.5 w-3.5 shrink-0 text-emerald-400" aria-label="Watched" />}{isCurrent && <Badge tone="accent">Up next</Badge>}</p>
                  <p className="mt-0.5 line-clamp-2 text-xs text-muted">{e.description}</p>
                </div>
                <span className="shrink-0 text-xs tabular-nums text-muted">{fmtRuntime(e.runtime)}</span>
                <IconButton label={`Download episode ${e.number}`} icon={e.sources[0]?.cors ? Download : ExternalLink} size="sm" onClick={() => e.sources[0] && downloadSource(item, e.sources[0], `${item.title} S${current.number}E${e.number} ${e.title}`)} />
              </li>
            );
          })}
        </ul>
      </Card>
      <Credits cast={item.tv.cast} crew={item.tv.crew} />
    </>
  );
}

function GameSection({ item }: { item: MediaItem }) {
  const state = useStore((s) => s.gameState[item.id]);
  const setGameState = useStore((s) => s.setGameState);
  const logPlaySession = useStore((s) => s.logPlaySession);
  const fmt = useDateFormat();
  const [minutes, setMinutes] = useState('45');
  const [location, setLocation] = useState(state?.location ?? '');
  const [version, setVersion] = useState(state?.version ?? '');
  const g = item.game!;
  const log = (m: number) => { if (m > 0) { logPlaySession(item.id, m); useStore.getState().logHistory({ id: item.id, kind: 'play', detail: `${fmtRuntime(m)} session` }); toast(`Logged ${fmtRuntime(m)} of playtime`, 'success'); } };
  return (
    <>
      <Card title="Get the game" description="DebridOne never hosts game files. Downloads come from each project’s official website.">
        <div className="flex flex-wrap gap-2">
          {g.website && <Button variant="primary" icon={ExternalLink} onClick={() => window.open(g.website, '_blank', 'noopener,noreferrer')}>Official site & downloads</Button>}
          <Button icon={Monitor} onClick={() => { void startDownload({ title: `${item.title} — desktop install (demo)`, fileName: `${item.title} installer`, source: 'Open-source games index', kind: 'simulated', size: (g.sizeMB ?? 500) * 1e6, itemId: item.id }); toast('Queued a simulated install — see Downloads', 'info'); }}>Queue simulated install</Button>
        </div>
        <Callout tone="desktop" className="mt-4" title="Installing and launching needs the desktop app">
          A browser cannot install or start native games. The simulated install only demonstrates the download manager; a future Tauri build would hand this job to a native downloader and launcher.
        </Callout>
        <div className="mt-4 flex flex-wrap gap-1.5">{g.platforms.map((p) => <Badge key={p} tone="outline">{p}</Badge>)}{g.license && <Badge>{g.license}</Badge>}{g.sizeMB && <Badge>≈ {g.sizeMB >= 1000 ? `${(g.sizeMB / 1000).toFixed(1)} GB` : `${g.sizeMB} MB`}</Badge>}</div>
      </Card>

      <Card title="Installation record" description="A note-to-self about where this game lives. It does not detect or change anything on disk.">
        <div className="flex items-center justify-between gap-4 border-b border-line/[0.06] pb-3">
          <span className="flex items-center gap-2.5 text-sm"><HardDrive className="h-4 w-4 text-muted" />{state?.installed ? 'Marked as installed' : 'Not installed'}</span>
          <Switch label="Mark as installed" checked={!!state?.installed} onChange={(v) => setGameState(item.id, { installed: v })} />
        </div>
        <div className="mt-3 grid gap-3 sm:grid-cols-2">
          <label className="block"><span className="mb-1.5 block text-xs font-medium text-muted">Install location (label only)</span>
            <input className="field" placeholder="e.g. ~/Games/…" value={location} onChange={(e) => setLocation(e.target.value)} onBlur={() => setGameState(item.id, { location: location.trim() || undefined })} /></label>
          <label className="block"><span className="mb-1.5 block text-xs font-medium text-muted">Installed version</span>
            <input className="field" placeholder="e.g. 1.4.2" value={version} onChange={(e) => setVersion(e.target.value)} onBlur={() => setGameState(item.id, { version: version.trim() || undefined })} /></label>
        </div>
      </Card>

      <Card title="Playtime" description="Tracked manually in the web prototype." action={<span className="flex items-center gap-2 text-sm font-semibold"><Timer className="h-4 w-4 text-accent" />{fmtRuntime(state?.playMinutes ?? 0)}</span>}>
        <div className="flex flex-wrap items-center gap-2">
          {[15, 30, 60, 120].map((m) => <Button key={m} size="sm" onClick={() => log(m)}>+{fmtRuntime(m)}</Button>)}
          <form className="flex items-center gap-2" onSubmit={(e) => { e.preventDefault(); log(Math.round(Number(minutes))); }}>
            <input className="field h-8 w-20 py-0" type="number" min={1} max={1440} value={minutes} onChange={(e) => setMinutes(e.target.value)} aria-label="Minutes played" />
            <Button size="sm" variant="primary" type="submit" disabled={!(Number(minutes) > 0)}>Log minutes</Button>
          </form>
          {!!state?.playMinutes && <Button size="sm" variant="ghost" icon={RotateCcw} onClick={() => setGameState(item.id, { playMinutes: 0, sessions: [], lastPlayed: undefined })}>Reset</Button>}
        </div>
        {!!state?.sessions.length && (
          <ul className="mt-4 space-y-1 text-xs text-muted">
            {state.sessions.slice(0, 5).map((s) => <li key={s.ts} className="flex justify-between"><span>{fmt(s.ts)}</span><span className="tabular-nums">{fmtRuntime(s.minutes)}</span></li>)}
          </ul>
        )}
      </Card>
    </>
  );
}

function MangaSection({ item }: { item: MediaItem }) {
  const navigate = useNavigate();
  const progress = useStore((s) => s.progress[item.id]);
  const m = item.manga!;
  return (
    <Card title="Chapters" action={<div className="flex gap-1.5"><Badge>{m.status}</Badge><Badge tone="outline">{m.direction === 'webtoon' ? 'Vertical webtoon' : m.direction === 'rtl' ? 'Reads right-to-left' : 'Reads left-to-right'}</Badge></div>}>
      <ul className="divide-y divide-line/[0.06]">
        {m.chapters.map((c) => {
          const done = progress?.units?.[c.id]?.done;
          const current = progress?.unit === c.id && !done;
          return (
            <li key={c.id} className="flex items-center gap-3 py-2.5">
              <span className="flex h-9 w-9 shrink-0 items-center justify-center rounded-full bg-line/[0.07] text-xs font-semibold tabular-nums text-muted">{c.number}</span>
              <div className="min-w-0 flex-1">
                <p className="flex items-center gap-2 text-[0.8125rem] font-medium"><span className="truncate">{c.title}</span>{done && <CheckCircle2 className="h-3.5 w-3.5 shrink-0 text-emerald-400" aria-label="Read" />}</p>
                <p className="text-xs text-muted">{c.pages} pages{current ? ` · on page ${(progress?.position ?? 0) + 1}` : ''}{c.source === 'generated' ? ' · placeholder artwork' : ' · imported'}</p>
              </div>
              <Button size="sm" variant={current ? 'primary' : 'secondary'} icon={BookOpen} onClick={() => navigate(`/read/manga/${item.id}?ch=${c.id}`)}>{current ? 'Continue' : done ? 'Reread' : 'Read'}</Button>
            </li>
          );
        })}
      </ul>
    </Card>
  );
}

function BookSection({ item }: { item: MediaItem }) {
  const navigate = useNavigate();
  const bookmarks = useStore((s) => s.bookmarks[item.id]) ?? [];
  const notes = useStore((s) => s.bookNotes[item.id]) ?? [];
  const removeBookmark = useStore((s) => s.removeBookmark);
  const removeNote = useStore((s) => s.removeNote);
  const [chapters, setChapters] = useState<BookChapter[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const b = item.book!;
  useEffect(() => {
    let cancelled = false;
    setChapters(null); setError(null);
    loadBook(item).then((c) => { if (!cancelled) setChapters(c.kind === 'html' ? c.chapters : []); }).catch((e) => { if (!cancelled) setError((e as Error).message); });
    return () => { cancelled = true; };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [item.id]);
  const read = (chapter: number, fraction = 0) => navigate(`/read/book/${item.id}?ch=${chapter}&at=${fraction.toFixed(4)}`);
  const safeName = item.title.replace(/[\\/:*?"<>|]/g, '');
  return (
    <>
      <Card title="Contents" action={<Badge tone="outline">{b.format === 'text' ? (b.contentKey.startsWith('idb:') ? 'Plain text' : 'Bundled text') : b.format.toUpperCase()}</Badge>}>
        {error ? <Callout tone="error">{error}</Callout> : !chapters ? <Spinner /> : b.format === 'pdf' ? (
          <p className="text-[0.8125rem] text-muted">PDFs open in the browser’s built-in viewer inside the reader.</p>
        ) : (
          <ol className="divide-y divide-line/[0.06]">
            {chapters.map((c, i) => (
              <li key={i}><button type="button" onClick={() => read(i)} className="flex w-full items-center justify-between gap-3 py-2.5 text-left text-[0.8125rem] hover:text-accent"><span className="truncate">{c.title}</span><BookOpen className="h-3.5 w-3.5 shrink-0 text-muted" /></button></li>
            ))}
          </ol>
        )}
        {b.excerpt && (
          <Callout tone="info" className="mt-4" title="This is an excerpt"
            action={b.gutenberg ? <Button size="sm" icon={ExternalLink} onClick={() => window.open(`https://www.gutenberg.org/ebooks/${b.gutenberg}`, '_blank', 'noopener,noreferrer')}>Full text</Button> : undefined}>
            The complete public-domain text is free at Project Gutenberg. Download the EPUB or plain-text file there, then use Books → Import to read it here.
          </Callout>
        )}
        {chapters && chapters.length > 0 && (
          <div className="mt-4 flex flex-wrap gap-2">
            <Button size="sm" icon={Download} onClick={() => { void startDownload({ title: `${item.title} (EPUB)`, fileName: `${safeName}.epub`, source: 'Generated in browser', kind: 'generated', itemId: item.id, generate: () => buildEpub(item.title, item.creator ?? 'Unknown', chapters) }); toast('Building EPUB…'); }}>Download as EPUB</Button>
            <Button size="sm" variant="ghost" icon={Download} onClick={() => { void startDownload({ title: `${item.title} (text)`, fileName: `${safeName}.txt`, source: 'Generated in browser', kind: 'generated', itemId: item.id, generate: async () => new Blob([bookPlainText(chapters)], { type: 'text/plain' }) }); }}>Download as text</Button>
          </div>
        )}
      </Card>
      <Card title="Bookmarks & notes" description="Add these from inside the reader.">
        {!bookmarks.length && !notes.length && <p className="text-[0.8125rem] text-muted">No bookmarks or notes yet.</p>}
        <ul className="space-y-1.5">
          {bookmarks.map((bm) => (
            <li key={bm.id} className="flex items-center gap-2.5 rounded-btn bg-line/[0.04] px-3 py-2">
              <Bookmark className="h-4 w-4 shrink-0 text-accent" />
              <button type="button" onClick={() => read(bm.chapter, bm.fraction)} className="min-w-0 flex-1 text-left"><span className="block truncate text-[0.8125rem] font-medium hover:text-accent">{bm.label}</span><span className="text-xs text-muted">{chapters?.[bm.chapter]?.title ?? `Chapter ${bm.chapter + 1}`} · {timeAgo(bm.ts)}</span></button>
              <IconButton label="Delete bookmark" icon={Trash2} size="sm" onClick={() => removeBookmark(item.id, bm.id)} />
            </li>
          ))}
          {notes.map((n) => (
            <li key={n.id} className="flex items-start gap-2.5 rounded-btn bg-line/[0.04] px-3 py-2">
              <StickyNote className="mt-0.5 h-4 w-4 shrink-0 text-amber-400" />
              <div className="min-w-0 flex-1"><p className="whitespace-pre-wrap text-[0.8125rem]">{n.text}</p><p className="mt-0.5 text-xs text-muted">{chapters?.[n.chapter]?.title ?? `Chapter ${n.chapter + 1}`} · {timeAgo(n.ts)}</p></div>
              <IconButton label="Delete note" icon={Trash2} size="sm" onClick={() => removeNote(item.id, n.id)} />
            </li>
          ))}
        </ul>
      </Card>
    </>
  );
}

function PersonalNotes({ item }: { item: MediaItem }) {
  const [text, setText] = useState(item.notes ?? '');
  useEffect(() => setText(item.notes ?? ''), [item.id, item.notes]);
  const save = () => {
    const s = useStore.getState();
    const next = text.trim();
    if (next === (item.notes ?? '')) return;
    if (next) s.setOverride(item.id, { notes: next }); else s.clearOverride(item.id, ['notes']);
    toast('Notes saved', 'success');
  };
  return (
    <Card title="Personal notes">
      <textarea className="field min-h-[5.5rem] resize-y" placeholder="Only you can see this. Saved when you click away." value={text} onChange={(e) => setText(e.target.value)} onBlur={save} aria-label="Personal notes" />
    </Card>
  );
}

/** Current prices at licensed stores for a live game (CheapShark index; links go to the store). */
function DealsCard({ sid }: { sid: string }) {
  const [deals, setDeals] = useState<GameDeal[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  useEffect(() => {
    let off = false;
    setDeals(null); setError(null);
    gameDeals(sid).then((d) => { if (!off) setDeals(d); }).catch((e) => { if (!off) setError((e as Error).message); });
    return () => { off = true; };
  }, [sid]);
  return (
    <Card title="Where to buy" description="Current prices at licensed stores, from the open CheapShark index. Links open the store.">
      {error ? <Callout tone="error">{error}</Callout> : !deals ? <Spinner /> : !deals.length ? <p className="text-[0.8125rem] text-muted">No store is listing this game right now.</p> : (
        <ul className="divide-y divide-line/[0.06]">
          {deals.slice(0, 12).map((d) => (
            <li key={d.store + d.url} className="flex flex-wrap items-center gap-3 py-2.5">
              <span className="min-w-0 flex-1 basis-32 truncate text-[0.8125rem] font-medium">{d.store}</span>
              {d.savings > 0 && <Badge tone="success">−{d.savings}%</Badge>}
              <span className="text-sm font-semibold tabular-nums">${d.price.toFixed(2)}</span>
              {d.retail > d.price && <span className="text-xs tabular-nums text-muted line-through">${d.retail.toFixed(2)}</span>}
              <Button size="sm" icon={ExternalLink} onClick={() => window.open(d.url, '_blank', 'noopener,noreferrer')}>View</Button>
            </li>
          ))}
        </ul>
      )}
    </Card>
  );
}

/** Streams and downloads for a series, looked up one episode at a time. */
function EpisodeStreams({ item }: { item: MediaItem }) {
  const episodes = item.tv?.seasons.flatMap((s) => s.episodes.map((e) => ({ e, season: s.number }))) ?? [];
  const [pick, setPick] = useState(episodes[0]?.e.id ?? '');
  const chosen = episodes.find((x) => x.e.id === pick);
  return (
    <Card title="Streams & downloads by episode" description="Your addons are asked about each episode separately.">
      {!episodes.length ? <p className="text-[0.8125rem] text-muted">This series has no episode list yet.</p> : (
        <>
          <Select label="Episode" value={pick} onChange={setPick} options={episodes.map((x) => ({ value: x.e.id, label: `S${x.season} · E${x.e.number} — ${x.e.title}` }))} className="mb-4 w-full max-w-md" />
          <StreamOptions item={item} videoId={pick} label={chosen ? `S${chosen.season}E${chosen.e.number}` : undefined} />
        </>
      )}
    </Card>
  );
}

export default function ItemDetailPage() {
  const { id = '' } = useParams();
  const item = useItem(id);
  const navigate = useNavigate();
  const catalog = useCatalog();
  const act = usePrimaryAction();
  const saved = useStore((s) => !!s.saved[id]);
  const favorite = useStore((s) => !!s.favorites[id]);
  const completed = useStore((s) => !!s.completed[id]);
  const progress = useStore((s) => s.progress[id]);
  const edited = useStore((s) => !!s.overrides[id] || !!s.imported[id]);
  const collections = useStore((s) => s.collections);
  const gameInstalled = useStore((s) => !!s.gameState[id]?.installed);
  const store = useStore.getState();

  useEffect(() => { if (item) useStore.getState().touch(item.id); }, [item?.id]); // eslint-disable-line react-hooks/exhaustive-deps
  const [remoteErr, setRemoteErr] = useState<string | null>(null);
  useEffect(() => { if (item?.remote || (!item && isRemoteId(id))) { setRemoteErr(null); ensureRemote(id).catch((e) => setRemoteErr((e as Error).message)); } }, [id, item?.remote?.partial]); // eslint-disable-line react-hooks/exhaustive-deps

  const related = useMemo(() => {
    if (!item) return [];
    return catalog.filter((i) => i.id !== item.id && i.type === item.type)
      .map((i) => ({ i, n: i.genres.filter((g) => item.genres.includes(g)).length + (i.creator === item.creator ? 2 : 0) }))
      .filter((x) => x.n > 0).sort((a, b) => b.n - a.n || b.i.popularity - a.i.popularity).slice(0, 12).map((x) => x.i);
  }, [catalog, item]);

  if (!item && isRemoteId(id)) {
    return <div className="page">{remoteErr ? <EmptyState icon={SearchX} title="Couldn’t load this title" body={remoteErr} action={<Button variant="primary" onClick={() => navigate('/discover')}>Browse Discover</Button>} /> : <div className="flex justify-center py-24"><Spinner /></div>}</div>;
  }
  if (!item) {
    return <div className="page"><EmptyState icon={SearchX} title="That item isn’t in your catalogue" body="It may have been removed, or the link is wrong." action={<Button variant="primary" onClick={() => navigate('/discover')}>Browse Discover</Button>} /></div>;
  }

  const Icon = TYPE_ICON[item.type];
  const year = yearOf(item.releaseDate);
  const tracks = item.album?.tracks ?? [];
  const inProgress = !!progress && progress.percent > 0 && progress.percent < 97;
  const facts: [string, string | undefined][] = [
    [CREATOR_LABEL[item.type], item.creator], [PUBLISHER_LABEL[item.type], item.publisher], ['Released', item.releaseDate], ['Language', item.language],
    ['Licence', item.license], ['Collection', item.collection],
    ...Object.entries(item.custom ?? {}).filter(([, v]) => v).map(([k, v]) => [k, v] as [string, string]),
  ];
  const metaLine = [
    year,
    item.type === 'movie' && item.video ? fmtRuntime(item.video.runtime) : '',
    item.type === 'tv' ? `${item.tv?.seasons.length} season${item.tv?.seasons.length === 1 ? '' : 's'} · ${item.tv?.seasons.reduce((n, s) => n + s.episodes.length, 0)} episodes` : '',
    item.type === 'album' ? `${tracks.length} song${tracks.length === 1 ? '' : 's'} · ${fmtTime(tracks.reduce((n, t) => n + t.duration, 0))}` : '',
    item.type === 'manga' ? `${item.manga?.chapters.length} chapters` : '',
    item.type === 'game' ? item.game?.platforms.slice(0, 3).join(', ') : '',
  ].filter(Boolean).join('  ·  ');

  const remove = async () => {
    if (!(await confirmDialog({ title: `Remove “${item.title}”?`, body: 'This deletes the entry, its progress and the imported file stored in this browser. It cannot be undone.', confirmLabel: 'Remove', danger: true }))) return;
    await deleteUserItem(item);
    toast('Removed from your library', 'success');
    navigate(-1);
  };

  return (
    <div>
      <div className="relative isolate overflow-clip">
        <Backdrop item={item} className="absolute inset-0 -z-10 scale-110 opacity-70 blur-2xl" />
        <div className="absolute inset-0 -z-10 bg-gradient-to-b from-bg/40 via-bg/75 to-bg" />
        <div className="page !pb-6 flex flex-col gap-6 sm:flex-row sm:items-end sm:gap-8">
          <Cover item={item} className={cn('w-40 shrink-0 self-center rounded-card shadow-pop ring-1 ring-inset ring-white/10 sm:w-52 sm:self-auto', item.type === 'album' ? 'aspect-square' : 'aspect-[2/3]')} />
          <div className="min-w-0 flex-1">
            <div className="mb-2.5 flex flex-wrap items-center gap-1.5">
              <Badge tone="accent" icon={Icon}>{TYPE_LABEL[item.type]}</Badge>
              {item.remote ? <Badge tone="accent">From addons</Badge> : item.demo ? <Badge tone="outline">{item.fictional ? 'Fictional demo title' : 'Demo library'}</Badge> : <Badge tone="success">Your import</Badge>}
              {item.license && <Badge>{item.license}</Badge>}
              {completed && <Badge tone="success" icon={Check}>Completed</Badge>}
              {item.type === 'game' && gameInstalled && <Badge tone="success" icon={HardDrive}>Installed (record)</Badge>}
              {edited && <Badge tone="warn" icon={Pencil}>Metadata edited</Badge>}
            </div>
            <h1 className="h-display text-3xl leading-[1.08] sm:text-[2.6rem]">{item.title}</h1>
            {item.altTitle && <p className="mt-1 text-sm italic text-muted">{item.altTitle}</p>}
            <p className="mt-2 flex flex-wrap items-center gap-x-3 gap-y-1 text-sm text-muted">
              {item.creator && (item.type === 'album' ? <Link to={`/music/artist/${encodeURIComponent(item.creator)}`} className="font-medium text-fg hover:underline">{item.creator}</Link> : <span className="font-medium text-fg">{item.creator}</span>)}
              <span>{metaLine}</span>
              {item.rating != null && <span className="flex items-center gap-1 text-fg"><Star className="h-3.5 w-3.5 fill-amber-400 text-amber-400" />{item.rating.toFixed(1)}</span>}
            </p>
            <div className="mt-2 flex flex-wrap gap-1.5">{item.genres.map((g) => <Link key={g} to={`/search?q=${encodeURIComponent(g)}`} className="rounded-full border border-line/10 px-2.5 py-0.5 text-xs text-muted transition-colors hover:border-line/25 hover:text-fg">{g}</Link>)}</div>

            {inProgress && item.type !== 'album' && (
              <div className="mt-4 max-w-sm"><ProgressBar value={progress.percent} /><p className="mt-1.5 flex items-center gap-1.5 text-xs text-muted"><Clock className="h-3 w-3" />{progressLabel(item)} · {timeAgo(progress.updatedAt)}</p></div>
            )}

            <div className="mt-5 flex flex-wrap items-center gap-2">
              {item.type === 'game'
                ? <Button variant="primary" size="lg" icon={ExternalLink} onClick={() => item.game?.website && window.open(item.game.website, '_blank', 'noopener,noreferrer')}>Get from official site</Button>
                : <Button variant="primary" size="lg" icon={item.type === 'manga' || item.type === 'book' ? BookOpen : Play} onClick={() => act(item)}>{inProgress ? (item.type === 'manga' || item.type === 'book' ? 'Continue reading' : 'Resume') : PRIMARY_LABEL[item.type]}</Button>}
              {item.type === 'album' && <Button size="lg" icon={Shuffle} onClick={() => { const p = usePlayer.getState(); if (!p.shuffle) p.toggleShuffle(); p.play(tracks.map((t) => t.id), Math.floor(Math.random() * Math.max(1, tracks.length))); }}>Shuffle</Button>}
              <Button size="lg" icon={saved ? BookmarkCheck : Bookmark} aria-pressed={saved} onClick={() => toast(store.toggleSaved(item.id) ? `Added to ${SAVE_LABEL[item.type]}` : `Removed from ${SAVE_LABEL[item.type]}`, 'success')}>{saved ? `In ${SAVE_LABEL[item.type]}` : SAVE_LABEL[item.type]}</Button>
              <IconButton label={favorite ? 'Remove from favorites' : 'Add to favorites'} icon={Heart} tone="solid" size="lg" active={favorite} onClick={() => toast(store.toggleFavorite(item.id) ? 'Added to Favorites' : 'Removed from Favorites', 'success')} className={favorite ? '!text-rose-400 [&_svg]:fill-current' : undefined} />
              <IconButton label="Add to collection" icon={FolderPlus} tone="solid" size="lg" onClick={() => openCollector(item.id)} />
              <IconButton label="Edit metadata" icon={Pencil} tone="solid" size="lg" onClick={() => openEditor(item.id)} />
              <Menu label="More actions" className="inline-flex h-11 w-11 items-center justify-center rounded-full bg-line/10 text-fg hover:bg-line/20" items={[
                { label: completed ? 'Mark as not completed' : 'Mark as completed', icon: CheckCircle2, onClick: () => { store.setCompleted(item.id, !completed); toast(completed ? 'Marked as not completed' : 'Marked as completed', 'success'); } },
                { label: 'Clear progress', icon: Undo2, disabled: !progress, onClick: () => { store.clearProgress(item.id); toast('Progress cleared'); } },
                'divider',
                { label: 'Edit metadata', icon: Pencil, onClick: () => openEditor(item.id) },
                { label: 'Export metadata (JSON)', icon: FileJson, onClick: () => { const { video, tv, album, game, manga, book, ...meta } = item; void video; void tv; void album; void game; void manga; void book; saveJson({ debridoneMetadata: 1, item: meta, original: originalItem(item.id)?.title }, `${item.title} metadata.json`); toast('Metadata exported', 'success'); } },
                ...(!item.demo ? ['divider' as const, { label: 'Remove from library…', icon: Trash2, danger: true, onClick: remove }] : []),
              ]} />
            </div>
          </div>
        </div>
      </div>

      <div className="page !pt-2">
        <div className="grid gap-6 lg:grid-cols-[minmax(0,1fr)_21rem]">
          <div className="min-w-0 space-y-6">
            {item.remote?.partial && (remoteErr ? <Callout tone="error">{remoteErr}</Callout> : <p className="flex items-center gap-2 text-xs text-muted"><Spinner className="h-3.5 w-3.5" />Loading full details…</p>)}
            <p className="max-w-3xl whitespace-pre-line text-[0.9375rem] leading-relaxed text-fg/85">{item.description || 'No description yet. Use Edit metadata to add one.'}</p>
            {item.type === 'album' && (
              <Card title="Songs" description={item.fictional ? 'Original audio, generated in your browser the first time each song plays.' : undefined}>
                {tracks.length ? <TrackList tracks={tracks} /> : <p className="text-[0.8125rem] text-muted">No songs in this album.</p>}
              </Card>
            )}
            {(item.type === 'movie' || item.type === 'tv') && <VideoSection item={item} />}
            {item.type === 'game' && <GameSection item={item} />}
            {item.type === 'manga' && <MangaSection item={item} />}
            {item.type === 'book' && <BookSection item={item} />}
            {item.remote && item.type === 'game' && <DealsCard sid={item.remote.id} />}
            {item.remote && item.type !== 'movie' && item.type !== 'tv' && (
              <Card title="Downloads & streams from your addons" description="Options your installed addons offer for this title. Links download through your browser or open at their source.">
                <StreamOptions item={item} />
              </Card>
            )}
          </div>
          <aside className="space-y-6">
            <Card title="Details" action={<Button size="sm" variant="ghost" icon={Pencil} onClick={() => openEditor(item.id)}>Edit</Button>}>
              <dl className="space-y-2.5 text-[0.8125rem]">
                {facts.filter(([, v]) => v).map(([k, v]) => <div key={k} className="flex justify-between gap-4"><dt className="shrink-0 text-muted">{k}</dt><dd className="min-w-0 break-words text-right">{v}</dd></div>)}
              </dl>
              {item.tags.length > 0 && (
                <div className="mt-4 border-t border-line/[0.06] pt-3">
                  <p className="label mb-2">Tags</p>
                  <div className="flex flex-wrap gap-1.5">{item.tags.map((t) => <Link key={t} to={`/search?q=${encodeURIComponent(t)}`} className="rounded-full bg-line/[0.07] px-2.5 py-0.5 text-xs text-muted hover:bg-line/[0.14] hover:text-fg">#{t}</Link>)}</div>
                </div>
              )}
            </Card>
            <PersonalNotes item={item} />
            <Card title="In collections" action={<Button size="sm" variant="ghost" icon={FolderPlus} onClick={() => openCollector(item.id)}>Add</Button>}>
              {collections.some((c) => c.itemIds.includes(item.id))
                ? <div className="flex flex-wrap gap-1.5">{collections.filter((c) => c.itemIds.includes(item.id)).map((c) => <Link key={c.id} to={`/collection/${c.id}`} className="rounded-full bg-accent/15 px-3 py-1 text-xs font-medium text-accent hover:bg-accent/25">{c.name}</Link>)}</div>
                : <p className="text-[0.8125rem] text-muted">Not in any collection yet.</p>}
            </Card>
          </aside>
        </div>
        <div className="mt-10"><ItemShelf title="More like this" items={related} /></div>
      </div>
    </div>
  );
}
