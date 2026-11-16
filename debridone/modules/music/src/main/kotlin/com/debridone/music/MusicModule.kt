package com.debridone.music

import com.debridone.module.AppearanceOption
import com.debridone.module.ChoiceValue
import com.debridone.module.MetadataProvider
import com.debridone.module.ModuleId
import com.debridone.module.ModuleReadiness
import com.debridone.module.PluginKind
import com.debridone.module.StandardAppearanceOptions
import com.debridone.module.storage.MetadataProviderTableStore
import com.debridone.module.storage.ModuleStorage
import com.debridone.module.storage.PluginTableStore
import com.debridone.module.storage.ProfileSettingsTableStore
import com.debridone.module.storage.SqliteBackedModule
import com.debridone.platform.db.Migration

/**
 * Music module. Original DebridOne code: Eclipse Music is the feature reference only, none of its
 * source is used (it is not publicly available).
 *
 * Owns `modules/music/music.db`. Nothing in here may reference Streaming, Games or Manga.
 */
class MusicModule(
    storage: ModuleStorage,
    clock: () -> Long = System::currentTimeMillis,
) : SqliteBackedModule(ModuleId.MUSIC, storage, clock) {

    override val readiness: ModuleReadiness = ModuleReadiness.FoundationOnly(
        summary = "Music storage, plugin registry, metadata settings and appearance are in place. " +
            "The player and library are not built yet.",
        upcoming = listOf(
            "Local folder import with embedded tag reading",
            "Playback with queue, shuffle, repeat, gapless and media keys",
            "Playlists, favourites, history and lyrics",
            "Navidrome/Subsonic and Jellyfin libraries",
            "MusicBrainz and Cover Art Archive metadata",
            "Music plugins installed by manifest URL",
        ),
    )

    override val pluginTable = "music_plugins"
    override val metadataTable = "music_metadata_providers"
    override val settingsTable = "music_profile_settings"

    override val migrations = listOf(
        Migration(
            1, "music plugin registry, metadata providers, profile settings",
            PluginTableStore.createTableSql("music_plugins"),
            MetadataProviderTableStore.createTableSql("music_metadata_providers"),
            ProfileSettingsTableStore.createTableSql("music_profile_settings"),
        ),
    )

    override val supportedPluginKinds = setOf(
        PluginKind.SOURCE,
        PluginKind.METADATA,
        PluginKind.TEXT_TRACKS,
        PluginKind.LIBRARY_INTEGRATION,
        PluginKind.UTILITY,
    )

    override val pluginImportHint =
        "Music sources, library integrations (Navidrome/Subsonic, Jellyfin), metadata and lyrics providers."

    override val defaultMetadataProviders = listOf(
        MetadataProvider("embedded-tags", "Embedded tags", "Title, artist, album and artwork stored inside your music files.", enabled = true, priority = 0, implemented = false),
        MetadataProvider("musicbrainz", "MusicBrainz", "Open music encyclopedia: releases, artists, track listings.", enabled = true, priority = 10, implemented = false),
        MetadataProvider("cover-art-archive", "Cover Art Archive", "Album artwork linked to MusicBrainz releases.", enabled = true, priority = 20, implemented = false),
        MetadataProvider("lrclib", "LRCLIB", "Community lyrics, including time-synced lyrics.", enabled = true, priority = 30, implemented = false),
    )

    override val appearanceOptions: List<AppearanceOption> = listOf(
        StandardAppearanceOptions.accent(),
        StandardAppearanceOptions.cardSize("Album grid size"),
        StandardAppearanceOptions.density(),
        AppearanceOption.Choice(
            "playlist_presentation", "Playlist presentation",
            listOf(ChoiceValue("grid", "Cover grid"), ChoiceValue("list", "Track list")), "grid",
        ),
        AppearanceOption.Choice(
            "player_layout", "Player layout",
            listOf(ChoiceValue("compact", "Compact bar"), ChoiceValue("expanded", "Expanded with artwork")), "expanded",
        ),
        AppearanceOption.Choice(
            "artwork_size", "Now-playing artwork size",
            listOf(ChoiceValue("small", "Small"), ChoiceValue("medium", "Medium"), ChoiceValue("large", "Large")), "large",
        ),
        AppearanceOption.Toggle("dynamic_album_colours", "Dynamic album colours", true, "Tint the player from the current album artwork."),
        AppearanceOption.Choice(
            "lyrics_style", "Lyrics appearance",
            listOf(ChoiceValue("standard", "Standard"), ChoiceValue("large", "Large, centred"), ChoiceValue("minimal", "Minimal")), "standard",
        ),
    )
}
