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
1. Cmd+1…Cmd+4 switch Streaming, Music, Games, Manga. Cmd+, opens Settings. Cmd+F in Streaming opens Streaming's search.
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
4. Open Streaming, Games or Settings while it plays: the music continues and the mini-player stays.
5. Start a video in Streaming: the music pauses. The media keys now control the video.
6. Plug in or switch headphones or speakers while a song plays: the sound follows.
7. Quit with Cmd+Q and reopen: library, playlists, favourites and the paused queue are back.
8. Switch to another profile: the music stops and that profile has its own (or an empty) library.
9. Plugins › Music: add your Navidrome/Subsonic or Jellyfin server; its songs appear and play.

Plugins isolation
1. Plugins › Music shows Music's sources; Games and Manga each show their own (empty) list and their own metadata providers.
2. Disable a Streaming addon: nothing changes in the other three tabs.

Storage
1. Settings › Storage & backups shows the folders. "Back up accounts now" writes a snapshot.

Not testable yet (their engines are not built): game launching, manga reading, installing
Games/Manga plugins.
