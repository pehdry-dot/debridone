# Upstream projects

| Project | Role | Licence | Pinned at |
|---|---|---|---|
| [NuvioMedia/NuvioDesktop](https://github.com/NuvioMedia/NuvioDesktop) (`Dev`) | Foundation and Streaming module; this repository's history continues from it | GPL-3.0 | `0b283d0a393079c9f7e5ff4b4e825dba76ce80d4` |
| [hydralauncher/hydra](https://github.com/hydralauncher/hydra) (`main`) | Reference for the Games module (to be ported) | MIT | `ff952e2312e191bcf0836210540ca03b169b80d3` |
| [xgi/houdoku](https://github.com/xgi/houdoku) (`master`) | Reference for the Manga module (to be ported) | MIT | `a0c69348d5d3cf018bb6d127de86c63434913d14` |
| [xgi/tiyo](https://github.com/xgi/tiyo) (`main`), npm `@tiyo/core` 1.0.3 | Manga extensions, to run unmodified | MIT | `84e75a3d6afe73cc698da86a45f45f81f50e067a` |
| Eclipse Music | Feature reference for Music only. No source is available or used. | proprietary | — |

Only Nuvio Desktop's code is in this repository today. No Hydra, Houdoku or Tiyo code has been
copied yet; when it is, record the files and commit here and keep the notices in
`THIRD_PARTY_NOTICES.md`.

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
| `composeApp/build.gradle.kts` | Package name, bundle id `com.debridone.desktop`, `debridone://` scheme, icons, `java.sql` runtime module, DebridOne source directories, branded resources task, optional `local.properties`, Discord/supporter defaults blanked |
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
| `desktopMain/.../Main.kt` | Start-up of DebridOne services, window title and icon, URL scheme mapping |

New upstream-side files: `AppShellHost.kt`, `core/auth/LocalAccountBackend.kt`,
`features/profiles/LocalAvatarImporter.kt`.

Removed: upstream's GitHub workflows (release, issue triage, store publishing). They depend on
Nuvio's secrets and infrastructure.

Strings are not edited in place; see "Rebranding" in `ARCHITECTURE.md`.
