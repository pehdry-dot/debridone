import { Heart, ImagePlus, ListMusic, ListPlus, Mic2, Music, Pencil, Play, Plug, Search, Shuffle, Trash2, Upload } from 'lucide-react';
import { useMemo, useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { Art } from '../components/Cover';
import { AddonShelves } from '../components/addons';
import { CatalogBrowser, ItemShelf, MediaGrid, Shelf } from '../components/media';
import { TrackList } from '../components/TrackList';
import { Badge, Button, Callout, EmptyState, Field, Menu, Modal, PageHeader, Segmented, Select } from '../components/ui';
import { useTab } from '../lib/hooks';
import { importAudio, runImport } from '../lib/importers';
import { cn, fmtTime, hash, imageToDataUrl, pickFiles, safeImageUrl } from '../lib/utils';
import { artistId, byType, listArtists, useCatalog, useTracks } from '../store/catalog';
import { usePlayer } from '../store/player';
import { confirmDialog, toast } from '../store/ui';
import { useStore } from '../store/useStore';
import type { Playlist, Track } from '../types';

type Tab = 'home' | 'albums' | 'artists' | 'songs' | 'playlists' | 'liked';

const playAll = (tracks: Track[], shuffle = false) => {
  if (!tracks.length) { toast('Nothing to play here yet.', 'warn'); return; }
  const p = usePlayer.getState();
  if (shuffle !== p.shuffle) p.toggleShuffle();
  p.play(tracks.map((t) => t.id), shuffle ? Math.floor(Math.random() * tracks.length) : 0);
};

export function PlaylistArt({ playlist, className }: { playlist: Playlist; className?: string }) {
  const src = safeImageUrl(playlist.cover);
  return (
    <div className={cn('relative aspect-square overflow-hidden rounded-card bg-card ring-1 ring-inset ring-line/10 [container-type:inline-size]', className)}>
      {src ? <img src={src} alt="" className="absolute inset-0 h-full w-full object-cover" /> : (
        <>
          <Art seed={playlist.id} palette={playlist.palette} />
          <div className="absolute inset-0 flex flex-col justify-between p-[8cqw] text-white [text-shadow:0_1px_8px_rgb(0_0_0/0.5)]">
            <ListMusic className="h-[14cqw] w-[14cqw] opacity-90" />
            <p className="line-clamp-2 text-[11cqw] font-bold leading-[1.05] tracking-tight">{playlist.name}</p>
          </div>
        </>
      )}
    </div>
  );
}

function PlaylistTile({ playlist, className }: { playlist: Playlist; className?: string }) {
  return (
    <Link to={`/music/playlist/${playlist.id}`} className={cn('group block min-w-0', className)}>
      <PlaylistArt playlist={playlist} className="lift shadow-lift" />
      <p className="mt-2.5 truncate text-[0.8125rem] font-medium">{playlist.name}</p>
      <p className="truncate text-xs text-muted">{playlist.trackIds.length} songs{playlist.demo ? ' · Demo playlist' : ''}</p>
    </Link>
  );
}

function ArtistTile({ artist, className }: { artist: ReturnType<typeof listArtists>[number]; className?: string }) {
  return (
    <Link to={`/music/artist/${encodeURIComponent(artist.name)}`} className={cn('group block min-w-0 text-center', className)}>
      <div className="lift relative mx-auto aspect-square w-full overflow-hidden rounded-full shadow-lift ring-1 ring-inset ring-line/10">
        <Art seed={artist.id} palette={artist.palette} shade={false} />
        <span className="absolute inset-0 flex items-center justify-center text-4xl font-bold text-white/90 [text-shadow:0_2px_12px_rgb(0_0_0/0.4)]">{artist.name[0]}</span>
      </div>
      <p className="mt-2.5 truncate text-[0.8125rem] font-medium">{artist.name}</p>
      <p className="truncate text-xs text-muted">{artist.albums.length} release{artist.albums.length === 1 ? '' : 's'}</p>
    </Link>
  );
}

function NewPlaylistModal({ open, onClose }: { open: boolean; onClose: () => void }) {
  const [name, setName] = useState('');
  const navigate = useNavigate();
  const create = () => {
    if (!name.trim()) return;
    const id = useStore.getState().createPlaylist(name.trim());
    toast(`Created “${name.trim()}”`, 'success');
    setName(''); onClose();
    navigate(`/music/playlist/${id}`);
  };
  return (
    <Modal open={open} onClose={onClose} title="New playlist" size="sm" footer={<><Button variant="ghost" onClick={onClose}>Cancel</Button><Button variant="primary" disabled={!name.trim()} onClick={create}>Create</Button></>}>
      <form onSubmit={(e) => { e.preventDefault(); create(); }}>
        <Field label="Name" hint="Add songs from any album, the Songs tab or search using the ⋯ menu on each row."><input autoFocus className="field" value={name} onChange={(e) => setName(e.target.value)} maxLength={60} placeholder="e.g. Late night coding" /></Field>
      </form>
    </Modal>
  );
}

export default function MusicPage() {
  const navigate = useNavigate();
  const catalog = useCatalog();
  const trackMap = useTracks();
  const [tab, setTab] = useTab<Tab>('home');
  const [creating, setCreating] = useState(false);
  const [importing, setImporting] = useState(false);
  const [q, setQ] = useState('');
  const [sort, setSort] = useState<'title' | 'artist' | 'album' | 'duration'>('title');
  const favorites = useStore((s) => s.favorites);
  const saved = useStore((s) => s.saved);
  const liked = useStore((s) => s.likedTracks);
  const recentTracks = useStore((s) => s.recentTracks);
  const playlists = useStore((s) => s.playlists);

  const albums = useMemo(() => byType(catalog, 'album'), [catalog]);
  const artists = useMemo(() => listArtists(catalog), [catalog]);
  const tracks = useMemo(() => [...trackMap.values()], [trackMap]);
  const albumTitle = (t: Track) => albums.find((a) => a.id === t.albumId)?.title ?? '';
  const recent = recentTracks.map((r) => trackMap.get(r.id)).filter((t): t is Track => !!t);
  const likedList = Object.entries(liked).sort((a, b) => b[1] - a[1]).map(([id]) => trackMap.get(id)).filter((t): t is Track => !!t);
  const favAlbums = albums.filter((a) => favorites[a.id]);
  const favArtistNames = new Set([...favAlbums.map((a) => a.creator), ...likedList.map((t) => t.artist)]);
  const favArtists = artists.filter((a) => favArtistNames.has(a.name));
  const newest = [...albums].sort((a, b) => (b.releaseDate ?? '').localeCompare(a.releaseDate ?? '') || b.addedAt - a.addedAt);
  const recentlyAdded = [...albums].sort((a, b) => b.addedAt - a.addedAt).flatMap((a) => a.album!.tracks.slice(0, 2)).slice(0, 10);
  const songs = useMemo(() => {
    const needle = q.trim().toLowerCase();
    const list = tracks.filter((t) => !needle || t.title.toLowerCase().includes(needle) || t.artist.toLowerCase().includes(needle) || albumTitle(t).toLowerCase().includes(needle));
    const key = (t: Track) => (sort === 'title' ? t.title : sort === 'artist' ? t.artist : albumTitle(t));
    return sort === 'duration' ? list.sort((a, b) => a.duration - b.duration) : list.sort((a, b) => key(a).localeCompare(key(b)));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [tracks, q, sort, albums]);

  const doImport = async () => { setImporting(true); await runImport(importAudio); setImporting(false); };

  return (
    <div className="page">
      <PageHeader title="Music" icon={Music} subtitle="Albums and tracks from your music addons, plus anything you import from your device."
        actions={<><Button icon={ListPlus} onClick={() => setCreating(true)}>New playlist</Button><Button icon={Upload} loading={importing} onClick={doImport}>Import audio</Button></>}>
        <div className="mt-5">
          <Segmented value={tab} onChange={setTab} options={[
            { value: 'home', label: 'Listen Now' }, { value: 'albums', label: 'Albums' }, { value: 'artists', label: 'Artists' }, { value: 'songs', label: 'Songs' },
            { value: 'playlists', label: 'Playlists' }, { value: 'liked', label: 'Liked Songs' },
          ]} />
        </div>
      </PageHeader>

      {tab === 'home' && (
        <>
          <AddonShelves media={['album']} limit={6} />
          {recent.length > 0 && (
            <section className="mb-9">
              <div className="mb-3 flex items-end justify-between"><h2 className="text-lg font-semibold tracking-tight">Recently Played</h2><Button size="sm" variant="ghost" icon={Play} onClick={() => playAll(recent)}>Play all</Button></div>
              <div className="surface grid gap-x-4 p-2 md:grid-cols-2"><TrackList tracks={recent.slice(0, 6)} showArt /></div>
            </section>
          )}
          <ItemShelf title="Favorite Albums" items={favAlbums} empty={<EmptyState icon={Heart} title="No favorite albums yet" body="Tap the heart on an album to pin it here." className="py-8" />} />
          {favArtists.length > 0 && <Shelf title="Favorite Artists" subtitle="From your favorite albums and liked songs">{favArtists.map((a) => <ArtistTile key={a.id} artist={a} className="w-32 shrink-0 snap-start sm:w-36" />)}</Shelf>}
          <ItemShelf title="Your Music Library" subtitle="Albums you saved or imported" items={albums.filter((a) => saved[a.id])} empty={<EmptyState icon={Music} title="Your music library is empty" body="Save an album, or import audio files from your device." className="py-8" />} />
          <section className="mb-9">
            <h2 className="mb-3 text-lg font-semibold tracking-tight">Recently Added Songs</h2>
            <div className="surface grid gap-x-4 p-2 md:grid-cols-2"><TrackList tracks={recentlyAdded} showArt /></div>
          </section>
          <Callout tone="desktop" title="Streaming services" action={<Button size="sm" icon={Plug} onClick={() => navigate('/settings/integrations')}>Integrations</Button>}>
            DebridOne does not access Spotify or Apple Music catalogues. A licensed integration would plug into the same album and track adapter, through a backend that holds the service’s credentials.
          </Callout>
        </>
      )}

      {tab === 'albums' && <CatalogBrowser items={albums} defaultSort="recent" emptyAction={<Button icon={Upload} onClick={doImport}>Import audio</Button>} />}

      {tab === 'artists' && (
        <div className="grid grid-cols-[repeat(auto-fill,minmax(8rem,1fr))] gap-x-5 gap-y-7 sm:grid-cols-[repeat(auto-fill,minmax(9.5rem,1fr))]">
          {artists.map((a) => <ArtistTile key={a.id} artist={a} />)}
        </div>
      )}

      {tab === 'songs' && (
        <>
          <div className="mb-4 flex flex-wrap items-center gap-2">
            <div className="relative min-w-[11rem] flex-1 sm:max-w-xs">
              <Search className="pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-muted" />
              <input className="field h-9 py-0 pl-9" placeholder="Filter songs, artists, albums" value={q} onChange={(e) => setQ(e.target.value)} aria-label="Filter songs" />
            </div>
            <Select label="Sort songs" value={sort} onChange={setSort} options={[{ value: 'title', label: 'Title' }, { value: 'artist', label: 'Artist' }, { value: 'album', label: 'Album' }, { value: 'duration', label: 'Duration' }]} />
            <Button size="sm" icon={Shuffle} onClick={() => playAll(songs, true)}>Shuffle all</Button>
            <span className="ml-auto text-xs tabular-nums text-muted">{songs.length} songs · {fmtTime(songs.reduce((n, t) => n + t.duration, 0))}</span>
          </div>
          {songs.length ? <div className="surface p-2"><TrackList tracks={songs} showAlbum showArt /></div> : <EmptyState icon={Music} title="No songs match" />}
        </>
      )}

      {tab === 'playlists' && (
        <div className="grid grid-cols-[repeat(auto-fill,minmax(8.25rem,1fr))] gap-x-4 gap-y-6 sm:grid-cols-[repeat(auto-fill,minmax(10rem,1fr))]">
          <button type="button" onClick={() => setCreating(true)} className="group block text-left">
            <div className="lift flex aspect-square items-center justify-center rounded-card border border-dashed border-line/20 text-muted transition-colors group-hover:border-accent group-hover:text-accent"><ListPlus className="h-8 w-8" /></div>
            <p className="mt-2.5 text-[0.8125rem] font-medium">New playlist</p><p className="text-xs text-muted">Start from scratch</p>
          </button>
          {playlists.map((p) => <PlaylistTile key={p.id} playlist={p} />)}
        </div>
      )}

      {tab === 'liked' && (likedList.length ? (
        <>
          <div className="mb-4 flex items-center gap-2"><Button variant="primary" icon={Play} onClick={() => playAll(likedList)}>Play</Button><Button icon={Shuffle} onClick={() => playAll(likedList, true)}>Shuffle</Button><span className="ml-auto text-xs text-muted">{likedList.length} liked songs</span></div>
          <div className="surface p-2"><TrackList tracks={likedList} showAlbum showArt /></div>
        </>
      ) : <EmptyState icon={Heart} title="No liked songs yet" body="Press the heart next to any song." />)}

      <NewPlaylistModal open={creating} onClose={() => setCreating(false)} />
    </div>
  );
}

export function ArtistPage() {
  const { name = '' } = useParams();
  const catalog = useCatalog();
  const navigate = useNavigate();
  const artist = listArtists(catalog).find((a) => a.id === artistId(decodeURIComponent(name)));
  if (!artist) return <div className="page"><EmptyState icon={Mic2} title="Artist not found" action={<Button variant="primary" onClick={() => navigate('/music?tab=artists')}>All artists</Button>} /></div>;
  const tracks = artist.albums.flatMap((a) => a.album!.tracks);
  return (
    <div className="page">
      <div className="mb-8 flex flex-col items-center gap-6 text-center sm:flex-row sm:items-end sm:text-left">
        <div className="relative h-40 w-40 shrink-0 overflow-hidden rounded-full shadow-pop ring-1 ring-inset ring-line/10 sm:h-48 sm:w-48">
          <Art seed={artist.id} palette={artist.palette} shade={false} />
          <span className="absolute inset-0 flex items-center justify-center text-6xl font-bold text-white/90">{artist.name[0]}</span>
        </div>
        <div className="min-w-0">
          <Badge tone="accent" icon={Mic2}>Artist</Badge>
          <h1 className="h-display mt-2 text-4xl sm:text-5xl">{artist.name}</h1>
          <p className="mt-2 max-w-xl text-sm leading-relaxed text-muted">{artist.bio}</p>
          <p className="mt-1 text-xs text-muted">{artist.genres.join(' · ')} · {artist.albums.length} releases · {tracks.length} songs</p>
          <div className="mt-4 flex justify-center gap-2 sm:justify-start"><Button variant="primary" size="lg" icon={Play} onClick={() => playAll(tracks)}>Play</Button><Button size="lg" icon={Shuffle} onClick={() => playAll(tracks, true)}>Shuffle</Button></div>
        </div>
      </div>
      <section className="mb-9"><h2 className="mb-3 text-lg font-semibold tracking-tight">Songs</h2><div className="surface p-2"><TrackList tracks={tracks.slice(0, 8)} showAlbum showArt /></div></section>
      <h2 className="mb-3 text-lg font-semibold tracking-tight">Releases</h2>
      <MediaGrid items={artist.albums} />
    </div>
  );
}

export function PlaylistPage() {
  const { id = '' } = useParams();
  const navigate = useNavigate();
  const trackMap = useTracks();
  const playlist = useStore((s) => s.playlists.find((p) => p.id === id));
  const update = useStore((s) => s.updatePlaylist);
  const [editing, setEditing] = useState(false);
  const [name, setName] = useState('');
  const [description, setDescription] = useState('');
  if (!playlist) return <div className="page"><EmptyState icon={ListMusic} title="Playlist not found" action={<Button variant="primary" onClick={() => navigate('/music?tab=playlists')}>All playlists</Button>} /></div>;
  const tracks = playlist.trackIds.map((t) => trackMap.get(t)).filter((t): t is Track => !!t);
  const setCover = async () => {
    const [file] = await pickFiles('image/*');
    if (!file) return;
    try { update(playlist.id, { cover: await imageToDataUrl(file, 500) }); toast('Playlist artwork updated', 'success'); }
    catch (e) { toast((e as Error).message, 'error'); }
  };
  const remove = async () => {
    if (!(await confirmDialog({ title: `Delete “${playlist.name}”?`, body: 'The songs stay in your library.', confirmLabel: 'Delete playlist', danger: true }))) return;
    useStore.getState().deletePlaylist(playlist.id);
    toast('Playlist deleted', 'success');
    navigate('/music?tab=playlists');
  };
  return (
    <div className="page">
      <div className="mb-8 flex flex-col items-center gap-6 text-center sm:flex-row sm:items-end sm:text-left">
        <PlaylistArt playlist={playlist} className="w-44 shrink-0 shadow-pop sm:w-52" />
        <div className="min-w-0 flex-1">
          <Badge tone="accent" icon={ListMusic}>{playlist.demo ? 'Demo playlist' : 'Playlist'}</Badge>
          <h1 className="h-display mt-2 text-3xl sm:text-5xl">{playlist.name}</h1>
          {playlist.description && <p className="mt-2 max-w-xl text-sm text-muted">{playlist.description}</p>}
          <p className="mt-1 text-xs text-muted">{tracks.length} songs · {fmtTime(tracks.reduce((n, t) => n + t.duration, 0))}</p>
          <div className="mt-4 flex flex-wrap items-center justify-center gap-2 sm:justify-start">
            <Button variant="primary" size="lg" icon={Play} onClick={() => playAll(tracks)}>Play</Button>
            <Button size="lg" icon={Shuffle} onClick={() => playAll(tracks, true)}>Shuffle</Button>
            <Menu label="Playlist actions" className="inline-flex h-11 w-11 items-center justify-center rounded-full bg-line/10 text-fg hover:bg-line/20" items={[
              { label: 'Edit name & description', icon: Pencil, onClick: () => { setName(playlist.name); setDescription(playlist.description); setEditing(true); } },
              { label: 'Upload custom artwork', icon: ImagePlus, onClick: setCover },
              { label: 'Use generated artwork', icon: Shuffle, onClick: () => update(playlist.id, { cover: undefined, palette: (playlist.palette + 1 + (hash(playlist.id) % 3)) % 16 }) },
              'divider',
              { label: 'Delete playlist…', icon: Trash2, danger: true, onClick: remove },
            ]} />
          </div>
        </div>
      </div>
      {tracks.length
        ? <div className="surface p-2"><TrackList tracks={tracks} showAlbum showArt onRemove={(i) => { update(playlist.id, { trackIds: playlist.trackIds.filter((_, k) => k !== i) }); toast('Removed from playlist'); }} /></div>
        : <EmptyState icon={ListPlus} title="This playlist is empty" body="Open an album or the Songs tab and choose “Add to playlist” from a song’s ⋯ menu." action={<Button variant="primary" onClick={() => navigate('/music?tab=songs')}>Browse songs</Button>} />}
      <Modal open={editing} onClose={() => setEditing(false)} title="Edit playlist" size="sm"
        footer={<><Button variant="ghost" onClick={() => setEditing(false)}>Cancel</Button><Button variant="primary" disabled={!name.trim()} onClick={() => { update(playlist.id, { name: name.trim(), description: description.trim() }); setEditing(false); toast('Playlist updated', 'success'); }}>Save</Button></>}>
        <div className="space-y-3">
          <Field label="Name"><input className="field" value={name} onChange={(e) => setName(e.target.value)} maxLength={60} /></Field>
          <Field label="Description"><textarea className="field min-h-[4.5rem]" value={description} onChange={(e) => setDescription(e.target.value)} maxLength={240} /></Field>
        </div>
      </Modal>
    </div>
  );
}
