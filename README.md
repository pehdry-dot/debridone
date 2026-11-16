# DebridOne

A free macOS entertainment app with independent sections in one window:
**Streaming**, **Music** and **Manga**. Each section has its own plugins, metadata,
library, settings and storage. One account holds several profiles.

DebridOne is built on [Nuvio Desktop](https://github.com/NuvioMedia/NuvioDesktop) and is free
software under the GPL-3.0. There are no subscriptions, tiers or paywalls.

## Status

This is the foundation milestone.

| Section | State |
|---|---|
| Streaming | Working. It is Nuvio Desktop, running on DebridOne's on-device accounts. |
| Music | Working: local library, player, playlists, Discover with Audius and your Navidrome/OpenSubsonic or Jellyfin server, radio streams, importable plugins, MusicBrainz, Cover Art Archive and LRCLIB. See [debridone/docs/MUSIC.md](debridone/docs/MUSIC.md) and [MUSIC_PLUGINS.md](debridone/docs/MUSIC_PLUGINS.md). |
| Games | Not part of the app for now. Its module is kept dormant in the repository; saved data is left untouched. |
| Manga | In development: same foundation; no reader or extension host yet. |

Also in place: on-device accounts with multiple profiles that survive restarts, crash-safe storage
with automatic backups and recovery, a Plugins screen with one tab per section with isolated registries, per-section
appearance settings, and keyboard shortcuts.

The macOS app bundle has not been built or tested yet; so far the app has been run from source on
Linux. See [Known limitations](debridone/docs/LIMITATIONS.md) before relying on anything.

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
- [Architecture](debridone/docs/ARCHITECTURE.md)
- [Upstream projects and how to merge them](debridone/docs/UPSTREAM.md)
- [Known limitations](debridone/docs/LIMITATIONS.md)
- [Third-party notices](THIRD_PARTY_NOTICES.md)

## Licence

[GNU General Public License v3.0](./LICENSE). DebridOne is a modified version of Nuvio Desktop by
Nuvio Media; it is not affiliated with or endorsed by Nuvio Media.
