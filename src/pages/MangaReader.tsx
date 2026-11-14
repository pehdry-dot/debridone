import { ArrowLeft, ChevronLeft, ChevronRight, Maximize, Minimize, Settings2, ZoomIn, ZoomOut } from 'lucide-react';
import { useCallback, useEffect, useMemo, useRef, useState, type CSSProperties } from 'react';
import { useNavigate, useParams, useSearchParams } from 'react-router-dom';
import { MangaPage, pageHeight, PAGE_W } from '../components/MangaPage';
import { Button, EmptyState, Range, Segmented, Select, Spinner } from '../components/ui';
import { blobUrl } from '../lib/idb';
import { clamp, cn, luminance } from '../lib/utils';
import { useItem } from '../store/catalog';
import { toast } from '../store/ui';
import { useStore } from '../store/useStore';
import type { Chapter, MangaReaderSettings, MediaItem } from '../types';

const BACKGROUNDS = ['#0B0B0F', '#1E1E26', '#3a3a40', '#F4ECD8', '#FFFFFF'];

function ImportedPage({ storageKey, alt, fitHeight }: { storageKey: string; alt: string; fitHeight: boolean }) {
  const [url, setUrl] = useState<string | null | undefined>(undefined);
  useEffect(() => { let off = false; blobUrl(storageKey).then((u) => { if (!off) setUrl(u ?? null); }); return () => { off = true; }; }, [storageKey]);
  if (url === undefined) return <div className="flex aspect-[2/3] w-full items-center justify-center"><Spinner /></div>;
  if (url === null) return <div className="flex aspect-[2/3] w-full items-center justify-center bg-black/20 p-6 text-center text-sm text-white/70">This page is no longer in browser storage.</div>;
  return <img src={url} alt={alt} draggable={false} loading="lazy" decoding="async" className={cn('block select-none', fitHeight ? 'h-full w-auto max-w-none' : 'h-auto w-full')} />;
}

function Page({ item, chapter, index, style, fitHeight }: { item: MediaItem; chapter: Chapter; index: number; style: CSSProperties; fitHeight: boolean }) {
  const slug = item.id.split(':')[1];
  const color = !!item.manga?.color;
  const generated = chapter.source === 'generated';
  return (
    <div data-page={index} className="mx-auto shrink-0 bg-white shadow-[0_8px_40px_-12px_rgb(0_0_0/0.6)]"
      style={{ ...style, ...(generated ? { aspectRatio: `${PAGE_W} / ${pageHeight(color)}` } : {}) }}>
      {generated
        ? <MangaPage series={slug} chapter={chapter.number} page={index} total={chapter.pages} title={item.title} chapterTitle={chapter.title} color={color} palette={item.palette} className="h-full w-full" />
        : <ImportedPage storageKey={`manga:${chapter.id}:${index}`} alt={`${item.title} page ${index + 1}`} fitHeight={fitHeight} />}
    </div>
  );
}

export default function MangaReaderPage() {
  const { id = '' } = useParams();
  const [params, setParams] = useSearchParams();
  const navigate = useNavigate();
  const item = useItem(id);
  const settings = useStore((s) => s.mangaReader);
  const setSettings = useStore((s) => s.setMangaReader);
  const root = useRef<HTMLDivElement>(null);
  const scroller = useRef<HTMLDivElement>(null);
  const [panel, setPanel] = useState(false);
  const [fullscreen, setFullscreen] = useState(false);

  const chapters = item?.manga?.chapters ?? [];
  const saved = useMemo(() => (item ? useStore.getState().progress[item.id] : undefined), [item?.id]); // eslint-disable-line react-hooks/exhaustive-deps
  const chapter = chapters.find((c) => c.id === (params.get('ch') ?? saved?.unit)) ?? chapters[0];
  const chapterIndex = chapter ? chapters.indexOf(chapter) : 0;
  const [page, setPage] = useState(() => (saved && saved.unit === chapter?.id ? clamp(saved.position, 0, (chapter?.pages ?? 1) - 1) : 0));

  const mode: Exclude<MangaReaderSettings['mode'], 'auto'> = settings.mode === 'auto' ? (item?.manga?.direction ?? 'rtl') : settings.mode;
  const paged = mode === 'ltr' || mode === 'rtl';
  const zoom = settings.zoom / 100;
  const darkBg = luminance(settings.bg) < 0.4;

  const goChapter = useCallback((index: number, at: 'start' | 'end' = 'start') => {
    const next = chapters[index];
    if (!next) return false;
    const p = new URLSearchParams(params);
    p.set('ch', next.id);
    setParams(p, { replace: true });
    setPage(at === 'end' ? next.pages - 1 : 0);
    scroller.current?.scrollTo({ top: 0 });
    return true;
  }, [chapters, params, setParams]);

  const next = useCallback(() => {
    if (!chapter) return;
    if (page < chapter.pages - 1) setPage(page + 1);
    else if (!goChapter(chapterIndex + 1)) toast('You’ve reached the end of the latest chapter.', 'success');
  }, [chapter, page, chapterIndex, goChapter]);
  const prev = useCallback(() => {
    if (page > 0) setPage(page - 1);
    else goChapter(chapterIndex - 1, 'end');
  }, [page, chapterIndex, goChapter]);

  // Save progress whenever the page or chapter changes.
  useEffect(() => {
    if (!item || !chapter) return;
    const s = useStore.getState();
    const before = s.progress[item.id]?.units ?? {};
    const units = { ...before, [chapter.id]: { position: page, done: page >= chapter.pages - 1 || !!before[chapter.id]?.done } };
    const done = chapters.filter((c) => units[c.id]?.done).length;
    const partial = units[chapter.id].done ? 0 : (page + 1) / chapter.pages;
    s.setProgress(item.id, { percent: Math.min(100, ((done + partial) / Math.max(1, chapters.length)) * 100), position: page, unit: chapter.id, units });
  }, [item?.id, chapter?.id, page]); // eslint-disable-line react-hooks/exhaustive-deps

  useEffect(() => {
    if (item && chapter) useStore.getState().logHistory({ id: item.id, kind: 'read', detail: `Chapter ${chapter.number}` });
  }, [item?.id, chapter?.id]); // eslint-disable-line react-hooks/exhaustive-deps

  // Paged modes: start each page at the top. Scroll modes: track the page nearest the top of the viewport.
  useEffect(() => { if (paged) scroller.current?.scrollTo({ top: 0, left: 0 }); }, [page, paged]);
  useEffect(() => {
    const el = scroller.current;
    if (paged || !el) return;
    el.querySelector<HTMLElement>(`[data-page="${page}"]`)?.scrollIntoView({ block: 'start' });
    let frame = 0;
    const onScroll = () => {
      cancelAnimationFrame(frame);
      frame = requestAnimationFrame(() => {
        const mid = el.getBoundingClientRect().top + el.clientHeight * 0.4;
        let current = 0;
        el.querySelectorAll<HTMLElement>('[data-page]').forEach((p) => { if (p.getBoundingClientRect().top <= mid) current = Number(p.dataset.page); });
        setPage(current);
      });
    };
    el.addEventListener('scroll', onScroll, { passive: true });
    return () => { el.removeEventListener('scroll', onScroll); cancelAnimationFrame(frame); };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [paged, chapter?.id, mode]);

  useEffect(() => {
    const onFs = () => setFullscreen(document.fullscreenElement === root.current);
    document.addEventListener('fullscreenchange', onFs);
    root.current?.focus();
    return () => document.removeEventListener('fullscreenchange', onFs);
  }, []);
  const toggleFullscreen = () => { if (document.fullscreenElement) document.exitFullscreen().catch(() => {}); else root.current?.requestFullscreen?.().catch(() => {}); };
  const back = () => navigate(item ? `/item/${item.id}` : '/manga');

  const onKey = (e: React.KeyboardEvent) => {
    if (/^(INPUT|SELECT|TEXTAREA)$/.test((e.target as HTMLElement).tagName)) return;
    const forward = mode === 'rtl' ? 'ArrowLeft' : 'ArrowRight', backward = mode === 'rtl' ? 'ArrowRight' : 'ArrowLeft';
    if (e.key === 'Escape') { if (panel) setPanel(false); else if (!document.fullscreenElement) back(); }
    else if (e.key === 'f') toggleFullscreen();
    else if (e.key === '+' || e.key === '=') setSettings({ zoom: clamp(settings.zoom + 10, 50, 300) });
    else if (e.key === '-') setSettings({ zoom: clamp(settings.zoom - 10, 50, 300) });
    else if (paged && (e.key === forward || e.key === ' ' || e.key === 'PageDown')) { e.preventDefault(); next(); }
    else if (paged && (e.key === backward || e.key === 'PageUp')) { e.preventDefault(); prev(); }
  };

  if (!item || !item.manga || !chapter) {
    return <div className="flex h-full items-center justify-center bg-bg p-6"><EmptyState icon={ArrowLeft} title="Nothing to read" body="That series could not be found." action={<Button variant="primary" onClick={() => navigate('/manga')}>Back to Manga</Button>} /></div>;
  }

  const fitHeight = settings.fit === 'height';
  const pageStyle: CSSProperties = settings.fit === 'width'
    ? { width: `calc(min(100%, ${mode === 'webtoon' ? 760 : 1100}px) * ${zoom})`, maxWidth: 'none' }
    : fitHeight ? { height: `calc((100dvh - 6.5rem) * ${zoom})` } : { width: `${PAGE_W * zoom}px`, maxWidth: 'none' };
  const fg = darkBg ? 'text-white' : 'text-black';
  const bar = darkBg ? 'bg-black/55 text-white' : 'bg-white/70 text-black';
  const pages = Array.from({ length: chapter.pages }, (_, i) => i);
  const leftAction = mode === 'rtl' ? next : prev, rightAction = mode === 'rtl' ? prev : next;

  return (
    <div ref={root} tabIndex={-1} onKeyDown={onKey} className={cn('relative flex h-full flex-col outline-none', fg)} style={{ background: settings.bg }}>
      <header className={cn('z-20 flex h-12 shrink-0 items-center gap-2 px-2 backdrop-blur-md sm:px-4', bar)}>
        <button type="button" onClick={back} aria-label="Back to series" className="flex h-9 w-9 items-center justify-center rounded-full hov-tint"><ArrowLeft className="h-5 w-5" /></button>
        <div className="min-w-0 flex-1">
          <p className="truncate text-sm font-semibold">{item.title}</p>
          <p className="truncate text-xs opacity-65">Ch. {chapter.number} — {chapter.title}</p>
        </div>
        <Select label="Chapter" className="hidden w-44 sm:block [&_select]:!bg-transparent [&_select]:!text-inherit" value={chapter.id} onChange={(v) => goChapter(chapters.findIndex((c) => c.id === v))} options={chapters.map((c) => ({ value: c.id, label: `Ch. ${c.number} · ${c.title}` }))} />
        <button type="button" onClick={() => setSettings({ zoom: clamp(settings.zoom - 10, 50, 300) })} aria-label="Zoom out" className="hidden h-9 w-9 items-center justify-center rounded-full hov-tint sm:flex"><ZoomOut className="h-[1.1rem] w-[1.1rem]" /></button>
        <span className="hidden w-10 text-center text-xs tabular-nums sm:block">{settings.zoom}%</span>
        <button type="button" onClick={() => setSettings({ zoom: clamp(settings.zoom + 10, 50, 300) })} aria-label="Zoom in" className="hidden h-9 w-9 items-center justify-center rounded-full hov-tint sm:flex"><ZoomIn className="h-[1.1rem] w-[1.1rem]" /></button>
        <button type="button" onClick={() => setPanel((p) => !p)} aria-label="Reader settings" aria-expanded={panel} className={cn('flex h-9 w-9 items-center justify-center rounded-full hov-tint', panel && 'tint')}><Settings2 className="h-[1.1rem] w-[1.1rem]" /></button>
        <button type="button" onClick={toggleFullscreen} aria-label={fullscreen ? 'Exit fullscreen' : 'Fullscreen'} className="flex h-9 w-9 items-center justify-center rounded-full hov-tint">{fullscreen ? <Minimize className="h-[1.1rem] w-[1.1rem]" /> : <Maximize className="h-[1.1rem] w-[1.1rem]" />}</button>
      </header>

      {panel && (
        <div className="absolute right-2 top-14 z-30 w-[min(21rem,calc(100%-1rem))] space-y-4 rounded-2xl border border-white/10 bg-[#17171d] p-4 text-white shadow-pop" role="dialog" aria-label="Reader settings" style={{ ['--line' as string]: '255 255 255', ['--card' as string]: '45 45 56', ['--fg' as string]: '245 245 247', ['--muted' as string]: '161 161 170' }}>
          <div>
            <p className="mb-1.5 text-xs font-medium text-white/60">Reading mode</p>
            <Segmented size="sm" value={settings.mode} onChange={(v) => setSettings({ mode: v })} options={[{ value: 'auto', label: 'Auto' }, { value: 'rtl', label: 'Right → left' }, { value: 'ltr', label: 'Left → right' }, { value: 'vertical', label: 'Vertical' }, { value: 'webtoon', label: 'Webtoon' }]} />
            <p className="mt-1.5 text-xs text-white/50">{settings.mode === 'auto' ? `Auto follows the series: ${mode === 'rtl' ? 'right-to-left manga' : mode === 'ltr' ? 'left-to-right' : 'continuous webtoon'}.` : mode === 'vertical' ? 'Pages stacked with gaps.' : mode === 'webtoon' ? 'Continuous strip, no gaps.' : 'One page at a time. Arrow keys follow the reading direction.'}</p>
          </div>
          <div>
            <p className="mb-1.5 text-xs font-medium text-white/60">Fit</p>
            <Segmented size="sm" value={settings.fit} onChange={(v) => setSettings({ fit: v })} options={[{ value: 'width', label: 'Fit width' }, { value: 'height', label: 'Fit height' }, { value: 'original', label: 'Original' }]} />
          </div>
          <div>
            <p className="mb-1.5 flex justify-between text-xs font-medium text-white/60"><span>Zoom</span><button type="button" className="text-accent" onClick={() => setSettings({ zoom: 100 })}>{settings.zoom}% · reset</button></p>
            <Range label="Zoom" className="w-full" min={50} max={300} step={5} value={settings.zoom} onChange={(v) => setSettings({ zoom: v })} />
          </div>
          <div>
            <p className="mb-1.5 text-xs font-medium text-white/60">Background</p>
            <div className="flex items-center gap-2">
              {BACKGROUNDS.map((c) => <button key={c} type="button" aria-label={`Background ${c}`} aria-pressed={settings.bg === c} onClick={() => setSettings({ bg: c })} className={cn('h-8 w-8 rounded-full border-2', settings.bg === c ? 'border-accent' : 'border-white/20')} style={{ background: c }} />)}
              <label className="relative ml-1 flex h-8 cursor-pointer items-center rounded-full border border-white/20 px-3 text-xs">Custom<input type="color" value={settings.bg} onChange={(e) => setSettings({ bg: e.target.value })} className="absolute inset-0 cursor-pointer opacity-0" aria-label="Custom background colour" /></label>
            </div>
          </div>
        </div>
      )}

      <div ref={scroller} className="relative min-h-0 flex-1 overflow-auto" onClick={() => panel && setPanel(false)}>
        {paged ? (
          <div className={cn('flex min-h-full min-w-full p-3', fitHeight ? 'w-max items-center justify-center' : 'items-start justify-center')}>
            <Page item={item} chapter={chapter} index={page} style={pageStyle} fitHeight={fitHeight} />
          </div>
        ) : (
          <div className={cn('flex flex-col items-center', mode === 'vertical' ? 'gap-4 p-3' : 'gap-0 py-0')}>
            {pages.map((i) => <Page key={`${chapter.id}-${i}`} item={item} chapter={chapter} index={i} style={pageStyle} fitHeight={fitHeight} />)}
            <div className="flex flex-wrap justify-center gap-2 py-8">
              {chapterIndex > 0 && <Button onClick={() => goChapter(chapterIndex - 1)} icon={ChevronLeft}>Previous chapter</Button>}
              {chapterIndex < chapters.length - 1 ? <Button variant="primary" onClick={() => goChapter(chapterIndex + 1)}>Next chapter</Button> : <Button onClick={back}>Back to series</Button>}
            </div>
          </div>
        )}
      </div>

      {paged && (
        <>
          <button type="button" aria-label={mode === 'rtl' ? 'Next page' : 'Previous page'} onClick={leftAction} className="group absolute bottom-12 left-0 top-12 z-10 w-[18%] cursor-w-resize"><span className={cn('absolute left-3 top-1/2 flex h-10 w-10 -translate-y-1/2 items-center justify-center rounded-full opacity-0 transition-opacity group-hover:opacity-100 group-focus-visible:opacity-100', bar)}><ChevronLeft className="h-5 w-5" /></span></button>
          <button type="button" aria-label={mode === 'rtl' ? 'Previous page' : 'Next page'} onClick={rightAction} className="group absolute bottom-12 right-0 top-12 z-10 w-[18%] cursor-e-resize"><span className={cn('absolute right-3 top-1/2 flex h-10 w-10 -translate-y-1/2 items-center justify-center rounded-full opacity-0 transition-opacity group-hover:opacity-100 group-focus-visible:opacity-100', bar)}><ChevronRight className="h-5 w-5" /></span></button>
        </>
      )}

      <footer className={cn('z-20 flex h-12 shrink-0 items-center gap-3 px-3 text-xs backdrop-blur-md sm:px-5', bar)} style={{ ['--line' as string]: darkBg ? '255 255 255' : '0 0 0' }}>
        <button type="button" disabled={chapterIndex === 0} onClick={() => goChapter(chapterIndex - 1)} className="hidden shrink-0 rounded-full px-2.5 py-1 font-medium hov-tint disabled:opacity-35 sm:block">‹ Prev chapter</button>
        <span className="w-16 shrink-0 tabular-nums">{page + 1} / {chapter.pages}</span>
        <Range label="Page" className="min-w-0 flex-1" min={0} max={Math.max(1, chapter.pages - 1)} value={mode === 'rtl' && paged ? chapter.pages - 1 - page : page} style={mode === 'rtl' && paged ? { transform: 'scaleX(-1)' } : undefined}
          onChange={(v) => { const target = mode === 'rtl' && paged ? chapter.pages - 1 - v : v; setPage(target); if (!paged) scroller.current?.querySelector<HTMLElement>(`[data-page="${target}"]`)?.scrollIntoView({ block: 'start' }); }} />
        <span className="hidden shrink-0 opacity-65 md:block">{mode === 'rtl' ? 'Right → left' : mode === 'ltr' ? 'Left → right' : mode === 'vertical' ? 'Vertical' : 'Webtoon'}</span>
        <button type="button" disabled={chapterIndex >= chapters.length - 1} onClick={() => goChapter(chapterIndex + 1)} className="hidden shrink-0 rounded-full px-2.5 py-1 font-medium hov-tint disabled:opacity-35 sm:block">Next chapter ›</button>
      </footer>
    </div>
  );
}
