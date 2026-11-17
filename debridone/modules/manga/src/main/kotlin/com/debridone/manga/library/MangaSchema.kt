package com.debridone.manga.library

import com.debridone.platform.db.Migration

/**
 * Manga's library tables. Everything a profile owns carries its `profile_id`: two profiles that
 * open the same series get two independent rows, with their own chapters, progress, bookmarks,
 * collections and edits. Extension packages themselves (files on disk) are shared by the app.
 */
internal object MangaSchema {
    val library = Migration(
        2, "manga library, chapters and progress, bookmarks, history, collections, sources, metadata links",
        """
        CREATE TABLE manga_series (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            profile_id INTEGER NOT NULL,
            origin TEXT NOT NULL,
            extension_id TEXT NOT NULL,
            source_id TEXT NOT NULL,
            source_name TEXT NOT NULL DEFAULT '',
            title TEXT NOT NULL,
            title_key TEXT NOT NULL,
            alt_titles TEXT NOT NULL DEFAULT '[]',
            description TEXT NOT NULL DEFAULT '',
            authors TEXT NOT NULL DEFAULT '[]',
            artists TEXT NOT NULL DEFAULT '[]',
            tags TEXT NOT NULL DEFAULT '[]',
            custom_tags TEXT NOT NULL DEFAULT '[]',
            status TEXT,
            original_language TEXT,
            media_type TEXT NOT NULL DEFAULT 'manga',
            cover_url TEXT NOT NULL DEFAULT '',
            cover_file TEXT,
            banner_url TEXT,
            locked_fields TEXT NOT NULL DEFAULT '',
            source_raw TEXT,
            in_library INTEGER NOT NULL DEFAULT 0,
            library_status TEXT,
            favorite INTEGER NOT NULL DEFAULT 0,
            added_at INTEGER,
            created_at INTEGER NOT NULL,
            updated_at INTEGER NOT NULL,
            chapters_refreshed_at INTEGER,
            last_read_at INTEGER,
            last_chapter_id INTEGER,
            reader_overrides TEXT,
            UNIQUE (profile_id, extension_id, source_id)
        )
        """,
        "CREATE INDEX manga_series_library ON manga_series (profile_id, in_library)",
        "CREATE INDEX manga_series_recent ON manga_series (profile_id, last_read_at)",
        """
        CREATE TABLE manga_chapters (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            series_id INTEGER NOT NULL REFERENCES manga_series (id) ON DELETE CASCADE,
            source_id TEXT NOT NULL,
            title TEXT NOT NULL DEFAULT '',
            chapter_number TEXT NOT NULL DEFAULT '',
            chapter_sort REAL,
            volume_number TEXT NOT NULL DEFAULT '',
            language TEXT,
            group_name TEXT NOT NULL DEFAULT '',
            released_at INTEGER NOT NULL DEFAULT 0,
            position INTEGER NOT NULL DEFAULT 0,
            page_count INTEGER NOT NULL DEFAULT 0,
            last_page INTEGER NOT NULL DEFAULT 0,
            scroll_offset REAL NOT NULL DEFAULT 0,
            read INTEGER NOT NULL DEFAULT 0,
            read_at INTEGER,
            progress_at INTEGER,
            first_seen_at INTEGER NOT NULL,
            download_state TEXT,
            download_dir TEXT,
            download_pages INTEGER NOT NULL DEFAULT 0,
            download_error TEXT,
            download_queued_at INTEGER,
            UNIQUE (series_id, source_id)
        )
        """,
        "CREATE INDEX manga_chapters_series ON manga_chapters (series_id)",
        "CREATE INDEX manga_chapters_downloads ON manga_chapters (download_state)",
        """
        CREATE TABLE manga_bookmarks (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            profile_id INTEGER NOT NULL,
            series_id INTEGER NOT NULL REFERENCES manga_series (id) ON DELETE CASCADE,
            chapter_id INTEGER NOT NULL REFERENCES manga_chapters (id) ON DELETE CASCADE,
            page INTEGER NOT NULL,
            note TEXT NOT NULL DEFAULT '',
            created_at INTEGER NOT NULL,
            UNIQUE (chapter_id, page)
        )
        """,
        """
        CREATE TABLE manga_history (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            profile_id INTEGER NOT NULL,
            series_id INTEGER NOT NULL REFERENCES manga_series (id) ON DELETE CASCADE,
            chapter_id INTEGER NOT NULL REFERENCES manga_chapters (id) ON DELETE CASCADE,
            page INTEGER NOT NULL DEFAULT 0,
            read_at INTEGER NOT NULL
        )
        """,
        "CREATE INDEX manga_history_recent ON manga_history (profile_id, read_at)",
        """
        CREATE TABLE manga_collections (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            profile_id INTEGER NOT NULL,
            name TEXT NOT NULL,
            created_at INTEGER NOT NULL,
            UNIQUE (profile_id, name)
        )
        """,
        """
        CREATE TABLE manga_collection_items (
            collection_id INTEGER NOT NULL REFERENCES manga_collections (id) ON DELETE CASCADE,
            series_id INTEGER NOT NULL REFERENCES manga_series (id) ON DELETE CASCADE,
            added_at INTEGER NOT NULL,
            PRIMARY KEY (collection_id, series_id)
        )
        """,
        """
        CREATE TABLE manga_source_prefs (
            profile_id INTEGER NOT NULL,
            extension_id TEXT NOT NULL,
            enabled INTEGER NOT NULL DEFAULT 1,
            preferred_rank INTEGER,
            PRIMARY KEY (profile_id, extension_id)
        )
        """,
        """
        CREATE TABLE manga_source_health (
            extension_id TEXT PRIMARY KEY,
            tested_at INTEGER NOT NULL,
            ok INTEGER NOT NULL,
            steps TEXT NOT NULL DEFAULT '[]',
            message TEXT
        )
        """,
        """
        CREATE TABLE manga_metadata_links (
            series_id INTEGER NOT NULL REFERENCES manga_series (id) ON DELETE CASCADE,
            provider TEXT NOT NULL,
            remote_id TEXT NOT NULL,
            url TEXT,
            linked_at INTEGER NOT NULL,
            tracked INTEGER NOT NULL DEFAULT 0,
            PRIMARY KEY (series_id, provider)
        )
        """,
    )
}
