import { Disc3, Download, Heart, ListEnd, ListPlus, ListStart, Play, Trash2 } from 'lucide-react';
import { Link, useNavigate } from 'react-router-dom';
import { getSongBlob } from '../lib/synth';
import { cn, fmtTime } from '../lib/utils';
import { getItem } from '../store/catalog';
import { startDownload } from '../store/downloads';
import { usePlayer } from '../store/player';
import { toast } from '../store/ui';
import { useStore } from '../store/useStore';
import type { Track } from '../types';
import { Thumb } from './media';
import { Equalizer } from './Player';
import { Menu, type MenuItem } from './ui';

export function downloadTrack(track: Track) {
  if (!track.spec) { toast('Imported files are already on your device, so there is nothing to download.', 'info'); return; }
  const spec = track.spec;
  void startDownload({
    title: `${track.artist} — ${track.title}`, fileName: `${track.artist} - ${track.title}.wav`.replace(/[\\/:*?"<>|]/g, ''),
    source: 'DebridOne generated audio', kind: 'generated', itemId: track.albumId, generate: () => getSongBlob(spec),
  });
  toast('Rendering and downloading WAV…');
}

/** Song table used by albums, playlists, the Songs tab and search. Clicking a row plays the whole list from that song. */
export function TrackList({ tracks, showAlbum, showArt, onRemove, className }: { tracks: Track[]; showAlbum?: boolean; showArt?: boolean; onRemove?: (index: number) => void; className?: string }) {
  const navigate = useNavigate();
  const currentId = usePlayer((s) => s.queue[s.index]);
  const playing = usePlayer((s) => s.playing);
  const liked = useStore((s) => s.likedTracks);
  const playlists = useStore((s) => s.playlists);
  const state = useStore.getState();
  const ids = tracks.map((t) => t.id);

  return (
    <div className={cn('text-[0.8125rem]', className)} role="table" aria-label="Songs">
      {tracks.map((t, i) => {
        const current = t.id === currentId;
        const album = getItem(t.albumId, state);
        const userLists = playlists.filter((p) => !p.demo);
        const menu: (MenuItem | 'divider')[] = [
          { label: 'Play next', icon: ListStart, onClick: () => { usePlayer.getState().enqueue(t.id, true); toast(`“${t.title}” will play next`); } },
          { label: 'Add to queue', icon: ListEnd, onClick: () => { usePlayer.getState().enqueue(t.id); toast(`Added “${t.title}” to the queue`); } },
          'divider',
          ...userLists.slice(0, 5).map((p): MenuItem => ({ label: `Add to “${p.name}”`, icon: ListPlus, onClick: () => { state.updatePlaylist(p.id, { trackIds: [...p.trackIds, t.id] }); toast(`Added to “${p.name}”`, 'success'); } })),
          { label: 'Add to new playlist', icon: ListPlus, onClick: () => { const id = state.createPlaylist(`${t.title} mix`, [t.id]); toast('Playlist created', 'success', { label: 'Open', run: () => navigate(`/music/playlist/${id}`) }); } },
          'divider',
          { label: 'Go to album', icon: Disc3, onClick: () => navigate(`/item/${t.albumId}`) },
          { label: t.spec ? 'Download WAV' : 'Download (already local)', icon: Download, disabled: !t.spec, onClick: () => downloadTrack(t) },
          ...(onRemove ? ['divider' as const, { label: 'Remove from this playlist', icon: Trash2, danger: true, onClick: () => onRemove(i) }] : []),
        ];
        return (
          <div key={`${t.id}-${i}`} role="row" className={cn('group flex items-center gap-3 rounded-btn px-2 py-1.5 transition-colors hover:bg-line/[0.06]', current && 'bg-line/[0.06]')}>
            <button type="button" onClick={() => (current ? usePlayer.getState().toggle() : usePlayer.getState().play(ids, i))} aria-label={`${current && playing ? 'Pause' : 'Play'} ${t.title}`}
              className="relative flex h-9 w-9 shrink-0 items-center justify-center rounded-md text-muted">
              {showArt && album && <span className="absolute inset-0"><Thumb item={album} className="h-full w-full !rounded-md" /></span>}
              {current
                ? <span className={cn('relative flex h-full w-full items-center justify-center rounded-md', showArt && 'bg-black/55')}><Equalizer paused={!playing} /></span>
                : <>
                    {!showArt && <span className="tabular-nums group-hover:hidden">{i + 1}</span>}
                    <span className={cn('relative hidden h-full w-full items-center justify-center rounded-md group-hover:flex', showArt && 'bg-black/55 text-white')}><Play className="h-3.5 w-3.5 fill-current" /></span>
                  </>}
            </button>
            <button type="button" className="min-w-0 flex-1 text-left" onDoubleClick={() => usePlayer.getState().play(ids, i)} onClick={() => usePlayer.getState().play(ids, i)} tabIndex={-1}>
              <span className={cn('block truncate font-medium', current && 'text-accent')}>{t.title}</span>
              <span className="block truncate text-xs text-muted">{t.artist}</span>
            </button>
            {showAlbum && <Link to={`/item/${t.albumId}`} className="hidden w-1/4 truncate text-xs text-muted hover:text-fg hover:underline md:block">{album?.title}</Link>}
            <button type="button" aria-label={liked[t.id] ? `Unlike ${t.title}` : `Like ${t.title}`} aria-pressed={!!liked[t.id]} onClick={() => state.toggleLike(t.id)}
              className={cn('flex h-8 w-8 shrink-0 items-center justify-center rounded-full transition-opacity hover:bg-line/10', liked[t.id] ? 'text-accent' : 'text-muted opacity-0 focus-visible:opacity-100 group-hover:opacity-100')}>
              <Heart className={cn('h-4 w-4', !!liked[t.id] && 'fill-current')} />
            </button>
            <span className="w-10 shrink-0 text-right text-xs tabular-nums text-muted">{t.duration ? fmtTime(t.duration) : '—'}</span>
            <Menu items={menu} label={`More actions for ${t.title}`} />
          </div>
        );
      })}
    </div>
  );
}
