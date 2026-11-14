import { create } from 'zustand';
import { createJSONStorage, persist } from 'zustand/middleware';
import { kvStorage } from '../lib/kv';
import { blobUrl } from '../lib/idb';
import { getSongUrl } from '../lib/synth';
import { clamp } from '../lib/utils';
import { storageKey } from './auth';
import { getItem, getTrack } from './catalog';
import { toast } from './ui';
import { useStore } from './useStore';

/*
 * Music playback engine: one shared <audio> element driven by a small store.
 * Sources are either generated WAVs (demo library), blobs from IndexedDB
 * (your imports) or plain URLs.
 */

const audio = new Audio();
audio.preload = 'auto';
let loadToken = 0;
let loadedId: string | null = null;

interface PlayerState {
  queue: string[];
  index: number;
  playing: boolean;
  loading: boolean;
  time: number;
  duration: number;
  volume: number;
  muted: boolean;
  shuffle: boolean;
  repeat: 'off' | 'all' | 'one';
  play(ids: string[], start?: number): void;
  toggle(): void;
  next(auto?: boolean): void;
  prev(): void;
  seek(t: number): void;
  jump(index: number): void;
  setVolume(v: number): void;
  toggleMute(): void;
  toggleShuffle(): void;
  cycleRepeat(): void;
  enqueue(id: string, next?: boolean): void;
  removeAt(index: number): void;
  clear(): void;
  pause(): void;
}

async function load(autoplay: boolean) {
  const s = usePlayer.getState();
  const id = s.queue[s.index];
  const track = id ? getTrack(id) : undefined;
  const token = ++loadToken;
  if (!track) { usePlayer.setState({ playing: false, loading: false }); return; }
  audio.pause();
  usePlayer.setState({ loading: true, time: 0, duration: track.duration });
  try {
    let url: string | undefined;
    if (!track.spec && !track.src) { usePlayer.setState({ loading: false, playing: false }); toast('This track has no audio yet. Install a music addon that provides streams to play it.', 'warn'); return; }
    if (track.spec) url = await getSongUrl(track.spec);
    else if (track.src.startsWith('idb:')) url = await blobUrl(track.src.slice(4));
    else url = track.src;
    if (token !== loadToken) return;
    if (!url) throw new Error('missing');
    audio.src = url;
    loadedId = id;
    if (autoplay) await audio.play();
    usePlayer.setState({ loading: false });
    const app = useStore.getState();
    app.logTrack(id);
    app.logHistory({ id: track.albumId, kind: 'listen', detail: track.title });
    if ('mediaSession' in navigator) {
      navigator.mediaSession.metadata = new MediaMetadata({ title: track.title, artist: track.artist, album: getItem(track.albumId)?.title ?? '' });
    }
  } catch (e) {
    if (token !== loadToken) return;
    usePlayer.setState({ loading: false, playing: false });
    if ((e as Error)?.name === 'NotAllowedError') return; // autoplay blocked: the user can press play
    toast(`Couldn’t play “${track.title}”. ${track.src.startsWith('idb:') ? 'The imported file is no longer in browser storage.' : ''}`, 'error');
  }
}

export const usePlayer = create<PlayerState>()(
  persist(
    (set, get) => ({
      queue: [], index: 0, playing: false, loading: false, time: 0, duration: 0, volume: 0.8, muted: false, shuffle: false, repeat: 'off',

      play(ids, start = 0) {
        if (!ids.length) return;
        set({ queue: ids, index: clamp(start, 0, ids.length - 1) });
        void load(true);
      },
      toggle() {
        const s = get();
        if (!s.queue.length) return;
        if (loadedId !== s.queue[s.index]) { void load(true); return; }
        if (audio.paused) audio.play().catch(() => {}); else audio.pause();
      },
      pause() { audio.pause(); },
      next(auto = false) {
        const s = get();
        if (!s.queue.length) return;
        if (auto && s.repeat === 'one') { audio.currentTime = 0; audio.play().catch(() => {}); return; }
        let index = s.index + 1;
        if (s.shuffle && s.queue.length > 1) {
          do index = Math.floor(Math.random() * s.queue.length); while (index === s.index);
        } else if (index >= s.queue.length) {
          if (s.repeat === 'off' && auto) { set({ playing: false, time: 0 }); audio.currentTime = 0; return; }
          index = 0;
        }
        set({ index });
        void load(true);
      },
      prev() {
        const s = get();
        if (!s.queue.length) return;
        if (audio.currentTime > 3 || s.index === 0) { audio.currentTime = 0; set({ time: 0 }); return; }
        set({ index: s.index - 1 });
        void load(true);
      },
      seek(t) { if (loadedId) { audio.currentTime = t; set({ time: t }); } },
      jump(index) { set({ index }); void load(true); },
      setVolume(v) { const volume = clamp(v, 0, 1); audio.volume = volume; audio.muted = false; set({ volume, muted: false }); },
      toggleMute() { const muted = !get().muted; audio.muted = muted; set({ muted }); },
      toggleShuffle() { set({ shuffle: !get().shuffle }); },
      cycleRepeat() { const r = get().repeat; set({ repeat: r === 'off' ? 'all' : r === 'all' ? 'one' : 'off' }); },
      enqueue(id, next = false) {
        const s = get();
        if (!s.queue.length) { get().play([id]); return; }
        const queue = s.queue.slice();
        if (next) queue.splice(s.index + 1, 0, id); else queue.push(id);
        set({ queue });
      },
      removeAt(i) {
        const s = get();
        if (i === s.index) return;
        set({ queue: s.queue.filter((_, k) => k !== i), index: i < s.index ? s.index - 1 : s.index });
      },
      clear() { audio.pause(); audio.removeAttribute('src'); loadedId = null; loadToken++; set({ queue: [], index: 0, playing: false, time: 0, duration: 0 }); },
    }),
    {
      name: storageKey('debridone:player:v1'),
      storage: createJSONStorage(() => kvStorage),
      partialize: (s) => ({ queue: s.queue, index: s.index, volume: s.volume, muted: s.muted, shuffle: s.shuffle, repeat: s.repeat }),
      onRehydrateStorage: () => (s) => {
        if (!s) return;
        audio.volume = s.volume; audio.muted = s.muted;
        // The catalogue isn't ready during hydration, so fill in the restored track's length on the next tick.
        setTimeout(() => { const t = getTrack(usePlayer.getState().queue[usePlayer.getState().index]); if (t) usePlayer.setState({ duration: t.duration }); }, 0);
      },
    },
  ),
);

audio.addEventListener('timeupdate', () => usePlayer.setState({ time: audio.currentTime }));
audio.addEventListener('durationchange', () => { if (isFinite(audio.duration)) usePlayer.setState({ duration: audio.duration }); });
audio.addEventListener('play', () => usePlayer.setState({ playing: true }));
audio.addEventListener('pause', () => usePlayer.setState({ playing: false }));
audio.addEventListener('ended', () => usePlayer.getState().next(true));

if ('mediaSession' in navigator) {
  const p = () => usePlayer.getState();
  navigator.mediaSession.setActionHandler('play', () => p().toggle());
  navigator.mediaSession.setActionHandler('pause', () => p().pause());
  navigator.mediaSession.setActionHandler('nexttrack', () => p().next());
  navigator.mediaSession.setActionHandler('previoustrack', () => p().prev());
}

export const currentTrackId = (s: PlayerState) => s.queue[s.index];
