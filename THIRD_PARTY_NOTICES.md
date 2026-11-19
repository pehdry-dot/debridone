# Third-party notices

DebridOne is a modified version of **Nuvio Desktop** and is distributed under the
GNU General Public License v3.0 (see `LICENSE`). The complete corresponding source is this
repository.

## Nuvio Desktop

- Source: https://github.com/NuvioMedia/NuvioDesktop
- Licence: GPL-3.0
- Copyright: Nuvio Media and the Nuvio Desktop contributors
- Use in DebridOne: the application foundation and the Streaming module. Changes made by DebridOne
  are listed in `debridone/docs/UPSTREAM.md` and marked in the source with `DebridOne:` comments.
  Nuvio's own third-party attributions remain available in the app under
  Streaming › Settings › Licenses & Attributions, and in `composeApp/src/desktopMain/resources/licenses`.

"Nuvio" is the name of the upstream project. DebridOne is not affiliated with or endorsed by
Nuvio Media.

## Hydra Launcher

- Source: https://github.com/hydralauncher/hydra
- Licence: MIT
- Copyright: Los Broxas and the Hydra Launcher contributors
- Use in DebridOne: design and behaviour reference for the Games module. No Hydra source code is
  included yet. Code ported from Hydra will carry this notice and be listed in
  `debridone/docs/UPSTREAM.md`.

## Suwayomi-Server

- Source: https://github.com/Suwayomi/Suwayomi-Server (release `v2.4.2366`; the source of exactly
  that release is at https://github.com/Suwayomi/Suwayomi-Server/tree/v2.4.2366)
- Licence: Mozilla Public License 2.0 (https://www.mozilla.org/MPL/2.0/)
- Copyright: Contributors to the Suwayomi project
- Use in DebridOne: Manga's backend. The release jar is **bundled unmodified** in the macOS app
  and run as a separate program; DebridOne talks to it over its GraphQL API and contains none of
  its source. Under the MPL you may obtain, change and redistribute Suwayomi-Server's source from
  the address above; DebridOne places no further restriction on it. The libraries inside the jar
  keep their own licences, which ship inside it.

## Eclipse Temurin (OpenJDK 21 runtime)

- Source: https://adoptium.net (builds `21.0.13.1+2` for Apple Silicon and `21.0.13+9` for Intel); OpenJDK sources at https://github.com/adoptium/jdk21u
- Licence: GNU General Public License, version 2, with the Classpath Exception
- Use in DebridOne: the Java runtime Suwayomi-Server runs on. The official build is **bundled
  unmodified** beside it, with its own `legal/` folder (the licence texts of the runtime and of
  everything in it) and `NOTICE`.

## Mihon/Tachiyomi extensions

- Not bundled, named or preconfigured. An extension is installed by the user, into their
  Suwayomi-Server, from an extension store whose address they add. Each extension keeps its own
  licence; what it fetches is between the user, the extension and its site.

## Mangayomi

- Source: https://github.com/kodjodevf/mangayomi
- Licence: Apache License 2.0; Copyright the Mangayomi contributors
- Use in DebridOne: the API a Mangayomi JavaScript source expects (`MProvider`, `Client`,
  `Document`/`Element`, `SharedPreferences`, filter and preference shapes, helper names) is
  reproduced in Manga's bridge (`debridone/modules/manga-bridge/src/main/resources/debridone-mangayomi/runtime.js`),
  following the shape of Mangayomi's own `lib/eval/javascript`; the class-selector and attribute
  semantics of its selector library (`pseudom`, by the same author) are reproduced in Kotlin. No
  Mangayomi code is run, and no source or index is shipped: sources are fetched from the indexes
  the person imports. The bridge runs the sources in GraalJS and parses pages with jsoup, both of
  which are part of Suwayomi-Server and are not bundled again.

## Houdoku and Tiyo

- Source: https://github.com/xgi/houdoku and https://github.com/xgi/tiyo (MIT, xgi and contributors)
- Use in DebridOne: none any more. Earlier versions of Manga were built on them; they are no
  longer used, bundled or downloaded. A library made with those versions is carried over.

### MIT License (Hydra Launcher)

Permission is hereby granted, free of charge, to any person obtaining a copy of this software and
associated documentation files (the "Software"), to deal in the Software without restriction,
including without limitation the rights to use, copy, modify, merge, publish, distribute,
sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all copies or
substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT
NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM,
DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT
OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.

## Eclipse Music and BeatBoss

Eclipse Music is a feature reference for the Music module. Music also implements, as a client, the
addon format that Eclipse Music documents publicly (its Addon Developer Guide) and that
BeatBoss (https://github.com/TheVolecitor/BeatBoss, MIT, by TheVolecitor) documents and implements
as well. The format was read from those descriptions and from BeatBoss's client code; no source
code, asset or text of either project is included in DebridOne.

## Libraries added by DebridOne

| Library | Licence | Use |
|---|---|---|
| SQLite JDBC (`org.xerial:sqlite-jdbc`) | Apache-2.0 | Accounts and module databases |
| Bouncy Castle (`org.bouncycastle:bcprov-jdk18on`) | MIT-style (Bouncy Castle Licence) | Argon2id password hashing |
| jaudiotagger (`net.jthink:jaudiotagger`) | LGPL-2.1-or-later | Music: reading tags and embedded covers. Used as an unmodified library. |
| Java Native Access (`net.java.dev.jna:jna`) | Apache-2.0 or LGPL-2.1-or-later | Music: calling libmpv and DebridOne's macOS music bridge. Already part of Nuvio Desktop. |

Music plays audio through the libmpv and FFmpeg libraries that Nuvio Desktop already bundles for
video (their licences are among Nuvio's attributions, see "Nuvio Desktop" above); DebridOne adds no
other decoder.

## Bundled with Manga

The macOS app contains, under `Contents/app/resources/suwayomi/`:

| Component | Licence | Use |
|---|---|---|
| `server/Suwayomi-Server.jar`: Suwayomi-Server `v2.4.2366`, the official release, unmodified (checksum in `scripts/debridone/fetch-suwayomi-runtime.sh`) | MPL-2.0; the libraries it contains under their own licences, inside the jar | Manga's backend, run as a separate process |
| `jre/`: Eclipse Temurin JRE 21 (`21.0.13.1+2` for Apple Silicon, `21.0.13+9` for Intel), the official build, unmodified | GPL-2.0 with Classpath Exception; full texts in `jre/legal/` | Runs Suwayomi-Server |

Nothing else is added to either. The earlier Node.js runtime and npm packages are no longer shipped.

## Online services used by Manga

Manga contacts these only when you use the feature, and sends no account or device identifier of
DebridOne's:

| Service | Used for | Terms |
|---|---|---|
| The extension stores you add | Listing and downloading the extensions they offer, by your Suwayomi-Server | Each store's own terms |
| The Mangayomi indexes you import | Reading the index and each source's code, by DebridOne; the sources then reach their sites through your Suwayomi-Server | Each index's and site's own terms |
| AniList (`graphql.anilist.co`) | The trending rows on Manga's Home and looking up details you ask for | AniList API terms |
| MangaDex (`api.mangadex.org`) | Looking up details you ask for | MangaDex API terms; covers are hot-linked as its API intends |
| MyAnimeList (`api.myanimelist.net`) | Looking up details, only with your own API client ID | MyAnimeList API licence |
| The trackers you sign in to (AniList, MyAnimeList, Kitsu, MangaUpdates, Shikimori, Bangumi) | Contacted by your Suwayomi-Server, with your sign-in, to keep your reading list up to date | Each tracker's own terms |
| `suwayomi.org/tracker-oauth` | During a tracker sign-in in your browser: the tracker sends the browser there, and that page sends it on to DebridOne on your computer | Suwayomi's website |
| The sites of the sources you enable | Whatever the extension you installed requests | Each site's own terms |

## Online services used by Music

Only when asked, and each can be switched off in Plugins › Music:

| Service | Used for | Data |
|---|---|---|
| MusicBrainz (musicbrainz.org) | Song details when you look a song up | CC0 / CC BY-NC-SA for some derived data; see musicbrainz.org/doc/About/Data_License |
| Cover Art Archive (coverartarchive.org) | A cover for a song that has none | Images belong to their rights holders |
| LRCLIB (lrclib.net) | Lyrics for a song that has none | Community-contributed |

Navidrome, other Subsonic-compatible servers and Jellyfin are reached through their public APIs;
no code from those projects is included.

## Real-Debrid and TorBox

DebridOne can read the user's own Real-Debrid and TorBox accounts through the providers' public
REST APIs (https://api.real-debrid.com/ and https://api-docs.torbox.app/). For Real-Debrid it
signs in with the method and the client identifier that documentation publishes for open-source
applications, or with the user's own API token; for TorBox with the user's own API key. No code
from either provider or from their SDKs is included. DebridOne is not affiliated with or endorsed
by Real-Debrid or TorBox, their names are used only to say which service an account belongs to,
and a subscription is a matter between the user and the provider.
