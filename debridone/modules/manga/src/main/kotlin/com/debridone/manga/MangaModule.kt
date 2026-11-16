package com.debridone.manga

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
 * Manga & Manhwa module. Its engine will follow Houdoku (https://github.com/xgi/houdoku, MIT) and
 * run unmodified Tiyo extensions (https://github.com/xgi/tiyo, MIT) in a bundled Node.js sidecar;
 * see THIRD_PARTY_NOTICES.md.
 *
 * Owns `modules/manga/manga.db`. Nothing in here may reference Streaming, Music or Games.
 */
class MangaModule(
    storage: ModuleStorage,
    clock: () -> Long = System::currentTimeMillis,
) : SqliteBackedModule(ModuleId.MANGA, storage, clock) {

    override val readiness: ModuleReadiness = ModuleReadiness.FoundationOnly(
        summary = "Manga storage, extension registry, metadata settings and appearance are in place. " +
            "The library, reader and Tiyo extension host are not built yet.",
        upcoming = listOf(
            "Tiyo extension host (install, update, diagnostics, removal)",
            "Series search and library with Manga and Manhwa tabs",
            "Reader: right-to-left, left-to-right, vertical, webtoon, single and double page",
            "Reading progress, bookmarks, history and offline chapters",
            "AniList, MangaDex and MyAnimeList metadata",
        ),
    )

    /** Houdoku/Tiyo extensions live here, separate from every other module's plugins. */
    override val pluginTable = "manga_extensions"
    override val metadataTable = "manga_metadata_providers"
    override val settingsTable = "manga_profile_settings"

    override val migrations = listOf(
        Migration(
            1, "tiyo extension registry, manga metadata providers, profile settings",
            PluginTableStore.createTableSql("manga_extensions"),
            MetadataProviderTableStore.createTableSql("manga_metadata_providers"),
            ProfileSettingsTableStore.createTableSql("manga_profile_settings"),
        ),
    )

    override val supportedPluginKinds = setOf(
        PluginKind.SOURCE,
        PluginKind.METADATA,
        PluginKind.UTILITY,
    )

    override val pluginImportHint =
        "Houdoku-compatible Tiyo extensions and manga metadata providers."

    override val defaultMetadataProviders = listOf(
        MetadataProvider("extension", "Extension metadata", "Details supplied by the source extension itself.", enabled = true, priority = 0, implemented = false),
        MetadataProvider("anilist", "AniList", "Titles, genres, tags and covers.", enabled = true, priority = 10, implemented = false),
        MetadataProvider("mangadex", "MangaDex", "Titles, tags and covers.", enabled = true, priority = 20, implemented = false),
        MetadataProvider("myanimelist", "MyAnimeList", "Uses the official API. Needs your own client ID.", enabled = false, priority = 30, implemented = false, requiresCredentials = true),
        MetadataProvider("manual", "Manual edits", "Details you set yourself always win.", enabled = true, priority = -10, implemented = false),
    )

    override val appearanceOptions: List<AppearanceOption> = listOf(
        StandardAppearanceOptions.accent(),
        StandardAppearanceOptions.cardSize("Cover size"),
        StandardAppearanceOptions.layout("Library layout"),
        StandardAppearanceOptions.density(),
        AppearanceOption.Choice(
            "manga_direction", "Manga reading direction",
            listOf(ChoiceValue("rtl", "Right to left"), ChoiceValue("ltr", "Left to right"), ChoiceValue("vertical", "Vertical")), "rtl",
        ),
        AppearanceOption.Choice(
            "manga_page_layout", "Manga page layout",
            listOf(ChoiceValue("single", "Single page"), ChoiceValue("double", "Double page")), "single",
        ),
        AppearanceOption.Choice(
            "manhwa_mode", "Manhwa reading mode",
            listOf(ChoiceValue("webtoon", "Continuous webtoon scroll"), ChoiceValue("vertical", "Vertical, page by page")), "webtoon",
        ),
        AppearanceOption.Choice(
            "reader_background", "Reader background",
            listOf(ChoiceValue("black", "Black"), ChoiceValue("dark", "Dark grey"), ChoiceValue("sepia", "Sepia"), ChoiceValue("white", "White")), "black",
        ),
        AppearanceOption.Choice(
            "reader_width", "Reader width",
            listOf(ChoiceValue("fit", "Fit window"), ChoiceValue("comfortable", "Comfortable"), ChoiceValue("narrow", "Narrow")), "fit",
        ),
        AppearanceOption.Choice(
            "page_transition", "Page transition",
            listOf(ChoiceValue("none", "None"), ChoiceValue("slide", "Slide"), ChoiceValue("fade", "Fade")), "none",
        ),
        AppearanceOption.Choice(
            "chapter_display", "Chapter display",
            listOf(ChoiceValue("list", "List"), ChoiceValue("grid", "Grid")), "list",
        ),
    )
}
