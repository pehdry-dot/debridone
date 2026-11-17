# Manga

Manga is one of DebridOne's sections: a reader for manga and manhwa with its own sources, library,
metadata, downloads and settings, stored in its own database. Nothing in it is shared with
Streaming or Music.

Online sources come from **Houdoku's own extension package, Tiyo (`@tiyo/core`)**, which DebridOne
runs unmodified. How that works, which versions are supported and what was tested is in
[MANGA_PLUGINS.md](MANGA_PLUGINS.md). Files on your computer need no extension at all.

Its screens follow Streaming and Music: one navigation pill at the top (Home, Discover, Search,
Library, Downloads, a cogwheel for settings), large page headings, portrait cover cards and the
same colours, spacing and buttons.

DebridOne ships with no sources and no catalogue. What you install and read is your choice.

## What it does

**Home.** Continue Reading, Recently Updated, Popular Manga and Popular Manhwa (AniList's public
trending lists, labelled as such; clicking a title searches your sources for it), Recently Added to
Library, Ongoing, Completed, Favorites and a row for each of your collections. A row appears only when there is
something real to show. With nothing installed, Home shows how to add a source or a file, and both
buttons work.

**Discover.** Browse the catalogue of one source at a time: a search field, a Manga / Manhwa
switch, the source, a language filter, and the genre, status and sort options that the source
itself offers (they differ from source to source), on a grid of covers with "load more". Discover
starts on the source you used last.

**Search.** One search across all enabled sources at once, or a selection of them, plus your
library. Results stay grouped by source and are never merged; a source that fails says why without
hiding the others.

**Manga and Manhwa.** Both use the same engine. A series is shelved as Manhwa when its original
language is Korean or Chinese or its tags say webtoon / long strip, otherwise as Manga; you can
change it per series ("Shelve as…"), and your choice sticks. A source is never assumed to be one
kind. Each kind has its own reading preset.

**Series page.** Cover, backdrop, titles, type, status, source, language, authors and artists,
synopsis, tags, progress, and the chapter list with sorting, search, language and group, read
marks and multi-select. Actions: Start / Continue Reading, Add to Library (with reading status and
collections), Favorite, Mark as Read, Download, Track Series, Change Source, Edit Metadata, find
details online, refresh, shelve as Manga or Manhwa, per-series reading settings.

**Reader.**

- Modes: right to left, left to right, vertical paging, continuous webtoon.
- Layout: single page or double page (with an offset for spreads), fit screen, width, height or
  original size, zoom, full screen, four backgrounds, strip width for webtoons.
- Navigation: page slider, chapter list, previous and next chapter, bookmarks, a page number.
- Keys: arrows, Space / Shift+Space, Page Up / Down, Home / End, `[` `]` or `P` `N` for chapters,
  `F` full screen, `B` bookmark, `M` mode, `D` double page, `S` settings, `+` `-` zoom, Cmd+0
  reset zoom, Esc to leave. Trackpad and wheel scroll; Cmd/Ctrl + scroll zooms.
- Controls hide while you read and come back when the pointer moves.
- Continuous mode loads pages as they come near, prefetches a few ahead (you choose how many),
  keeps a size-limited cache and reserves each page's height so the strip does not jump.
- The position (series, chapter, page, offset within a strip) is saved as you read and restored
  after a restart. A chapter counts as read when you reach its end.

**Library.** Reading status (Currently Reading, Plan to Read, Completed, On Hold, Dropped),
favorites, collections, filters (Manga, Manhwa, status, favorites, unread, downloaded, local
files), sorting, text filter, unread counts, multi-select with bulk actions, duplicate hints,
refresh for new chapters. Changing a series to another source keeps what you read: the old edition
stays recorded and the chapters you finished can be marked as read in the new one.

**Local files.** CBZ and ZIP archives, a folder per series with archives or sub-folders as
chapters, and plain folders of images (JPEG, PNG, WebP, GIF, BMP). Files are read where they are;
nothing is copied or changed. Volume and chapter numbers are read from file names the way Houdoku
does. This works with no extension installed and with no extension runtime at all.

**Downloads.** Save chapters to read offline: a queue that survives restarts, progress per chapter,
stop, retry, delete. A page whose request stalls is asked for again before a chapter is reported as
failed, and a retry continues with the pages already saved. The reader uses the saved copy
automatically. Files live under DebridOne's Manga data folder, one image per page.

**Metadata.** Details can come from the source itself, AniList, MangaDex and MyAnimeList, in the
order you set in Plugins › Manga › Metadata. "Find details online" searches a provider, shows the
matches and applies the one you pick. "Edit Metadata" changes any field by hand; a field you
edited is locked (padlock) and no refresh replaces it. MyAnimeList uses its official API with your
own client ID. None of this is shared with Streaming or Music.

**Tracking (optional).** With an AniList or MyAnimeList account that you connect yourself,
DebridOne can update your reading list as you read — only for the series you switch it on for.

**Profiles.** Each profile has its own library, progress, bookmarks, history, collections,
metadata edits, source choices and logins, reading presets and tracking accounts. Extension
packages are installed once and shared; nothing else is.

**Backup.** Manga's settings (the cogwheel) export the library of the current profile (series, progress, edits,
bookmarks, collections) to a file and restore it.

**Appearance.** Accent colour, card size, card style, density, layout, typography, chapter list
style, background and animations, through the same per-section appearance settings as the other
sections; reading presets are separate for Manga and Manhwa.

## Where things are kept

```
<data>/modules/manga/manga.db        library, progress, settings (per profile)
<data>/modules/manga/plugins/        installed extension packages (shared by profiles)
<data>/modules/manga/downloads/      saved chapters: <profile>/<series>/<chapter>/0001.jpg …
<cache>/manga/                       covers, pages read online, the extension host's scratch folder
```

Logins for sources and tracking accounts are in the macOS Keychain, per profile.

## What it does not do

- CBR, RAR and 7z archives are not opened (no decoder is included); the app says so and suggests
  CBZ.
- Pinch-to-zoom is not available; use Cmd + scroll or the keys.
- Sources that depend on a real browser window work only where the site answers a plain request;
  see [MANGA_PLUGINS.md](MANGA_PLUGINS.md).
- There is no built-in catalogue, no account system of its own, and nothing that gets around a
  site's login, paywall or browser check.
