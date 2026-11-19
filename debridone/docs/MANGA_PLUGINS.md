# Manga extensions

Manga's online sources come from **extensions** that the profile's Suwayomi-Server runs (see
[MANGA.md](MANGA.md)). Two kinds are supported:

- **Mihon/Tachiyomi extensions** (`.apk`, or `.jar` made for Suwayomi): Suwayomi's own mechanism,
  unchanged. The server installs and runs them as they are.
- **Mangayomi JavaScript sources**: run through DebridOne's **Mangayomi bridge**, a small extension
  that DebridOne builds for each source and the server installs like any other (see
  [The Mangayomi bridge](#the-mangayomi-bridge)). Mangayomi's **Dart** sources are recognised but
  cannot run (see [What cannot run](#what-cannot-run)).

DebridOne ships no store, index or source.

Everything is managed in **Plugins › Manga** and, identically, in Manga's own settings (the
cogwheel): **Installed · Available · Sources · Import**, then Trackers, Metadata and Server. Nothing
opens elsewhere, and what you were doing in the tab is kept while a panel is open.

## Before you add extensions

A Mihon/Tachiyomi extension is a small Android program. Suwayomi runs its code **inside the
Suwayomi server process, with your user's rights and without a sandbox** — that is how Suwayomi and
Mihon extensions work. It never runs inside DebridOne itself, and the server it runs in can only be
reached by DebridOne, but such an extension can do on your computer what any program you start can
do. Add stores and indexes, and install extensions, only from people you trust.

A Mangayomi JavaScript source is more contained: it runs in a closed JavaScript engine inside the
manga server, with no access to files, processes, other programs or Java classes; it can only make
web requests through the server and read the pages it gets back.

DebridOne does not name, recommend or preconfigure any store or index, and neither its code nor its
tests contain one's address.

## Import: adding extensions from an index

In **Import**, paste the address of an index (or **Choose a JSON file…**) and press **Look**.
DebridOne reads it, recognises its format and shows what it offers **before anything is
installed**: name, language, site, kind (Mihon/Tachiyomi, JavaScript, Dart), version, 18+, and for
each entry whether it can run here — **Runs directly**, **Runs through the Mangayomi bridge** or
**Cannot run here** — with the reason and any warning (a site known to put up a browser check, a
call the bridge does not provide). Filter by language, show only what can run, choose entries,
then **Install selected** or **Install all that can run**. A short list starts chosen; a long one
starts empty, so nothing is installed in bulk by surprise. The outcome is reported entry by entry.

| What the address holds | Recognised as | What happens |
|---|---|---|
| A Mihon/Tachiyomi store: `repo.json`, `index.pb`, `index.min.json` | Store | Registered with the manga server (so it keeps finding updates) and its extensions listed; installed by the server |
| `index.json` beside `index.min.json` | Store | As above; DebridOne gives the server `index.min.json` or `repo.json` from the same folder, because Suwayomi refuses the name `index.json` ("Provided legacy store url is not valid") |
| A Mangayomi index (a JSON list with `sourceCodeUrl`) | Mangayomi index | Each source's code is fetched and judged; JavaScript manga sources are installed through the bridge |
| LNReader plugins, Aidoku sources | Recognised | Shown with the reason they cannot be used (light novels; iOS WebAssembly) |
| A web page, an empty file, other JSON | Not an index | Said in a sentence ("This is a web page, not a list of extensions. For a file on GitHub, use its Raw address.") |

A link to GitHub's page about a file (`github.com/…/blob/…`) is turned into the file's own address.
A store's list chosen as a file is not registered (the server reads stores from their address).
**Install an extension file (.apk, .jar)…** hands a package on your computer to the server.

The stores and Mangayomi indexes you imported are listed under **Your stores and indexes**, with
**Look again** and **Remove**. Removing one keeps what was installed from it. Both lists are kept
by the profile's manga server, with its library, and survive restarts.

DebridOne shows the notice below once, before the first index or store is used.

## Installed

One card per installed extension: icon, name, languages, site, version, kind
(**Mihon/Tachiyomi** or **Mangayomi · JavaScript**), how it runs (**Runs directly** / **Runs through
the Mangayomi bridge**), and its **status** — the last test of its sources on this computer, with
its date: *Working*, or the kind of failure (name not found (DNS), no network route, timed out,
site unavailable, secure connection failed, HTTP error, sign-in needed, browser check, needs its
settings, extension error, chapter or image failed to load, incompatible format). *Not tested* until
it has been tried; nothing is called working that has not been.

Actions on each card: **On/Off** (all its sources for this profile), **favourite** (its sources are
searched and listed first), **Configure** (the source's own settings, in a panel in place),
**Test**, **Browse** (opens it in Discover), **Update** (from its store, or from its Mangayomi
index after **Check for updates**), **Uninstall** (asks first; series you added from it stay in
your library with progress and downloads), and **Diagnosis**, which opens below the card: the
package and where it came from, then each of its sources with its own On/Off, Configure, Test,
Browse and its last test step by step — and, for a network failure, **Diagnose connection**.

A spare source that only waits for its settings (a second Komga server never filled in) does not
hide one that works; the card counts it ("1 waiting for settings").

## Available

What your stores offer and is not installed, with search, language, 18+ and **Install**. Mangayomi
indexes are opened under Import, where each source shows whether it can run.

## Sources

**Sources** lists the sources of the installed extensions, and Suwayomi's own **Local source**
(your files; it needs no extension).

- **On / off** per source, and a preferred order for searches.
- **Configure** opens the source's own settings in a panel, in the tab you are in: the fields are
  the ones the extension defines (an address and sign-in for a server of your own, a preferred
  quality, a content filter…). A sign-in typed there is kept by the profile's Suwayomi server.
- **Test** (and **Test the ones shown**, two at a time) goes step by step and records each step
  with its time and, when it fails, the kind of failure and the technical report: *Switched on,
  Extension load, Discovery, Search, Series metadata, Chapters, Page request, Image download,
  Image decode*. The picture is checked (format, complete, decodes, dimensions); one page is
  fetched, nothing is added to your library and nothing is downloaded. Timeouts, sites down for a
  moment, HTTP 5xx and 429 are retried with a growing pause.
- **Browse** opens the source in Discover.

## The Mangayomi bridge

Mangayomi's JavaScript sources are written against Mangayomi's own API (`MProvider`, `Client`,
`Document`/`Element`, `SharedPreferences`, filters, preferences and helper functions). DebridOne
provides that API inside the manga server:

```
Mangayomi index → DebridOne (fetch, judge, pack) → .jar extension → Suwayomi-Server
   Suwayomi-Server → MangayomiSource (Kotlin, a normal HttpSource) → GraalJS context → the source's code
```

- For each source, DebridOne builds a small extension: the bridge's classes, the source's entry
  from the index and its code, and a manifest. The server installs it like any other extension,
  so library, chapters, pages, downloads, trackers and backups work as for Mihon sources. The
  source id is fixed (derived from the index's id and language), so an update keeps your library.
- The code runs in a **GraalJS** context (the JavaScript engine Suwayomi already carries) with
  host access, class lookup, file and process access, threads and environment switched off. Its
  only way out is the bridge: web requests through the server's HTTP client (with the source's
  headers, cookies and the server's FlareSolverr if you set one), HTML parsing (jsoup, with
  Mangayomi's looser class selectors and attribute helpers reproduced), dates, the crypto and
  unpacking helpers Mangayomi offers, and the source's preferences.
- Supported: popular, latest, search with the source's filters (select, text, sort, check box,
  tri-state, group, header, separator), series details, chapters, pages (with per-page headers),
  and the source's settings (lists, multi-select, switches, check boxes, text fields), shown in
  **Configure**.
- Not provided (a source that needs them fails at that point, with the reason): Mangayomi's
  in-app web view (`evaluateJavascriptViaWebview`), its JS password deobfuscator, and anything for
  anime or novels.
- Failures keep their nature: a network failure inside the source is reported as the network
  failure (DNS, no route, timeout…), and a browser check (Cloudflare, DDoS-Guard) as a browser
  check, not as an empty list.

## What cannot run

- **Mangayomi Dart sources** (most of a large index: 225 of 272 entries in one of the indexes this
  was tried with). They are run in Mangayomi by its own Dart interpreter, which DebridOne does not
  include; running them would mean embedding a Dart interpreter and reimplementing Mangayomi's Dart
  API and its templates (Madara, MangaReader, MMRCMS, HeanCMS…). They are listed with that reason
  and not installed. When one of your stores offers a Mihon extension of the same name, the entry
  says so.
- Anime and novel sources of any index, LNReader plugins and Aidoku sources.
- Sites that require a browser check, unless you run a FlareSolverr of your own (Server tab).

## What was tried, and how

With the real, pinned Suwayomi-Server (v2.4.2366) and its bundled Java:

- Every GraphQL document DebridOne sends is checked against that server in the test suite.
- The **Local source** end to end, in the test suite and in the packaged app's self-test: files
  listed, pages read in order, progress, restart and resume.
- One **real extension from a real store**: the Komga extension (v1.6.76 at the time), installed
  from a store named by the person running the test, configured through its own settings to read
  Komga's public demo server, then browsed, searched, opened, read, its progress kept across a
  restart of the server, and removed again. The same was done by hand in the running app, where a
  chapter was also saved by the server and read with no network connection.
- A store with about 1,700 extensions was listed and filtered in the app.
- Adding a store by an `index.json` address, against a stand-in store served from the same
  computer to the real server (in the test suite and by hand in the app): refused by the server
  itself, added by DebridOne through the files beside it, and refused with a sentence when those
  are not there.

- **Mangayomi indexes, through the real server** (test suite, `MangayomiImportTest`): a stand-in
  site and index served from the same computer; the index recognised, the JavaScript source
  judged runnable and the Dart and anime ones refused with their reasons; the source installed
  through the bridge, listed by the server, browsed, a series opened with its chapters, a page
  fetched with the headers the source asks for and checked as a picture, the source test passed,
  a new version found and installed under the same source id, and removed. The bridge's API is
  tested on its own (`JsBridgeTest`: lists, details, pages with Referer, settings defaults,
  filters, JSON POST, failures keeping their nature, Mangayomi's selector semantics, browser
  check, helpers and unpacker).
- **The two indexes named in the request, in the running app** (19 Nov 2026): the first
  (4 JavaScript manga sources) and the second (272 entries: 47 JavaScript sources that can
  run, 225 Dart). Installed through Import and tested on their real sites with the step-by-step
  test: **Mangapill** (working: 50 series, search, 60 chapters, 31 pages, a 1000×1414 JPEG),
  **Weeb Central** (working), **MangaDex (unlocked)** from the second index (working: 20 series,
  216 chapters, a 1200×1200 PNG), **ReadComicOnline** (reported as a browser check by
  `rcostation.xyz`, which is what the site does), **WeLoMa** (Japanese; installed, switched off by
  the language setting until switched on). A Mangapill chapter was read in the reader (49 pages).
  After a restart of the app, the indexes, the installed sources and their test results were still
  there.

Not tried:

- Other extensions and sources. Whether a given one works is between it, its site and, for
  Mangayomi sources, the bridge; the status on its card says what this computer found.
- Installing from an `.apk` file against a real package (the request is checked against the
  server, and the same upload mechanism is tested with a backup file).
- Sources behind a browser check. Suwayomi's embedded browser is switched off; a FlareSolverr of
  your own can be named in the server's settings.

The opt-in test is `SuwayomiExtensionLiveTest`; it runs only with `DEBRIDONE_LIVE_TESTS=1` and
`DEBRIDONE_TEST_EXTENSION_STORE=<address of a store's index>`.
