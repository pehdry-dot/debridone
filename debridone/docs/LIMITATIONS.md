# Known limitations (Milestone 1)

Verification status
- Verified by running the app from source on Linux (virtual display): account sign-up, the
  remembered sign-in after a restart, creating a second profile, a different theme and different
  Music appearance per profile surviving a full restart, the selected section being restored,
  the four Plugins tabs, and the Cmd/Ctrl+1-4, comma and F shortcuts.
- **Not verified:** the macOS `.app`/`.dmg`. It has not been built or run. The CI workflow that
  builds and smoke-tests it has not been run either, because this repository could not be pushed
  to from the build environment.
- **Not verified:** installing a Streaming addon and playing video in this fork. The build
  environment could not reach addon hosts. Nuvio's own code paths for these are unchanged.
- **Not verified:** the macOS Keychain store. Its test only runs on macOS.
- Nothing is signed or notarized.

Modules
- Music, Games and Manga have storage, registries, metadata settings and appearance only. They
  cannot play, launch or read anything yet, and their plugin import is disabled.
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
- Cmd+F only does something in Streaming; the other modules have no search yet.
- The Downloads section shows Streaming downloads; opening one continues in Streaming.
- macOS menu-bar items for the modules, notifications and media keys are not implemented.

Upstream compatibility notes for later phases
- Hydra's main process depends on Electron, a Rust native addon and Hydra's hosted API, and its
  libtorrent bridge is prebuilt only for Windows and Linux. It will be ported selectively.
- Tiyo's client expects an Electron `BrowserWindow`; it will run in a bundled Node.js process with
  a small stand-in. `@tiyo/core` was last published in May 2024, so some bundled sources may no
  longer work. Compatibility will be claimed per extension, only after testing.
- `eclipsemusic.app` could not be reached from the build environment and no public source or SDK
  was found, so Music is original work and Eclipse addon compatibility is not claimed.
