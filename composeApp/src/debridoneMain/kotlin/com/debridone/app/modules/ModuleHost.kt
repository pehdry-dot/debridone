package com.debridone.app.modules

import com.debridone.gaming.GamingModule
import com.debridone.manga.MangaModule
import com.debridone.module.EntertainmentModule
import com.debridone.module.ModuleId
import com.debridone.module.storage.ModuleStorage
import com.debridone.module.storage.SqliteBackedModule
import com.debridone.music.MusicModule
import com.debridone.platform.AppPaths
import com.debridone.platform.secrets.SecretStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Owns the three module engines that are not Nuvio. (Streaming is Nuvio itself and is hosted by the
 * shell as a composable; it keeps its own storage under `modules/streaming`.)
 *
 * This is the only place that holds more than one module. It never passes one module's objects to
 * another: each is constructed with its own [ModuleStorage] and started in its own coroutine, so a
 * module that fails to start is reported as failed while the others keep working.
 */
class ModuleHost(paths: AppPaths, secrets: SecretStore) {
    val music: MusicModule = MusicModule(ModuleStorage(ModuleId.MUSIC, paths, secrets))
    val gaming: GamingModule = GamingModule(ModuleStorage(ModuleId.GAMING, paths, secrets))
    val manga: MangaModule = MangaModule(ModuleStorage(ModuleId.MANGA, paths, secrets))

    private val engines: List<SqliteBackedModule> = listOf(music, gaming, manga)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile
    private var activeProfileId: Long? = null

    fun module(id: ModuleId): EntertainmentModule? = when (id) {
        ModuleId.MUSIC -> music
        ModuleId.GAMING -> gaming
        ModuleId.MANGA -> manga
        ModuleId.STREAMING -> null
    }

    /** Starts every engine independently; returns immediately. */
    fun start() {
        engines.forEach { engine ->
            scope.launch {
                engine.start()
                engine.onProfileChanged(activeProfileId)
            }
        }
    }

    fun onProfileChanged(profileId: Long?) {
        activeProfileId = profileId
        engines.forEach { engine ->
            scope.launch { runCatching { engine.onProfileChanged(profileId) } }
        }
    }

    /** Erases what a deleted profile owned, module by module. */
    suspend fun deleteProfileData(profileId: Long) {
        engines.forEach { engine -> runCatching { engine.deleteProfileData(profileId) } }
    }

    fun close() {
        engines.forEach { runCatching { it.close() } }
    }
}
