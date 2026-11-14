import { Check, ListVideo, Puzzle, ShieldCheck, SkipForward, X } from 'lucide-react';
import { useCallback, useEffect, useMemo, useState } from 'react';
import { useNavigate, useParams, useSearchParams } from 'react-router-dom';
import { debridMatches, getDebrid, type DebridFile } from '../adapters/providers';
import { ensureRemote, getSelectedStream, selectStream, StreamOptions, useAddonSubtitles } from '../components/addons';
import { isRemoteId } from '../adapters/stremio';
import { resolveOption } from '../adapters/realdebrid';
import { Badge, Button, EmptyState, Spinner } from '../components/ui';
import { VideoPlayer } from '../components/VideoPlayer';
import { blobUrl } from '../lib/idb';
import { cn, fmtRuntime } from '../lib/utils';
import { useItem } from '../store/catalog';
import { useStore } from '../store/useStore';
import type { Episode, MediaItem, StreamSource } from '../types';

export const allEpisodes = (item: MediaItem) => item.tv?.seasons.flatMap((s) => s.episodes.map((e) => ({ episode: e, season: s.number }))) ?? [];

/** Pick a source that best matches Settings → Playback → Preferred quality. */
export function pickSource(sources: StreamSource[], preferred: string, id?: string | null): StreamSource | undefined {
  if (id) { const hit = sources.find((s) => s.id === id); if (hit) return hit; }
  if (preferred !== 'auto') { const hit = sources.find((s) => s.quality.includes(preferred.replace('p', ''))); if (hit) return hit; }
  return sources[0];
}

export default function WatchPage() {
  const { id = '' } = useParams();
  const [params, setParams] = useSearchParams();
  const navigate = useNavigate();
  const item = useItem(id);
  const prefs = useStore((s) => s.settings.playback);
  const connected = useStore((s) => s.debrid);
  const [panel, setPanel] = useState(false);
  const [url, setUrl] = useState<string | null>(null);
  const [resolving, setResolving] = useState<string | null>(null);
  const [resolveError, setResolveError] = useState<string | null>(null);
  const [debridFiles, setDebridFiles] = useState<{ adapterId: string; adapterName: string; file: DebridFile }[]>([]);
  const [ended, setEnded] = useState(false);

  const episodes = useMemo(() => (item ? allEpisodes(item) : []), [item]);
  const epParam = params.get('ep');
  const current: { episode: Episode; season: number } | undefined = useMemo(() => {
    if (!item || item.type !== 'tv') return undefined;
    const saved = useStore.getState().progress[item.id]?.unit;
    return episodes.find((e) => e.episode.id === (epParam ?? saved)) ?? episodes[0];
  }, [item, episodes, epParam]);
  const sources = item?.type === 'tv' ? current?.episode.sources ?? [] : item?.video?.sources ?? [];
  const fileParam = params.get('file');
  const source = pickSource(sources, prefs.quality, params.get('src'));
  const unit = current?.episode.id;
  const addonSubs = useAddonSubtitles(item, unit);
  // Streams chosen from an addon (?sx=1) are kept per title/episode for this tab.
  const sxChosen = params.get('sx') ? getSelectedStream(item?.id ?? '', unit) : null;
  // A remote title opened by link (or after a reload) isn't in the store yet: fetch it by id.
  useEffect(() => { if (!item && isRemoteId(id)) void ensureRemote(id).catch(() => {}); }, [id, item]);

  // Resume position is read once per item/episode so saving progress doesn't restart playback.
  const startAt = useMemo(() => {
    if (!item || !prefs.resume) return 0;
    const p = useStore.getState().progress[item.id];
    if (!p || p.percent >= 97) return 0;
    if (item.type === 'tv') return p.unit === unit ? p.position : p.units?.[unit ?? '']?.done ? 0 : p.units?.[unit ?? '']?.position ?? 0;
    return p.position;
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [item?.id, unit, prefs.resume]);

  // Which connected (demo) debrid accounts hold this title?
  useEffect(() => {
    let cancelled = false;
    const ids = Object.keys(connected).filter((k) => connected[k].connected);
    if (!item || !ids.length) { setDebridFiles([]); return; }
    debridMatches(ids, item.id, unit).then((m) => { if (!cancelled) setDebridFiles(m.map((x) => ({ adapterId: x.adapter.id, adapterName: x.adapter.name, file: x.file }))); });
    return () => { cancelled = true; };
  }, [item, unit, connected]);

  // Resolve the playable URL: direct source, IndexedDB import, or an authorised link from a debrid adapter.
  useEffect(() => {
    let cancelled = false;
    setUrl(null); setResolveError(null); setEnded(false);
    (async () => {
      try {
        if (sxChosen?.via === 'realdebrid') {
          setResolving('Preparing through Real-Debrid…');
          const r = await resolveOption(sxChosen, { onStatus: (s) => { if (!cancelled) setResolving(s); }, cancelled: () => cancelled });
          if (!cancelled) setUrl(r.playUrl);
        } else if (sxChosen?.url) {
          if (!cancelled) setUrl(sxChosen.url);
        } else if (fileParam) {
          const adapter = getDebrid(fileParam.split(':')[0]);
          if (!adapter) throw new Error('Unknown provider.');
          setResolving(`Requesting an authorised stream link from ${adapter.name} (demo adapter)…`);
          const link = await adapter.getStreamLink(fileParam);
          if (!cancelled) setUrl(link.url);
        } else if (source) {
          if (source.url.startsWith('idb:')) {
            setResolving('Loading your imported file from browser storage…');
            const local = await blobUrl(source.url.slice(4));
            if (!local) throw new Error('This imported file is no longer in browser storage. Import it again.');
            if (!cancelled) setUrl(local);
          } else if (!cancelled) setUrl(source.url);
        }
      } catch (e) {
        if (!cancelled) setResolveError((e as Error).message);
      } finally {
        if (!cancelled) setResolving(null);
      }
    })();
    return () => { cancelled = true; };
  }, [source?.id, source?.url, fileParam, sxChosen?.url, sxChosen?.key, sxChosen?.via]);

  useEffect(() => {
    if (!item) return;
    const s = useStore.getState();
    s.touch(item.id);
    s.logHistory({ id: item.id, kind: 'watch', detail: current ? `S${current.season} · E${current.episode.number} ${current.episode.title}` : undefined });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [item?.id, unit]);

  const onProgress = useCallback((time: number, duration: number) => {
    if (!item) return;
    const s = useStore.getState();
    const fraction = duration ? time / duration : 0;
    if (item.type === 'tv' && unit) {
      const prev = s.progress[item.id];
      const units = { ...prev?.units, [unit]: { position: time, done: fraction > 0.95 || !!prev?.units?.[unit]?.done } };
      const total = Math.max(1, episodes.length);
      const done = episodes.filter((e) => units[e.episode.id]?.done).length;
      s.setProgress(item.id, { percent: Math.min(100, ((done + (units[unit].done ? 0 : fraction)) / total) * 100), position: time, duration, unit, units });
    } else {
      s.setProgress(item.id, { percent: fraction * 100, position: time, duration });
    }
  }, [item, unit, episodes]);

  const next = current ? episodes[episodes.findIndex((e) => e.episode.id === current.episode.id) + 1] : undefined;
  const go = (patch: Record<string, string | null>) => {
    const p = new URLSearchParams(params);
    Object.entries(patch).forEach(([k, v]) => (v === null ? p.delete(k) : p.set(k, v)));
    setParams(p, { replace: true });
  };
  const back = () => navigate(item ? `/item/${item.id}` : '/movies');

  if (!item && isRemoteId(id)) return <div className="flex h-full items-center justify-center bg-black"><Spinner className="h-8 w-8 text-white" /></div>;
  if (!item || (item.type !== 'movie' && item.type !== 'tv')) {
    return <div className="flex h-full items-center justify-center bg-bg p-6"><EmptyState icon={ListVideo} title="Nothing to play" body="That title could not be found." action={<Button variant="primary" onClick={() => navigate('/movies')}>Back to Movies & TV</Button>} /></div>;
  }
  const sub = current ? `Season ${current.season} · Episode ${current.episode.number} — ${current.episode.title}` : [item.releaseDate?.slice(0, 4), item.creator].filter(Boolean).join(' · ');
  const activeLabel = fileParam ? `${getDebrid(fileParam.split(':')[0])?.name} (demo)` : source ? `${source.provider} · ${source.quality}` : '';

  return (
    <div className="relative flex h-full bg-black">
      <div className="relative min-w-0 flex-1">
        {url ? (
          <VideoPlayer
            src={url} title={item.title} subtitle={sub} subtitles={[...addonSubs, ...(item.video?.subtitles ?? item.tv?.subtitles ?? [])]} startAt={startAt}
            onProgress={onProgress} onBack={back}
            onEnded={() => { if (next && prefs.autoplayNext) go({ ep: next.episode.id, src: null, file: null }); else setEnded(true); }}
            errorActions={<Button onClick={() => setPanel(true)} className="!bg-white/15 !text-white">Choose another source</Button>}
            topRight={<>
              {activeLabel && <span className="hidden rounded-full bg-white/10 px-3 py-1.5 text-xs text-white/80 backdrop-blur-sm md:block">{activeLabel}</span>}
              <button type="button" onClick={() => setPanel((p) => !p)} aria-expanded={panel} className="flex h-10 items-center gap-2 rounded-full bg-white/10 px-3.5 text-[0.8125rem] font-medium text-white backdrop-blur-sm hover:bg-white/20">
                <ListVideo className="h-4 w-4" /><span className="hidden sm:inline">{item.type === 'tv' ? 'Episodes & sources' : 'Sources'}</span>
              </button>
            </>}
          />
        ) : (
          <div className="flex h-full flex-col items-center justify-center gap-4 p-6 text-center text-white">
            {resolveError ? (
              <><p className="text-base font-semibold">Couldn’t open this source</p><p className="max-w-md text-sm text-white/65">{resolveError}</p></>
            ) : sources.length || fileParam || sxChosen ? (
              <><Spinner className="h-8 w-8 text-white" /><p className="text-sm text-white/70">{resolving ?? 'Preparing stream…'}</p></>
            ) : item.remote ? (
              <div className="w-full max-w-xl text-left">
                <p className="mb-3 text-center text-base font-semibold">Choose a stream</p>
                <StreamOptions item={item} videoId={unit} dark onPlay={(o) => { selectStream(item.id, unit, o); go({ sx: '1', src: null, file: null }); }} />
              </div>
            ) : (
              <><p className="text-base font-semibold">No source attached</p><p className="max-w-md text-sm text-white/65">This entry has metadata but nothing to play yet. Import a video file from its page, or connect a source that provides it.</p></>
            )}
            <Button onClick={back} className="!bg-white/15 !text-white">Back to details</Button>
          </div>
        )}
        {ended && (
          <div className="absolute inset-0 flex flex-col items-center justify-center gap-4 bg-black/85 text-white">
            <p className="text-lg font-semibold">Finished “{current ? current.episode.title : item.title}”</p>
            <div className="flex flex-wrap justify-center gap-2">
              {next && <Button variant="primary" icon={SkipForward} onClick={() => go({ ep: next.episode.id, src: null, file: null })}>Next: {next.episode.title}</Button>}
              <Button onClick={back} className="!bg-white/15 !text-white">Back to details</Button>
            </div>
          </div>
        )}
      </div>

      {panel && (
        <aside className="absolute inset-y-0 right-0 z-20 flex w-[22rem] max-w-full flex-col border-l border-white/10 bg-[#121217] text-white lg:static" aria-label="Sources and episodes">
          <div className="flex items-center justify-between border-b border-white/10 px-4 py-3">
            <p className="text-sm font-semibold">{item.type === 'tv' ? 'Episodes & sources' : 'Sources'}</p>
            <button type="button" aria-label="Close panel" onClick={() => setPanel(false)} className="flex h-8 w-8 items-center justify-center rounded-full hover:bg-white/10"><X className="h-4 w-4" /></button>
          </div>
          <div className="min-h-0 flex-1 space-y-5 overflow-y-auto p-3">
            <div>
              <p className="mb-2 px-1 text-[0.6875rem] font-semibold uppercase tracking-wider text-white/50">Direct sources</p>
              {sources.map((s) => {
                const on = !fileParam && s.id === source?.id;
                return (
                  <button key={s.id} type="button" onClick={() => go({ src: s.id, file: null, sx: null })} aria-pressed={on} className={cn('mb-1 flex w-full items-center gap-3 rounded-xl px-3 py-2.5 text-left', on ? 'bg-white/10' : 'hover:bg-white/[0.06]')}>
                    <span className="min-w-0 flex-1">
                      <span className="block truncate text-[0.8125rem] font-medium">{s.label}</span>
                      <span className="block truncate text-xs text-white/55">{s.quality} · {s.license}{s.sizeMB ? ` · ${s.sizeMB} MB` : ''}</span>
                    </span>
                    {on && <Check className="h-4 w-4 text-accent" />}
                  </button>
                );
              })}
              {!sources.length && <p className="px-1 text-xs text-white/50">No direct sources.</p>}
            </div>
            <div>
              <p className="mb-2 flex items-center gap-1.5 px-1 text-[0.6875rem] font-semibold uppercase tracking-wider text-white/50"><ShieldCheck className="h-3.5 w-3.5" />Your debrid files</p>
              {debridFiles.map(({ adapterName, file }) => {
                const on = fileParam === file.id;
                return (
                  <button key={file.id} type="button" onClick={() => go({ file: file.id, src: null, sx: null })} aria-pressed={on} className={cn('mb-1 flex w-full items-center gap-3 rounded-xl px-3 py-2.5 text-left', on ? 'bg-white/10' : 'hover:bg-white/[0.06]')}>
                    <span className="min-w-0 flex-1">
                      <span className="block truncate text-[0.8125rem] font-medium">{adapterName} <Badge tone="warn" className="ml-1">Demo adapter</Badge></span>
                      <span className="block truncate text-xs text-white/55">{file.name}</span>
                    </span>
                    {on && <Check className="h-4 w-4 text-accent" />}
                  </button>
                );
              })}
              {!debridFiles.length && <p className="px-1 text-xs leading-relaxed text-white/50">No matching files in a connected provider. Connect a demo account in Sources → Debrid providers.</p>}
            </div>
            {item.remote && (
              <div>
                <p className="mb-2 flex items-center gap-1.5 px-1 text-[0.6875rem] font-semibold uppercase tracking-wider text-white/50"><Puzzle className="h-3.5 w-3.5" />From your addons</p>
                <StreamOptions item={item} videoId={unit} dark onPlay={(o) => { selectStream(item.id, unit, o); go({ sx: '1', src: null, file: null }); }} />
              </div>
            )}
            {item.type === 'tv' && item.tv?.seasons.map((season) => (
              <div key={season.number}>
                <p className="mb-2 px-1 text-[0.6875rem] font-semibold uppercase tracking-wider text-white/50">Season {season.number}</p>
                {season.episodes.map((e) => {
                  const on = e.id === unit;
                  return (
                    <button key={e.id} type="button" onClick={() => go({ ep: e.id, src: null, file: null })} aria-current={on} className={cn('mb-1 flex w-full items-center gap-3 rounded-xl px-3 py-2.5 text-left', on ? 'bg-white/10' : 'hover:bg-white/[0.06]')}>
                      <span className="w-5 text-center text-xs tabular-nums text-white/50">{e.number}</span>
                      <span className="min-w-0 flex-1">
                        <span className={cn('block truncate text-[0.8125rem] font-medium', on && 'text-accent')}>{e.title}</span>
                        <span className="block text-xs text-white/55">{fmtRuntime(e.runtime)}</span>
                      </span>
                    </button>
                  );
                })}
              </div>
            ))}
          </div>
        </aside>
      )}
    </div>
  );
}
