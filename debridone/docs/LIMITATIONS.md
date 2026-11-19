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
- Manga, verified by tests that run on Linux and in CI, on the real, pinned Suwayomi-Server
  (v2.4.2366) started by DebridOne's own server manager with the pinned Java 21: every GraphQL
  document DebridOne sends is accepted by the server; the server answers only on this computer and
  only with DebridOne's password; starting, stopping, a second DebridOne on the same data, and a
  server whose DebridOne was killed; sample comics read through the Local source (pages in order,
  progress, restart and resume); library status, favourites, collections, bookmarks, hand edits
  kept across a restart; two profiles with separate libraries and server folders; the server's own
  settings changed and kept; its check for new chapters; trackers listed and a browser sign-in
  returning through DebridOne's listener (refused, as it carried no sign-in: no tracker is
  contacted by the tests); a backup restored into another profile with DebridOne's extras and
  without the server's password; a profile reading from a second real server as "a server of
  your own"; a remote server going away and coming back (stand-in); the migration of a library in
  the earlier format, with its backup and report.
- Manga, verified by running the app from source on Linux (virtual display): the server starting
  when Manga is opened; adding an extension store (with the one-time notice), about 1,700
  extensions listed and filtered, the Komga extension installed and configured through its own
  settings against Komga's public demo server; search, a series page, reading in the native
  reader, quitting and resuming on the same page with no server process left behind; adding to
  the library with a status and as a favourite; a chapter saved by the server, then read with the
  network cut off, while a chapter that was not saved said it could not be opened; a CBZ imported
  and read; a comic archive from the cloud opened in the reader; the Trackers page with the
  server's six trackers, both kinds of sign-in panel, and a browser sign-in that came back
  refused; the Server page with the server's settings; Library's check for new chapters; a second
  profile with an empty Manga and its own server, the first profile's library intact on return;
  the migration of a real library left by the earlier engine (three local series carried over,
  two online ones listed, one of them carried over on request), with its backup folder and report.
- Manga inside the packaged macOS app: the first packaged build with Suwayomi (commit `63a6a034c`,
  CI run 44416258890) built the unsigned DMG on all three machines (Apple Silicon on `macos-15`
  and `macos-26`, Intel on `macos-15-intel`), the bundled Java started there, and then
  `--manga-self-test` **failed**: the server died while starting, because its jar lay beside the
  Java runtime's `jre` folder, where Suwayomi's own class scan takes it for a part of the runtime.
  That was reproduced on Linux and fixed by giving the jar a folder of its own; a test now builds
  the packaged layout on every machine (`PackagedLayoutTest`), and a jar in such a place is
  refused with the reason instead of a start that never finishes.
- Manga, verified inside the packaged macOS app on GitHub's runners after that fix (commit
  `4e4f3c3d5`, CI run 44422145370), on **Apple Silicon** (`macos-15` and `macos-26`, arm64) and on
  **Intel** (`macos-15-intel`): the unsigned DMG is built with Suwayomi-Server and its Java 21
  runtime inside (about 405 MB); `--manga-self-test` passes in the packaged app: the server and
  Java found are the copies inside the application; the server starts for a profile, listens on
  this computer only and answers nothing without DebridOne's password; no extension store is
  configured and the only source is the Local source; a comic archive is read through the
  server (pages in order, cover, the five steps of the source test); closing DebridOne ends the
  server process; after a restart the series resumes on the same page with its favourite; no
  server process is left behind and the person's own file is where it was. The Java runtime also
  starts from inside the mounted DMG. The same run passed the Linux tests, the Keychain test and
  the Music self-test in the packaged app. Nobody opened that DMG on a Mac of their own.
- Manga, **not verified**: any extension other than Komga's (whether one works is between that
  extension, its site and Suwayomi); installing an extension from an `.apk` file with a real
  package (the request is validated by the server and the same upload path is tested with a
  backup file); updating an extension to a newer version; signing in to a tracker and linking,
  updating and unlinking an entry there (no tracker account was available: the sign-in panels,
  the listener and the refusal path are tested, the rest is Suwayomi's own code); Suwayomi's
  "simple login" and "UI login" sign-in modes on a server of your own (only "basic" or none);
  a SOCKS proxy and FlareSolverr (the settings are saved and read back; no proxy or FlareSolverr
  was run); MyAnimeList lookups against the real service (no client ID was available; tested
  against a stand-in); the reader's feel on a Mac trackpad.
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
- The Downloads section is the file browser of the connected accounts (`CLOUD.md`). Its *Local*
  tab also points to what each section downloads by itself: Streaming's downloads continue in
  Streaming, Music's are under Music › Library › Available offline, Manga's chapters are listed
  and managed there.
- macOS menu-bar items for the modules and notifications are not implemented. Media keys and
  Now Playing are implemented for Music (and by Nuvio for video).

Connected accounts (Real-Debrid, TorBox) and Downloads
- **What was run against the real services** (the owner's own accounts, read-only, counts only):
  Real-Debrid's code sign-in was started (the real service issued a code) but not completed, since
  that takes the owner's browser; both accounts were connected in the running app with an API
  token / key; account, plan and expiry were read; Real-Debrid's torrents and generated links were
  listed (17 torrents, 221 files in them, 30 generated links of which 24 are files of those
  torrents and are shown once) and shown in Downloads and in Streaming › Library; one download
  address was made and a second request reused it.
- **What was not run against the real services:** playing or downloading a real file (the
  providers' download hosts were not reachable from where DebridOne is built, and each Real-Debrid
  address adds a "generated link" record to the account); deleting anything; TorBox's lists (the
  account's three lists were empty, so the reading of real TorBox entries rests on TorBox's
  documentation, its official SDK and the stand-in server); TorBox's download-address request
  (it carries the key in the address). Everything else was run against local stand-in servers
  that answer in the providers' documented formats.
- Finding from the real service, built in: Real-Debrid leaves a torrent's links out of listings
  asked with more than 100 entries per page (not in its documentation). Pages are 100.
- Nothing about connected accounts was run on a Mac: the Keychain store has its own test on
  macOS, but the pages, "Reveal in Finder", "Open with the default app" and sound were not tried
  there. The packaged app's self-tests cover Music and Manga, not the cloud pages. The build with
  connected accounts (commit `5329c8100`, CI run 44241102644) passed on every machine: the Linux
  tests including the cloud, Music and architecture tests, the Keychain test, and the unsigned
  DMGs for Apple Silicon on `macos-15` and `macos-26` and for Intel, each with the Music and
  Manga self-tests inside the packaged app.
- RAR and 7-Zip archives (true CBR, CB7) are not read: the only Java reader for RAR is under the
  UnRAR licence, which does not fit this project's GPL-3. A `.cbr` that is really a ZIP is read.
- Videos are sorted into *TV Shows* only by markers in their names; everything else is *Movies*.
  There are no posters, synopses or episode titles for cloud files, in Downloads or in Streaming.
- Streaming keeps no watch progress for videos shorter than about two minutes (Nuvio's rule for
  placeholder clips), so those never appear under Continue Watching.
- In Nuvio's sync mode (a sync server connected), watch progress of cloud videos, with their file
  names, is synced by Nuvio like any other progress.
- One account per provider for a profile: a profile that already sees an account of a provider
  (its own, or one shared with all profiles) is not offered a second one there.
- Provider marks are plain monograms (RD, TB), not the providers' logos.
- Playing or downloading a Real-Debrid torrent file adds a "generated link" record to the
  account. That is how its API hands out addresses; DebridOne reuses an existing one.
- A cloud song that was added to the Music library but not saved leaves the library when its
  cloud entry is deleted or its account is disconnected.

Music
- No gapless playback, crossfade or equaliser; there is a short gap between songs.
- Tags are never written back into audio files; edits live in DebridOne's library.
- A compilation whose files have no album-artist tag appears as one album per track artist.
- Online lookups are per song; there is no bulk "fix my whole library".
- Reordering in playlists and the queue is done with move up/down, not by dragging.
- A live radio stream has no duration, so it cannot be seeked or downloaded.
- Streaming services with copy protection and plugins that carry code are not supported (see
  `MUSIC.md`). Addons in the Eclipse Music / BeatBoss format are (`MUSIC_ADDONS.md`), except those
  that answer the Eclipse app only and streams marked as encrypted.
- Music addons were tested against a local addon that follows the published format and serves
  DebridOne's own test recordings: in automated tests (installing, the address staying out of
  every file, search, album / artist / playlist pages, rows, settings, playback through libmpv,
  "still preparing" answers, saving with the real file type, profiles, the step-by-step test,
  lyrics) and by hand in the running app on Linux (review dialog, install, test, settings, Discover,
  playback, a second profile seeing nothing, restart). **No third-party addon was tested**, and
  nothing about addons has been run on a Mac yet. HLS and DASH streams from an addon are handed to
  the engine untested. Rows of albums, artists and playlists are not shown.
- Music addons inside the packaged macOS app: the build with addon support (commit `e3390ce6c`,
  CI run 44208118939) passed on every machine (Linux tests including the addon tests, the Keychain
  test, and the unsigned DMGs for Apple Silicon on `macos-15` and `macos-26` and for Intel, each
  with the Music and Manga self-tests inside the packaged app). The self-tests do not install an
  addon, so on a Mac addons are covered by the Linux tests only.
- Cloud Music knows files, not tags: titles, artists and albums of cloud songs are read from file
  and folder names, and a song's length is known only after it has played once. Its own tags and
  cover are used once the song is saved to this computer.
- Switching a source off marks its songs unavailable rather than hiding them.

Manga
- Manga needs its Suwayomi-Server: nothing in Manga works while the server is not running, your
  own files included (they are read through its Local source). It starts in a few seconds when
  Manga is opened and uses a few hundred megabytes of memory while it runs.
- Extensions are Android program code that Suwayomi runs inside its own process, with your user's
  rights and without a sandbox. They never run inside DebridOne, and the server can be reached by
  DebridOne only, but an extension can do what any program you start can do. Earlier versions of
  Manga ran their (different) extensions with file access switched off; this is a real change.
- Only the Komga extension and the Local source were proven to work. Sources behind a browser
  check (Cloudflare and the like) fail with Suwayomi's message: its embedded browser is off (and
  does not exist on macOS). A FlareSolverr of your own can be named in the server's settings.
- Each profile has its own server folder, so an extension store, an extension or a tracker
  sign-in set up in one profile is not there in another.
- Other devices cannot use the server DebridOne starts. A server of your own is supported with
  Suwayomi's "basic" sign-in or none; while a profile uses it, files of this computer and cloud
  archives cannot be opened in Manga.
- A tracker sign-in in the browser passes through Suwayomi's own page (`suwayomi.org`), which
  sends the browser back to DebridOne on this computer. If that page is unreachable, paste the
  address the browser ended at.
- Series from sources of the earlier version are not fetched again automatically: they are listed
  and carried over when you have added them from a new source (matching chapters by number).
  Chapters the earlier version downloaded stay where they were but cannot be used.
- 7-Zip archives are not opened. Pinch-to-zoom is not available (Cmd + scroll and keys are).
- The download is about 405 MB: the app carries Suwayomi-Server (its jar alone is 183 MB) and a
  Java 21 runtime.
- The bundled Java is the official, publisher-signed build inside an unsigned app; whether macOS
  asks about it separately on a downloaded copy has not been checked on a personal Mac.
- MyAnimeList lookups need your own API client ID (its API has no anonymous access).

Upstream compatibility notes for later phases
- Hydra's main process depends on Electron, a Rust native addon and Hydra's hosted API, and its
  libtorrent bridge is prebuilt only for Windows and Linux. It will be ported selectively.
- Eclipse Music itself is closed source. Its addon format is published (`eclipsemusic.app/docs`)
  and is what Music's addon support follows; nothing else of Eclipse is used.
