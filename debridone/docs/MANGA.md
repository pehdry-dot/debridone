# Manga

Manga is one of DebridOne's sections: a reader for manga and manhwa with its own sources, library,
metadata, downloads and settings. Nothing in it is shared with Streaming or Music.

Its backend is **Suwayomi-Server** (https://github.com/Suwayomi/Suwayomi-Server, MPL-2.0): the
unmodified release, started by DebridOne as a separate process with a Java runtime that ships
inside the application. Sources are the **Mihon/Tachiyomi extensions** Suwayomi loads from the
extension stores you add; the library, categories, chapters, reading progress, downloads and
trackers are Suwayomi's. The screens and the reader are DebridOne's own: Suwayomi's web interface
is switched off and is not shown anywhere.

```
DebridOne → Manga screens and reader → Manga engine → typed GraphQL client → Suwayomi-Server → extensions
```

DebridOne ships with no extension store, no extension and no catalogue. What you add and read is
your choice. How extensions work, and what you should know before adding a store, is in
[MANGA_PLUGINS.md](MANGA_PLUGINS.md).

## The server

**One server per profile.** Suwayomi has no user accounts: one data folder is one library. So each
DebridOne profile has a Suwayomi data folder of its own, and only the active profile's server
runs. A profile's library, categories, progress, downloads, extension stores, extensions, source
sign-ins and tracker sign-ins are therefore its own; switching profile stops one server and starts
the other.

**Started when needed.** Nothing is started until Manga (or Plugins › Manga) is opened for the
first time. Manga shows "Starting…", then its pages; if the server cannot start, the reason and
the server's last output are shown, with "Start" to try again. A server that stops by itself is
started again (three times in five minutes, then it is reported).

**Reachable by DebridOne only.** The server listens on this computer only (`127.0.0.1`, a free
port chosen at every start) and answers only with a password DebridOne makes anew at every start.
The password is in the profile folder's `server.conf` (readable by your user only) and never on a
command line or in a log. Without it, the server answers nothing — neither its API nor an image —
so no other program and no web page can use it. When DebridOne quits, the server is stopped; a
small watchdog inside it makes it exit even if DebridOne is killed. DebridOne never stops a
Suwayomi it did not start.

**A server of your own instead.** In Manga's settings › Server, a profile can use a Suwayomi
server you run yourself (at home, on a NAS): address, user name and password, how long to wait,
and "require a secure connection" (plain `http` is accepted only for this computer itself, or
when you switch that requirement off for your own network). "Test the connection" tries it before
anything is saved. The password is kept in the macOS Keychain, for that profile. While a profile
uses your server, the library is that server's (shared with whatever else uses it), DebridOne's
own server is not running for the profile, and files of this computer and cloud archives cannot
be opened through it (Suwayomi's Local source reads the server's own disk). A server that goes
away is reported and used again by itself when it is back; a refused password is reported and not
retried. Suwayomi's "basic" sign-in, or none, is supported; its other sign-in modes are not.

**Its own settings**, on the same page, with Save and Discard: one CBZ per downloaded chapter;
downloading new chapters by themselves; how often it looks for new chapters and which series it
leaves out; how many sources are asked at once; a SOCKS proxy; a FlareSolverr of your own; how
often it backs the library up to its own folder. A server DebridOne sets up looks at every series
when it checks for new chapters (Suwayomi's own default leaves out series with unread chapters,
series not started and completed ones); you can switch those rules on.

## What it does

**Home.** Continue Reading, Recently Updated, Manga, Manhwa, Your Library, Recently Added,
Favorites, Downloaded Chapters, a row per collection, and — labelled as such — AniList's public
trending lists (clicking a title searches your sources for it). A row appears only when there is
something real to show. With nothing set up, Home shows how to add extensions or files.

**Discover.** One source at a time: Popular, Latest (where the source has it) and search, with the
filters that source's extension defines (they differ from source to source), on a grid of covers
with "load more". The source's own settings open from here in a panel.

**Search.** One search across all enabled sources at once, or a selection of them. Results appear
source by source as they arrive, stay grouped by source and are never merged; a source that fails
says why without hiding the others.

**Library.** Reading status (Currently Reading, Plan to Read, Completed, On Hold, Dropped),
favourites, collections (they are Suwayomi's categories), filters (Manga, Manhwa, status,
favourites, unread, downloaded, local files), sorting, text filter, multi-select with bulk
actions, and "check for new chapters", which is the server's own check and shows its progress.

**Series page.** Cover, titles, type, status, source, authors, synopsis, tags, progress, and the
chapter list with sorting and multi-select. Actions: Start / Continue Reading, Add to Library,
Favourite, Mark as Read, Download, Track Series, Change Source, Edit Metadata, find details
online, refresh, shelve as Manga or Manhwa, per-series reading settings.

**Manga and Manhwa.** Both use the same engine. A series is shelved as Manhwa when its original
language is Korean or Chinese or its tags say webtoon / long strip, otherwise as Manga; you can
change it per series, and your choice sticks. Each kind has its own reading preset.

**Reader** (DebridOne's own; pages come from the server).

- Modes: right to left, left to right, vertical paging, continuous webtoon.
- Layout: single page or double page (with an offset for spreads), fit screen, width, height or
  original size, zoom, full screen, four backgrounds, strip width for webtoons.
- Navigation: page slider, chapter list, previous and next chapter, bookmarks with notes.
- Keys: arrows, Space / Shift+Space, Page Up / Down, Home / End, `[` `]` or `P` `N` for chapters,
  `F` full screen, `B` bookmark, `M` mode, `D` double page, `S` settings, `+` `-` zoom, Cmd+0
  reset zoom, Esc to leave. Trackpad and wheel scroll; Cmd/Ctrl + scroll zooms.
- Continuous mode loads pages as they come near, prefetches a few ahead, keeps a size-limited
  cache and reserves each page's height so the strip does not jump.
- The position (chapter, page, offset within a strip) is saved as you read and restored after a
  restart. A chapter counts as read when you reach its end.

**Your own files.** CBZ, ZIP, CBR, RAR and EPUB files, a folder per series with archives or
sub-folders as chapters, and plain folders of images. They are read where they are: DebridOne
links them into the profile's Suwayomi Local source folder (nothing is copied, changed or
uploaded) and they are read through the server like any other series. This needs no extension.
Removing such a series removes the link, never your files.

**Downloads.** Chapters are saved by Suwayomi's own downloader into the profile's server folder:
a queue with progress, stop, retry and delete. A saved chapter is read without its source and
without a network connection.

**Trackers.** AniList, MyAnimeList, Kitsu, MangaUpdates, Shikimori and Bangumi, as Suwayomi offers
them. You sign in under Manga's settings › Trackers: with user name and password where the
tracker works that way, or on the tracker's own page in your browser. For a browser sign-in,
DebridOne listens on this computer for that one sign-in, at an address made for it, and the
tracker's page sends the browser back there (through Suwayomi's own sign-in page, as Suwayomi's
other clients do); if the browser cannot come back, you can paste the address it ended at. The
sign-in is kept by the profile's server. A series is linked to its entry from its page ("Track
Series"): search the tracker, pick the entry, set its status, unlink. The server reports your
progress as you read.

**Details from elsewhere.** Titles, synopsis, people, tags and covers can be looked up on AniList,
MangaDex and MyAnimeList (the last with your own API client ID) and applied to a series, in the
order you set under Metadata. "Edit Metadata" changes any field by hand; what you typed is kept
when the source is asked again. These lookups are Manga's own and separate from the trackers.

**Backups.** Manga's settings › Library export the profile's library to a file in the format
Suwayomi and Mihon read (`.tachibk`) and restore one. It holds the series, categories, read
chapters, tracker links, and DebridOne's extras (reading status, favourites, your tags and edits,
bookmarks); it never holds the server's own settings, and restoring never changes them. The
server also backs the library up to its own folder on its own.

**Settings**, behind the cogwheel, open in a panel inside Manga: Appearance, Reading, Library,
Extensions, Extension stores, Sources, Trackers, Metadata, Server.

## If you used the earlier version

Manga used to be built on Houdoku's extensions. That library is carried over the first time a
profile opens Manga:

1. First, Manga's database and the covers you chose are copied to a folder named
   `backup-before-suwayomi-<date>` beside them, and the copy is checked. If that fails, nothing
   else is done.
2. Series read from your own files are linked again and get back their place in the library,
   favourite, tags, collections, your edits, read chapters, the page you were on, and bookmarks.
   If the files are no longer where they were, the series is listed with where they were, and
   found again the next time Manga opens.
3. Series from a source of the earlier version cannot be fetched by the new server on its own:
   its sources are different programs. They are listed as "to add again". Once you have added the
   same series from one of your new sources, "Carry over" gives it the library status, favourite,
   tags, collections and the chapters you had read, matched by chapter number.
4. Chapters the earlier version had downloaded cannot be used by the new server. They are left
   where they are (`modules/manga/downloads`), untouched and not copied; the report says how much
   space they take. Save the chapters again from their new source and delete that folder when you
   no longer want it. The earlier version's own AniList / MyAnimeList sign-in is not used any
   more (sign in again under Trackers).

A card on Manga's Home, and Manga's settings › Library, show the report at any time; it is also
written as a text file in the backup folder. Nothing of the earlier library is deleted: it stays
in Manga's database as well as in the backup.

## Where things are kept

```
<data>/modules/manga/manga.db                    Manga's settings, the earlier library, what the migration did
<data>/modules/manga/suwayomi/profile-<id>/      one profile's Suwayomi-Server: library, downloads,
                                                 extensions, stores, tracker sign-ins, its own backups
<data>/modules/manga/suwayomi/profile-<id>/local/   links to your own files (the server's Local source)
<data>/modules/manga/covers/                     covers you chose yourself
<data>/modules/manga/backup-before-suwayomi-…/   the copy made before the earlier library was carried over
<data>/modules/manga/downloads/                  chapters the earlier version had saved (no longer used)
<cache>/manga/                                   pages and covers already shown
```

The password of a server of your own is in the macOS Keychain, per profile. Deleting a profile
deletes its Suwayomi folder.

## What it does not do

- 7-Zip archives (`.7z`, `.cb7`) are not opened.
- Pinch-to-zoom is not available; use Cmd + scroll or the keys.
- Sources that only work behind a browser check (Cloudflare and the like) fail with Suwayomi's
  message, unless you run a FlareSolverr yourself and name it in the server's settings. Suwayomi's
  embedded browser is not used.
- Other devices cannot reach the server DebridOne starts.
- There is no built-in catalogue, and nothing that gets around a site's login or paywall.

See also [LIMITATIONS.md](LIMITATIONS.md) for what was and was not tested.
