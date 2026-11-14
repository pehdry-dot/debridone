import { ALargeSmall, ArrowLeft, Bookmark, BookmarkCheck, ChevronLeft, ChevronRight, List, Maximize, Minimize, Minus, Plus, StickyNote, Trash2, X } from 'lucide-react';
import { useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState } from 'react';
import { useNavigate, useParams, useSearchParams } from 'react-router-dom';
import { Button, EmptyState, Modal, Range, Segmented, Spinner } from '../components/ui';
import { loadBook, type BookContent } from '../lib/books';
import { clamp, cn, timeAgo } from '../lib/utils';
import { useItem } from '../store/catalog';
import { toast } from '../store/ui';
import { useStore } from '../store/useStore';
import type { BookReaderSettings } from '../types';

const THEMES: Record<BookReaderSettings['theme'], { bg: string; fg: string; sub: string; label: string }> = {
  light: { bg: '#FAFAF7', fg: '#1C1C1E', sub: 'rgb(0 0 0 / 0.55)', label: 'Light' },
  sepia: { bg: '#F4ECD8', fg: '#5B4636', sub: 'rgb(91 70 54 / 0.65)', label: 'Sepia' },
  dark: { bg: '#121216', fg: '#D6D6DC', sub: 'rgb(255 255 255 / 0.5)', label: 'Dark' },
};
const FONTS: Record<BookReaderSettings['font'], { label: string; stack: string }> = {
  serif: { label: 'Serif', stack: `'Iowan Old Style', 'Palatino Linotype', Palatino, Georgia, 'Times New Roman', serif` },
  sans: { label: 'Sans', stack: `-apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, 'Helvetica Neue', Arial, sans-serif` },
  rounded: { label: 'Rounded', stack: `ui-rounded, 'SF Pro Rounded', Nunito, 'Varela Round', system-ui, sans-serif` },
  mono: { label: 'Mono', stack: `ui-monospace, 'SF Mono', Menlo, Consolas, monospace` },
};
const GAP = 56;

export default function BookReaderPage() {
  const { id = '' } = useParams();
  const [params] = useSearchParams();
  const navigate = useNavigate();
  const item = useItem(id);
  const settings = useStore((s) => s.bookReader);
  const setSettings = useStore((s) => s.setBookReader);
  const bookmarks = useStore((s) => s.bookmarks[id]) ?? [];
  const notes = useStore((s) => s.bookNotes[id]) ?? [];

  const root = useRef<HTMLDivElement>(null);
  const frame = useRef<HTMLDivElement>(null);
  const flow = useRef<HTMLDivElement>(null);
  const [content, setContent] = useState<BookContent | null>(null);
  const [error, setError] = useState<string | null>(null);
  const saved = useMemo(() => useStore.getState().progress[id], [id]);
  const [chapter, setChapter] = useState(() => Number(params.get('ch') ?? saved?.unit ?? 0) || 0);
  /** position inside the chapter, 0–1; survives re-pagination when type settings change */
  const fraction = useRef(params.get('at') != null ? Number(params.get('at')) : params.get('ch') != null ? 0 : saved?.position ?? 0);
  const [page, setPage] = useState(0);
  const [pages, setPages] = useState(1);
  const [size, setSize] = useState({ w: 0, h: 0 });
  const [panel, setPanel] = useState<'settings' | 'contents' | null>(null);
  const [noteOpen, setNoteOpen] = useState(false);
  const [noteText, setNoteText] = useState('');
  const [fullscreen, setFullscreen] = useState(false);
  const [wide, setWide] = useState(() => window.innerWidth >= 1180);

  const theme = THEMES[settings.theme];
  const paged = settings.layout === 'paged';
  const chapters = content?.kind === 'html' ? content.chapters : [];
  const current = chapters[clamp(chapter, 0, Math.max(0, chapters.length - 1))];
  const columns = paged && wide ? 2 : 1;

  useEffect(() => {
    if (!item) return;
    let off = false;
    setContent(null); setError(null);
    loadBook(item).then((c) => { if (!off) setContent(c); }).catch((e) => { if (!off) setError((e as Error).message); });
    useStore.getState().touch(item.id);
    return () => { off = true; };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [item?.id]);

  useEffect(() => {
    if (item && current) useStore.getState().logHistory({ id: item.id, kind: 'read', detail: current.title });
  }, [item?.id, chapter, !!current]); // eslint-disable-line react-hooks/exhaustive-deps

  useEffect(() => {
    const el = frame.current;
    if (!el) return;
    const ro = new ResizeObserver(() => { setSize({ w: el.clientWidth, h: el.clientHeight }); setWide((root.current?.clientWidth ?? window.innerWidth) >= 1180); });
    ro.observe(el);
    if (root.current) ro.observe(root.current);
    return () => ro.disconnect();
  }, [content, paged]);

  // Paginate with CSS multi-column layout: the chapter flows into fixed-height columns and we slide between them.
  useLayoutEffect(() => {
    const el = flow.current;
    if (!paged || !el || !size.w || !current) return;
    const colWidth = (size.w - GAP * (columns - 1)) / columns;
    const cols = Math.max(1, Math.round((el.scrollWidth + GAP) / (colWidth + GAP)));
    const total = Math.max(1, Math.ceil(cols / columns));
    setPages(total);
    setPage(clamp(Math.floor(fraction.current * total + 0.0001), 0, total - 1));
  }, [paged, current, size.w, size.h, columns, settings.fontSize, settings.font, settings.lineHeight]);

  // Scroll layout: restore position after content or type settings change.
  useLayoutEffect(() => {
    const el = frame.current;
    if (paged || !el || !current) return;
    el.scrollTop = fraction.current * Math.max(0, el.scrollHeight - el.clientHeight);
  }, [paged, current, settings.fontSize, settings.font, settings.lineHeight, settings.width]);

  const save = useCallback((within: number) => {
    if (!item || !chapters.length) return;
    useStore.getState().setProgress(item.id, { percent: Math.min(100, ((chapter + within) / chapters.length) * 100), position: fraction.current, unit: String(chapter) });
  }, [item, chapter, chapters.length]);

  useEffect(() => { if (paged && current) save((page + 1) / pages); }, [paged, page, pages, current, save]);

  const goChapter = (index: number, at = 0) => {
    if (index < 0 || index >= chapters.length) return false;
    fraction.current = at;
    setChapter(index);
    if (!paged) frame.current?.scrollTo({ top: 0 });
    return true;
  };
  const turn = (dir: 1 | -1) => {
    if (!paged) { frame.current?.scrollBy({ top: dir * (frame.current.clientHeight - 60), behavior: 'smooth' }); return; }
    const next = page + dir;
    if (next >= 0 && next < pages) { fraction.current = next / pages; setPage(next); }
    else if (dir === 1) { if (!goChapter(chapter + 1)) toast('You’ve reached the end of this book.', 'success'); }
    else goChapter(chapter - 1, 0.9999);
  };
  const onScroll = () => {
    const el = frame.current;
    if (paged || !el) return;
    fraction.current = el.scrollTop / Math.max(1, el.scrollHeight - el.clientHeight);
    save(fraction.current);
  };

  useEffect(() => {
    const onFs = () => setFullscreen(document.fullscreenElement === root.current);
    document.addEventListener('fullscreenchange', onFs);
    root.current?.focus();
    return () => document.removeEventListener('fullscreenchange', onFs);
  }, []);
  const toggleFullscreen = () => { if (document.fullscreenElement) document.exitFullscreen().catch(() => {}); else root.current?.requestFullscreen?.().catch(() => {}); };
  const back = () => navigate(item ? `/item/${item.id}` : '/books');

  const here = paged ? page / pages : fraction.current;
  const nearby = bookmarks.find((b) => b.chapter === chapter && Math.abs(b.fraction - here) < (paged ? 0.5 / pages : 0.02));
  const toggleBookmark = () => {
    if (!item || !current) return;
    const s = useStore.getState();
    if (nearby) { s.removeBookmark(item.id, nearby.id); toast('Bookmark removed'); }
    else { s.addBookmark(item.id, { chapter, fraction: here, label: `${current.title}${paged ? ` · page ${page + 1}` : ` · ${Math.round(here * 100)}%`}` }); toast('Bookmark added', 'success'); }
  };

  const onKey = (e: React.KeyboardEvent) => {
    if (/^(INPUT|SELECT|TEXTAREA)$/.test((e.target as HTMLElement).tagName)) return;
    if (e.key === 'Escape') { if (panel) setPanel(null); else if (!document.fullscreenElement) back(); }
    else if (e.key === 'ArrowRight' || e.key === 'PageDown' || (e.key === ' ' && paged)) { e.preventDefault(); turn(1); }
    else if (e.key === 'ArrowLeft' || e.key === 'PageUp') { e.preventDefault(); turn(-1); }
    else if (e.key === 'b') toggleBookmark();
    else if (e.key === 'f') toggleFullscreen();
  };

  if (!item || item.type !== 'book') {
    return <div className="flex h-full items-center justify-center bg-bg p-6"><EmptyState icon={ArrowLeft} title="Nothing to read" body="That book could not be found." action={<Button variant="primary" onClick={() => navigate('/books')}>Back to Books</Button>} /></div>;
  }

  const overall = chapters.length ? Math.round(((chapter + (paged ? (page + 1) / pages : here)) / chapters.length) * 100) : 0;
  const iconBtn = 'flex h-9 w-9 shrink-0 items-center justify-center rounded-full hov-tint';
  const colWidth = (size.w - GAP * (columns - 1)) / columns;

  return (
    <div ref={root} tabIndex={-1} onKeyDown={onKey} className="relative flex h-full flex-col outline-none" style={{ background: theme.bg, color: theme.fg }}>
      <header className="z-20 flex h-12 shrink-0 items-center gap-1 px-2 sm:px-4" style={{ borderBottom: `1px solid ${theme.sub.replace(/[\d.]+\)$/, '0.12)')}` }}>
        <button type="button" onClick={back} aria-label="Back to book" className={iconBtn}><ArrowLeft className="h-5 w-5" /></button>
        <div className="min-w-0 flex-1 px-1">
          <p className="truncate text-sm font-semibold">{item.title}</p>
          <p className="truncate text-xs" style={{ color: theme.sub }}>{current?.title ?? item.creator}</p>
        </div>
        {content?.kind === 'html' && (
          <>
            <button type="button" onClick={toggleBookmark} aria-label={nearby ? 'Remove bookmark' : 'Bookmark this page'} aria-pressed={!!nearby} className={iconBtn}>{nearby ? <BookmarkCheck className="h-[1.15rem] w-[1.15rem] text-accent" /> : <Bookmark className="h-[1.15rem] w-[1.15rem]" />}</button>
            <button type="button" onClick={() => { setNoteText(''); setNoteOpen(true); }} aria-label="Add a note" className={iconBtn}><StickyNote className="h-[1.15rem] w-[1.15rem]" /></button>
            <button type="button" onClick={() => setPanel(panel === 'contents' ? null : 'contents')} aria-label="Contents, bookmarks and notes" aria-expanded={panel === 'contents'} className={cn(iconBtn, panel === 'contents' && 'tint')}><List className="h-[1.15rem] w-[1.15rem]" /></button>
            <button type="button" onClick={() => setPanel(panel === 'settings' ? null : 'settings')} aria-label="Reading settings" aria-expanded={panel === 'settings'} className={cn(iconBtn, panel === 'settings' && 'tint')}><ALargeSmall className="h-[1.25rem] w-[1.25rem]" /></button>
          </>
        )}
        <button type="button" onClick={toggleFullscreen} aria-label={fullscreen ? 'Exit fullscreen' : 'Fullscreen'} className={iconBtn}>{fullscreen ? <Minimize className="h-[1.1rem] w-[1.1rem]" /> : <Maximize className="h-[1.1rem] w-[1.1rem]" />}</button>
      </header>

      {error ? (
        <div className="flex flex-1 items-center justify-center p-6"><div className="max-w-md text-center"><p className="text-base font-semibold">This book couldn’t be opened</p><p className="mt-2 text-sm" style={{ color: theme.sub }}>{error}</p><Button className="mt-4" onClick={back}>Back</Button></div></div>
      ) : !content ? (
        <div className="flex flex-1 items-center justify-center"><Spinner /></div>
      ) : content.kind === 'pdf' ? (
        <>
          <iframe title={item.title} src={content.url} className="min-h-0 w-full flex-1 border-0 bg-white" />
          <p className="shrink-0 px-4 py-2 text-center text-xs" style={{ color: theme.sub }}>PDFs are shown by your browser’s built-in viewer, so typography controls and page tracking don’t apply.</p>
        </>
      ) : (
        <>
          <div className="relative min-h-0 flex-1">
            <div className={cn('mx-auto h-full', paged && 'px-6 py-8 sm:px-14 sm:py-10')} style={{ maxWidth: paged ? (wide ? 1240 : settings.width + 112) : undefined }}>
            <div ref={frame} onScroll={onScroll} onClick={() => panel && setPanel(null)} className={cn('h-full', paged ? 'overflow-hidden' : 'overflow-y-auto px-6 py-8 sm:px-10')}>
              <div ref={flow} className="reader-prose" lang="en"
                style={{
                  fontFamily: FONTS[settings.font].stack, fontSize: settings.fontSize, lineHeight: settings.lineHeight,
                  ...(paged
                    ? { height: size.h || undefined, width: size.w || undefined, columnWidth: colWidth > 0 ? colWidth : undefined, columnGap: GAP, columnFill: 'auto', transform: `translateX(${-page * (size.w + GAP)}px)`, transition: 'transform calc(220ms * var(--anim)) ease' }
                    : { maxWidth: settings.width, margin: '0 auto', paddingBottom: '4rem' }),
                }}>
                {!/^\s*<h[1-3][\s>]/i.test(current?.html ?? '') && <h2 style={{ marginTop: 0 }}>{current?.title}</h2>}
                <div dangerouslySetInnerHTML={{ __html: current?.html ?? '' }} />
                {!paged && (
                  <div className="mt-10 flex flex-wrap justify-between gap-2" style={{ fontFamily: FONTS.sans.stack, fontSize: 14 }}>
                    <Button disabled={chapter === 0} onClick={() => goChapter(chapter - 1)} icon={ChevronLeft}>Previous</Button>
                    {chapter < chapters.length - 1 ? <Button variant="primary" onClick={() => goChapter(chapter + 1)}>Next: {chapters[chapter + 1].title}</Button> : <Button onClick={back}>Finish</Button>}
                  </div>
                )}
              </div>
            </div>
            </div>
            {paged && (
              <>
                <button type="button" aria-label="Previous page" onClick={() => turn(-1)} className="group absolute inset-y-0 left-0 w-[12%] min-w-10"><ChevronLeft className="absolute left-2 top-1/2 h-6 w-6 -translate-y-1/2 opacity-0 transition-opacity group-hover:opacity-60 group-focus-visible:opacity-60" /></button>
                <button type="button" aria-label="Next page" onClick={() => turn(1)} className="group absolute inset-y-0 right-0 w-[12%] min-w-10"><ChevronRight className="absolute right-2 top-1/2 h-6 w-6 -translate-y-1/2 opacity-0 transition-opacity group-hover:opacity-60 group-focus-visible:opacity-60" /></button>
              </>
            )}
          </div>
          <footer className="z-10 flex h-11 shrink-0 items-center gap-3 px-4 text-xs tabular-nums" style={{ color: theme.sub }}>
            <button type="button" onClick={() => turn(-1)} aria-label="Previous page" className={iconBtn}><ChevronLeft className="h-4 w-4" /></button>
            <span className="min-w-0 flex-1 truncate text-center">{paged ? `Page ${page + 1} of ${pages} in this chapter · ` : ''}Chapter {chapter + 1} of {chapters.length} · {overall}% of book</span>
            <button type="button" onClick={() => turn(1)} aria-label="Next page" className={iconBtn}><ChevronRight className="h-4 w-4" /></button>
          </footer>
          <div className="absolute inset-x-0 bottom-0 h-0.5"><div className="h-full bg-accent transition-[width] duration-300" style={{ width: `${overall}%` }} /></div>
        </>
      )}

      {panel === 'settings' && (
        <div role="dialog" aria-label="Reading settings" className="absolute right-2 top-14 z-30 w-[min(20rem,calc(100%-1rem))] space-y-4 rounded-2xl border border-white/10 bg-[#17171d] p-4 text-white shadow-pop"
          style={{ ['--line' as string]: '255 255 255', ['--card' as string]: '45 45 56', ['--fg' as string]: '245 245 247', ['--muted' as string]: '161 161 170' }}>
          <div>
            <p className="mb-1.5 text-xs font-medium text-white/60">Page colour</p>
            <div className="flex gap-2">
              {(Object.keys(THEMES) as BookReaderSettings['theme'][]).map((t) => (
                <button key={t} type="button" aria-pressed={settings.theme === t} onClick={() => setSettings({ theme: t })} className={cn('flex h-10 flex-1 items-center justify-center rounded-xl border-2 text-xs font-semibold', settings.theme === t ? 'border-accent' : 'border-white/15')} style={{ background: THEMES[t].bg, color: THEMES[t].fg }}>{THEMES[t].label}</button>
              ))}
            </div>
          </div>
          <div>
            <p className="mb-1.5 text-xs font-medium text-white/60">Typeface</p>
            <div className="grid grid-cols-4 gap-1.5">
              {(Object.keys(FONTS) as BookReaderSettings['font'][]).map((f) => (
                <button key={f} type="button" aria-pressed={settings.font === f} onClick={() => setSettings({ font: f })} className={cn('rounded-lg border py-2 text-xs', settings.font === f ? 'border-accent bg-white/10' : 'border-white/15 hover:bg-white/5')} style={{ fontFamily: FONTS[f].stack }}>{FONTS[f].label}</button>
              ))}
            </div>
          </div>
          <div>
            <p className="mb-1.5 flex justify-between text-xs font-medium text-white/60"><span>Font size</span><span>{settings.fontSize}px</span></p>
            <div className="flex items-center gap-2">
              <button type="button" aria-label="Smaller text" onClick={() => setSettings({ fontSize: clamp(settings.fontSize - 1, 13, 30) })} className="flex h-8 w-8 items-center justify-center rounded-full bg-white/10 hover:bg-white/20"><Minus className="h-4 w-4" /></button>
              <Range label="Font size" className="flex-1" min={13} max={30} value={settings.fontSize} onChange={(v) => setSettings({ fontSize: v })} />
              <button type="button" aria-label="Larger text" onClick={() => setSettings({ fontSize: clamp(settings.fontSize + 1, 13, 30) })} className="flex h-8 w-8 items-center justify-center rounded-full bg-white/10 hover:bg-white/20"><Plus className="h-4 w-4" /></button>
            </div>
          </div>
          <div>
            <p className="mb-1.5 flex justify-between text-xs font-medium text-white/60"><span>Line spacing</span><span>{settings.lineHeight.toFixed(2)}</span></p>
            <Range label="Line spacing" className="w-full" min={1.2} max={2.2} step={0.05} value={settings.lineHeight} onChange={(v) => setSettings({ lineHeight: v })} />
          </div>
          <div>
            <p className="mb-1.5 flex justify-between text-xs font-medium text-white/60"><span>Text width</span><span>{settings.width}px</span></p>
            <Range label="Text width" className="w-full" min={420} max={960} step={20} value={settings.width} onChange={(v) => setSettings({ width: v })} />
          </div>
          <div>
            <p className="mb-1.5 text-xs font-medium text-white/60">Layout</p>
            <Segmented size="sm" value={settings.layout} onChange={(v) => { fraction.current = here; setSettings({ layout: v }); }} options={[{ value: 'paged', label: 'Pages' }, { value: 'scroll', label: 'Scrolling' }]} />
          </div>
        </div>
      )}

      {panel === 'contents' && (
        <aside aria-label="Contents, bookmarks and notes" className="absolute inset-y-0 right-0 z-30 flex w-[min(22rem,100%)] flex-col border-l border-white/10 bg-[#17171d] text-white shadow-pop">
          <div className="flex items-center justify-between border-b border-white/10 px-4 py-3"><p className="text-sm font-semibold">Contents</p><button type="button" aria-label="Close" onClick={() => setPanel(null)} className="flex h-8 w-8 items-center justify-center rounded-full hover:bg-white/10"><X className="h-4 w-4" /></button></div>
          <div className="min-h-0 flex-1 space-y-5 overflow-y-auto p-3">
            <ol>
              {chapters.map((c, i) => <li key={i}><button type="button" onClick={() => { goChapter(i); setPanel(null); }} aria-current={i === chapter} className={cn('w-full truncate rounded-lg px-3 py-2 text-left text-[0.8125rem] hover:bg-white/10', i === chapter && 'bg-white/10 font-semibold text-accent')}>{c.title}</button></li>)}
            </ol>
            <div>
              <p className="mb-1.5 px-1 text-[0.6875rem] font-semibold uppercase tracking-wider text-white/50">Bookmarks</p>
              {bookmarks.length ? bookmarks.map((b) => (
                <div key={b.id} className="flex items-center gap-2 rounded-lg px-3 py-2 hover:bg-white/[0.06]">
                  <button type="button" className="min-w-0 flex-1 text-left" onClick={() => { goChapter(b.chapter, b.fraction); setPanel(null); }}><span className="block truncate text-[0.8125rem]">{b.label}</span><span className="text-xs text-white/50">{timeAgo(b.ts)}</span></button>
                  <button type="button" aria-label="Delete bookmark" onClick={() => useStore.getState().removeBookmark(item.id, b.id)} className="flex h-7 w-7 items-center justify-center rounded-full text-white/50 hover:bg-white/10 hover:text-white"><Trash2 className="h-3.5 w-3.5" /></button>
                </div>
              )) : <p className="px-1 text-xs text-white/50">Press the bookmark icon (or B) on any page.</p>}
            </div>
            <div>
              <p className="mb-1.5 px-1 text-[0.6875rem] font-semibold uppercase tracking-wider text-white/50">Notes</p>
              {notes.length ? notes.map((n) => (
                <div key={n.id} className="flex items-start gap-2 rounded-lg px-3 py-2 hover:bg-white/[0.06]">
                  <button type="button" className="min-w-0 flex-1 text-left" onClick={() => { goChapter(n.chapter); setPanel(null); }}><span className="block whitespace-pre-wrap text-[0.8125rem]">{n.text}</span><span className="text-xs text-white/50">{chapters[n.chapter]?.title} · {timeAgo(n.ts)}</span></button>
                  <button type="button" aria-label="Delete note" onClick={() => useStore.getState().removeNote(item.id, n.id)} className="flex h-7 w-7 items-center justify-center rounded-full text-white/50 hover:bg-white/10 hover:text-white"><Trash2 className="h-3.5 w-3.5" /></button>
                </div>
              )) : <p className="px-1 text-xs text-white/50">Use the note icon to jot something down about this chapter.</p>}
            </div>
          </div>
        </aside>
      )}

      <Modal open={noteOpen} onClose={() => setNoteOpen(false)} title="Add a note" subtitle={current ? `Attached to “${current.title}”` : undefined} size="sm"
        footer={<><Button variant="ghost" onClick={() => setNoteOpen(false)}>Cancel</Button><Button variant="primary" disabled={!noteText.trim()} onClick={() => { useStore.getState().addNote(item.id, { chapter, text: noteText.trim() }); setNoteOpen(false); toast('Note saved', 'success'); }}>Save note</Button></>}>
        <textarea autoFocus className="field min-h-[7rem]" placeholder="Your thoughts on this passage…" value={noteText} onChange={(e) => setNoteText(e.target.value)} maxLength={2000} aria-label="Note text" />
      </Modal>
    </div>
  );
}
