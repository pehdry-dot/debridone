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
- Use in DebridOne: design and behaviour reference for the Manga module. No Houdoku source code is
  included yet.

## Tiyo

- Source: https://github.com/xgi/tiyo (npm: `@tiyo/core`, `@tiyo/common`)
- Licence: MIT
- Copyright: xgi and the Tiyo contributors
- Use in DebridOne: the Manga module is designed to run the unmodified `@tiyo/core` package, which
  the user installs. It is not bundled.

### MIT License (Hydra Launcher, Houdoku, Tiyo)

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

## Eclipse Music

Eclipse Music is a feature reference for the Music module only. No Eclipse Music source code,
assets or protocol implementation is included.

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

## Online services used by Music

Only when asked, and each can be switched off in Plugins › Music:

| Service | Used for | Data |
|---|---|---|
| MusicBrainz (musicbrainz.org) | Song details when you look a song up | CC0 / CC BY-NC-SA for some derived data; see musicbrainz.org/doc/About/Data_License |
| Cover Art Archive (coverartarchive.org) | A cover for a song that has none | Images belong to their rights holders |
| LRCLIB (lrclib.net) | Lyrics for a song that has none | Community-contributed |

Navidrome, other Subsonic-compatible servers and Jellyfin are reached through their public APIs;
no code from those projects is included.
