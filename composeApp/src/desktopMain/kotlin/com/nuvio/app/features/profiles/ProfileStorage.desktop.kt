package com.nuvio.app.features.profiles

import com.debridone.app.DebridOneRuntime
import com.nuvio.app.core.auth.LocalAccounts
import com.nuvio.app.core.storage.DesktopStorage

internal actual object ProfileStorage {
    private val store = DesktopStorage.store("nuvio_profiles")

    // DebridOne: on-device accounts keep their profiles in accounts.db (transactional, backed up).
    // The properties store is only used when a sync server is configured (upstream behaviour).
    actual fun loadPayload(): String? =
        if (LocalAccounts.isActive) DebridOneRuntime.loadProfilePayload() else store.getString("profiles")

    actual fun savePayload(payload: String) {
        if (LocalAccounts.isActive) {
            DebridOneRuntime.saveProfilePayload(payload)
        } else {
            store.putString("profiles", payload)
        }
    }
}
