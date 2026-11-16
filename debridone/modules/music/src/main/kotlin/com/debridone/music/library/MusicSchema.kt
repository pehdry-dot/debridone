package com.debridone.music.library

import com.debridone.platform.db.Migration

/**
 * Music's library tables. Everything a profile owns carries its `profile_id`: two profiles that add
 * the same folder get two independent sets of rows, playlists, favourites and edits.
 */
internal object MusicSchema {
    val library = Migration(
        2, "music library, artwork, playlists, history, playback state, sources, metadata cache",
        """
        CREATE TABLE music_folders (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            profile_id INTEGER NOT NULL,
            path TEXT NOT NULL,
            added_at INTEGER NOT NULL,
            last_scan_at INTEGER,
            UNIQUE (profile_id, path)
        )
        """,
        """
        CREATE TABLE music_artwork (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            profile_id INTEGER NOT NULL,
            hash TEXT NOT NULL,
            file TEXT NOT NULL,
            thumb TEXT NOT NULL,
            width INTEGER NOT NULL,
            height INTEGER NOT NULL,
            dominant_color INTEGER,
            origin TEXT NOT NULL,
            created_at INTEGER NOT NULL,
            UNIQUE (profile_id, hash)
        )
        """,
        """
        CREATE TABLE music_artists (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            profile_id INTEGER NOT NULL,
            name TEXT NOT NULL,
            name_key TEXT NOT NULL,
            artwork_id INTEGER,
            favorite_at INTEGER,
            musicbrainz_id TEXT,
            UNIQUE (profile_id, name_key)
        )
        """,
        """
        CREATE TABLE music_albums (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            profile_id INTEGER NOT NULL,
            title TEXT NOT NULL,
            title_key TEXT NOT NULL,
            artist_id INTEGER,
            artist_key TEXT NOT NULL DEFAULT '',
            year INTEGER,
            artwork_id INTEGER,
            custom_artwork INTEGER NOT NULL DEFAULT 0,
            favorite_at INTEGER,
            musicbrainz_release_id TEXT,
            added_at INTEGER NOT NULL,
            UNIQUE (profile_id, title_key, artist_key)
        )
        """,
        """
        CREATE TABLE music_tracks (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            profile_id INTEGER NOT NULL,
            source_id INTEGER NOT NULL DEFAULT 0,
            remote_id TEXT,
            folder_id INTEGER,
            path TEXT,
            file_size INTEGER NOT NULL DEFAULT 0,
            file_mtime INTEGER NOT NULL DEFAULT 0,
            fingerprint TEXT,
            missing INTEGER NOT NULL DEFAULT 0,
            format TEXT,
            codec TEXT,
            bitrate_kbps INTEGER,
            sample_rate INTEGER,
            channels INTEGER,
            bit_depth INTEGER,
            duration_ms INTEGER NOT NULL DEFAULT 0,
            title TEXT NOT NULL,
            title_key TEXT NOT NULL,
            artist TEXT,
            album TEXT,
            album_artist TEXT,
            genre TEXT,
            composer TEXT,
            year INTEGER,
            track_no INTEGER,
            track_total INTEGER,
            disc_no INTEGER,
            disc_total INTEGER,
            artist_id INTEGER,
            album_id INTEGER,
            artwork_id INTEGER,
            custom_artwork INTEGER NOT NULL DEFAULT 0,
            search_text TEXT NOT NULL DEFAULT '',
            original_tags TEXT NOT NULL DEFAULT '{}',
            locked_fields TEXT NOT NULL DEFAULT '',
            musicbrainz_recording_id TEXT,
            musicbrainz_release_id TEXT,
            offline_path TEXT,
            added_at INTEGER NOT NULL,
            updated_at INTEGER NOT NULL,
            play_count INTEGER NOT NULL DEFAULT 0,
            last_played_at INTEGER,
            favorite_at INTEGER
        )
        """,
        "CREATE INDEX music_albums_artist ON music_albums (profile_id, artist_id)",
        "CREATE UNIQUE INDEX music_tracks_local_path ON music_tracks (profile_id, path) WHERE source_id = 0",
        "CREATE UNIQUE INDEX music_tracks_remote ON music_tracks (profile_id, source_id, remote_id) WHERE source_id <> 0",
        "CREATE INDEX music_tracks_album ON music_tracks (profile_id, album_id, disc_no, track_no)",
        "CREATE INDEX music_tracks_artist ON music_tracks (profile_id, artist_id)",
        "CREATE INDEX music_tracks_title ON music_tracks (profile_id, title_key)",
        "CREATE INDEX music_tracks_fingerprint ON music_tracks (profile_id, fingerprint)",
        "CREATE INDEX music_tracks_folder ON music_tracks (folder_id)",
        """
        CREATE TABLE music_playlists (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            profile_id INTEGER NOT NULL,
            name TEXT NOT NULL,
            description TEXT,
            artwork_id INTEGER,
            created_at INTEGER NOT NULL,
            updated_at INTEGER NOT NULL
        )
        """,
        "CREATE INDEX music_playlists_profile ON music_playlists (profile_id, name)",
        """
        CREATE TABLE music_playlist_items (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            playlist_id INTEGER NOT NULL,
            track_id INTEGER NOT NULL,
            position INTEGER NOT NULL,
            added_at INTEGER NOT NULL
        )
        """,
        "CREATE INDEX music_playlist_items_order ON music_playlist_items (playlist_id, position)",
        "CREATE INDEX music_playlist_items_track ON music_playlist_items (track_id)",
        """
        CREATE TABLE music_history (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            profile_id INTEGER NOT NULL,
            track_id INTEGER NOT NULL,
            played_at INTEGER NOT NULL,
            listened_ms INTEGER NOT NULL,
            completed INTEGER NOT NULL
        )
        """,
        "CREATE INDEX music_history_recent ON music_history (profile_id, played_at)",
        "CREATE INDEX music_history_track ON music_history (track_id)",
        """
        CREATE TABLE music_playback_state (
            profile_id INTEGER PRIMARY KEY NOT NULL,
            queue TEXT NOT NULL,
            play_order TEXT NOT NULL,
            order_position INTEGER NOT NULL,
            position_ms INTEGER NOT NULL,
            shuffle INTEGER NOT NULL,
            repeat_mode TEXT NOT NULL,
            volume INTEGER NOT NULL,
            muted INTEGER NOT NULL,
            updated_at INTEGER NOT NULL
        )
        """,
        """
        CREATE TABLE music_sources (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            profile_id INTEGER NOT NULL,
            adapter TEXT NOT NULL,
            name TEXT NOT NULL,
            base_url TEXT,
            username TEXT,
            config TEXT NOT NULL DEFAULT '{}',
            enabled INTEGER NOT NULL DEFAULT 1,
            priority INTEGER NOT NULL DEFAULT 0,
            created_at INTEGER NOT NULL,
            updated_at INTEGER NOT NULL,
            last_sync_at INTEGER,
            last_test_at INTEGER,
            last_test_ok INTEGER,
            last_error TEXT
        )
        """,
        "CREATE INDEX music_sources_profile ON music_sources (profile_id, priority)",
        """
        CREATE TABLE music_lyrics (
            track_id INTEGER PRIMARY KEY NOT NULL,
            profile_id INTEGER NOT NULL,
            plain TEXT,
            synced TEXT,
            origin TEXT NOT NULL,
            fetched_at INTEGER NOT NULL
        )
        """,
        """
        CREATE TABLE music_metadata_cache (
            provider TEXT NOT NULL,
            cache_key TEXT NOT NULL,
            value TEXT NOT NULL,
            fetched_at INTEGER NOT NULL,
            PRIMARY KEY (provider, cache_key)
        )
        """,
    )

    /**
     * Online catalogues, plugin manifests, provider health, MusicBrainz identifiers.
     *
     * `hidden` marks a song that was only played from an online catalogue (Audius, a server search)
     * and that the user has not added to the library: it can be played, appears in the listening
     * history and nowhere else.
     */
    val catalogue = Migration(
        3, "music catalogue tracks, plugin manifests, provider health, MusicBrainz identifiers",
        "ALTER TABLE music_tracks ADD COLUMN hidden INTEGER NOT NULL DEFAULT 0",
        "CREATE INDEX music_tracks_hidden ON music_tracks (profile_id, hidden)",
        "ALTER TABLE music_albums ADD COLUMN musicbrainz_release_group_id TEXT",
        "ALTER TABLE music_sources ADD COLUMN plugin_id TEXT",
        """
        CREATE TABLE music_provider_health (
            provider TEXT PRIMARY KEY,
            ok INTEGER NOT NULL,
            message TEXT,
            latency_ms INTEGER,
            checked_at INTEGER NOT NULL
        )
        """,
        """
        CREATE TABLE music_plugin_manifests (
            plugin_id TEXT PRIMARY KEY,
            manifest TEXT NOT NULL,
            origin TEXT,
            installed_at INTEGER NOT NULL
        )
        """,
    )
}
