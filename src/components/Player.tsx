import { AnimatePresence, motion } from 'framer-motion';
import { ChevronDown, Heart, ListMusic, Maximize2, Pause, Play, Repeat, Repeat1, Shuffle, SkipBack, SkipForward, Trash2, Volume1, Volume2, VolumeX, X } from 'lucide-react';
import { Link } from 'react-router-dom';
import { cn, fmtTime } from '../lib/utils';
import { getItem, getTrack, useItem } from '../store/catalog';
import { usePlayer } from '../store/player';
import { toast, useUi } from '../store/ui';
import { useStore } from '../store/useStore';
import { Art, Cover } from './Cover';
import { IconButton, Range, Spinner } from './ui';

export function Equalizer({ className, paused }: { className?: string; paused?: boolean }) {
  return (
    <span className={cn('inline-flex h-3.5 items-end gap-[2px]', className)} aria-hidden>
      {[0, 0.25, 0.5].map((d) => <span key={d} className="w-[3px] origin-bottom rounded-sm bg-accent" style={{ height: '100%', animation: paused ? 'none' : `eq 0.9s ${d}s ease-in-out infinite`, transform: paused ? 'scaleY(0.4)' : undefined }} />)}
    </span>
  );
}

function useNow() {
  const id = usePlayer((s) => s.queue[s.index]);
  const track = useStore((s) => (id ? getTrack(id, s) : undefined));
  const album = useItem(track?.albumId);
  const liked = useStore((s) => !!(id && s.likedTracks[id]));
  return { id, track, album, liked };
}

function Transport({ large }: { large?: boolean }) {
  const playing = usePlayer((s) => s.playing);
  const loading = usePlayer((s) => s.loading);
  const shuffle = usePlayer((s) => s.shuffle);
  const repeat = usePlayer((s) => s.repeat);
  const p = usePlayer.getState();
  return (
    <div className={cn('flex items-center justify-center', large ? 'gap-4' : 'gap-1.5')}>
      <IconButton label={shuffle ? 'Shuffle on' : 'Shuffle off'} icon={Shuffle} active={shuffle} size={large ? 'md' : 'sm'} onClick={p.toggleShuffle} />
      <IconButton label="Previous" icon={SkipBack} size={large ? 'lg' : 'md'} onClick={p.prev} className="[&_svg]:fill-current text-fg" />
      <button type="button" aria-label={playing ? 'Pause' : 'Play'} onClick={p.toggle}
        className={cn('flex shrink-0 items-center justify-center rounded-full bg-fg text-bg transition-transform hover:scale-105 active:scale-95', large ? 'h-16 w-16' : 'h-9 w-9')}>
        {loading ? <Spinner className={cn('text-bg', large ? 'h-6 w-6' : 'h-4 w-4')} /> : playing ? <Pause className={cn('fill-current', large ? 'h-6 w-6' : 'h-4 w-4')} /> : <Play className={cn('ml-0.5 fill-current', large ? 'h-6 w-6' : 'h-4 w-4')} />}
      </button>
      <IconButton label="Next" icon={SkipForward} size={large ? 'lg' : 'md'} onClick={() => p.next()} className="[&_svg]:fill-current text-fg" />
      <IconButton label={`Repeat: ${repeat}`} icon={repeat === 'one' ? Repeat1 : Repeat} active={repeat !== 'off'} size={large ? 'md' : 'sm'} onClick={p.cycleRepeat} />
    </div>
  );
}

function SeekBar({ className }: { className?: string }) {
  const time = usePlayer((s) => s.time);
  const duration = usePlayer((s) => s.duration);
  return (
    <div className={cn('flex items-center gap-2.5 text-[0.6875rem] tabular-nums text-muted', className)}>
      <span className="w-9 text-right">{fmtTime(time)}</span>
      <Range label="Seek" className="min-w-0 flex-1" min={0} max={Math.max(1, duration)} step={0.1} value={Math.min(time, duration)} onChange={(v) => usePlayer.getState().seek(v)} />
      <span className="w-9">{fmtTime(duration)}</span>
    </div>
  );
}

function VolumeControl({ className }: { className?: string }) {
  const volume = usePlayer((s) => s.volume);
  const muted = usePlayer((s) => s.muted);
  const level = muted ? 0 : volume;
  return (
    <div className={cn('flex items-center gap-1.5', className)}>
      <IconButton label={muted ? 'Unmute' : 'Mute'} icon={level === 0 ? VolumeX : level < 0.5 ? Volume1 : Volume2} size="sm" onClick={usePlayer.getState().toggleMute} />
      <Range label="Volume" className="w-24" min={0} max={1} step={0.01} value={level} onChange={(v) => usePlayer.getState().setVolume(v)} />
    </div>
  );
}

function LikeButton({ id, liked, size = 'sm' }: { id: string; liked: boolean; size?: 'sm' | 'md' }) {
  return (
    <IconButton label={liked ? 'Remove from Liked Songs' : 'Add to Liked Songs'} icon={Heart} active={liked} size={size}
      onClick={() => toast(useStore.getState().toggleLike(id) ? 'Added to Liked Songs' : 'Removed from Liked Songs', 'success')}
      className={liked ? '[&_svg]:fill-current' : undefined} />
  );
}

/** Persistent bottom player. Rendered by the layout whenever the queue is non-empty. */
export function PlayerBar() {
  const { id, track, album, liked } = useNow();
  const time = usePlayer((s) => s.time);
  const duration = usePlayer((s) => s.duration);
  const playing = usePlayer((s) => s.playing);
  const open = () => useUi.setState({ nowPlaying: true });
  if (!id || !track) return null;
  return (
    <div className="fx-glass relative z-40 shrink-0 border-t border-line/[0.07] bg-panel" role="region" aria-label="Music player">
      {/* Mobile: compact bar */}
      <div className="md:hidden">
        <div className="absolute inset-x-0 top-0 h-0.5 bg-line/10"><div className="h-full bg-accent" style={{ width: `${duration ? (time / duration) * 100 : 0}%` }} /></div>
        <div className="flex h-14 items-center gap-3 px-3">
          <button type="button" onClick={open} className="flex min-w-0 flex-1 items-center gap-3 text-left" aria-label="Open Now Playing">
            {album && <Cover item={album} text={false} className="h-10 w-10 shrink-0 rounded-md" />}
            <span className="min-w-0">
              <span className="block truncate text-[0.8125rem] font-medium">{track.title}</span>
              <span className="block truncate text-xs text-muted">{track.artist}</span>
            </span>
          </button>
          <LikeButton id={id} liked={liked} size="md" />
          <IconButton label={playing ? 'Pause' : 'Play'} icon={playing ? Pause : Play} onClick={usePlayer.getState().toggle} className="text-fg [&_svg]:fill-current" />
          <IconButton label="Next" icon={SkipForward} onClick={() => usePlayer.getState().next()} className="text-fg [&_svg]:fill-current" />
        </div>
      </div>
      {/* Desktop */}
      <div className="hidden h-[4.75rem] grid-cols-[minmax(0,1fr)_minmax(0,1.6fr)_minmax(0,1fr)] items-center gap-4 px-4 md:grid">
        <div className="flex min-w-0 items-center gap-3">
          <button type="button" onClick={open} aria-label="Open Now Playing" className="group relative shrink-0">
            {album && <Cover item={album} text={false} className="h-12 w-12 rounded-md shadow-lift" />}
            <span className="absolute inset-0 flex items-center justify-center rounded-md bg-black/50 opacity-0 transition-opacity group-hover:opacity-100"><Maximize2 className="h-4 w-4 text-white" /></span>
          </button>
          <div className="min-w-0">
            <Link to={`/item/${track.albumId}`} className="block truncate text-[0.8125rem] font-medium hover:underline">{track.title}</Link>
            <p className="truncate text-xs text-muted">{track.artist}</p>
          </div>
          <LikeButton id={id} liked={liked} />
        </div>
        <div className="flex min-w-0 flex-col gap-1">
          <Transport />
          <SeekBar />
        </div>
        <div className="flex items-center justify-end gap-1">
          <IconButton label="Queue" icon={ListMusic} size="sm" onClick={open} />
          <VolumeControl />
          <IconButton label="Expand Now Playing" icon={Maximize2} size="sm" onClick={open} />
        </div>
      </div>
    </div>
  );
}

function QueueList() {
  const queue = usePlayer((s) => s.queue);
  const index = usePlayer((s) => s.index);
  const playing = usePlayer((s) => s.playing);
  const state = useStore.getState();
  return (
    <ol className="space-y-0.5">
      {queue.map((id, i) => {
        const t = getTrack(id, state);
        if (!t) return null;
        const album = getItem(t.albumId, state);
        const current = i === index;
        return (
          <li key={`${id}-${i}`} className={cn('group flex items-center gap-3 rounded-btn px-2 py-1.5', current ? 'bg-white/10' : 'hover:bg-white/[0.06]')}>
            <button type="button" className="flex min-w-0 flex-1 items-center gap-3 text-left" onClick={() => usePlayer.getState().jump(i)} aria-label={`Play ${t.title}`} aria-current={current}>
              <span className="relative shrink-0">
                {album && <Cover item={album} text={false} className="h-10 w-10 rounded-md" />}
                {current && <span className="absolute inset-0 flex items-center justify-center rounded-md bg-black/55"><Equalizer paused={!playing} /></span>}
              </span>
              <span className="min-w-0 flex-1">
                <span className={cn('block truncate text-[0.8125rem] font-medium', current && 'text-accent')}>{t.title}</span>
                <span className="block truncate text-xs text-white/55">{t.artist}</span>
              </span>
              <span className="text-xs tabular-nums text-white/50">{fmtTime(t.duration)}</span>
            </button>
            {!current && <IconButton label={`Remove ${t.title} from queue`} icon={X} size="sm" onClick={() => usePlayer.getState().removeAt(i)} className="text-white/60 opacity-0 hover:bg-white/10 hover:text-white focus-visible:opacity-100 group-hover:opacity-100" />}
          </li>
        );
      })}
    </ol>
  );
}

/** Full-screen expanded player with the queue. */
export function NowPlaying() {
  const open = useUi((s) => s.nowPlaying);
  const { id, track, album, liked } = useNow();
  const count = usePlayer((s) => s.queue.length);
  const close = () => useUi.setState({ nowPlaying: false });
  return (
    <AnimatePresence>
      {open && id && track && (
        <motion.div role="dialog" aria-modal="true" aria-label="Now Playing" className="fixed inset-0 z-[70] overflow-clip bg-[#0b0b0f] text-white"
          initial={{ y: '100%' }} animate={{ y: 0 }} exit={{ y: '100%' }} transition={{ type: 'spring', damping: 34, stiffness: 320 }}
          onKeyDown={(e) => e.key === 'Escape' && close()}>
          {album && <Art seed={album.id} palette={album.palette} shade={false} className="scale-150 opacity-60 blur-3xl" />}
          <div className="absolute inset-0 bg-gradient-to-b from-black/40 via-black/55 to-black/85" />
          <div className="relative flex h-full flex-col">
            <div className="flex items-center justify-between px-4 py-3 md:px-8">
              <IconButton label="Close Now Playing" icon={ChevronDown} onClick={close} autoFocus className="text-white hover:bg-white/10" />
              <p className="label !text-white/60">Now Playing</p>
              <IconButton label="Clear queue" icon={Trash2} onClick={() => { usePlayer.getState().clear(); close(); toast('Queue cleared'); }} className="text-white/70 hover:bg-white/10 hover:text-white" />
            </div>
            <div className="grid min-h-0 flex-1 grid-cols-1 gap-8 overflow-y-auto px-6 pb-8 md:px-12 lg:grid-cols-[minmax(0,1fr)_minmax(0,26rem)] lg:overflow-hidden">
              <div className="flex flex-col items-center justify-center gap-6 lg:min-h-0">
                {album && <Cover item={album} className="aspect-square w-full max-w-[min(26rem,48vh)] rounded-[calc(var(--radius)*1.2)] shadow-pop" />}
                <div className="w-full max-w-md">
                  <div className="flex items-center justify-between gap-4">
                    <div className="min-w-0">
                      <h2 className="truncate text-2xl font-semibold tracking-tight">{track.title}</h2>
                      <Link to={`/item/${track.albumId}`} onClick={close} className="block truncate text-white/60 hover:text-white hover:underline">{track.artist} — {album?.title}</Link>
                    </div>
                    <LikeButton id={id} liked={liked} size="md" />
                  </div>
                  <div className="mt-5 [--line:255_255_255] [&_span]:text-white/60"><SeekBar /></div>
                  <div className="mt-4 [&_button:not(.bg-fg)]:text-white/80 [&_.bg-fg]:bg-white [&_.bg-fg]:text-black"><Transport large /></div>
                  <div className="mt-5 flex justify-center [--line:255_255_255] [&_button]:text-white/70"><VolumeControl /></div>
                </div>
              </div>
              <div className="flex min-h-0 flex-col rounded-[calc(var(--radius)*1.2)] bg-white/[0.06] p-4 backdrop-blur-md lg:mb-4">
                <div className="mb-3 flex items-center justify-between px-2">
                  <h3 className="text-sm font-semibold">Queue</h3>
                  <span className="text-xs text-white/50">{count} track{count === 1 ? '' : 's'}</span>
                </div>
                <div className="min-h-0 flex-1 overflow-y-auto pr-1"><QueueList /></div>
              </div>
            </div>
          </div>
        </motion.div>
      )}
    </AnimatePresence>
  );
}
