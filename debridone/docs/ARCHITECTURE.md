# DebridOne architecture

DebridOne is a macOS desktop app built on [Nuvio Desktop](https://github.com/NuvioMedia/NuvioDesktop)
(Kotlin Multiplatform + Compose). It hosts independent entertainment modules in one window: Streaming, Music and Manga.
Games is dormant: its module stays in the repository, is not linked into the app, and its saved data is left untouched.

| Module | Engine | State in this build |
|---|---|---|
| Streaming | Nuvio Desktop itself (`:composeApp`) | Working: Nuvio's features, running on on-device accounts |
| Music | Original code (`:debridone:modules:music`), audio through libmpv | Working: library, player, playlists, sources, metadata. See [MUSIC.md](MUSIC.md) |
| Games | Dormant. `:debridone:modules:gaming` is kept but not part of the application | Not in the app |
| Manga | DebridOne's own screens and reader (`:debridone:modules:manga`); the backend is the unmodified Suwayomi-Server, one separate process per profile, reached through its GraphQL API; sources are the Mihon/Tachiyomi extensions the user installs in it | Working: extensions and stores, sources, search, reader, library, local files, downloads, trackers, metadata, migration from the earlier engine. See [MANGA.md](MANGA.md) and [MANGA_PLUGINS.md](MANGA_PLUGINS.md) |

### Manga's server

```
Manga screens and reader → Manga engine (Kotlin) → typed GraphQL client → Suwayomi-Server (own process) → extensions
```

Manga's backend is Suwayomi-Server, the unmodified release jar, with a Java 21 runtime; both are
inside the application. `SuwayomiServerManager` starts one server per profile, in that profile's
own data folder, bound to `127.0.0.1` on a free port, with the web interface, tray icon and
embedded browser off and a password made anew at every start. `SuwayomiApi` is the typed client:
one function per GraphQL operation DebridOne uses, each document validated against the pinned
server in the tests. `MangaServer` is the one connection (the managed server, or a server the
person runs themselves), `MangaRepository` and the classes around it give the screens the library,
chapters, pages, downloads, sources, extensions, trackers and server settings. Suwayomi is the
system of record; what it has no field for (reading status, favourite, hand edits, bookmarks with
notes) is kept in Suwayomi's own per-series "meta", so it stays with the profile's data and
travels in its backups. Extension code runs in the server's process, never in DebridOne's.

Mangayomi JavaScript sources reach the same server through the **Mangayomi bridge**
(`:debridone:modules:manga-bridge`): a Kotlin `HttpSource` compiled against the server's own jar,
packed by DebridOne with one source's index entry and code into a `.jar` extension that the server
installs (`ExtensionImports`, `MangayomiPackage`). Inside the server, each source's code runs in a
GraalJS context with host access, I/O, threads and class lookup switched off; Mangayomi's API is a
JavaScript layer (`runtime.js`) over a single host function that the bridge answers (HTTP through
the server's client, jsoup parsing, preferences, dates, crypto). `ExtensionIndexDetector` and
`MangayomiCompatibility` recognise an index and judge each entry before anything is installed.

### Settings and configuration panels

A section's settings open inside the section, over the page that is showing, and a "Configure"
opens in the tab it was clicked in. Three shared pieces do this:

- `core/ui/SideDrawer`: a panel drawn over its area rather than instead of it (a side drawer on a
  wide window, an overlay filling the section on a narrow one), with Close, Escape and a click
  outside. The page underneath keeps its scroll position, text, selection and playback.
- `shell/ModuleSettingsPanel`: the categories of a section's settings (Music, Manga); Streaming
  hosts Nuvio's own settings pages in the same drawer (`StreamingSettingsOverlay`).
- `core/ui/PluginConfigurationPanel`: one panel for configuring an addon, plugin, extension source,
  tracker or server, filled per module.

Only the shell navigates to the application's own Settings section; a link from a section to one
of its pages (Connected Accounts, Download settings) shows that page as a panel over the section.
The architecture tests check that section code does not navigate to Plugins or Settings.

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

The modules share the window, the account and low-level utilities. They share nothing else.

1. **Code.** A module's Gradle project may depend only on `:debridone:platform` and
   `:debridone:module-api`. It cannot import another module because that module is not on its
   classpath. `SourceBoundaryTest` also scans build files and imports, including the module
   screens inside `composeApp/src/debridoneMain`, and fails the build on a violation.
2. **Storage.** Each module receives a `ModuleStorage` bound to its own directory, database file,
   cache directory and secret namespace. There is no API to obtain another module's storage.
3. **Plugins and metadata.** `PluginRegistry` and `MetadataProviderRegistry` are UI contracts. Every
   module has its own implementation over its own tables (`music_plugins`, `hydra_sources`,
   `manga_extensions`). There is no method that lists plugins across modules. The Plugins screen
   asks each registry separately; its tabs are separate screens, not filters. Streaming's tab
   hosts Nuvio's own addon and plugin screens.
4. **Failure.** Each engine has its own supervised coroutine scope and status. `ModuleHost` starts
   them independently, so a module that fails to open its storage is shown as failed while the
   others keep running. `ModuleIsolationTest` covers this, including recovery from a damaged
   database.

The shell's Home screen is a launch dashboard. It has no cross-module search and lists no content.

### Connected accounts are the application's, not a module's

The Real-Debrid and TorBox accounts (`:debridone:cloud`, see `CLOUD.md`) sit beside the modules
as shared infrastructure, like accounts and profiles: one sign-in per provider, one cached file
listing, one download manager. They do not make the modules share anything:

- No module's engine may import `com.debridone.cloud`, and no module's screens may import it or
  the app's cloud pages (`com.debridone.app.cloud`). `SourceBoundaryTest` enforces both.
- Each module is reached through one narrow bridge that lives with the cloud pages and hands over
  only that module's kind of file: a playback request for one video (`AppShellHost`), the
  `CloudAudioSource` interface that Music itself defines (account names, audio file names, one
  address when a song starts, "save these"), a downloaded archive for Manga's own importer.
- No sign-in, key or file list reaches a plugin, an addon or an extension of any module, and the
  Downloads section is a file browser: it has no covers, no metadata and no library of its own.

## Storage

macOS locations (`AppPaths`):

```
~/Library/Application Support/DebridOne/
  accounts/accounts.db           accounts, profiles, device session, shell settings
  accounts/avatars/              profile pictures imported from this Mac
  modules/streaming/             Nuvio's own stores (.properties files)
  modules/music/music.db
  modules/gaming/gaming.db
  modules/manga/manga.db         Manga's settings; the earlier library, kept for migration
  modules/manga/suwayomi/profile-<id>/   one profile's Suwayomi-Server data: library, downloads,
                                 extensions, stores, tracker sign-ins, links to the person's own files
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
