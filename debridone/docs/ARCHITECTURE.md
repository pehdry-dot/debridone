# DebridOne architecture

DebridOne is a macOS desktop app built on [Nuvio Desktop](https://github.com/NuvioMedia/NuvioDesktop)
(Kotlin Multiplatform + Compose). It hosts four independent entertainment modules in one window.

| Module | Engine | State in this build |
|---|---|---|
| Streaming | Nuvio Desktop itself (`:composeApp`) | Working: Nuvio's features, running on on-device accounts |
| Music | Original code (`:debridone:modules:music`), audio through libmpv | Working: library, player, playlists, sources, metadata. See [MUSIC.md](MUSIC.md) |
| Games | Kotlin port of Hydra Launcher functionality (`:debridone:modules:gaming`) | Foundation only |
| Manga | Houdoku-style reader with unmodified Tiyo extensions (`:debridone:modules:manga`) | Foundation only |

"Foundation only" means the module has its own database and registries but no engine or content
screens yet. The app says so wherever such a module appears.

## Layout

```
composeApp/                          upstream Nuvio = Streaming engine, app entry point, packaging
  src/commonMain, src/desktopMain    upstream sources, with small marked "DebridOne:" patches
  src/debridoneMain/kotlin           DebridOne shell and module screens (com.debridone.app)
  src/debridoneMain/composeResources brand images that replace upstream's at build time
debridone/
  platform/                          shared infrastructure only: SQLite, migrations, backups,
                                     atomic files, paths, secrets (Keychain)
  module-api/                        contracts between the shell and a module
  accounts/                          accounts.db: accounts, profiles, device session, settings
  modules/music | gaming | manga     one Gradle project per module engine
  architecture-tests/                build-breaking isolation tests
  docs/                              this documentation
```

`:composeApp` remains the application and packaging module, so all of Nuvio's native player build
logic is untouched. It depends on the `:debridone:*` projects; none of them depends on it.

## Module isolation

The four modules share the window, the account and low-level utilities. They share nothing else.

1. **Code.** A module's Gradle project may depend only on `:debridone:platform` and
   `:debridone:module-api`. It cannot import another module because that module is not on its
   classpath. `SourceBoundaryTest` also scans build files and imports, including the module
   screens inside `composeApp/src/debridoneMain`, and fails the build on a violation.
2. **Storage.** Each module receives a `ModuleStorage` bound to its own directory, database file,
   cache directory and secret namespace. There is no API to obtain another module's storage.
3. **Plugins and metadata.** `PluginRegistry` and `MetadataProviderRegistry` are UI contracts. Every
   module has its own implementation over its own tables (`music_plugins`, `hydra_sources`,
   `manga_extensions`). There is no method that lists plugins across modules. The Plugins screen
   asks each registry separately; its tabs are four screens, not four filters. Streaming's tab
   hosts Nuvio's own addon and plugin screens.
4. **Failure.** Each engine has its own supervised coroutine scope and status. `ModuleHost` starts
   them independently, so a module that fails to open its storage is shown as failed while the
   others keep running. `ModuleIsolationTest` covers this, including recovery from a damaged
   database.

The shell's Home screen is a launch dashboard. It has no cross-module search and lists no content.

## Storage

macOS locations (`AppPaths`):

```
~/Library/Application Support/DebridOne/
  accounts/accounts.db           accounts, profiles, device session, shell settings
  accounts/avatars/              profile pictures imported from this Mac
  modules/streaming/             Nuvio's own stores (.properties files)
  modules/music/music.db
  modules/gaming/gaming.db
  modules/manga/manga.db
  backups/<database>/            rolling snapshots; backups/<database>/corrupt/ keeps damaged files
~/Library/Caches/DebridOne/<module>/
```

`DEBRIDONE_DATA_DIR` / `DEBRIDONE_CACHE_DIR` (or `-Ddebridone.data.dir`) relocate these, which the
tests and `scripts/debridone/dev.sh --sandbox` use.

SQLite databases are opened through `SqliteDatabases.open`:

- WAL journal, foreign keys on, `synchronous=FULL`.
- Forward-only migrations tracked in `PRAGMA user_version`; each runs in one transaction with its
  version bump, so it applies fully or not at all.
- A snapshot is taken before any schema upgrade, and a routine one about daily (five are kept).
- On open, a damaged file is moved to `backups/<db>/corrupt/` and the newest healthy snapshot is
  restored; if there is none a new database is created. The user is told when this happens.
- A database written by a newer build is refused and left untouched.

Nuvio's `.properties` stores are now written atomically (temp file, fsync, `.bak`, rename) and
fall back to the `.bak` copy when the main file is unreadable.

Secrets go through `SecretStore`: the macOS Keychain on macOS, an encrypted-file fallback elsewhere
for development. Modules get a view restricted to `module.<key>[.profile.<id>]`.

## Accounts and profiles

Accounts are on-device. `accounts.db` stores an Argon2id hash of the password, never the password.
Sign-in is rate limited; profile PINs are hashed and rate limited too. Signing out ends the session
and deletes nothing. Deleting an account or profile erases what it owned in every module.

A profile id is unique across all accounts on the device and is never reused. Nuvio scopes every
per-profile value by this id, and the other modules key their rows by it, so profile data cannot
collide between accounts or resurface under a later profile.

Nuvio's sign-in, profile chooser and profile editor are reused. Two seams connect them to
`accounts.db`:

- `LocalAccountBackend` (in `core/auth`) serves sign-up, sign-in, guest access, profile ids and
  PINs. `AuthRepository` and `ProfileRepository` call it instead of Supabase when it is active.
- `ProfileStorage` (desktop) reads and writes profiles through `LocalProfilePayloads`. Saving only
  creates or updates profiles; deletion is always an explicit call, so a transiently empty list in
  memory can never delete profiles.

Local accounts reuse upstream's "anonymous" code paths, which is what keeps every remote sync call
switched off.

**Sync.** There is no cross-device sync in this build and the app says "Local only". The
`SyncBackend` interface is the seam for a future backend. Upstream's ability to connect to a
Nuvio-compatible server is still present behind the sign-in screen's menu; connecting one switches
Streaming back to upstream's remote accounts. That path is untested in DebridOne.

## Shell

`AppShellHost` (in `com.nuvio.app`) is the seam between Nuvio and the shell. After sign-in and
profile selection Nuvio hands its main content to `DebridOneShell` as one composable slot. The
shell draws the single sidebar and shows one section at a time. Streaming is kept alive but
paused while another section is open, and it reports when it needs the whole window for playback.
Streaming uses its top navigation bar because the shell owns the sidebar.

Shell state (selected section, sidebar style) is stored per profile in `accounts.db`. Each module's
appearance is stored per profile in that module's own database.

## Rebranding without forking the resources

Upstream's `composeResources` directory is not edited. The `debridoneBrandedResources` Gradle task
writes a branded copy: user-visible strings say "DebridOne" (except the two that credit Nuvio),
a few strings get DebridOne wording, and brand images are replaced from
`src/debridoneMain/composeResources`. This keeps upstream merges free of string conflicts.
