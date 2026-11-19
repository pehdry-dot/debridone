# Upstream projects

| Project | Role | Licence | Pinned at |
|---|---|---|---|
| [NuvioMedia/NuvioDesktop](https://github.com/NuvioMedia/NuvioDesktop) (`Dev`) | Foundation and Streaming module; this repository's history continues from it | GPL-3.0 | `0b283d0a393079c9f7e5ff4b4e825dba76ce80d4` |
| [hydralauncher/hydra](https://github.com/hydralauncher/hydra) (`main`) | Reference for the Games module (to be ported) | MIT | `ff952e2312e191bcf0836210540ca03b169b80d3` |
| [Suwayomi/Suwayomi-Server](https://github.com/Suwayomi/Suwayomi-Server) `v2.4.2366` | Manga's backend: the unmodified release jar, bundled in the macOS app, run as a separate process and reached through its GraphQL API. | MPL-2.0 | release `v2.4.2366`, jar SHA-256 in `scripts/debridone/fetch-suwayomi-runtime.sh` |
| [Eclipse Temurin](https://adoptium.net) JRE 21 | The Java runtime Suwayomi-Server runs on; the official build is bundled in the macOS app beside it. | GPL-2.0 with Classpath Exception | `21.0.13.1+2` (Apple Silicon), `21.0.13+9` (Intel, Linux tests); checksums in the same script |
| Mihon/Tachiyomi extensions | Manga's sources. Installed by the user into their Suwayomi-Server from a store they add; none is bundled, named or preconfigured. | each extension's own (commonly Apache-2.0) | not pinned: the user's choice |
| [Suwayomi/Suwayomi-JUI](https://github.com/Suwayomi/Suwayomi-JUI) | Read as a reference for starting the server from a desktop application. No code copied. | MPL-2.0 | `1.3.3` |
| [Suwayomi/website](https://github.com/Suwayomi/website) (`suwayomi.org/tracker-oauth`) | The page trackers send the browser to after a sign-in; it forwards to the address the client names. DebridOne names a listener on this computer, as Suwayomi's other clients name theirs. Not bundled. | MPL-2.0 | as served |
| [xgi/houdoku](https://github.com/xgi/houdoku) and [xgi/tiyo](https://github.com/xgi/tiyo) | The earlier Manga engine was built on them. They are no longer used or bundled; the library made with them is carried over (see MANGA.md). | MIT | — |
| Eclipse Music | Feature reference for Music. Its published addon format (`eclipsemusic.app/docs`) is what Music's addon support follows. No source is available or used. | proprietary | docs read 2026-11-17 |
| [TheVolecitor/BeatBoss](https://github.com/TheVolecitor/BeatBoss) (`main`) | Second description and a client implementation of the same addon format (`ADDONS.md`, `lib/core/services/addon_service.dart`), read to match real-world field names. No code copied. | MIT | `abc1b86f5ec266d499337fa77a602a2028327e9e` |

Nuvio Desktop's code is the foundation of this repository. No Hydra or Suwayomi source file is
copied into it: Manga's screens, reader, server manager and API client are DebridOne code, written
against Suwayomi-Server's own GraphQL schema, and Suwayomi-Server itself is used as released, as a
separate program. What is redistributed (Suwayomi-Server, the Temurin runtime) is listed in
`THIRD_PARTY_NOTICES.md`. How extensions work: `MANGA_PLUGINS.md`.

## Merging Nuvio updates

```bash
git remote add upstream https://github.com/NuvioMedia/NuvioDesktop.git
git fetch upstream Dev
git merge upstream/Dev
```

DebridOne keeps its changes to upstream files small and marked with a `DebridOne:` comment:

| Upstream file | Change |
|---|---|
| `settings.gradle.kts`, `build.gradle.kts`, `gradle/libs.versions.toml` | Project name, `:debridone:*` projects, library versions |
| `composeApp/build.gradle.kts` | Package name, bundle id `com.debridone.desktop`, `debridone://` scheme, icons, `java.sql` runtime module, DebridOne source directories, branded resources task, optional `local.properties`, Discord/supporter defaults blanked; `buildMacosMusicBridge` task and its output added to the packaged native libraries; `prepareMangaHostAppResources` puts Manga's extension host and its Node.js runtime into the app resources, and the app image marks that runtime executable again |
| `core/storage/DesktopStorage.kt` | Storage under the Streaming module directory; atomic writes; per-profile key removal |
| `core/auth/AuthRepository.kt` | On-device account backend for sign-up, sign-in, guest, sign-out |
| `features/profiles/ProfileRepository.kt`, `ProfileModels.kt`, `ProfileEditScreen.kt`, `AvatarRepository.kt`, `ProfileStorage.desktop.kt` | On-device profiles, PINs, imported pictures |
| `features/auth/AuthScreen.kt` | Device-link sign-in hidden without a sync server |
| `AppGate.kt`, `MainAppContent.kt`, `MainTabsDestination.kt` | Hand main content to the shell; report playback; accept tab requests |
| `features/membership/MemberAccessRepository.kt`, `ThemeAccess.kt`, `features/settings/MemberBrandWordmark.kt` | All themes unlocked; violet start theme; no tier badge |
| `features/party/PartyApi.kt` | Watch-party availability is not requested without a sync server |
| `features/settings/AppIconPlatform.desktop.kt` | Upstream's app-icon switcher off (it installs Nuvio-branded icons) |
| Tests: `LibraryRepositoryTest`, `ThemeAccessTest`, `DesktopStorageTest` | Expectations updated for the rebranded string, the unlocked themes and the `.bak` file kept by atomic writes |
| `core/build/AppFeaturePolicy.desktop.kt` | Supporter pages, donation prompts and the in-app updater off |
| `desktopMain/.../Main.kt` | Start-up of DebridOne services, window title and icon, URL scheme mapping; `--music-self-test` and `--manga-self-test` handled before anything else starts |

Changes for Play Next, the Streaming navigation and Games removal:

| Upstream file | Change |
|---|---|
| `core/network/NetworkStatusRepository.kt` | No account backend configured now counts as online (was "servers unreachable", which every offline check treated as offline) |
| `features/player/PlayerNextEpisodeAutoPlay.kt`, `PlayerNextEpisodeStreamSelection.kt`, `PlayerScreenRuntimeSourceActions.kt`, `PlayerScreenRuntimeState.kt`, `PlayerScreenRuntimeUi.kt`, `PlayerEngine.kt`, `PlayerStreamsRepository.kt` | One next-episode transition; a press is tried whatever the probe says; similarity selection; reasons for the source list; cancel; test hooks |
| `features/streams/StreamAutoPlaySelector.kt` | Exposes whether a stream can start by itself |
| `features/player/PlayerSettingsRepository.kt`, `PlayerSettingsStorage*.kt`, `features/settings/PlaybackUpNextSettings.kt` | Two settings: match the current source, countdown |
| `desktopMain/resources/player-ui/controls.{html,js,css}`, `desktop/NativePlayerController.kt` | The next-episode card cancels while busy and shows a spinner while searching |
| `core/ui/FloatingNavigationBar.kt`, `core/ui/jelly/JellyTabs.kt`, `MainTabsDestination.kt` | An item can be icon-only with a tooltip; on desktop, Settings is a cogwheel |
| `composeResources/values*/strings.xml` | New keys appended (English, pt, pt-BR); no existing string changed |

Changes for the connected accounts (see `CLOUD.md`):

| Upstream file | Change |
|---|---|
| `AppShellHost.kt` (DebridOne's own seam file) | `requestPlayback` / `streamingPlaybackRequests`: the shell asks Streaming to play one video file; `resumeShellContent`: Continue Watching on such a file asks the shell for a new address; `librarySource`: a list the shell adds to the Library |
| `MainAppContent.kt` | Collects playback requests and builds the player launch like Nuvio's own cloud files do (content type `cloud`); Continue Watching items whose id starts with `debridone:` go to the hook |
| `features/library/LibraryScreen.kt` | One more chip in the source switch ("Connected Accounts") when the shell contributes a list, and one branch that lets the shell add its rows. Nuvio's own Saved, Cloud and Servers views and its debrid settings are unchanged |

New upstream-side files: `features/streams/StreamSimilarity.kt`; tests `NextEpisodeRootCauseTest`,
`NextEpisodeTransitionTest`, `StreamSimilarityTest`. Also: `AppShellHost.kt`, `core/auth/LocalAccountBackend.kt`,
`features/profiles/LocalAvatarImporter.kt`.

Removed: upstream's GitHub workflows (release, issue triage, store publishing). They depend on
Nuvio's secrets and infrastructure.

Strings are not edited in place; see "Rebranding" in `ARCHITECTURE.md`.
