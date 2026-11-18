# DebridOne

A free macOS entertainment app with independent sections in one window:
**Streaming**, **Music** and **Manga**. Each section has its own plugins, metadata,
library, settings and storage. One account holds several profiles.

DebridOne is built on [Nuvio Desktop](https://github.com/NuvioMedia/NuvioDesktop) and is free
software under the GPL-3.0. There are no subscriptions, tiers or paywalls.

## Status

| Section | State |
|---|---|
| Streaming | Working. It is Nuvio Desktop, running on DebridOne's on-device accounts. |
| Music | Working: local library, player, playlists, Discover with Audius and your Navidrome/OpenSubsonic or Jellyfin server, radio streams, importable plugins, addons in the Eclipse Music / BeatBoss manifest format, the audio in your own Real-Debrid cloud (untested against the real service), MusicBrainz, Cover Art Archive and LRCLIB. See [debridone/docs/MUSIC.md](debridone/docs/MUSIC.md), [MUSIC_PLUGINS.md](debridone/docs/MUSIC_PLUGINS.md) and [MUSIC_ADDONS.md](debridone/docs/MUSIC_ADDONS.md). |
| Games | Not part of the app for now. Its module is kept dormant in the repository; saved data is left untouched. |
| Manga | Working: a reader for manga and manhwa (paged and continuous webtoon modes), search and discovery through Houdoku's unmodified Tiyo extensions, local CBZ/ZIP/folders, library, downloads, AniList / MangaDex / MyAnimeList metadata. See [debridone/docs/MANGA.md](debridone/docs/MANGA.md) and [MANGA_PLUGINS.md](debridone/docs/MANGA_PLUGINS.md). |

Also in place: on-device accounts with multiple profiles that survive restarts, crash-safe storage
with automatic backups and recovery, a Plugins screen with one tab per section with isolated registries, per-section
appearance settings, and keyboard shortcuts.

Development builds for Apple Silicon and Intel Macs are made by GitHub Actions; they are unsigned
and not notarized. What has and has not been verified is in
[Known limitations](debridone/docs/LIMITATIONS.md) — read it before relying on anything.

DebridOne ships with no addons, sources or extensions. What you install is your choice.

## Run it

```bash
./scripts/debridone/dev.sh
```

Build an unsigned development DMG on a Mac:

```bash
./scripts/debridone/build-macos-dev-dmg.sh
```

Run the tests:

```bash
./scripts/debridone/test.sh
```

## Documentation

- [Installing on a Mac](debridone/docs/INSTALL.md)
- [Building](debridone/docs/BUILD.md)
- [Testing](debridone/docs/TESTING.md)
- [Music](debridone/docs/MUSIC.md), [Music plugins](debridone/docs/MUSIC_PLUGINS.md) and [Music addons (Eclipse Music / BeatBoss format)](debridone/docs/MUSIC_ADDONS.md), [Real-Debrid in Music](debridone/docs/MUSIC_REAL_DEBRID.md)
- [Manga](debridone/docs/MANGA.md) and [Manga extensions (Houdoku / Tiyo compatibility)](debridone/docs/MANGA_PLUGINS.md)
- [Architecture](debridone/docs/ARCHITECTURE.md)
- [Upstream projects and how to merge them](debridone/docs/UPSTREAM.md)
- [Known limitations](debridone/docs/LIMITATIONS.md)
- [Third-party notices](THIRD_PARTY_NOTICES.md)

## Licence

[GNU General Public License v3.0](./LICENSE). DebridOne is a modified version of Nuvio Desktop by
Nuvio Media; it is not affiliated with or endorsed by Nuvio Media.
