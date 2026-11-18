# Music addons (Eclipse Music / BeatBoss format)

Music can use addons in the manifest format that **Eclipse Music** and **BeatBoss** share. An addon
is somebody's HTTP server; you add it by the address of its `manifest.json`. DebridOne is a client
of that protocol and nothing more: it reads the JSON the addon answers with and plays the audio
addresses it gives. No code from an addon runs in DebridOne, and DebridOne ships with no addon.

Addons belong to Music only. They are not Streaming add-ons (a different protocol, refused here
with that explanation) and have nothing to do with Manga extensions.

The format was taken from its published descriptions: Eclipse Music's *Addon Developer Guide*
(`eclipsemusic.app/docs`) and BeatBoss's `ADDONS.md` and client code
([TheVolecitor/BeatBoss](https://github.com/TheVolecitor/BeatBoss), MIT). No code of either is used.

## Adding one

Plugins › Music › **Addons**: paste the address (`https://…/manifest.json`, or the address without
`/manifest.json`) and press *Add addon*. DebridOne reads the manifest and shows, before anything is
installed: the addon's name, version and kind, the server it is on, what DebridOne will use, what
the addon offers that DebridOne does not use, and anything worth knowing. *Install for this
profile* then installs it. Pasting such an address into "Import a plugin" leads to the same review.

Each addon in the list can be switched off and on, configured, tested and removed.

- **Settings** shows the addon's own settings (as its manifest declares them: choices, switches,
  text and number fields), the name it has in DebridOne, and *Allow saving songs from this addon*.
- **Test** tries the addon step by step — manifest, search, browsing rows, a song's address, and
  the first bytes of that song's audio — and says which step fails and why.
- **Remove** takes the addon out of the profile with the songs it brought, their saved copies and
  its stored address.

## The address is a secret

Addons sign their users in through the address itself (`https://host/<your-token>/manifest.json`).
DebridOne therefore treats the whole address like a password:

- it is kept only in the macOS Keychain, per profile;
- it is not written to the music database, to a log, or into an error message — what is shown and
  stored in the open is the server's name only;
- songs that carry their own audio address under the addon's address are remembered relative to
  it, so the token does not reach the library either;
- another profile neither sees the addon nor can use it; it can install the same addon itself.

An automated test searches every file Music writes for the token and fails if it finds it.

## What is supported

| Part of the format | In DebridOne |
|---|---|
| `manifest.json`: `id`, `name`, `version`, `resources` (required); `description`, `icon`, `types`, `contentType` | Read and validated. `contentType` `audiobook` and `podcast` addons are accepted and play in the music player (no chapter list, speed control or sleep timer). |
| `search` — `GET /search?q=&limit=` | Songs, albums, artists and playlists, in Discover and its search. Field aliases used by addons in the wild are read (`artistName`, `albumTitle`, `albumCover` / `image` / `cover`, `name`, `songs` / `songList`, `numberOfTracks`, `year` as text or number, ids as text or number). |
| `stream` — `GET /stream/{id}` | Asked when a song starts, never for a whole queue. `503` with `Retry-After` ("still preparing") is asked again up to three more times. `format`, `codec`, `container`, `quality`, `manifest`, `expiresAt` are read. |
| `streamURL` on a song | Played directly, as the format says, without asking `/stream`. |
| `catalog` — `/album/{id}`, `/artist/{id}`, `/playlist/{id}` | Album, artist and playlist pages. |
| `catalogs` rows — `GET /catalog/{id}?skip=` | Rows of songs are shown in Discover: the first as "From ⟨addon⟩", and with several rows Discover's filter chips choose between them (that choice was exercised by tests only). Album, artist and playlist rows are read by the engine but not yet shown as rows. |
| `settings` | A settings dialog built from the manifest; the values are sent as query parameters on every request, per profile. `perNetwork` is not distinguished: one value is kept. |
| `lyrics` — `GET /lyrics?artist=&title=` (BeatBoss) | Asked before LRCLIB for songs when an installed addon offers lyrics. |
| `notifications` — a `notice` in any answer | An addon's own words are shown when something fails (rate limit, song unavailable). Progress toasts are not shown. |
| Tokens in the address | Supported; see above. |

Not used, and said so in the review: `library` (BeatBoss's library sync — your library stays on
your Mac), `isrc` and `resolve` (matching songs chosen elsewhere), video, and anything else an
addon lists.

Not supported on purpose:

- **Eclipse-only addons.** Some addons answer only requests that carry a token issued by Eclipse's
  server to a signed-in Eclipse account. DebridOne does not have such a token and does not imitate
  the Eclipse app; those addons refuse it and the message says so.
- **Protected streams.** A stream the addon marks `encrypted` is not played.

## What the audio really is

Addons often call everything `mp3`. DebridOne does not rely on that: the player (libmpv) identifies
the audio itself, and for the connection test and for saved files DebridOne reads the first bytes:
MP3, FLAC, AAC, MPEG-4 audio (AAC or ALAC), Ogg Vorbis, Opus, WAV and AIFF are recognised, and so
are HLS playlists and DASH manifests. A saved file gets the extension of what it contains; an HLS
or DASH stream, which is not one file, cannot be saved and is reported as such. HLS and DASH
playback is passed to the engine and has not been tested.

## Saving songs for offline listening

Off for every addon until you switch it on in that addon's settings. Installing an addon says
nothing about what its provider permits, and the format has no way for a provider to say so;
DebridOne leaves the decision to you, per addon, and asks you to switch it on only where the
provider permits downloading and the files are yours to keep. With it on, "Download for offline"
appears in the menu of the addon's songs, as for a personal server; a saved song plays without the
addon.

## What stays as it was

Songs from an addon behave like any other song of Music: they can be added to the library, to
playlists and to favourites, appear in the listening history, and survive restarts. What the
library keeps is the addon's id for the song, not an address, so a kept song asks the addon for a
fresh address each time it plays. Removing or switching off the addon makes its songs unavailable,
not wrong.

## Trying it without anyone's server

`scripts/debridone/sample-music-addon.py` is a small addon in this format (Python, standard library
only, this computer only). By default it serves DebridOne's own test recordings; point `--audio` at
a folder of audio you are free to use.

```bash
python3 scripts/debridone/sample-music-addon.py --token my-private-token
```

It prints the address to paste into Plugins › Music › Addons.

## Limits

- Tested against a local addon that follows the format and serves DebridOne's test recordings
  (automated tests, and by hand in the running app). **No third-party addon was tested**; an addon
  that departs from the documented format may need more of its quirks handled.
- Rows of albums, artists and playlists are not shown yet; their contents are reachable by search.
- No audiobook or podcast player modes, no video, no library sync, no playlist import through an
  addon, no "default playback source" matching.
- An addon sees what you search for and which songs you open, and your network address, like any
  web server you use.
