# Music: your own Real-Debrid cloud

Music can list, play and save the **audio files that are already in your own Real-Debrid
account**. It is a personal source, like a Navidrome server: you sign in to *your* account and the
songs found there join this profile's library.

It is a separate integration. It has nothing to do with addons (see
[MUSIC_ADDONS.md](MUSIC_ADDONS.md)): an addon is never given your Real-Debrid sign-in, never uses
your cloud, and installing one does not make its catalogue downloadable. It is also separate from
whatever Streaming does with a debrid account: Music has its own sign-in, kept with Music's data.

> **Status.** Built from Real-Debrid's public API documentation and tested against a local server
> that answers in the documented format. **It has not been run against the real service**: that
> needs a Real-Debrid account, and none was available where DebridOne is built. The first real
> sign-in will be yours. See "What was and was not tested".

## What it does, and what it never does

| It does | It never does |
|---|---|
| Lists the audio files of your **finished torrents** | Add a torrent or a magnet |
| Lists the audio among the **links you generated before** ("downloads") | Search for anything |
| Plays them in Music's own player | Select or deselect files of a torrent |
| Saves them for offline listening ("Download for offline") | Delete anything from your account |
| Renews its own sign-in when it expires | Unlock a link of another file host |

Files that are not audio (films, covers, notes, archives) are ignored. A torrent that is still
downloading is not listed until it is finished. A torrent Real-Debrid packed into one archive is
left out, because its songs cannot be told apart.

## Connecting

Plugins › Music › Catalogue › **Real-Debrid** › *Sign in*.

1. DebridOne asks Real-Debrid for a code and shows it.
2. *Open real-debrid.com/device* opens Real-Debrid's own page in your browser. Sign in there if
   asked, type the code, allow the app.
3. The dialog notices by itself (it looks every five seconds), connects, and reads your cloud.

Your Real-Debrid password is never typed into DebridOne. This is the sign-in Real-Debrid documents
for open-source apps; what it hands over is limited to reading your account, your torrents and
your downloads, and turning your own files into download addresses. You can withdraw it at any
time on Real-Debrid's website, where it lists the apps you allowed; DebridOne then says that the
sign-in is no longer accepted.

**Or an API token.** *Use an API token instead* takes the private token from your Real-Debrid
account page. It works the same afterwards, but such a token opens everything in your account,
which is more than DebridOne needs or uses, and Real-Debrid itself advises against giving it to
apps. It is there for people who prefer it; the code is the better way.

Either way, the sign-in is kept in the macOS Keychain, for this profile only. It is never written
to Music's database, to a log or into a message, and no other profile, section, plugin or addon
can read it. Removing the connection erases it.

## Using it

- The connection appears under *Connected for this profile*. **Synchronise** reads the cloud
  again: new files are added, files that are gone leave the library. The cloud is also read when
  DebridOne starts, like every personal source (Music settings › "Rescan when DebridOne starts"
  switches that off).
- The songs are in Music's Library with everything else and behave like any song: queue,
  playlists, favourites, history, lyrics, "Edit details".
- **Names.** Real-Debrid knows files, not tags. Title, artist, album, year and track number are
  read from the file's name and its folder's ("Artist - Album (2021)/03 - Title.flac"). Where
  that guesses wrong, "Edit details" or a MusicBrainz match corrects it, and your correction is
  kept. A song's length is learned the first time it plays; until then it shows as "—".
- **Playing.** The download address of a file is asked from Real-Debrid at the moment the song
  starts (such addresses are made for the moment), so a long queue costs nothing until it plays.
- **Saving.** "Download for offline" in a song's menu saves the file into DebridOne's own offline
  folder; it then plays without Real-Debrid and is listed under Library › Available offline.

## Limits and manners

- Real-Debrid allows 250 requests a minute. Reading the cloud asks once per finished torrent
  (about three a second at most), so an account with hundreds of torrents takes a few minutes the
  first time. What a finished torrent contains is remembered, also across restarts, so later
  readings ask only about torrents that are new.
  If Real-Debrid says "too many requests", DebridOne waits twenty seconds, tries once more, and
  otherwise stops with that message.
- If reading the cloud fails half-way (no network, the service down, the account locked), nothing
  is removed from your library: an interrupted listing is not treated as a smaller cloud.
- Up to 5 000 torrents and 5 000 generated links are read.
- No covers: Real-Debrid has none to give. Covers come from the cover lookup like for any album.
- Whether a file may be in your account, and whether you may download it, is between you and
  Real-Debrid; DebridOne shows what your account already holds. Real-Debrid may refuse a file
  (it says so, and DebridOne repeats its words).

## What was and was not tested

Tested, automatically (`MusicRealDebridTest`, 9 tests, on Linux and in CI), against a local server
that answers as Real-Debrid's documentation describes, with DebridOne's own test recordings as the
"cloud":

- the code sign-in: nothing is stored before the code is allowed; afterwards the sign-in is in the
  credential store and in no file of Music; the same account cannot be connected twice;
- the API-token sign-in, a wrong token, and what looks like no token at all;
- only audio of finished torrents and generated links is listed; the same file is not listed
  twice; names, album, year and track numbers come out of the paths;
- only reading methods are ever called (the test fails if anything else is);
- a finished torrent is asked about once, not again at the next reading or after a restart;
- a failed listing leaves the library as it was; "too many requests" is waited out once;
- playback through the real audio engine (libmpv), with the address asked only for the song that
  plays; saving a file and playing it with the service gone;
- an expiring sign-in is renewed, the new one is stored, a withdrawn one is reported in words;
- another profile sees nothing; an addon installed in the same profile is sent nothing of the
  cloud and cannot save its songs because of it.

Tested by hand in the running application on Linux, against the same kind of local server: the
card, the dialog with its code, connecting, the songs appearing in the Library, playing both kinds
of file, "Download for offline".

**Not tested:** the real Real-Debrid service (sign-in page, real answers, real limits, real
download addresses), and anything on a Mac. The documented formats are followed closely, but
documentation and service can differ; if the first real sign-in or listing fails, the message
shown is Real-Debrid's own and is the thing to report.

## For developers

- `debridone/modules/music/src/main/kotlin/com/debridone/music/sources/RealDebridAdapter.kt`: the
  adapter (sign-in, listing, addresses). It is one of Music's source adapters; it is not a plugin
  and a plugin manifest cannot name it.
- `StandInRealDebrid.kt` (tests): the documented API as a local server.
- `DEBRIDONE_REAL_DEBRID_API=http://127.0.0.1:<port>` points the application at such a local
  server instead of Real-Debrid. Only an address on this same computer is accepted, and the
  sign-in dialog says in red that a test server is in use.
- Methods used: `GET /oauth/v2/device/code`, `GET /oauth/v2/device/credentials`,
  `POST /oauth/v2/token`, `GET /rest/1.0/user`, `GET /rest/1.0/torrents`,
  `GET /rest/1.0/torrents/info/{id}`, `GET /rest/1.0/downloads`, `POST /rest/1.0/unrestrict/link`
  (only with links of the account's own torrents). An architecture test fails the build if the
  adapter ever contains one of the methods that add, select or delete.
