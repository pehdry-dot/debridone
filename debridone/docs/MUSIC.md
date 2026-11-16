# Music

Music is one of DebridOne's four sections. It is original DebridOne code: a library, a player,
playlists, sources and metadata of its own, stored in its own database. Nothing in it is shared
with Streaming, Games or Manga.

Eclipse Music was the feature reference. No Eclipse code, asset or protocol is used; its source
and add-on format are not published.

## What it does

**Playback.** Play, pause, resume, next, previous (restarts the song when more than three seconds
in), seek, volume, mute, shuffle, repeat queue and repeat one, automatic progression to the next
song, an editable queue (play next, add to queue, reorder, remove, clear). Music keeps playing
while you use other sections; a mini-player stays at the bottom of the window. Starting a video in
Streaming pauses the music. Quitting saves the queue and the position; the next start restores
them paused (or playing, if you choose that in Music settings).

**Formats.** MP3, FLAC, AAC and ALAC in M4A, raw AAC (`.aac`), WAV, AIFF, Ogg Vorbis and Opus.
Each of these has a test that plays a real file from start to end through the engine, and a test
that reads its tags. Other formats are not imported.

**Library.** Add folders or single files. Files are read where they are: nothing is copied, moved
or rewritten. Scans are incremental (unchanged files are not opened again), run in the background,
and repeat when DebridOne starts. A file that was moved or renamed is recognised by its content
and keeps its playlists, favourites, play count and edits. A file that disappeared, or a folder on
an unplugged drive, stays in the library marked as missing and comes back when the file does.
Byte-identical copies are listed as duplicates in Music settings.

**Browsing.** Home (jump back in, recently added, most played, playlists, favourites, artists),
Library (Songs, Albums, Artists, Genres, Playlists, Favourites, Available offline), album, artist,
genre and playlist pages, and search across songs, albums, artists and playlists. Search and
sorting ignore capitals and accents.

**Playlists.** Create, rename, add, remove, reorder, choose a picture. Import and export M3U and
M3U8; importing adds the files a playlist names to the library.

**Details editor.** Edit title, artist, album, album artist, genre, composer, year, track and disc
numbers, and the cover. Edits live in DebridOne's library; the audio file is never written to. A
field you edit is locked: a rescan or an online lookup will not change it. Each field shows what
the file says, any lock can be opened again, and "Restore original" puts everything back.

**Sources** (Plugins › Music). Besides files on this computer:

- Navidrome and other Subsonic-compatible servers,
- Jellyfin servers,
- internet radio and other direct stream addresses.

A source is a connection DebridOne signs in to itself. It is saved only if the sign-in works; a
stream address is added only if it really answers with audio. Songs of a server join the profile's
library, play from the server, and can be downloaded for offline listening. Passwords and tokens
are kept in the macOS Keychain, never in the database.

**Metadata.** Tags and covers embedded in the files, cover images in the album folder, and, when
you ask for them: MusicBrainz (song details), the Cover Art Archive (a cover for a song that has
none) and LRCLIB (lyrics). Lyrics embedded in a file are used first; you can also type or paste
them, including time-stamped LRC lines that follow the music. Each online provider can be switched
off. MusicBrainz is asked at most once a second and answers are cached.

**Per profile.** Library, folders, playlists, favourites, history, edits, sources, sign-ins,
queue, settings and appearance all belong to one profile. Switching profile stops the music, saves
that profile's queue, and loads the other profile's own last queue, paused. Deleting a profile
erases its Music data and its stored passwords.

**Appearance** (Settings › Appearance › Music, per profile). Accent colour, album grid size,
density, playlist layout, full-screen player layout, player background (colour from the artwork,
blurred artwork, plain), artwork size, text size, artwork-tinted pages, lyrics size. In Music
settings: which Home sections are shown, whether Music opens on Home or the Library, whether to
rescan and whether to continue playing when DebridOne starts, and the output device.

## Shortcuts

| Keys | Action |
|---|---|
| Cmd+2 | Open Music |
| Cmd+F | Search (in Music) |
| Cmd+P | Play / pause |
| Cmd+Right, Cmd+Left | Next, previous |
| Cmd+Up, Cmd+Down | Volume |
| Esc | Close the full-screen player |
| Media keys, Control Centre, lock screen | Play/pause, next, previous, seek (packaged macOS app) |

## How it is built

```
debridone/modules/music            the engine: pure JVM, no UI, no other module
  playback/   MpvLibrary, MpvAudioEngine      libmpv through JNA; one audio-only mpv instance
              MusicPlayerController           queue, shuffle, repeat, progression, errors
  library/    AudioTagReader                  jaudiotagger + own Ogg Opus and ADTS parsers
              MusicScanner                    incremental scan, move detection, missing files
              MusicLibraryRepository          queries, favourites, history, edits and locks
              ArtworkStore                    covers, thumbnails, dominant colour
  playlists/  PlaylistRepository, M3uCodec
  sources/    MusicSourceAdapter, SubsonicAdapter, JellyfinAdapter, MusicSourceManager
  metadata/   MusicMetadataService            MusicBrainz, Cover Art Archive, LRCLIB
  MusicPlayer                                 library tracks in, history and saved sessions out
  MusicModule                                 wiring, profiles, background jobs

composeApp/src/debridoneMain/kotlin/com/debridone/app/music     the screens
composeApp/src/debridoneMain/native/macos/debridone_music_bridge.m   macOS bridge
```

The engine depends only on `:debridone:platform` and `:debridone:module-api`; the screens may use
only that engine and the shared theme. Both rules are enforced by `:debridone:architecture-tests`.

**Audio engine.** libmpv (FFmpeg decoders), the same library file the app already ships for
video, but a separate instance that belongs to Music: no window, no user mpv configuration. On
macOS it is loaded through DebridOne's own small bridge library, which is linked against the
bundled libmpv and also connects Music to Now Playing and the media keys. Music does not use
Streaming's player or its bridge. On Linux (development) the system libmpv is used.

**Storage.** `modules/music/music.db` (SQLite; every row carries the profile it belongs to),
`modules/music/artwork/` (covers, stored once per distinct image) and `modules/music/offline/`
(downloaded copies). The saved queue holds song ids only; a stream address with credentials in it
is never written to disk.

## Checking a build

```bash
./scripts/debridone/test.sh
```

runs the Music tests with the others. The playback tests drive the real libmpv with mpv's silent
output; without libmpv they are skipped, and with `DEBRIDONE_REQUIRE_LIBMPV=1` a missing library
is an error instead (CI sets this).

```bash
DebridOne --music-self-test /path/to/a/folder/of/audio
```

checks the application itself without opening a window: it imports the folder into a temporary
library, plays one file of every format from start to end through the app's own engine, tries
pause, seek and resume, restarts the Music engine and verifies that library, play counts, covers
and queue are intact. It exits with 0 only if everything passed. The macOS workflow runs it inside
the packaged app. Set `DEBRIDONE_MUSIC_AO=null` to use mpv's silent output on a machine without a
sound device.

## Not included

- Spotify, Apple Music, Tidal, Deezer, YouTube Music and similar services. Their terms and copy
  protection do not allow another player to stream their catalogues.
- Third-party plugin files, and Eclipse add-ons. Sources are built in.
- Gapless playback, crossfade and an equaliser. There is a short gap between songs.
- Writing tags back into audio files.
- Compilations without an album-artist tag are split into one album per track artist.
- Bulk online lookup for a whole library; lookups are per song.
- Drag and drop of folders onto the window; use "Add a folder" or "Add files".
- Windows. Linux is a development platform only (no Now Playing there).

See [LIMITATIONS.md](LIMITATIONS.md) for what has and has not been verified.
