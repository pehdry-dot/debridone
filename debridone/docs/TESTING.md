# Testing DebridOne

## Automated

```bash
./scripts/debridone/test.sh
```

| Suite | What it proves |
|---|---|
| `:debridone:platform:test` | Migrations apply and roll back atomically; newer-schema files are refused untouched; a damaged database is quarantined and restored from the newest healthy backup; backups rotate; atomic file writes; secret-store scoping. The macOS Keychain test runs only on macOS. |
| `:debridone:accounts:test` | Account creation, sign-in, lockout, password change, guest upgrade, device-unique profile ids, profile limit, PIN lifecycle, scoped settings, account deletion, and that two profiles with different settings survive a full close and reopen. |
| `:debridone:modules:music:test` | Music end to end, with real audio files: every supported format plays from start to end through libmpv; pause, seek, volume, mute, next, a whole album, shuffle, repeat; tags and covers are read; scanning (incremental, moved, renamed, deleted, duplicate and unreachable files); search, favourites, history; edits, locks and restore; playlists and M3U; Subsonic and Jellyfin sign-in, sync, streaming and offline copies against local stand-in servers; radio addresses; MusicBrainz, cover and lyrics lookups against a stand-in; restart persistence; profile isolation. Needs libmpv for the playback tests. |
| `:debridone:architecture-tests:test` | Each module opens its own database; a plugin installed in one module never appears in another; disabling or removing in one module leaves the others alone; metadata providers and appearance are per module (and per profile); a module that cannot start does not stop the others; no cross-module imports; no Books module. |

`DEBRIDONE_LIVE_TESTS=1 ./gradlew :debridone:modules:music:test --tests '*MusicLiveServicesTest*'`
additionally runs Music's adapters against the real public services (the Navidrome and Jellyfin
demo servers, MusicBrainz, LRCLIB). They are off by default so that a normal test run never
depends on someone else's server.

Add `--all` to also run Nuvio's own desktop tests (about 1,900). Two of them fail on unmodified
upstream Nuvio Desktop as well and are not related to DebridOne:
`SettingsNavigationTest.bufferOptionsFollowThePlatformAndPlaybackEngine` and
`WatchProgressIdentityTest."legacy payload derives episode progress key and preserves completed position"`.
Any other failure is a regression.

## Manual checklist on a Mac (Milestone 1)

Run `./scripts/debridone/dev.sh --sandbox`, or install a development DMG.

Accounts and profiles
1. Create an account (any name or email, password of 6+ characters). You land in the app with one profile.
2. Click the profile picture in the sidebar. Add a second profile, give it a different colour or picture.
3. In the first profile choose a theme in Settings › Appearance. Switch to the second profile and choose another.
4. In Streaming, add something to the library in one profile only.
5. Quit DebridOne completely (Cmd+Q) and open it again.
6. Expected: you are still signed in, both profiles exist, each has its own theme and library.
7. Settings › Account › Sign out, then sign in again: everything is still there.
8. Set a PIN on a profile, quit, reopen: the PIN is required.

Shell
1. Cmd+1…Cmd+3 switch Streaming, Music, Manga (there is no Games section). Cmd+, opens Settings. Cmd+F in Streaming opens Streaming's search.
2. Quit on Music and reopen: the app returns to Music. Window size and position are restored.
3. No browser window opens at any point.

Streaming
1. Plugins › Streaming › Addons: install an addon by manifest URL.
2. Browse, open a title, play authorised media. The sidebar hides during playback.
3. Quit mid-playback and reopen: Continue Watching resumes where you left off.

Music (see `MUSIC.md`; these are the points only a person at the Mac can confirm)
1. Music › Add a folder: your songs appear with titles, artists, albums and covers.
2. Play a song: **you hear it**. Pause, seek, change the volume, skip.
3. Press the keyboard's play/pause, next and previous keys: Music responds. The song shows in
   Control Centre and the Now Playing widget, with its cover.
4. Open Streaming, Manga or Settings while it plays: the music continues and the mini-player stays.
5. Start a video in Streaming: the music pauses. The media keys now control the video.
6. Plug in or switch headphones or speakers while a song plays: the sound follows.
7. Quit with Cmd+Q and reopen: library, playlists, favourites and the paused queue are back.
8. Switch to another profile: the music stops and that profile has its own (or an empty) library.
9. Plugins › Music: add your Navidrome/Subsonic or Jellyfin server; its songs appear and play.

Plugins isolation
1. Plugins has three tabs: Streaming, Music, Manga. Plugins › Music shows Music's integrations; Manga shows its own packages, sources and metadata providers.
2. Disable a Streaming addon: nothing changes in the other tabs.
3. Install Tiyo in Plugins › Manga: it is listed under Manga only. Switch it off: Manga's sources disappear; Streaming and Music are unchanged.

Storage
1. Settings › Storage & backups shows the folders. "Back up accounts now" writes a snapshot.

Not part of the app: game launching (the Games module is dormant).

## Play Next (Streaming player)

Automated: `./gradlew :composeApp:desktopTest --tests "com.nuvio.app.features.player.NextEpisode*" --tests "com.nuvio.app.features.streams.StreamSimilarityTest"`.
These drive the player's own command path (the command the native controls send for the button
and for the pop-up) with simulated add-on answers.

By hand, with an add-on installed:

1. Play an episode of a series. Press the Next episode button: the next episode starts from the
   source closest to the one that was playing (same add-on, group, resolution, codec).
2. Let an episode run to its end: the pop-up shows the next episode's number and title; its
   button starts it; pressing it while it says "Finding source…" or counts down cancels.
3. Settings › Playback › Up Next: "Auto-play Next Episode", the countdown, and "Match the current
   source". With matching off, or when nothing is close enough, the source list opens.
4. Play the last episode of a season: Next continues with the first of the following season.

## Music integrations

1. Music › Discover with no source: three cards. "Install" on Audius; trending songs appear.
2. Search for an artist and for a song; open an artist, an album, a playlist; play a song; the
   mini-player shows it and playback continues in other sections.
3. Plugins › Music: Test on MusicBrainz, Cover Art Archive and LRCLIB shows "Working" or the error.
4. Import a plugin from a file; it is listed as set up for this profile; remove it.
5. An album's menu › "Details and covers online": editions and covers, or a plain "none".
6. Restart: the sources, plugins, provider switches and their last health are as they were.

Live checks against the real services: `DEBRIDONE_LIVE_TESTS=1 ./gradlew :debridone:modules:music:test --tests "*MusicLiveServicesTest"`.

## Manga

Automated: `./gradlew :debridone:modules:manga:test` (needs the pinned Node.js once:
`./scripts/debridone/fetch-node-runtime.sh linux-x64`, or `darwin-arm64` / `darwin-x64`). The tests
start the real extension host, let DebridOne's installer download the real `@tiyo/core` 1.0.3 from
the npm registry, and read through Tiyo's Komga source against a stand-in server. With
`DEBRIDONE_REQUIRE_NODE=1` a missing runtime fails the run instead of skipping those tests.

Live checks (MangaDex's official test title, Komga's public demo server, AniList):
`DEBRIDONE_LIVE_TESTS=1 ./gradlew :debridone:modules:manga:test --tests "*LiveSourcesTest" --tests "*LegacyExtensionTest"`.

Inside the packaged app: `DebridOne.app/Contents/MacOS/DebridOne --manga-self-test` reads a CBZ
with no extension, starts the host on the bundled Node.js, installs Tiyo, reads a chapter through
it against a stand-in server, restarts and resumes, and ends with `MANGA SELF-TEST PASSED`
(`--offline` skips the parts that need the npm registry). CI runs it for every build.

By hand — online source (use a server or site you are allowed to read from; Komga's demo server
at `https://demo.komga.org`, user `demo@komga.org`, password `komga-demo`, has public-domain
comics):

1. Manga shows Home, Discover, Search, Library, Downloads and a cogwheel, and no "in development"
   card. With nothing installed, Home offers "Add Source" and "Add files".
2. Plugins › Manga › Browse: Tiyo is listed with its versions; Install 1.0.3; read the review;
   confirm. Installed shows it as Running and Verified; Sources lists 44 sources, "Not tested".
3. Sources › Komga › settings: enter the server and login; Test: the five steps pass.
4. Manga › Search: type a title; results are grouped by source, failures are named per source.
5. Open a series: cover, details, chapters. Start Reading: pages appear.
6. Turn pages with the arrow keys; press `M` for the modes, `S` for the settings, `F` for full
   screen; Esc leaves the reader.
7. Quit with Cmd+Q, reopen: Home › Continue Reading has the series; it opens on the same page.
8. Download a chapter (chapter menu), watch Downloads, open it from there with the network off.

By hand — local files:

1. Library › Add › Comic archives: choose a CBZ. It opens as a series; the cover is its first page.
2. Read, change mode and fit, set a bookmark (`B`), quit, reopen: progress and bookmark are kept.
3. Library › Add › A folder: a folder of chapter archives or image folders becomes one series.
4. "Shelve as Manhwa" on a series: it opens as a continuous strip (the Manhwa preset).

By hand — metadata:

1. On a series: ⋯ › Find details online › AniList: pick a match; the details and cover change.
2. Edit Metadata: change the title; Save. The title field now shows a closed padlock.
3. ⋯ › Refresh from the source, and Find details online › Refresh from linked: the title stays.

By hand — profiles: a second profile has an empty Manga library, its own sources switched on and
its own reading settings; the first profile's are untouched when you switch back.


## Music addons (Eclipse Music / BeatBoss format)

Automated: `./gradlew :debridone:modules:music:test --tests "*MusicAddonTest"` (11 tests against a
local addon in that format, including playback through libmpv).

By hand, with the sample addon (it serves DebridOne's own test recordings):

```bash
python3 scripts/debridone/sample-music-addon.py --token my-private-token
```

1. Plugins › Music › Addons: paste the printed address, *Add addon*. The review names the addon,
   its server (not the token) and what DebridOne will use. *Install for this profile*.
2. The addon is listed as Working. *Test*: all steps pass and the audio is identified.
3. *Settings*: change "Order of All songs"; the list in Discover follows. Saving is off.
4. *Open in Discover*: its songs are listed; search finds them; one plays, and the mini-player
   shows it. Playing continues in other sections.
5. Add a song to the library and to a playlist; quit and reopen: it is still there and plays.
6. Switch on *Allow saving songs*, choose "Download for offline" in a song's menu, stop the sample addon: the
   saved song still plays; the others say the addon could not be reached.
7. Another profile: Plugins › Music › Addons is empty there.
8. Paste a Streaming add-on's manifest address: refused, with the reason.

## Connected accounts, Downloads and cloud files

Automated, all against local stand-in servers that answer in the providers' documented formats
(`debridone/cloud/src/testFixtures`); no provider is contacted:

- `./gradlew :debridone:cloud:test`: sign-in with a code and with a token / key, renewal, a
  withdrawn sign-in, profiles and sharing, the listings of both providers (paging, Real-Debrid's
  100-per-page limit, duplicates, packed torrents, a failed listing), the classifier, download
  addresses made on demand and again after they expire, deletion (stand-in only), and the download
  manager (resume, retry with a new address, pause / cancel, name conflicts, disk space, checksum,
  unsafe names, restart).
- `./gradlew :debridone:modules:music:test --tests "*MusicCloudTest"`: Cloud Music with an
  in-memory cloud (listed outside the library until added, profiles, playback through libmpv with
  an address asked when the song starts, saving through the application, tags from the saved
  file, a saved song outliving its cloud entry and its account).
- `./gradlew :debridone:modules:manga:test`: includes a `.cbr` that is really a ZIP.
- `SourceBoundaryTest`: `theCloudLayerOnlyReadsExceptForTheConfirmedDeletion`,
  `musicAddonsNeverSeeTheCloud`, and the import rules that keep the cloud layer out of the
  sections' engines and screens.

Against your real accounts (optional, read-only, prints counts and types, never names):
`DEBRIDONE_LIVE_CLOUD=1 ./gradlew :debridone:cloud:test --tests "*CloudLiveAccountsTest"` with
`DEBRIDONE_LIVE_RD_TOKEN` and `DEBRIDONE_LIVE_TORBOX_KEY` set. It reads the account,
the lists and one download address; it reuses an existing Real-Debrid link unless
`DEBRIDONE_LIVE_CLOUD_UNRESTRICT=1` (which adds one "generated link" record to the account).

By hand:

1. Settings › Connected Accounts › Real-Debrid › *Connect*: a code appears; open
   real-debrid.com/device, type it, allow. The card shows your user name, plan and expiry.
   TorBox › *Connect*: paste your API key.
2. Downloads: *All*, *Real-Debrid*, *TorBox*, *Local*; the shelves; search; sort; grid and list;
   open an entry with several files.
3. Play a video (it opens in the Streaming player; leave a video longer than two minutes part of
   the way through and find it under Continue Watching), an audio file (Music's mini-player shows
   it), and "Download and read" a CBZ. Streaming › Library › Connected Accounts lists the same
   videos.
4. Download a file; pause and resume it; quit while one is running and reopen: it continues.
   *Local* shows the file and its folder; *Reveal in Finder* opens that folder.
5. Music › Library › Cloud Music: songs, albums, artists; add one to the library; "Download for
   offline"; it appears under *Available offline* with its own cover.
6. A second profile: an account marked *This profile only* and its files are not there; one
   marked *All profiles* is.
7. On the providers' websites nothing has changed, except the "generated link" records
   Real-Debrid keeps for files you played or downloaded.
8. "Delete from …" on an entry you no longer want: a confirmation naming the provider; after it,
   the entry is gone from the provider's website too. Local copies are still on the computer.

### The acceptance list, and how each point was checked

"Stand-ins" means the running app (from source, on Linux, virtual display) against the local
stand-in servers; "real" means the owner's own accounts, read-only.

| # | Point | Checked |
|---|---|---|
| 1 | Connect a Real-Debrid account | Stand-ins: with a code, and "Sign in again". Real: the service issued a code (not completed: it needs the owner's browser); connected with an API token |
| 2 | Connect a TorBox account at the same time | Stand-ins and real (API key) |
| 3 | Account information | Stand-ins: shown as served. Real: user, plan and expiry were read; not compared with the websites |
| 4 | Browse the files of each account | Stand-ins: both. Real: Real-Debrid (17 torrents, 221 files); TorBox's lists were empty |
| 5 | Transfers and download records | Stand-ins: torrents, usenet, web downloads, generated links. Real: Real-Debrid torrents and generated links |
| 6 | Filter by provider | Stand-ins (tabs, and TorBox's kinds) |
| 7 | Search by name | Stand-ins, and a unit test |
| 8 | Download a sample audio file | Stand-ins. Not real |
| 9 | Play it in the Music player | Stand-ins, through libmpv with silent output (nobody heard it) |
| 10 | Import a sample comic archive | Stand-ins (CBZ) |
| 11 | Read it in the Manga reader | Stand-ins |
| 12 | Open a video in the video player | Stand-ins, including resume from Continue Watching. Not real |
| 13 | Reveal a downloaded file in Finder | **Not run**: needs a Mac |
| 14 | Restart | Stand-ins, several times |
| 15 | Connections and download history persist | Stand-ins: accounts, listings, history, settings, and an unfinished download that went on by itself |
| 16 | Profile permissions | Stand-ins: a second profile sees only the shared account, its own downloads and its own Cloud Music; unit tests |
| 17 | Streaming, Music and Manga stay independent | `SourceBoundaryTest` (imports, registries, who may give Music cloud audio); separate databases |
| 18 | Expired credentials and dead links | Unit tests (withdrawn sign-in, expired address made again, missing file); in the app against stand-ins: "Sign in again" shown and recovered, songs of an expired sign-in reported instead of played. Not provoked on the real accounts |

Also checked against stand-ins: grid and list, sort, details, pause / resume / cancel, the
settings page, "Delete from TorBox…" with its confirmation, Cloud Music (songs, albums, add to
library, download for offline, play from Downloads), Streaming › Library › Connected Accounts.

For development, `DEBRIDONE_REAL_DEBRID_API=http://127.0.0.1:<port>` and
`DEBRIDONE_TORBOX_API=http://127.0.0.1:<port>` point the app at local test servers (loopback
addresses only; the cards show a red notice). See `CLOUD.md`.
