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

## Houdoku

- Source: https://github.com/xgi/houdoku
- Licence: MIT
- Copyright: xgi and the Houdoku contributors
- Use in DebridOne: reference for the Manga module (how extensions are installed and loaded, the
  reader's behaviour, how chapter and volume numbers are read from file names). No Houdoku source
  file is included; Manga's code is DebridOne's own.

## Tiyo and the legacy Houdoku extensions

- Source: https://github.com/xgi/tiyo (npm: `@tiyo/core`, `@tiyo/common`) and
  https://github.com/xgi/houdoku-extensions (npm: `@houdoku/extension-*`, `houdoku-extension-lib`)
- Licence: MIT
- Copyright: xgi and the Tiyo / Houdoku contributors
- Use in DebridOne: Manga runs these packages unmodified. They are **not bundled**: the user
  installs them from the npm registry, and each installed package keeps its own licence files in
  DebridOne's Manga plugin folder. A list of the archives that make up `@tiyo/core` 1.0.3 (names,
  versions, download addresses and checksums — no code) is included in
  `debridone/modules/manga/src/main/resources/manga/plans/`.

## Aki Plugin Manager

- Source: https://github.com/xgi/aki-plugin-manager (npm: `aki-plugin-manager` 1.3.3)
- Licence: MIT
- Copyright: xgi
- Use in DebridOne: bundled, unmodified, inside Manga's extension host to list, load and unload
  extension packages exactly as Houdoku does.

### MIT License (Hydra Launcher, Houdoku, Tiyo, Aki Plugin Manager)

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

## Bundled with Manga's extension host

The macOS app contains, under `Contents/app/resources/manga-host/`:

| Component | Licence | Use |
|---|---|---|
| Node.js 22.23.4, official build from nodejs.org, unmodified | MIT, with the licences of the components it includes (V8, libuv, OpenSSL, ICU and others) — the complete text ships next to it as `NODE-LICENSE.txt` | Runs extension code in a separate process |
| `aki-plugin-manager` 1.3.3 | MIT | Loads extension packages (see above) |
| `semver` 7.7.2 | ISC | Resolving a package's dependency versions before download |
| Dependencies of the two: `tar` 6.2.1, `minipass` 3.3.6 and 5.0.0, `fs-minipass` 2.1.0, `chownr` 2.0.0, `yallist` 4.0.0, `glob` 7.2.3, `minimatch` 3.1.5, `inflight` 1.0.6, `once` 1.4.0, `wrappy` 1.0.2, `inherits` 2.0.4, `fs.realpath` 1.0.0, `rimraf` 3.0.2 | ISC | |
| `minizlib` 2.1.2, `mkdirp` 1.0.4, `balanced-match` 1.0.2, `brace-expansion` 1.1.22, `concat-map` 0.0.1, `path-is-absolute` 1.0.1 | MIT | |

Each of these packages is included with its own licence file (`manga-host/host/node_modules/<name>/`).
Their exact versions are fixed by `debridone/modules/manga/host/package-lock.json`.

## Online services used by Manga

Manga contacts these only when you use the feature, and sends no account or device identifier of
DebridOne's:

| Service | Used for | Terms |
|---|---|---|
| npm registry (`registry.npmjs.org`) | Listing and downloading extension packages you choose to install | npm's terms of use |
| AniList (`graphql.anilist.co`) | The trending rows on Manga's Home, looking up details you ask for, and — only with an account you connect — updating your reading list | AniList API terms |
| MangaDex (`api.mangadex.org`) | Looking up details you ask for | MangaDex API terms; covers are hot-linked as its API intends |
| MyAnimeList (`api.myanimelist.net`) | Looking up details and updating your list, only with your own API client ID and sign-in | MyAnimeList API licence |
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
