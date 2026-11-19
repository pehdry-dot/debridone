# Manga extensions

Manga's online sources are **Mihon/Tachiyomi extensions**, loaded by the Suwayomi-Server that
DebridOne runs for each profile (see [MANGA.md](MANGA.md)). DebridOne does not have an extension
format of its own for Manga, does not convert extensions, and ships none: everything on this page
is Suwayomi's own mechanism, shown in DebridOne's screens.

They are managed in **Plugins › Manga** and, identically, in Manga's own settings (the cogwheel):
Extensions, Stores, Sources, Trackers, Metadata, Server.

## Before you add a store

An extension is a small Android program (an `.apk`). Suwayomi runs its code **inside the Suwayomi
server process, with your user's rights and without a sandbox** — that is how Suwayomi and Mihon
extensions work. It never runs inside DebridOne itself, and the server it runs in can only be
reached by DebridOne, but an extension can do on your computer what any program you start can do.
Add stores, and install extensions, only from people you trust. DebridOne says this once, when you
add your first store.

DebridOne does not name, recommend or preconfigure any store, and neither its code nor its tests
contain a store's address.

## Stores

A store is a web address of an index of extensions. In **Stores**, paste the address of the index
and press Add; Suwayomi fetches it, checks it and lists what it offers. Both kinds Suwayomi
understands are accepted:

- the current store format (an index file such as `index.pb`, or a `repo.json` pointing to one);
- the older `index.min.json` repositories, which Suwayomi upgrades itself when the store offers
  the new format.

**An address ending in `index.json` works too.** Such repositories publish their list twice:
compact as `index.min.json` and readable as `index.json`. Suwayomi takes the list only from the
first name and refuses the second ("Provided legacy store url is not valid"), so DebridOne tries
the address as you typed it and, when the server refuses it, `index.min.json` and then `repo.json`
in the same folder; the message says which file the store was read from. A folder that has
nothing but `index.json` cannot be used: Suwayomi needs `repo.json` beside the list, for the
store's name and signing key. A link copied from GitHub's page about a file
(`github.com/…/blob/…`) is turned into the file's own address.

What Suwayomi refuses is shown with Suwayomi's own reason. Removing a store removes its offers;
extensions already installed stay installed and are marked as no longer offered.

`Suwayomi/tachiyomi-extension` is something else: an extension that lets Mihon read *from* a
Suwayomi server. It is not a catalogue of sources and DebridOne does not treat it as one.

## Extensions

**Extensions** lists what the stores offer and what is installed, with search, a language filter,
the version, and whether an update is there.

- **Install / Update / Remove**: done by Suwayomi; DebridOne shows the progress and the outcome.
  "Update all" updates everything that has an update.
- **Install from a file**: an extension `.apk` on your computer is handed to Suwayomi, which
  checks and installs it like one from a store.
- An extension usually brings one source per language. The languages you choose in Manga's
  settings › Library decide which of them are switched on when they appear.

## Sources

**Sources** lists the sources of the installed extensions, and Suwayomi's own **Local source**
(your files; it needs no extension).

- **On / off** per source, and a preferred order for searches.
- **Configure** opens the source's own settings in a panel, in the tab you are in: the fields are
  the ones the extension defines (an address and sign-in for a server of your own, a preferred
  quality, a content filter…). A sign-in typed there is kept by the profile's Suwayomi server.
- **Test** tries the source step by step — list series, search, open a series, open a chapter,
  load a page — and says where it stops.
- **Browse** opens the source in Discover.

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

Not tried:

- Any other extension. Whether a given extension works is between that extension, its site and
  Suwayomi.
- Installing from an `.apk` file against a real package (the request is checked against the
  server, and the same upload mechanism is tested with a backup file).
- Sources behind a browser check. Suwayomi's embedded browser is switched off; a FlareSolverr of
  your own can be named in the server's settings.

The opt-in test is `SuwayomiExtensionLiveTest`; it runs only with `DEBRIDONE_LIVE_TESTS=1` and
`DEBRIDONE_TEST_EXTENSION_STORE=<address of a store's index>`.
