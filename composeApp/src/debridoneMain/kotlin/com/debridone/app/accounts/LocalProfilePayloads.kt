package com.debridone.app.accounts

import com.debridone.accounts.AccountsDatabase
import com.debridone.accounts.Profile
import com.debridone.accounts.ProfileDraft
import com.nuvio.app.features.profiles.NuvioProfile
import com.nuvio.app.features.profiles.StoredProfilePayload
import java.time.Instant
import kotlinx.serialization.json.Json

/**
 * Translates between Nuvio's cached-profile payload and rows in `accounts.db`, so the database is
 * the single source of truth for on-device profiles while Nuvio's profile screens stay unchanged.
 *
 * Nuvio identifies a profile by an integer index and scopes all per-profile data by it. Here that
 * index is the profile's database id, which is unique across accounts and never reused.
 */
internal class LocalProfilePayloads(private val accounts: AccountsDatabase) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun load(): String? {
        val session = accounts.currentSession() ?: return null
        val profiles = accounts.listProfiles(session.accountId)
        val default = profiles.firstOrNull { it.isDefault }
        val active = default?.id ?: session.activeProfileId ?: profiles.firstOrNull()?.id
        val payload = StoredProfilePayload(
            userId = session.accountId,
            activeProfileIndex = active?.let(Math::toIntExact) ?: 1,
            // A default profile opens automatically, exactly like upstream's "remember last profile".
            hasEverSelectedProfile = default != null || session.hasEverSelectedProfile,
            rememberLastProfileEnabled = default != null || session.rememberLastProfile,
            profiles = profiles.map { it.toNuvioProfile() },
        )
        return json.encodeToString(StoredProfilePayload.serializer(), payload)
    }

    fun save(encoded: String) {
        val payload = runCatching { json.decodeFromString(StoredProfilePayload.serializer(), encoded) }.getOrNull() ?: return
        val session = accounts.currentSession() ?: return
        // Only the signed-in account may be written; a stale payload of another account is ignored.
        if (payload.userId != session.accountId) return

        // Upsert only. Nuvio's in-memory list can be transiently empty, so a missing profile is
        // never treated as a deletion here; deletions go through LocalAccountBackend.deleteProfile.
        accounts.saveProfiles(
            session.accountId,
            payload.profiles.map { profile ->
                ProfileDraft(
                    id = profile.profileIndex.toLong(),
                    name = profile.name,
                    avatarColorHex = profile.avatarColorHex,
                    avatarId = profile.avatarId,
                    avatarUrl = profile.avatarUrl,
                    backgroundId = profile.profileBackgroundId,
                    backgroundUrl = profile.profileBackgroundUrl,
                    usesPrimaryAddons = profile.usesPrimaryAddons,
                    usesPrimaryPlugins = profile.usesPrimaryPlugins,
                )
            },
        )

        val known = accounts.listProfiles(session.accountId)
        val activeId = payload.activeProfileIndex.toLong().takeIf { id -> known.any { it.id == id } }
        if (activeId != null && payload.hasEverSelectedProfile) {
            accounts.setActiveProfile(session.accountId, activeId)
        }
        // With a default profile set, the "remember" flag shown to Nuvio is synthetic (see load),
        // so it must not overwrite the user's real preference.
        if (known.none { it.isDefault }) {
            accounts.setRememberLastProfile(session.accountId, payload.rememberLastProfileEnabled)
        }
    }

    private fun Profile.toNuvioProfile(): NuvioProfile = NuvioProfile(
        id = id.toString(),
        userId = accountId,
        profileIndex = Math.toIntExact(id),
        name = name,
        avatarColorHex = avatarColorHex,
        avatarId = avatarId,
        avatarUrl = avatarUrl,
        profileBackgroundId = backgroundId,
        profileBackgroundUrl = backgroundUrl,
        usesPrimaryAddons = usesPrimaryAddons,
        usesPrimaryPlugins = usesPrimaryPlugins,
        pinEnabled = pinEnabled,
        pinLockedUntil = pinLockedUntilEpochMillis?.let { Instant.ofEpochMilli(it).toString() },
        createdAt = Instant.ofEpochMilli(createdAtEpochMillis).toString(),
        updatedAt = Instant.ofEpochMilli(updatedAtEpochMillis).toString(),
    )
}
