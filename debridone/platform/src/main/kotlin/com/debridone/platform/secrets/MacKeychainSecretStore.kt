package com.debridone.platform.secrets

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.PointerByReference

/**
 * Generic-password items in the user's login Keychain, via Security.framework.
 *
 * Items are stored with service `com.debridone.desktop.<namespace>` and account `<name>`, so they
 * are visible (and removable) in Keychain Access under the DebridOne name.
 *
 * NOTE: this class can only run on macOS. It is exercised by `MacKeychainSecretStoreTest`, which
 * is skipped on other systems, so it must be verified on a Mac / macOS CI runner.
 */
class MacKeychainSecretStore(
    private val servicePrefix: String = "com.debridone.desktop",
) : SecretStore {
    override val backendName: String = "macOS Keychain"
    override val isOsProtected: Boolean = true

    private val security: Security by lazy { Native.load("Security", Security::class.java) }
    private val coreFoundation: CoreFoundation by lazy { Native.load("CoreFoundation", CoreFoundation::class.java) }

    override fun get(ref: SecretRef): String? {
        val service = serviceBytes(ref)
        val account = ref.name.toByteArray(Charsets.UTF_8)
        val length = IntByReference()
        val data = PointerByReference()
        val status = security.SecKeychainFindGenericPassword(
            null, service.size, service, account.size, account, length, data, null,
        )
        if (status == ERR_SEC_ITEM_NOT_FOUND) return null
        check(status, "read")
        val pointer = data.value ?: return null
        return try {
            String(pointer.getByteArray(0, length.value), Charsets.UTF_8)
        } finally {
            security.SecKeychainItemFreeContent(null, pointer)
        }
    }

    override fun put(ref: SecretRef, value: String) {
        val service = serviceBytes(ref)
        val account = ref.name.toByteArray(Charsets.UTF_8)
        val secret = value.toByteArray(Charsets.UTF_8)

        val existing = PointerByReference()
        val findStatus = security.SecKeychainFindGenericPassword(
            null, service.size, service, account.size, account, null, null, existing,
        )
        when (findStatus) {
            ERR_SEC_SUCCESS -> {
                val item = existing.value
                try {
                    check(security.SecKeychainItemModifyAttributesAndData(item, null, secret.size, secret), "update")
                } finally {
                    if (item != null) coreFoundation.CFRelease(item)
                }
            }
            ERR_SEC_ITEM_NOT_FOUND -> check(
                security.SecKeychainAddGenericPassword(
                    null, service.size, service, account.size, account, secret.size, secret, null,
                ),
                "add",
            )
            else -> check(findStatus, "look up")
        }
    }

    override fun delete(ref: SecretRef): Boolean {
        val service = serviceBytes(ref)
        val account = ref.name.toByteArray(Charsets.UTF_8)
        val existing = PointerByReference()
        val findStatus = security.SecKeychainFindGenericPassword(
            null, service.size, service, account.size, account, null, null, existing,
        )
        if (findStatus == ERR_SEC_ITEM_NOT_FOUND) return false
        check(findStatus, "look up")
        val item = existing.value ?: return false
        try {
            check(security.SecKeychainItemDelete(item), "delete")
        } finally {
            coreFoundation.CFRelease(item)
        }
        return true
    }

    private fun serviceBytes(ref: SecretRef): ByteArray =
        "$servicePrefix.${ref.namespace}".toByteArray(Charsets.UTF_8)

    private fun check(status: Int, action: String) {
        if (status != ERR_SEC_SUCCESS) {
            throw SecretStoreException("Keychain could not $action the item (OSStatus $status)")
        }
    }

    @Suppress("FunctionName")
    private interface Security : Library {
        fun SecKeychainAddGenericPassword(
            keychain: Pointer?,
            serviceNameLength: Int,
            serviceName: ByteArray,
            accountNameLength: Int,
            accountName: ByteArray,
            passwordLength: Int,
            passwordData: ByteArray,
            itemRef: PointerByReference?,
        ): Int

        fun SecKeychainFindGenericPassword(
            keychainOrArray: Pointer?,
            serviceNameLength: Int,
            serviceName: ByteArray,
            accountNameLength: Int,
            accountName: ByteArray,
            passwordLength: IntByReference?,
            passwordData: PointerByReference?,
            itemRef: PointerByReference?,
        ): Int

        fun SecKeychainItemModifyAttributesAndData(
            itemRef: Pointer?,
            attrList: Pointer?,
            length: Int,
            data: ByteArray,
        ): Int

        fun SecKeychainItemDelete(itemRef: Pointer?): Int

        fun SecKeychainItemFreeContent(attrList: Pointer?, data: Pointer?): Int
    }

    @Suppress("FunctionName")
    private interface CoreFoundation : Library {
        fun CFRelease(cf: Pointer?)
    }

    private companion object {
        const val ERR_SEC_SUCCESS = 0
        const val ERR_SEC_ITEM_NOT_FOUND = -25300
    }
}
