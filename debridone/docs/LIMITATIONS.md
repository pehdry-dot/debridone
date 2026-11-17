# Known limitations

Verification status
- Verified by running the app from source on Linux (virtual display): account sign-up, the
  remembered sign-in after a restart, creating a second profile, a different theme and different
  Music appearance per profile surviving a full restart, the selected section being restored,
  the Plugins tabs, and the Cmd/Ctrl+1-3, comma and F shortcuts.
- Verified on GitHub's macOS runners (Apple Silicon and Intel): the app compiles and packages
  into a `.dmg`, and the packaged app starts, stays running and creates `accounts.db` and the
  three module databases. The macOS Keychain store passes its test there too.
- Music, verified by tests that run on Linux and in CI (real audio files, real libmpv, silent
  output): every supported format plays from start to end; pause, seek, volume, mute, next,
  whole-album playback, shuffle, repeat; tag and cover reading; scanning including moved, renamed,
  deleted, duplicate and unreachable files; playlists and M3U; favourites and history; edits,
  locks and restore; restart persistence; profile isolation.
- Music, verified by running the app from source on Linux (virtual display): adding a folder,
  covers and tags on Home, Library, album and search pages, playing from an album, the mini-player
  and full-screen player, the queue, creating a playlist, the sources screen and its sign-in
  dialog, playback continuing while visiting Home, Settings and Streaming, and library, history
  and the paused queue coming back after a restart.
- Music, verified inside the packaged macOS app on GitHub's **Intel** runner: DebridOne's music
  bridge is in the bundle, loads, and reaches the bundled libmpv (mpv 0.38, FFmpeg 7.0); a folder
  is imported with tags and covers; one file of each of the nine formats plays from start to end,
  both with silent output and through CoreAudio (the runner's real audio output); pause, seek and
  resume work; library, play counts, covers and queue survive a restart of the Music engine.
- Music and the app as a whole, verified inside the packaged macOS app on GitHub's **Apple
  Silicon** runner (`macos-26`, arm64) for commit `9c0d84277`: the arm64 DMG is built; the app
  starts, stays running and creates the Music and Manga databases and nothing for Games; the music
  bridge loads and reaches the bundled libmpv; one file of each of the nine formats plays from
  start to end with silent output and through CoreAudio; pause, seek and resume work; library,
  play counts, covers and queue survive a restart of the Music engine. The `macos-15` Apple
  Silicon job of the same run was cancelled by GitHub for lack of a machine, as on the days
  before; the build is the same.
- Play Next, verified by tests only (615 tests of the player, streams, details and watching
  packages on Linux): the cause of the failure is reproduced by a test that failed before the fix;
  the button and the pop-up are driven through the command the native controls send; similarity
  ranking, the wrong-episode rule, season boundary, missing sources, an add-on that never answers,
  cancelling, the countdown, saved progress and subtitles are covered with simulated add-on
  answers. **Not verified on a real stream**: the test environment has no add-ons and no debrid
  account, so a transition between two real episodes has to be confirmed on your Mac.
- Streaming's cogwheel and the Games removal, verified by running the app from source on Linux:
  the navigation shows Home, Search, Library and a cogwheel with a "Settings" tooltip that opens
  Streaming's settings; the sidebar and the Plugins tabs list Streaming, Music and Manga only.
- Music integrations, verified by running the app from source on Linux against the real services:
  installing Audius from Discover, its trending grid with covers, searching, a gated track shown
  as unavailable, an Audius song playing through the audio engine with its cover in the
  mini-player, the queue coming back after a restart; Plugins › Music with a plugin imported from
  a file, "Test" reporting MusicBrainz as working and the Cover Art Archive as not answering (true
  on that machine); the album dialog reporting that MusicBrainz and the archive have nothing for
  a made-up album.
- Music integrations, verified by live tests (`DEBRIDONE_LIVE_TESTS=1`): Audius search, artist
  search, trending by genre, stream resolution, playback through libmpv and cover download;
  MusicBrainz artist, album, edition, track-listing and artist-release lookups; LRCLIB search.
- **Cover Art Archive: not verified end to end.** Every lookup is redirected to archive.org
  storage hosts whose names change, which the build environment cannot reach. The redirect from
  the real service was observed; listing, previewing and applying covers are tested against a
  stand-in that answers in the archive's format.
- Subsonic servers other than Navidrome, Jellyfin playlists, and the Music Catalog protocol were
  tested against stand-in servers only (no public server speaks the protocol yet).
- Music, verified against the real public services (`DEBRIDONE_LIVE_TESTS=1`, run from the build
  environment): the Navidrome project's demo server (a wrong password is refused; sign-in; 501
  songs, 50 albums and 465 covers synchronised; a song downloaded, and played both from the
  download and straight from the server through libmpv), the Jellyfin project's demo server
  (token sign-in, synchronisation with covers, a song downloaded), MusicBrainz (candidates found
  and applied; the release matching the album ranked first) and LRCLIB (time-stamped lyrics).
- Music, tested only against local stand-in servers that answer in the services' own formats: the
  Cover Art Archive (its downloads redirect to changing archive.org hosts the build environment
  cannot reach), internet radio addresses, and Subsonic-compatible servers other than Navidrome.
  The stand-in tests also cover unreachable servers and rejected sign-ins.
- Manga, verified by tests that run on Linux and in CI (the real extension host on the pinned
  Node.js, the real `@tiyo/core` 1.0.3 downloaded by DebridOne's installer, a stand-in Komga
  server): the package loads and registers its 44 sources; settings, browse, search, series,
  chapters, page lists and images through Tiyo's Komga source; reading progress and resume after
  a restart; profile isolation; the installer refusing wrong names, wrong versions, bad checksums,
  links and path tricks; the helper's file, process and environment restrictions; crash and
  time-out recovery; CBZ, ZIP and folder reading with no extension runtime present; the library,
  bookmarks, collections, source change with carried-over progress; downloads including resume
  and retry; metadata edit, lock and refresh; backup and restore.
- Manga, verified by running the app from source on Linux (virtual display), against the real
  services: installing Tiyo 1.0.3 from the npm registry through Plugins › Manga › Browse with its
  review dialog; the 44 sources listed as "Not tested"; setting up and testing Komga against
  Komga's public demo server; searching all sources; a series page; reading with the keyboard;
  quitting, restarting and resuming on the same page from Home; importing a CBZ and a folder of
  chapters; the continuous webtoon mode; the settings panel; Discover on MangaDex with its own
  filters; adding to the library; an AniList match applied, a title edited, and the edit kept
  after refreshing from the source and from AniList; downloading a chapter from the demo server
  and reading it offline; the Downloads, Settings and Plugins › Manga pages; a second profile
  starting with an empty Manga library (and the shared extension package) while the first
  profile's library is unchanged on return.
- Manga, **not verified**: any Tiyo source other than Komga and (up to page lists) MangaDex —
  they show "Not tested" in the app until you test them; MangaDex page images (their hosts were
  out of reach of the build environment); MyAnimeList and reading-list tracking against the real
  services (no client ID or account was available; they are tested against stand-ins that answer
  in the services' formats); legacy `@houdoku/extension-*` packages other than
  `@houdoku/extension-komga` 1.3.1; the reader's feel on a Mac trackpad.
- Music, **only a person at the Mac can confirm**: that the sound is audible, that the media keys
  and the Now Playing widget respond (the code compiles and loads; nothing can press a key in
  CI), that sound follows a change of output device, and how the screens look on macOS.
- **Not verified:** anything else that needs a person in front of a Mac: the windows and screens
  of the packaged app, sign-in and profiles there, video playback, and opening a downloaded build
  through Gatekeeper.
- **Not verified:** installing a Streaming addon and playing video in this fork. The build
  environment could not reach addon hosts. Nuvio's own code paths for these are unchanged.
- Nothing is signed or notarized.

Modules
- Manga is working; what it does not include is listed in `MANGA.md`, `MANGA_PLUGINS.md` and
  under "Manga" below. (Games is not part of the app for now; its module is dormant and its saved
  data is left untouched.)
- Music is working; what it does not include is listed in `MUSIC.md` and under "Music" below.
- Streaming is Nuvio Desktop, which upstream labels alpha software.

Accounts
- Accounts are on-device only. There is no cross-device sync and no password recovery.
- Up to 6 profiles per account (inherited from the profile chooser's layout).
- Profile pictures are a colour or an imported image. Upstream's avatar and profile-background
  catalogues come from Nuvio's servers and are not available.
- Connecting a Nuvio-compatible sync server is still possible from the sign-in screen but is
  untested in DebridOne.

Streaming integrations
- TMDB, Trakt, Simkl, MDBList and Premiumize sign-in need your own API keys at build time
  (see `BUILD.md`). Addon-based browsing and playback work without them.
- Discord presence, crash reporting and the in-app updater are disabled.

Shell
- Cmd+F opens search in Streaming and in Music; in Manga, use Search in its navigation.
- The Downloads section has a tab per section: Streaming downloads continue in Streaming, Music's
  are under Music › Library › Available offline, Manga's are listed and managed there.
- macOS menu-bar items for the modules and notifications are not implemented. Media keys and
  Now Playing are implemented for Music (and by Nuvio for video).

Music
- No gapless playback, crossfade or equaliser; there is a short gap between songs.
- Tags are never written back into audio files; edits live in DebridOne's library.
- A compilation whose files have no album-artist tag appears as one album per track artist.
- Online lookups are per song; there is no bulk "fix my whole library".
- Reordering in playlists and the queue is done with move up/down, not by dragging.
- A live radio stream has no duration, so it cannot be seeked or downloaded.
- Streaming services with copy protection, third-party plugin files and Eclipse add-ons are not
  supported (see `MUSIC.md`).
- Switching a source off marks its songs unavailable rather than hiding them.

Manga
- Sources come only from Houdoku's extension packages. Tiyo was last released in May 2024; some
  of its sources no longer work because their sites changed. Only what was tested is called
  verified (`MANGA_PLUGINS.md`).
- Ten Tiyo sources expect Houdoku's hidden browser window. DebridOne fetches their pages without
  running page scripts, so they work only where the site answers a plain request; a browser check
  is reported, not bypassed.
- Extension code is isolated in a helper process with Node's permission model (no access to your
  files or DebridOne's data, no other programs). That is not a full system sandbox: an extension
  can still reach any web server.
- CBR, RAR and 7z archives are not opened. Pinch-to-zoom is not available (Cmd + scroll and keys
  are).
- The app is about 50 MB larger because it carries Node.js for the extension host.
- The bundled Node.js is the official, publisher-signed build inside an unsigned app; whether
  macOS asks about it separately on a downloaded copy has not been checked on a personal Mac.
- MyAnimeList needs your own API client ID (its API has no anonymous access).
- Refreshing a series takes each field from the first provider in your priority list that offers
  one, so details applied from AniList can be replaced by the source's own on the next refresh
  unless AniList is placed above "Extension metadata" or the field is locked.

Upstream compatibility notes for later phases
- Hydra's main process depends on Electron, a Rust native addon and Hydra's hosted API, and its
  libtorrent bridge is prebuilt only for Windows and Linux. It will be ported selectively.
- `eclipsemusic.app` could not be reached from the build environment and no public source or SDK
  was found, so Music is original work and Eclipse addon compatibility is not claimed.
