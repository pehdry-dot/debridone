import { AlertTriangle, ArrowLeft, Captions, Check, Gauge, Maximize, Minimize, Pause, PictureInPicture2, Play, RotateCcw, RotateCw, Volume1, Volume2, VolumeX } from 'lucide-react';
import { useCallback, useEffect, useRef, useState, type ReactNode } from 'react';
import { clamp, cn, fmtTime } from '../lib/utils';
import { usePlayer } from '../store/player';
import { useStore } from '../store/useStore';
import type { SubtitleTrack } from '../types';
import { Button, Range, Spinner } from './ui';

interface Props {
  src: string;
  title: string;
  subtitle?: string;
  subtitles?: SubtitleTrack[];
  startAt?: number;
  onProgress?: (time: number, duration: number) => void;
  onEnded?: () => void;
  onBack: () => void;
  /** extra buttons rendered in the top-right corner (sources, episodes…) */
  topRight?: ReactNode;
  /** shown under the error message, e.g. a "try another source" button */
  errorActions?: ReactNode;
}

const RATES = [0.5, 0.75, 1, 1.25, 1.5, 2];
const isHlsUrl = (u: string) => /\.m3u8(\?|$)/i.test(u);
const CUE_SIZE = { sm: '0.9rem', md: '1.15rem', lg: '1.6rem' };

/**
 * Custom HTML5 video player: play/pause, seeking with buffer indicator, volume,
 * speed, subtitles (native text tracks), picture-in-picture, fullscreen,
 * resume position and keyboard control.
 */
export function VideoPlayer({ src, title, subtitle, subtitles = [], startAt = 0, onProgress, onEnded, onBack, topRight, errorActions }: Props) {
  const prefs = useStore((s) => s.settings.playback);
  const box = useRef<HTMLDivElement>(null);
  const video = useRef<HTMLVideoElement>(null);
  const hideTimer = useRef<number>();
  const lastReport = useRef(0);
  const [playing, setPlaying] = useState(false);
  const [waiting, setWaiting] = useState(true);
  const [time, setTime] = useState(0);
  const [duration, setDuration] = useState(0);
  const [buffered, setBuffered] = useState(0);
  const [volume, setVolume] = useState(1);
  const [muted, setMuted] = useState(false);
  const [rate, setRate] = useState(prefs.rate);
  const [sub, setSub] = useState<string>(prefs.subtitlesOn ? (subtitles.find((t) => t.lang === prefs.subtitleLang)?.id ?? subtitles[0]?.id ?? 'off') : 'off');
  const [menu, setMenu] = useState<'speed' | 'subs' | null>(null);
  const [controls, setControls] = useState(true);
  const [fullscreen, setFullscreen] = useState(false);
  const [error, setError] = useState(false);
  const [attempt, setAttempt] = useState(0);

  const report = useCallback(() => {
    const v = video.current;
    if (v && isFinite(v.duration) && v.duration > 0) onProgress?.(v.currentTime, v.duration);
  }, [onProgress]);

  const poke = useCallback(() => {
    setControls(true);
    window.clearTimeout(hideTimer.current);
    hideTimer.current = window.setTimeout(() => { if (video.current && !video.current.paused) { setControls(false); setMenu(null); } }, 2800);
  }, []);

  // Reset per source; save position when leaving.
  useEffect(() => {
    setError(false); setWaiting(true); setTime(0); setDuration(0); setBuffered(0);
    box.current?.focus();
    return () => { report(); window.clearTimeout(hideTimer.current); };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [src, attempt]);

  useEffect(() => {
    const onFs = () => setFullscreen(document.fullscreenElement === box.current);
    document.addEventListener('fullscreenchange', onFs);
    return () => document.removeEventListener('fullscreenchange', onFs);
  }, []);

  // Subtitles: toggle the browser's native text tracks.
  useEffect(() => {
    const v = video.current;
    if (!v) return;
    const apply = () => { for (let i = 0; i < v.textTracks.length; i++) v.textTracks[i].mode = subtitles[i]?.id === sub ? 'showing' : 'disabled'; };
    apply();
    v.textTracks.addEventListener?.('addtrack', apply);
    return () => v.textTracks.removeEventListener?.('addtrack', apply);
  }, [sub, subtitles, src, attempt]);

  useEffect(() => { if (video.current) video.current.playbackRate = rate; }, [rate, src, attempt]);

  // HLS playlists (Real-Debrid's transcoded streams) play through hls.js; everything else through the native element.
  useEffect(() => {
    const v = video.current;
    if (!v || !isHlsUrl(src)) return;
    let instance: { destroy(): void } | null = null;
    let stopped = false;
    (async () => {
      const mod = await import('hls.js');
      const Hls = mod.default as any;
      if (stopped) return;
      if (!Hls.isSupported()) {
        if (v.canPlayType('application/vnd.apple.mpegurl')) v.src = src;
        else setError(true);
        return;
      }
      const hls = new Hls({ enableWorker: false, maxBufferLength: 60 });
      hls.on(Hls.Events.ERROR, (_e: unknown, data: { fatal?: boolean }) => { if (data.fatal) setError(true); });
      hls.loadSource(src);
      hls.attachMedia(v);
      instance = hls;
    })().catch(() => setError(true));
    return () => { stopped = true; instance?.destroy(); };
  }, [src, attempt]);

  const toggle = () => { const v = video.current; if (!v) return; if (v.paused) v.play().catch(() => {}); else v.pause(); };
  const seek = (t: number) => { const v = video.current; if (!v || !isFinite(v.duration)) return; v.currentTime = clamp(t, 0, v.duration); setTime(v.currentTime); };
  const changeVolume = (val: number) => { const v = video.current; if (!v) return; v.volume = clamp(val, 0, 1); v.muted = false; };
  const toggleMute = () => { const v = video.current; if (v) v.muted = !v.muted; };
  const toggleFullscreen = () => {
    if (document.fullscreenElement) document.exitFullscreen().catch(() => {});
    else box.current?.requestFullscreen?.().catch(() => {});
  };
  const togglePip = () => {
    const v = video.current;
    if (!v) return;
    if (document.pictureInPictureElement) document.exitPictureInPicture().catch(() => {});
    else v.requestPictureInPicture?.().catch(() => {});
  };

  const onKey = (e: React.KeyboardEvent) => {
    if ((e.target as HTMLElement).tagName === 'INPUT' && e.key !== 'Escape') return;
    const skip = prefs.skipSeconds;
    const map: Record<string, () => void> = {
      ' ': toggle, k: toggle, ArrowLeft: () => seek(time - skip), ArrowRight: () => seek(time + skip), j: () => seek(time - skip), l: () => seek(time + skip),
      ArrowUp: () => changeVolume(volume + 0.1), ArrowDown: () => changeVolume(volume - 0.1), m: toggleMute, f: toggleFullscreen,
      c: () => setSub((s) => (s === 'off' ? subtitles[0]?.id ?? 'off' : 'off')),
      Escape: () => { if (menu) setMenu(null); else if (!document.fullscreenElement) onBack(); },
    };
    const fn = map[e.key];
    if (fn) { e.preventDefault(); fn(); poke(); }
  };

  const level = muted ? 0 : volume;
  const shown = controls || !playing || !!menu;
  const pipSupported = typeof document !== 'undefined' && 'pictureInPictureEnabled' in document && document.pictureInPictureEnabled;

  return (
    <div ref={box} tabIndex={0} onKeyDown={onKey} onMouseMove={poke} onTouchStart={poke} role="region" aria-label={`Video player: ${title}`}
      className={cn('relative h-full w-full overflow-hidden bg-black text-white outline-none', !shown && 'cursor-none')} style={{ ['--cue-size' as string]: CUE_SIZE[prefs.subtitleSize] }}>
      <video
        key={`${src}#${attempt}`} ref={video} src={isHlsUrl(src) ? undefined : src} className="h-full w-full" playsInline autoPlay preload="metadata"
        onClick={toggle} onDoubleClick={toggleFullscreen}
        onLoadedMetadata={(e) => {
          const v = e.currentTarget;
          setDuration(v.duration);
          if (startAt > 1 && startAt < v.duration - 3) v.currentTime = startAt;
          setVolume(v.volume); setMuted(v.muted);
        }}
        onPlay={() => { setPlaying(true); poke(); if (prefs.pauseMusicOnVideo) usePlayer.getState().pause(); }}
        onPause={() => { setPlaying(false); setControls(true); report(); }}
        onWaiting={() => setWaiting(true)} onPlaying={() => setWaiting(false)} onCanPlay={() => setWaiting(false)}
        onTimeUpdate={(e) => {
          const v = e.currentTarget;
          setTime(v.currentTime);
          if (v.buffered.length) setBuffered(v.buffered.end(v.buffered.length - 1));
          if (performance.now() - lastReport.current > 4000) { lastReport.current = performance.now(); report(); }
        }}
        onVolumeChange={(e) => { setVolume(e.currentTarget.volume); setMuted(e.currentTarget.muted); }}
        onEnded={() => { report(); onEnded?.(); }}
        onError={() => { setError(true); setWaiting(false); }}
      >
        {subtitles.map((t) => <track key={t.id} kind="subtitles" src={t.src} srcLang={t.lang} label={t.label} />)}
      </video>

      {waiting && !error && <div className="pointer-events-none absolute inset-0 flex items-center justify-center"><Spinner className="h-10 w-10 text-white" /></div>}

      {error && (
        <div className="absolute inset-0 flex flex-col items-center justify-center gap-3 bg-black/85 p-6 text-center">
          <AlertTriangle className="h-8 w-8 text-amber-400" />
          <p className="text-base font-semibold">This stream couldn’t be loaded</p>
          <p className="max-w-md text-sm text-white/65">The host may be offline or slow, your device may be offline, or the browser may not support this file format. Sample streams come from third-party hosts DebridOne does not control.</p>
          <div className="mt-2 flex flex-wrap justify-center gap-2">
            <Button variant="primary" icon={RotateCcw} onClick={() => setAttempt((a) => a + 1)}>Try again</Button>
            {errorActions}
            <Button onClick={onBack} className="!bg-white/15 !text-white">Go back</Button>
          </div>
        </div>
      )}

      {/* Top bar */}
      <div className={cn('absolute inset-x-0 top-0 flex items-start gap-3 bg-gradient-to-b from-black/80 to-transparent p-3 pb-10 transition-opacity duration-200 sm:p-4 sm:pb-12', shown ? 'opacity-100' : 'pointer-events-none opacity-0')}>
        <button type="button" onClick={onBack} aria-label="Back" className="flex h-10 w-10 shrink-0 items-center justify-center rounded-full bg-white/10 backdrop-blur-sm hover:bg-white/20"><ArrowLeft className="h-5 w-5" /></button>
        <div className="min-w-0 flex-1 pt-0.5">
          <p className="truncate text-base font-semibold sm:text-lg">{title}</p>
          {subtitle && <p className="truncate text-xs text-white/65 sm:text-sm">{subtitle}</p>}
        </div>
        <div className="flex shrink-0 items-center gap-2">{topRight}</div>
      </div>

      {/* Centre play button when paused */}
      {!playing && !waiting && !error && (
        <button type="button" onClick={toggle} aria-label="Play" className="absolute left-1/2 top-1/2 flex h-20 w-20 -translate-x-1/2 -translate-y-1/2 items-center justify-center rounded-full bg-white/15 backdrop-blur-md transition-transform hover:scale-105">
          <Play className="ml-1 h-8 w-8 fill-current" />
        </button>
      )}

      {/* Bottom controls */}
      <div className={cn('absolute inset-x-0 bottom-0 bg-gradient-to-t from-black/90 via-black/50 to-transparent px-3 pb-3 pt-14 transition-opacity duration-200 sm:px-5 sm:pb-4', shown && !error ? 'opacity-100' : 'pointer-events-none opacity-0')}
        style={{ ['--line' as string]: '255 255 255' }}>
        <div className="relative flex h-4 items-center">
          <div className="pointer-events-none absolute inset-x-0 top-1/2 h-1 -translate-y-1/2 overflow-hidden rounded-full"><div className="h-full bg-white/25" style={{ width: `${duration ? (buffered / duration) * 100 : 0}%` }} /></div>
          <Range label="Seek" className="relative w-full" min={0} max={Math.max(duration, 0.1)} step={0.1} value={Math.min(time, duration || 0)} onChange={seek} />
        </div>
        <div className="mt-1.5 flex items-center gap-1 sm:gap-2">
          <CtlButton label={playing ? 'Pause (space)' : 'Play (space)'} onClick={toggle}>{playing ? <Pause className="h-5 w-5 fill-current" /> : <Play className="h-5 w-5 fill-current" />}</CtlButton>
          <CtlButton label={`Back ${prefs.skipSeconds} seconds`} onClick={() => seek(time - prefs.skipSeconds)}><RotateCcw className="h-[1.1rem] w-[1.1rem]" /></CtlButton>
          <CtlButton label={`Forward ${prefs.skipSeconds} seconds`} onClick={() => seek(time + prefs.skipSeconds)}><RotateCw className="h-[1.1rem] w-[1.1rem]" /></CtlButton>
          <div className="group flex items-center">
            <CtlButton label={muted ? 'Unmute (m)' : 'Mute (m)'} onClick={toggleMute}>{level === 0 ? <VolumeX className="h-5 w-5" /> : level < 0.5 ? <Volume1 className="h-5 w-5" /> : <Volume2 className="h-5 w-5" />}</CtlButton>
            <Range label="Volume" className="hidden w-20 sm:block" min={0} max={1} step={0.02} value={level} onChange={changeVolume} />
          </div>
          <span className="ml-1 text-xs tabular-nums text-white/80 sm:text-[0.8125rem]">{fmtTime(time)} <span className="text-white/45">/ {fmtTime(duration)}</span></span>
          <div className="ml-auto flex items-center gap-1 sm:gap-2">
            <div className="relative">
              <CtlButton label="Subtitles (c)" active={sub !== 'off'} onClick={() => setMenu(menu === 'subs' ? null : 'subs')} expanded={menu === 'subs'}><Captions className="h-5 w-5" /></CtlButton>
              {menu === 'subs' && (
                <Popover title="Subtitles">
                  <PopItem active={sub === 'off'} onClick={() => { setSub('off'); setMenu(null); }}>Off</PopItem>
                  {subtitles.map((t) => <PopItem key={t.id} active={sub === t.id} onClick={() => { setSub(t.id); setMenu(null); }}>{t.label}</PopItem>)}
                  {!subtitles.length && <p className="px-3 py-2 text-xs text-white/55">No subtitle tracks are available for this video.</p>}
                </Popover>
              )}
            </div>
            <div className="relative">
              <CtlButton label="Playback speed" active={rate !== 1} onClick={() => setMenu(menu === 'speed' ? null : 'speed')} expanded={menu === 'speed'}>
                <Gauge className="h-5 w-5" />{rate !== 1 && <span className="ml-1 text-xs font-semibold">{rate}×</span>}
              </CtlButton>
              {menu === 'speed' && (
                <Popover title="Playback speed">
                  {RATES.map((r) => <PopItem key={r} active={rate === r} onClick={() => { setRate(r); setMenu(null); }}>{r === 1 ? 'Normal' : `${r}×`}</PopItem>)}
                </Popover>
              )}
            </div>
            {pipSupported && <CtlButton label="Picture-in-picture" onClick={togglePip}><PictureInPicture2 className="h-5 w-5" /></CtlButton>}
            <CtlButton label={fullscreen ? 'Exit fullscreen (f)' : 'Fullscreen (f)'} onClick={toggleFullscreen}>{fullscreen ? <Minimize className="h-5 w-5" /> : <Maximize className="h-5 w-5" />}</CtlButton>
          </div>
        </div>
      </div>
    </div>
  );
}

function CtlButton({ label, onClick, children, active, expanded }: { label: string; onClick: () => void; children: ReactNode; active?: boolean; expanded?: boolean }) {
  return (
    <button type="button" aria-label={label} title={label} aria-expanded={expanded} onClick={onClick}
      className={cn('flex h-9 min-w-9 items-center justify-center rounded-full px-2 transition-colors hover:bg-white/15', active ? 'text-accent' : 'text-white')}>
      {children}
    </button>
  );
}
function Popover({ title, children }: { title: string; children: ReactNode }) {
  return (
    <div role="menu" aria-label={title} className="absolute bottom-full right-0 mb-2 w-56 rounded-xl border border-white/10 bg-[#17171d]/95 p-1.5 shadow-pop backdrop-blur-md">
      <p className="px-3 pb-1 pt-1.5 text-[0.6875rem] font-semibold uppercase tracking-wider text-white/50">{title}</p>
      {children}
    </div>
  );
}
function PopItem({ active, onClick, children }: { active?: boolean; onClick: () => void; children: ReactNode }) {
  return (
    <button type="button" role="menuitemradio" aria-checked={active} onClick={onClick} className="flex w-full items-center justify-between rounded-lg px-3 py-2 text-left text-[0.8125rem] hover:bg-white/10">
      {children}{active && <Check className="h-4 w-4 text-accent" />}
    </button>
  );
}
