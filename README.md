# DebridOne

**All Your Entertainment. One Platform. Your Rules.**

An interactive prototype of a unified entertainment manager: movies & TV, music, PC games, manga and books in one app, with a universal metadata editor, user-managed sources, a download manager and a deep theming system.

React 18 · TypeScript · Vite · Tailwind CSS · Zustand · React Router · Framer Motion · Lucide

## Run it

```bash
./run.sh
```

That starts the dev server at http://localhost:5173 (it uses the Node.js in `.tools/` if none is installed system-wide). With your own Node 18+:

```bash
npm install
npm run dev
```

A production build is in `dist/` (`npm run build` recreates it). It is fully static, so any web server works, including the Python that ships with macOS:

```bash
python3 -m http.server 8080 --directory dist
```

## Personal use: accounts, addons and install

**Accounts.** Create one from the profile menu (avatar, top right) or Settings → Account. Accounts live on this device and are Pro: every customisation feature is unlocked and no payment is requested. Each account has its own library, progress, themes, addons and downloads. Passwords are stored only as salted PBKDF2 hashes. There is no server, so there is no password reset and nothing syncs between devices.

**Where your data lives.** In the Mac app, your profile (library, progress, history, settings, addons and themes) is saved to `profile-store.json` in the app's data folder (`~/Library/Application Support/DebridOne`). Logging out keeps it; only *Delete account* removes it. The Real-Debrid token is kept in the macOS Keychain, not in that file. In the browser, the same data is kept in that browser's storage.

**Home, Discover and Library.** Rows come from your addons (for example Cinemeta's popular and new lists), from what you have added, or from your own history. There are no made-up “recommended” or “trending” rows. Library has **Recent** (what you opened lately) and **Watched** (films and episodes you finished or started).

**Addons (Stremio protocol).** Addons supply catalogues, details, streams and download links, subtitles, and they can be installed by manifest URL (`https://…/manifest.json` or `stremio://`). Addons installed in Nuvio that use the same manifest format work here too. The Addons page lists the official Stremio addons with live availability, and it checks every installed addon. DebridOne reads JSON from addons only; packages that run JavaScript are not supported.

- **Movies & TV:** Cinemeta provides catalogues, details, cast and episodes. Each title's page lists every stream and download option from your addons. Direct links play in the player and download through your browser. Torrents show a "Copy magnet" action only, and "external" links open the streaming service.
- **Games:** the Games tab browses the Steam catalogue (paged, sortable, via CheapShark), and search covers every game in Wikidata across all platforms. Opening a game adds developers, genres, platforms, a Wikipedia summary and current store prices.
- **Music, manga and books:** each section (Movies & Series, Music, Games, Books & Manga) has its own page on the Addons tab. Built-in sources are MusicBrainz (albums, tracks and covers; metadata only), Open Library (book search and covers; metadata only), Project Gutenberg (public-domain EPUB and text downloads) and MangaDex (manga metadata; chapters are not included). Any Stremio-style addon for these types can be added to its section by URL.
- **Stream filters:** every section has AIOStreams-style filters for the streams it lists: minimum quality, size cap, source type, sort order, and words to include or hide.
- **Local addons:** addons on localhost or a private network are blocked unless you turn on "Allow local-network addons" in Settings → Integrations.

**Appearance.** Settings → Appearance and DebridOne Pro → Appearance control the accent colour (Apple blue by default), card size, large or compact page titles, shadows, a translucent or solid sidebar, glass, corner radius, density and font. Pro has Apple Dark, Apple Light and other presets.

**Install as an app.** The build is a PWA. In Chrome or Edge, use the install icon in the address bar (or Install from the browser menu) to add it to your Dock or Start menu. After one visit, the app shell opens offline.

## Mac app and Real-Debrid

DebridOne runs as a macOS app (Apple Silicon). The app is the web interface wrapped in Electron, plus a few native features the browser can't provide.

**Build and install**

```bash
npm install
npm run desktop:build
open release/DebridOne-0.1.0-arm64.dmg
```

Drag DebridOne into Applications. The build is ad-hoc signed, not notarised, so the first launch may be blocked. If it is, right-click the app, choose Open, and confirm once. Or, for a copy you downloaded, run:

```bash
xattr -dr com.apple.quarantine /Applications/DebridOne.app
```

To run the desktop build without packaging, use `npm run desktop:dev`.

**Real-Debrid**

1. Open Real-Debrid → Real-Debrid page (sidebar) → "Open my API token page" and copy your token.
2. Paste it and press Connect. DebridOne checks it with Real-Debrid and stores it in the macOS Keychain.
3. Add magnet links or file-host links on the Add tab. Torrents are sent to Real-Debrid; when ready, Play streams them and Download saves them to ~/Downloads with progress.
4. On any movie or episode, addon torrent and direct-link options show "Play via Real-Debrid" and "Download via Real-Debrid".

Real-Debrid calls run in the app's main process, so the token never reaches the web page, and CORS doesn't apply. The app only permits the Real-Debrid endpoints it needs (user, torrents, downloads, unrestrict). The Real-Debrid API limits requests to 250 per minute.

**Desktop-only features** — files go to your Downloads folder with live progress; addon and subtitle requests skip browser CORS limits (with the same safety checks as the web version); "Show in Finder"; a native menu; traffic-light window chrome. The browser version of the app keeps its demo Real-Debrid entry and says the live connection needs the Mac app.

## What is real and what is not

| | |
|---|---|
| **Works for real** | Navigation, search, library, collections, playlists, favourites, progress and history; HTML5 video player (resume, subtitles, speed, PiP, fullscreen); music player with queue/shuffle/repeat; manga reader (RTL, LTR, vertical, webtoon, zoom, fit); book reader (paged/scroll, fonts, themes, bookmarks, notes); metadata editing with restore, import/export and conflict handling; source manager with manifest validation and live connection tests; download manager for CORS-enabled and generated files; theming, dashboards, rules, shortcuts and custom CSS; importing your own audio, video, EPUB, PDF, TXT and CBZ files. Everything persists in the browser. |
| **Sample library (optional)** | Off by default. Settings → “Show the sample library” adds a small bundled sample library for trying the app. It is never shown unless you turn it on. Its contents: films are Blender open movies and public-domain classics streamed from the Internet Archive; music is generated in the browser (`src/lib/synth.ts`); manga pages are drawn by code; games are real open-source projects that link to their official sites; books are public-domain excerpts plus two original pieces. Fictional titles are labelled as such. Real-Debrid and Premiumize are demo adapters that never contact those services. Demo Pro Mode unlocks Pro features with no billing. |
| **Needs a backend / external API** | Live debrid accounts, TMDB/IGDB metadata, licensed music services, accounts and cloud sync, real subscriptions, and reading hosts that do not allow browser (CORS) access. Open Library lookups for books work today because that API is public and key-less. |
| **Needs a desktop app** | Choosing download folders, background/resumable transfers, installing and launching games, privileged networking. `src/adapters/desktop.ts` is the bridge a Tauri shell would implement; until then those paths are clearly labelled simulations. |

## Project map

```
src/
  App.tsx                 routes (HashRouter, so it runs from any static host or file-based shell)
  types.ts                shared domain types (MediaItem, Source, Theme, …)
  data/                   bundled sample catalogue and book texts
  store/
    useStore.ts           persisted app state (localStorage key "debridone:v1")
    catalog.ts            metadata resolution: original → provider imports → rules → your edits
    player.ts             music engine (single <audio>, persisted queue)
    downloads.ts          download engine: fetch / generated / simulated jobs
    ui.ts                 toasts, confirm dialogs, global modals
  adapters/
    sources.ts            built-in sources, manifest validation, connection tests
    providers.ts          debrid + metadata provider interfaces and adapters
    desktop.ts            desktop bridge interface (future Tauri shell)
  lib/
    features.ts           feature gates — the one place billing would plug in
    synth.ts              procedural music → WAV
    books.ts              EPUB parse/build (JSZip + DOMPurify), text splitting
    importers.ts          importing personal files into IndexedDB
    cssSanitizer.ts       sanitising + scoping of custom CSS themes
    idb.ts, i18n.ts, utils.ts, hooks.ts
  components/             UI kit, layout shell, players, cover art, metadata editor
  pages/                  one file per screen
```

## Extending it

- **A new source type**: add a case to `testConnection` and, if it lists files, map them to `ManifestItem`s in `adapters/sources.ts`.
- **A live debrid provider**: implement `DebridAdapter` (connect / listFiles / getStreamLink) against your backend and add it to `DEBRID_ADAPTERS`. Keep tokens server-side.
- **A metadata provider**: add a `MetadataProvider` with a `lookup()` in `adapters/providers.ts`; the editor's conflict view picks it up automatically.
- **Real subscriptions**: change `isEntitled` / `useEntitled` in `lib/features.ts` to read a server-verified entitlement.
- **Desktop build**: wrap `dist/` in Tauri and provide a `DesktopBridge` on `window.__DEBRIDONE_DESKTOP__`.

## Safety rules for user-added sources

Nobody approves sources a user adds, so the app enforces these automatically: manifests are validated field by field and rejected if malformed or if they contain script-like fields; nothing from a manifest is ever executed; only `https` endpoints are accepted and localhost/private-network addresses are refused; there is no server-side fetching; executable downloads need explicit per-file consent; credentials stay in memory for the current tab; user-added sources are always labelled **Unreviewed**.

Only add sources for content you are authorised to use.
