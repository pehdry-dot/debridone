# Connected accounts: Real-Debrid and TorBox

DebridOne can show, play, read and download the files that are **already in your own**
Real-Debrid and TorBox accounts, without opening their websites. It does not add anything to an
account, does not look for content at a provider, and removes something there only when you
choose "Delete from Real-Debrid / TorBox" and confirm.

Connecting an account is free, like everything in DebridOne. What a provider charges for its own
service is between you and the provider.

## Connecting

Settings › Connected Accounts has one card per provider, with the account's name or e-mail, plan,
expiry date and status, and *Connect*, *Refresh*, *Manage account* (opens the provider's site in
your browser) and *Disconnect*. Both can be connected at the same time.

- **Real-Debrid**: DebridOne shows a code; you open `real-debrid.com/device` in your browser, sign
  in there and type it. Your password is never typed into DebridOne. This is the sign-in
  Real-Debrid documents for open-source applications. Alternatively, paste the API token from your
  Real-Debrid account page.
- **TorBox**: paste the API key from TorBox's settings page.

What the provider hands over (tokens, the key) is kept in the macOS Keychain and nowhere else: not
in a database, a settings file, a log or an exported backup. A Real-Debrid sign-in is renewed by
itself; when a provider no longer accepts it, the card says *Sign in again*.

**Profiles.** Each account is either *Available to all profiles* or *This profile only*, chosen
when connecting and changeable on the card. A profile sees, plays and downloads only from the
accounts it may use. Deleting a profile removes the accounts that were only its own, with their
sign-ins.

## Downloads

The sidebar's Downloads section is the file browser for the connected accounts:

- Tabs **All | Real-Debrid | TorBox | Local**. On TorBox: *Torrents*, *Usenet*, *Web downloads*.
  Real-Debrid lists torrents and the links you generated; a file that is both a torrent's file and
  a generated link is shown once.
- Shelves: *Everything*, *Movies*, *TV Shows*, *Music*, *Manga & Comics*, *Other Files*; search;
  sort by name, date, size or status; list or grid; an entry opens to its folders and files;
  *View details* on entries and files.
- Each entry says which provider and which kind it is, its size, its state and, while a provider
  is still fetching it, its progress.

**How files are told apart.** By their extension, and by the type the provider reports only when
the extension says nothing. A video counts as a *TV Show* only when its path says so (`S01E02`,
`1x02`, a "Season 2" folder); every other video is under *Movies*, and the page says that this is
all the names can tell. A plain ZIP is under *Other Files* with a "Download and read" action,
because DebridOne cannot know it is a comic before opening it. Nothing is guessed from a title.

**Opening a file.**

| File | Opens in | How |
|---|---|---|
| Video | The Streaming player | Played from the cloud with an address made at that moment, or from its local copy. Progress is saved and it appears in Continue Watching. |
| Audio | The Music player | Played from the cloud or from its local copy. "Add to Music library" keeps it; see *Cloud Music* below. |
| CBZ, ZIP (and a `.cbr` that is really a ZIP) | The Manga reader | The archive has to be on this computer first: the action is called "Download and read", says so again before it starts, and opens the reader when the download is complete. |
| Anything else | Nothing | Download, *Reveal in Finder*, *Open with the default app*. Programs and installers are never opened or run by DebridOne. |

RAR and 7-Zip archives (true CBR, CB7) are not read. See [LIMITATIONS.md](LIMITATIONS.md).

**Deleting.** "Delete local copy" removes the file from this computer and says that the cloud is
not touched. "Delete from Real-Debrid / TorBox…" is a separate, red action with its own
confirmation that names the provider and says the entry cannot be brought back; it does not delete
your local copies or what you imported from them. Removing something from a library, or
disconnecting an account, never deletes anything in the cloud.

## Downloading to this computer

One download manager serves the whole app (Music's "Download for offline" on a cloud song and
Manga's "Download and read" use it too):

- a queue with progress, speed and time left; pause, resume, cancel, retry; a limit on how many
  run at once; history;
- a transfer that breaks is resumed where it stopped (when the server allows it) and retried by
  itself, with a new address when the old one expired; unfinished downloads continue after a
  restart;
- files go to `~/Downloads/DebridOne/<Video | Music | Manga>/<entry>/…` (other files straight
  into `~/Downloads/DebridOne/<entry>/…`) unless you chose other folders (Settings › Downloads,
  or "Download to…");
- an existing file is never overwritten silently: by default the new one is saved as
  `name (2).ext`; the alternatives are *skip* and *replace*;
- a warning instead of a download when the disk would be left with less than the reserve you set;
- where the provider gives a checksum (TorBox), the finished file is checked against it.

Settings › Downloads also has "add downloaded music to the Music library" and the same for
comics, both off until you switch them on.

## In the three sections

The accounts belong to the application, not to a section. Each section is handed only its own
kind of file, one file (or one selection) at a time, and keeps its own library, metadata, plugins
and settings.

- **Music › Library › Cloud Music** lists the audio files of the profile's accounts as songs,
  albums and artists. They are *listed*, not in your library: "Add to library" keeps a song (it
  then appears in Songs, Albums, playlists and search), "Remove from library" only un-keeps it.
  Songs play from the cloud; "Download for offline" saves them through the download manager and
  they then play from the file. Names are read from file and folder names until a song is saved,
  when its own tags and embedded cover are used; covers and details can otherwise be looked up
  with Music's own providers as for any song. Music holds no sign-in for the accounts, and its
  addons (Eclipse Music / BeatBoss format) and plugins are never shown them.
- **Streaming › Library › Connected Accounts** lists the video files of the profile's accounts,
  by entry, with a search field. They play in the Streaming player and resume from Continue
  Watching (Streaming keeps no progress for clips shorter than about two minutes: its own rule
  for placeholder videos). Streaming's "Cloud" chip and its debrid settings (Nuvio's, used to
  resolve add-on streams) are a different thing: they are not changed, and the connected accounts
  are never copied into them.
- **Manga**: "From your cloud…" in Manga's Library opens the comics shelf. A downloaded archive
  is linked into the profile's manga server (its Local source) and read with Manga's own reader.
  Manga's extensions never see the accounts. A profile that reads from a manga server of its own
  cannot open cloud archives through it.

## What is sent where

- To Real-Debrid (`api.real-debrid.com`): the sign-in, your account details, the list of your
  torrents and generated links, and, when you play or download a torrent's file, the request that
  turns it into a download address. **That request adds a "generated link" record to your
  Real-Debrid account**: that is how Real-Debrid's API works. DebridOne reuses a link that already
  exists instead of making another.
- To TorBox (`api.torbox.app`): your key, your account details, your three lists, and requests
  for download addresses. TorBox's API takes the key inside the address of that one request; the
  address is built, used and forgotten inside DebridOne's TorBox client and is never stored,
  logged or shown.
- To the providers' download servers: the request for the file itself.

Download addresses are made when they are needed and kept in memory only. Nothing is sent to
anyone else; there is no analytics.

## For developers

- `debridone/cloud` (`com.debridone.cloud`) is the whole layer: `RealDebridClient`, `TorBoxClient`,
  `DebridAccountManager`, `CloudFileRepository` (the cached listing, in `cloud/cloud.db`),
  `MediaFileClassifier`, `DownloadManager`. It depends only on `debridone/platform`.
- The app side is `composeApp/src/debridoneMain/kotlin/com/debridone/app/cloud`. Sections are
  reached through three bridges: `StreamingCloudBridge` and `StreamingCloudLibrary` (the seam in
  `AppShellHost`), `MusicCloudSource` (implements the `CloudAudioSource` interface that Music
  defines) and `MangaBridgeImpl`.
- Enforced by `SourceBoundaryTest`: no section's engine or screens import the cloud layer; the
  layer contains no request that adds or searches at a provider, and its removal requests are
  reached from one confirmed action only; Music's addon code knows nothing of the accounts.
- `DEBRIDONE_REAL_DEBRID_API` / `DEBRIDONE_TORBOX_API` point the app at a test server on this
  computer (loopback addresses only). The Connected Accounts page then shows a red "Test server"
  notice. `./gradlew :debridone:cloud:standInClasspath -q` prints the classpath of the stand-in
  servers used by the tests (`com.debridone.cloud.testing.StandInMainKt <rd-port> <torbox-port>
  <samples-folder>`).

How it was tested, and against what, is in [TESTING.md](TESTING.md); what was not is in
[LIMITATIONS.md](LIMITATIONS.md).
