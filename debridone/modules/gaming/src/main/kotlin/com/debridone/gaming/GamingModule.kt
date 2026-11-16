package com.debridone.gaming

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
 * Games module. Its engine will be a Kotlin port of selected Hydra Launcher functionality
 * (https://github.com/hydralauncher/hydra, MIT; see THIRD_PARTY_NOTICES.md). Hydra's Electron main
 * process, native addon and hosted API cannot be embedded, so behaviour is ported, not wrapped.
 *
 * Owns `modules/gaming/gaming.db`. Nothing in here may reference Streaming, Music or Manga.
 */
class GamingModule(
    storage: ModuleStorage,
    clock: () -> Long = System::currentTimeMillis,
) : SqliteBackedModule(ModuleId.GAMING, storage, clock) {

    override val readiness: ModuleReadiness = ModuleReadiness.FoundationOnly(
        summary = "Games storage, source registry, metadata settings and appearance are in place. " +
            "The library, launcher and download manager are not built yet.",
        upcoming = listOf(
            "Library with macOS app bundle and Steam install detection",
            "Launching and playtime tracking",
            "Steam store metadata, optional IGDB with your own credentials",
            "Compatibility info for Windows-only titles (never presented as launchable)",
            "Hydra-format source import with validation and priorities",
            "Download queue for authorised downloads",
        ),
    )

    /** Hydra-compatible source configurations live here, separate from every other module's plugins. */
    override val pluginTable = "hydra_sources"
    override val metadataTable = "game_metadata_providers"
    override val settingsTable = "gaming_profile_settings"

    override val migrations = listOf(
        Migration(
            1, "hydra source registry, game metadata providers, profile settings",
            PluginTableStore.createTableSql("hydra_sources"),
            MetadataProviderTableStore.createTableSql("game_metadata_providers"),
            ProfileSettingsTableStore.createTableSql("gaming_profile_settings"),
        ),
    )

    override val supportedPluginKinds = setOf(
        PluginKind.SOURCE,
        PluginKind.METADATA,
        PluginKind.LIBRARY_INTEGRATION,
        PluginKind.UTILITY,
    )

    override val pluginImportHint =
        "Hydra-compatible source configurations, game metadata providers and library integrations."

    override val defaultMetadataProviders = listOf(
        MetadataProvider("steam-store", "Steam Store", "Artwork, descriptions, developers and supported systems from Steam's public store data.", enabled = true, priority = 0, implemented = false),
        MetadataProvider("igdb", "IGDB", "Game database by Twitch. Needs your own API credentials.", enabled = false, priority = 10, implemented = false, requiresCredentials = true),
        MetadataProvider("manual", "Manual edits", "Artwork, tags and details you set yourself always win.", enabled = true, priority = -10, implemented = false),
    )

    override val appearanceOptions: List<AppearanceOption> = listOf(
        StandardAppearanceOptions.accent(),
        StandardAppearanceOptions.cardSize("Game card size"),
        StandardAppearanceOptions.layout("Library view"),
        StandardAppearanceOptions.density(),
        AppearanceOption.Choice(
            "library_order", "Library organisation",
            listOf(ChoiceValue("title", "Title"), ChoiceValue("recent", "Recently played"), ChoiceValue("playtime", "Most played"), ChoiceValue("added", "Recently added")),
            "recent",
        ),
        AppearanceOption.Choice(
            "installed_filter", "Default library filter",
            listOf(ChoiceValue("all", "All games"), ChoiceValue("installed", "Installed only")), "all",
        ),
        AppearanceOption.Choice(
            "detail_layout", "Detail page layout",
            listOf(ChoiceValue("hero", "Large artwork header"), ChoiceValue("compact", "Compact")), "hero",
        ),
        AppearanceOption.Toggle("background_artwork", "Background artwork", true, "Show the game's artwork behind its detail page."),
    )
}
