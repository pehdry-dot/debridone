package com.debridone.platform

import com.debridone.platform.io.AtomicFiles
import com.debridone.platform.secrets.FileSecretStore
import com.debridone.platform.secrets.MacKeychainSecretStore
import com.debridone.platform.secrets.ScopedSecretStore
import com.debridone.platform.secrets.SecretRef
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.UUID
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Assume.assumeTrue

class PlatformTest {
    private lateinit var dir: Path

    @BeforeTest
    fun setUp() {
        dir = Files.createTempDirectory("debridone-platform-test")
    }

    @AfterTest
    fun tearDown() {
        dir.toFile().deleteRecursively()
    }

    @Test
    fun macPathsUseStandardLibraryLocations() {
        val paths = AppPaths.resolveSystem(osName = "Mac OS X", userHome = "/Users/sam", property = { null }, env = { null })
        assertEquals(Paths.get("/Users/sam/Library/Application Support/DebridOne"), paths.dataRoot)
        assertEquals(Paths.get("/Users/sam/Library/Caches/DebridOne"), paths.cacheRoot)
        assertEquals(Paths.get("/Users/sam/Library/Application Support/DebridOne/accounts"), paths.accountsDir)
        assertEquals(Paths.get("/Users/sam/Library/Application Support/DebridOne/modules/music"), paths.moduleDataDir("music"))
    }

    @Test
    fun dataDirOverrideWins() {
        val paths = AppPaths.resolveSystem(
            osName = "Mac OS X",
            userHome = "/Users/sam",
            property = { if (it == AppPaths.DATA_DIR_PROPERTY) dir.toString() else null },
            env = { null },
        )
        assertEquals(dir.toAbsolutePath().normalize(), paths.dataRoot)
    }

    @Test
    fun moduleDirectoriesAreDistinctAndCannotEscape() {
        val paths = AppPaths(dir.resolve("data"), dir.resolve("cache"))
        val keys = listOf("streaming", "music", "gaming", "manga")
        assertEquals(keys.size, keys.map(paths::moduleDataDir).toSet().size)
        assertFailsWith<IllegalArgumentException> { paths.moduleDataDir("../accounts") }
        assertFailsWith<IllegalArgumentException> { paths.moduleDataDir("music/../gaming") }
        assertFailsWith<IllegalArgumentException> { paths.moduleDataDir("") }
    }

    @Test
    fun atomicWriteReplacesContentAndKeepsBackup() {
        val file = dir.resolve("settings.properties")
        AtomicFiles.writeBytes(file, "one".toByteArray())
        AtomicFiles.writeBytes(file, "two".toByteArray())
        assertEquals("two", file.readText())
        assertEquals("one", AtomicFiles.backupPathOf(file).readText())
        assertEquals(emptyList(), Files.list(dir).use { s -> s.filter { it.toString().endsWith(".tmp") }.toList() })
    }

    @Test
    fun failedWriteLeavesOriginalIntact() {
        val file = dir.resolve("settings.properties")
        AtomicFiles.writeBytes(file, "original".toByteArray())
        assertFailsWith<IllegalStateException> {
            AtomicFiles.write(file) { out ->
                out.write("partial".toByteArray())
                error("crash mid-write")
            }
        }
        assertEquals("original", file.readText())
        assertEquals(emptyList(), Files.list(dir).use { s -> s.filter { it.toString().endsWith(".tmp") }.toList() })
    }

    @Test
    fun readFallsBackToBackupWhenMainFileIsDamaged() {
        val file = dir.resolve("state.txt")
        AtomicFiles.writeBytes(file, "42".toByteArray())
        AtomicFiles.writeBytes(file, "43".toByteArray())
        file.writeText("not a number")
        val value = AtomicFiles.readWithBackup(file) { String(it).toInt() }
        assertEquals(42, value)
    }

    @Test
    fun fileSecretStoreRoundTripsAndEncryptsAtRest() {
        val secretsDir = dir.resolve("secrets")
        val store = FileSecretStore(secretsDir)
        val ref = SecretRef("module.music.profile.1001", "navidrome-token")
        assertNull(store.get(ref))
        store.put(ref, "s3cr3t-value")
        assertEquals("s3cr3t-value", store.get(ref))

        // A fresh instance (new process) can still read it.
        assertEquals("s3cr3t-value", FileSecretStore(secretsDir).get(ref))

        val raw = secretsDir.resolve("secrets.db.txt").readText()
        assertFalse(raw.contains("s3cr3t-value"), "secret must not be stored in clear text")
        assertFalse(raw.contains("navidrome-token"), "secret name must not be stored in clear text")

        store.put(ref, "rotated")
        assertEquals("rotated", store.get(ref))
        assertTrue(store.delete(ref))
        assertFalse(store.delete(ref))
        assertNull(store.get(ref))
    }

    @Test
    fun scopedSecretStoresCannotSeeEachOther() {
        val store = FileSecretStore(dir.resolve("secrets"))
        val music = ScopedSecretStore(store, "module.music")
        val gaming = ScopedSecretStore(store, "module.gaming")
        music.put("api-key", "music-key")
        assertNull(gaming.get("api-key"))
        gaming.put("api-key", "gaming-key")
        assertEquals("music-key", music.get("api-key"))
        assertEquals("gaming-key", gaming.get("api-key"))

        val profileA = music.child("profile.1001")
        val profileB = music.child("profile.1002")
        profileA.put("token", "a")
        assertNull(profileB.get("token"))
        assertFalse(gaming.delete("token"))
        assertTrue(music.delete("api-key"))
        assertEquals("gaming-key", gaming.get("api-key"))
    }

    @Test
    fun secretRefRejectsMalformedNamespaces() {
        assertFailsWith<IllegalArgumentException> { SecretRef("Module.Music", "x") }
        assertFailsWith<IllegalArgumentException> { SecretRef("module..music", "x") }
        assertFailsWith<IllegalArgumentException> { SecretRef("module.music", "") }
    }

    /** Runs only on macOS: verifies the real Keychain bindings end to end. */
    @Test
    fun macKeychainRoundTrip() {
        assumeTrue(System.getProperty("os.name").orEmpty().lowercase().contains("mac"))
        val store = MacKeychainSecretStore(servicePrefix = "com.debridone.desktop.test")
        val ref = SecretRef("selftest", "item-${UUID.randomUUID()}")
        try {
            assertNull(store.get(ref))
            store.put(ref, "first")
            assertEquals("first", store.get(ref))
            store.put(ref, "second-ünïcode")
            assertEquals("second-ünïcode", store.get(ref))
        } finally {
            store.delete(ref)
        }
        assertNull(store.get(ref))
        assertFalse(dir.resolve("secrets").exists())
    }
}
