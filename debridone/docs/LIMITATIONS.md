# Known limitations

Verification status
- Verified by running the app from source on Linux (virtual display): account sign-up, the
  remembered sign-in after a restart, creating a second profile, a different theme and different
  Music appearance per profile surviving a full restart, the selected section being restored,
  the four Plugins tabs, and the Cmd/Ctrl+1-4, comma and F shortcuts.
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
- Music, **not verified on Apple Silicon**: GitHub cancelled the Apple Silicon jobs of these runs
  for lack of runner capacity ("not acquired by Runner"). The code is the same; an Apple Silicon
  build is produced whenever such a runner is available.
- Music, **not verified against live servers**: the Navidrome/Subsonic and Jellyfin adapters,
  radio addresses, MusicBrainz, the Cover Art Archive and LRCLIB were tested against local
  stand-in servers that answer in those services' formats (including wrong passwords and
  unreachable servers). The build environment could not reach the real services.
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
- Games and Manga have storage, registries, metadata settings and appearance only. They cannot
  launch or read anything yet, and their plugin import is disabled.
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
- Cmd+F opens search in Streaming and in Music; Games and Manga have no search yet.
- The Downloads section shows Streaming downloads; opening one continues in Streaming.
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

Upstream compatibility notes for later phases
- Hydra's main process depends on Electron, a Rust native addon and Hydra's hosted API, and its
  libtorrent bridge is prebuilt only for Windows and Linux. It will be ported selectively.
- Tiyo's client expects an Electron `BrowserWindow`; it will run in a bundled Node.js process with
  a small stand-in. `@tiyo/core` was last published in May 2024, so some bundled sources may no
  longer work. Compatibility will be claimed per extension, only after testing.
- `eclipsemusic.app` could not be reached from the build environment and no public source or SDK
  was found, so Music is original work and Eclipse addon compatibility is not claimed.
