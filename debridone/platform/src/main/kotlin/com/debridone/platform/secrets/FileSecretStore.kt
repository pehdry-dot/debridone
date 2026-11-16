package com.debridone.platform.secrets

import com.debridone.platform.io.AtomicFiles
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Fallback secret store for systems without the macOS Keychain (development on Linux/Windows).
 *
 * Values are AES-256-GCM encrypted in `secrets.db.txt` with a random key kept in `secrets.key`,
 * both readable only by the current user. Because the key sits next to the data this protects
 * against casual disclosure (backups, screen sharing, grep), not against someone who can read the
 * user's files. Release builds on macOS never use this class.
 */
class FileSecretStore(private val directory: Path) : SecretStore {
    override val backendName: String = "Encrypted file (development fallback)"
    override val isOsProtected: Boolean = false

    private val lock = Any()
    private val keyFile: Path get() = directory.resolve("secrets.key")
    private val dataFile: Path get() = directory.resolve("secrets.db.txt")
    private val random = SecureRandom()

    override fun get(ref: SecretRef): String? = synchronized(lock) {
        val encoded = load()[entryKey(ref)] ?: return null
        decrypt(encoded)
    }

    override fun put(ref: SecretRef, value: String) = synchronized(lock) {
        val entries = load()
        entries[entryKey(ref)] = encrypt(value)
        save(entries)
    }

    override fun delete(ref: SecretRef): Boolean = synchronized(lock) {
        val entries = load()
        val removed = entries.remove(entryKey(ref)) != null
        if (removed) save(entries)
        removed
    }

    private fun entryKey(ref: SecretRef): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString("${ref.namespace}\u0000${ref.name}".toByteArray(Charsets.UTF_8))

    private fun load(): MutableMap<String, String> {
        val parsed = AtomicFiles.readWithBackup(dataFile) { bytes ->
            val map = LinkedHashMap<String, String>()
            String(bytes, Charsets.UTF_8).lineSequence().forEach { line ->
                if (line.isBlank()) return@forEach
                val separator = line.indexOf('=')
                require(separator > 0) { "Malformed secrets file" }
                map[line.substring(0, separator)] = line.substring(separator + 1)
            }
            map
        }
        return parsed ?: LinkedHashMap()
    }

    private fun save(entries: Map<String, String>) {
        Files.createDirectories(directory)
        val body = entries.entries.joinToString(separator = "\n", postfix = "\n") { "${it.key}=${it.value}" }
        AtomicFiles.writeBytes(dataFile, body.toByteArray(Charsets.UTF_8))
        restrictToOwner(dataFile)
        restrictToOwner(AtomicFiles.backupPathOf(dataFile))
    }

    private fun key(): SecretKeySpec {
        Files.createDirectories(directory)
        val existing = if (Files.isRegularFile(keyFile)) Files.readAllBytes(keyFile) else null
        val bytes = if (existing != null && existing.size == KEY_BYTES) {
            existing
        } else {
            if (existing != null) {
                throw SecretStoreException("Secret key file is damaged: $keyFile")
            }
            ByteArray(KEY_BYTES).also { fresh ->
                random.nextBytes(fresh)
                AtomicFiles.writeBytes(keyFile, fresh, keepBackup = false)
                restrictToOwner(keyFile)
            }
        }
        return SecretKeySpec(bytes, "AES")
    }

    private fun encrypt(value: String): String {
        val iv = ByteArray(IV_BYTES).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, iv))
        val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        val packed = ByteBuffer.allocate(iv.size + encrypted.size).put(iv).put(encrypted).array()
        return Base64.getEncoder().encodeToString(packed)
    }

    private fun decrypt(encoded: String): String {
        try {
            val packed = Base64.getDecoder().decode(encoded)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, packed, 0, IV_BYTES))
            return String(cipher.doFinal(packed, IV_BYTES, packed.size - IV_BYTES), Charsets.UTF_8)
        } catch (e: SecretStoreException) {
            throw e
        } catch (e: Exception) {
            throw SecretStoreException("Stored secret could not be decrypted", e)
        }
    }

    private fun restrictToOwner(file: Path) {
        if (!Files.exists(file)) return
        runCatching {
            Files.setPosixFilePermissions(
                file,
                setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
            )
        }
    }

    private companion object {
        const val KEY_BYTES = 32
        const val IV_BYTES = 12
        const val TAG_BITS = 128
    }
}
