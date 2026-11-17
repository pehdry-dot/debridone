# Manga extensions: Houdoku / Tiyo compatibility

Manga's online sources are **Houdoku's source extensions, run unmodified**. DebridOne does not
define a plugin format of its own for Manga. This page says exactly what is loaded, how, what was
tested, and where compatibility stops.

Extensions belong to Manga only. Streaming add-ons and Music plugins are different things, live in
different registries and cannot be installed here (nor Manga's there).

## What Houdoku loads, and what DebridOne loads

Audited upstream code (see `UPSTREAM.md` for the pinned commits):

- **Houdoku 2.16.0** supports exactly one plugin package: **`@tiyo/core`** from the npm registry.
  It installs it with `aki-plugin-manager` into `<userData>/plugins`, loads it with `aki.load(…)`
  and creates `new TiyoClient(spoofWindow)`. Any other package is ignored ("Ignoring unsupported
  plugin").
- **`@tiyo/core` 1.0.3** (the latest release, May 2024) contains 44 sources. Every source offers
  `getSeries`, `getChapters`, `getPageRequesterData`, `getPageUrls`, `getImage`, `getSearch`,
  `getDirectory`, `getSettingTypes`, `getSettings`, `setSettings` and `getFilterOptions`.
- Older Houdoku versions loaded one npm package per source, **`@houdoku/extension-*`**, built on
  `houdoku-extension-lib`. Current Houdoku no longer loads them.

DebridOne therefore accepts:

| Package | What it is | Status in DebridOne |
|---|---|---|
| `@tiyo/core` **1.0.3** | Houdoku's current extension package | **Verified** — loaded and exercised by DebridOne's tests (below) |
| `@tiyo/core` other 1.x | Same interface | Installable, labelled **Not tested** |
| `@tiyo/core` 0.x / 2.x+ | Different interface | Refused |
| `@houdoku/extension-*` | Houdoku's older per-source packages | Hidden unless you switch on "Show older Houdoku extensions" in Plugins › Manga › Browse; run through an adapter, labelled **Not tested**. The adapter itself is verified with `@houdoku/extension-komga` 1.3.1 only. |
| anything else | Not a Houdoku extension | Refused before anything is downloaded |

A package name, a URL or a file is never assumed to be an extension: the name must be one of the
two above, the version must fit, and the package must then load and register sources in a
throw-away trial run before it can be installed.

## How it runs

```
Manga screens (Compose)
   → Manga services (Kotlin)
      → ExtensionHost (Kotlin): one helper process, JSON lines over its stdin/stdout
         → Node.js 22 (bundled in the app) running debridone/modules/manga/host/src/host.js
            → aki-plugin-manager: list / load / unload      ← the same library Houdoku uses
               → @tiyo/core, unmodified: new TiyoClient(window).getExtensions()
```

- **Same loader, same folder layout.** Packages are unpacked the way Aki lays them out
  (`plugins/@tiyo/core/…` with nested `node_modules`) and loaded with Aki's own `load`.
- **No visible Houdoku, no Electron.** Tiyo needs two things from Electron: the object it is
  constructed with and a hidden browser window for some sources. DebridOne supplies a stand-in
  with the same methods (`loadURL`, `webContents.executeJavaScript`, cookies, …) that fetches
  pages over HTTP and runs no page scripts.
- **No network port.** The app and the helper talk over the helper's standard input and output
  only. Whatever a plugin prints goes to a diagnostics log (Plugins › Manga › Settings), never
  into the protocol.
- **Bundled runtime.** The macOS app contains the official Node.js 22.23.4 build for its
  architecture, checked against its published SHA-256 when the app is built. A Node.js installed
  on your Mac is never used. (When running from source, `scripts/debridone/dev.sh` fetches the
  same build.)
- **A plugin cannot take the app down.** Every call has a time limit; a source that throws fails
  that call with a message; if the helper crashes it is started again, and after five crashes in a
  minute it stays stopped until you restart it from the settings. Local files keep working
  throughout.

## Installing

Plugins › Manga › Browse reads the package's entry in the npm registry and shows its versions with
their status. Installing:

1. Resolves the package's dependencies (for 1.0.3 from a list pinned in this repository: 79
   archives with their SHA-512 checksums; for other versions live from the registry).
2. Downloads every archive from `registry.npmjs.org` only and checks its SHA-512.
3. Unpacks into a staging folder, refusing links, absolute paths, `..`, oversized archives and
   more than 400 packages. **No install script is ever run.**
4. Loads the staged package in a separate trial helper: it must export `TiyoClient` and register
   at least one source.
5. Shows a review: what the package is, its licence, how many sources it registers, what it can
   and cannot touch, and any warnings (for example, sources that expect a browser window).
6. Only after you confirm is it moved into place and loaded.

You can also import a package archive (`.tgz`) from a file; it goes through steps 3–6 and must be
`@tiyo/core` or `@houdoku/extension-*`. Updates, switching a package off, and uninstalling are on
Plugins › Manga › Installed. Packages are shared by all profiles; which sources are enabled, their
order, their settings and logins are per profile.

## What an extension can and cannot touch

Extension code is third-party JavaScript. It runs in the helper process, never inside the app.

- **Files:** Node's permission model limits the helper to *reading* its own program folder and the
  plugin folder and *writing* one scratch folder. It cannot read your documents, DebridOne's
  databases, or other sections' data; it cannot start other programs, load native add-ons or start
  worker threads.
- **Environment:** the helper starts with an emptied environment and a private home folder. It
  does not see your shell variables.
- **Credentials:** a source's own settings (for example the address and login of your Komga
  server) are stored in the macOS Keychain per profile and handed only to that source. Account
  passwords, tracking tokens and other sections' secrets are never given to the helper.
- **Network:** an extension can make web requests — that is its job. Requests to one source are
  spaced out and limited to three at a time. An optional proxy for extensions can be set.

This is process isolation with Node's permission model, not a full operating-system sandbox; an
extension can still talk to any web server. Install only packages you trust.

## What was tested

"Verified" is used only for things DebridOne's tests actually ran.

**The package.** The real `@tiyo/core` 1.0.3, downloaded from the npm registry by DebridOne's own
installer, is loaded through the helper on the pinned Node.js in the automated tests and in the
packaged macOS app on CI: it loads, all 44 sources register, and settings, directory, search,
series details, chapters, page lists and page images are exercised end to end through Tiyo's
**Komga** source against a stand-in Komga server, including a restart of the app and resuming.

**Live checks** (run by hand with `DEBRIDONE_LIVE_TESTS=1`, and in the running app):

| Source | Result |
|---|---|
| Komga (Tiyo), against Komga's public demo server | Browse, search, series, chapters, pages, images, reading, resume after restart and downloading a chapter all work |
| MangaDex (Tiyo) | Browse with its filters and sort orders, search, series details, chapters and page lists work. Page *images* come from changing hosts the build environment could not reach, so they were not tested there. |
| `@houdoku/extension-komga` 1.3.1 (legacy adapter) | Search, series, chapters, pages against the demo server |

**Every other source is "Not tested"** until you run **Test** on it (Plugins › Manga › Sources),
which tries browse, search, series details, chapters and pages on your Mac and records which steps
passed and when. Tiyo was last released in May 2024; expect some of its sources to have stopped
working because their sites changed. DebridOne reports that per source and does not hide it.

## Limits

- **Sources that need a browser window.** Ten of Tiyo's sources (ComicK, Komikcast, Mangabat,
  Mangakakalot, MangaLife, Manganato, MangaSee, nhentai, ReadComicOnline, TCB Scans) load pages in
  Houdoku's hidden browser window. DebridOne's stand-in fetches the page without running its
  scripts, so these work only where the site answers a plain request. A site that answers with a
  browser check is reported as "needs a browser check"; DebridOne does not try to get past it.
- **Logins and access.** A source's login is whatever that source's own settings ask for.
  DebridOne does not work around logins, paywalls, rate limits or regional blocks.
- **Legacy packages** are individually untested (see the table at the top), and their browser-window
  function is not available.
- **Tiyo quirks are kept as they are**, because the package is not modified. For example, its
  language table files Japanese under a code no source uses, so Japanese-language sources show as
  "Multiple languages".
- **Gatekeeper.** Builds are unsigned. The bundled Node.js carries its publisher's own signature;
  whether macOS asks about it separately on a downloaded, quarantined copy has not been checked on
  a personal Mac.

## Licences

Houdoku, Tiyo, Aki Plugin Manager and the legacy Houdoku extensions are MIT-licensed projects by
xgi and their contributors. DebridOne includes Aki Plugin Manager and its dependencies (in the
helper) and the Node.js runtime; it does **not** include Tiyo or any source — you install those.
Notices are in `THIRD_PARTY_NOTICES.md`; installed packages keep their own licence files, and the
Node.js licence is in the app as `manga-host/NODE-LICENSE.txt`.
