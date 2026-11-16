# Music plugins

A Music plugin is a **manifest**: a small JSON file that names one of Music's adapters and gives
it its settings. It carries no program code. What a plugin can do is exactly what the adapter it
names can do, and nothing from a plugin runs inside DebridOne.

Plugins belong to Music only. Streaming add-ons and Manga extensions are different things and are
refused here with a message that says so.

## Installing

Plugins › Music › **Import a plugin** accepts:

- an `https://` address that returns the manifest,
- a `.json` file,
- a `.zip` package with a `manifest.json` at its top level (other files in it are ignored).

A plugin that needs no sign-in is set up for the active profile at once. One that needs a sign-in
waits for you to press **Connect**. Every profile sets a plugin up for itself; removing a plugin
removes it for all profiles, with the songs or stations it brought and any saved sign-in.

## The manifest

```json
{
  "debridoneMusicPlugin": 1,
  "id": "org.example.radio",
  "name": "Example Radio",
  "version": "1.0.0",
  "description": "Three stations.",
  "adapter": "streams",
  "streams": [
    { "name": "Station One", "url": "https://radio.example.org/one.mp3", "genre": "Jazz" }
  ]
}
```

| Field | Meaning |
|---|---|
| `debridoneMusicPlugin` | Format version. This version of DebridOne reads `1`. |
| `id` | 3 to 64 lower-case letters, digits, dots, dashes or underscores. Not the id of a built-in integration. |
| `name` | Shown in DebridOne (up to 80 characters). |
| `version`, `description`, `homepage` | Optional. |
| `adapter` | `streams`, `debridone-catalog`, `subsonic`, `jellyfin` or `audius`. |
| `baseUrl` | Required for `debridone-catalog`, `subsonic` and `jellyfin`. |
| `auth` | `none`, `token` or `password`. Defaults to `password` for the two server adapters, else `none`. |
| `streams` | For `streams`: up to 500 entries of `name`, `url`, optional `genre`. |

Checks made before anything is installed: valid JSON object of at most 256 KB, the format key and
version, the id, a known adapter, and every address. Addresses must be `https`; plain `http` is
accepted only for this computer and the local network. Addresses with a user name or password in
them are refused. Each stream of a `streams` plugin is opened and added only if it answers with
audio; the ones that do not are listed as a note on the plugin.

## Adapters

| Adapter | What it is | Sign-in |
|---|---|---|
| `streams` | A list of internet radio stations or other direct audio addresses. | none |
| `debridone-catalog` | A server that speaks the Music Catalog protocol below. | none, or a token |
| `subsonic` | A preset for a Navidrome / OpenSubsonic server. | user name and password |
| `jellyfin` | A preset for a Jellyfin server. | user name and password |
| `audius` | The Audius catalogue (built in already). | none |

## The Music Catalog protocol (version 1)

This is how an integration is added without changing DebridOne: run a server that answers these
requests under one base address. All answers are JSON.

```
GET {base}/manifest.json            the plugin's manifest, with "adapter": "debridone-catalog"
GET {base}/search?q=&limit=         {"tracks":[],"artists":[],"albums":[],"playlists":[]}
GET {base}/featured?genre=&limit=   {"tracks":[]}
GET {base}/genres                   {"genres":["Rock", "Jazz"]}            (optional)
GET {base}/artists/{id}/tracks      {"tracks":[]}
GET {base}/albums/{id}/tracks       {"tracks":[]}
GET {base}/playlists                {"playlists":[]}                        (optional)
GET {base}/playlists/{id}/tracks    {"tracks":[]}
GET {base}/tracks/{id}/stream       the audio itself, or a redirect to it
```

A track:

```json
{ "id": "t1", "title": "River", "artist": "Band", "album": "Album", "albumArtist": "Band",
  "genre": "Folk", "year": 2022, "trackNumber": 1, "discNumber": 1, "durationMs": 215000,
  "format": "mp3", "bitrate": 320, "artworkUrl": "https://…/cover.jpg",
  "streamUrl": "https://…/river.mp3", "playable": true }
```

Only `title` and either `id` or `streamUrl` are required. With `streamUrl` the audio is played from
that address; without it, from `{base}/tracks/{id}/stream`. `"playable": false` lists a song
without offering to play it. Artists have `id`, `name`, optional `artworkUrl`, `trackCount`,
`description`; albums `id`, `title`, optional `artist`, `year`, `trackCount`, `artworkUrl`;
playlists `id`, `name`, optional `owner`, `trackCount`, `description`, `artworkUrl`.

With `"auth": "token"`, requests carry `Authorization: Bearer <token>`. The stream address is
opened by the audio engine itself and carries the token as `?token=`. The token is kept in the
macOS Keychain, per profile, never in the database.

Only `http(s)` addresses are taken from a catalogue's answers; anything else is dropped. At most
500 items of a list are read.

## What is not accepted

- Plugins that contain code to run (scripts, binaries, WebAssembly).
- Streaming (Stremio-style) add-on manifests and Manga extensions.
- Eclipse Music add-ons: their format is not published.
- Services whose terms or copy protection do not allow another player to stream their catalogue.
