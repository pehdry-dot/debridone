package com.debridone.manga.suwayomi

import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The server started from the folders a packaged DebridOne has: `resources/suwayomi/jre` and
 * `resources/suwayomi/server/Suwayomi-Server.jar`. Where the jar lies decides whether Suwayomi
 * can start at all (the first packaged build had the jar beside `jre`, and the server died while
 * starting, on every Mac); this builds that layout on any machine, so that it is not found out
 * on a Mac again.
 */
class PackagedLayoutTest {
    /** A copy of [source] at [target]: hard links where the disk allows, plain copies otherwise. */
    private fun mirror(source: Path, target: Path) {
        Files.walk(source).use { stream ->
            stream.forEach { path ->
                val to = target.resolve(source.relativize(path).toString())
                when {
                    Files.isSymbolicLink(path) -> Files.createSymbolicLink(to, Files.readSymbolicLink(path))
                    Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) -> Files.createDirectories(to)
                    else -> runCatching { Files.createLink(to, path) }.getOrElse { Files.copy(path, to, StandardCopyOption.COPY_ATTRIBUTES) }
                }
            }
        }
    }

    @Test
    fun theServerStartsFromTheFoldersOfAPackagedApp() {
        val runtime = SuwayomiTestRuntime.runtimeOrSkip()
        val root = Files.createTempDirectory("debridone-packaged")
        try {
            val resources = root.resolve("DebridOne.app/Contents/app/resources")
            val suwayomi = Files.createDirectories(resources.resolve("suwayomi"))
            mirror(runtime.java.parent.parent, suwayomi.resolve("jre"))
            Files.createDirectories(suwayomi.resolve("server"))
            mirror(runtime.jar, suwayomi.resolve("server/Suwayomi-Server.jar"))

            val bundled = SuwayomiRuntimeLocator.bundled(resources)
            assertEquals(suwayomi.resolve("jre/bin/java"), bundled.java)
            assertEquals(suwayomi.resolve("server/Suwayomi-Server.jar"), bundled.jar)
            val manager = SuwayomiServerManager(root.resolve("data"), { bundled })
            try {
                val endpoint = manager.start(1)
                assertIs<ServerState.Ready>(manager.state.value)
                assertTrue(SuwayomiApi(SuwayomiTransport(endpoint)).about().version.startsWith("v2."))
            } finally {
                manager.close()
            }

            // The layout that does not work is refused before anything is started, with the reason.
            mirror(runtime.jar, suwayomi.resolve("Suwayomi-Server.jar"))
            val beside = assertNotNull(SuwayomiRuntimeLocator.layoutProblem(suwayomi.resolve("jre/bin/java"), suwayomi.resolve("Suwayomi-Server.jar")))
            assertTrue(beside.contains("folder of its own"), beside)
            assertNotNull(SuwayomiRuntimeLocator.layoutProblem(suwayomi.resolve("jre/bin/java"), suwayomi.resolve("jre/lib/Suwayomi-Server.jar")))
            assertNull(SuwayomiRuntimeLocator.layoutProblem(suwayomi.resolve("jre/bin/java"), suwayomi.resolve("server/Suwayomi-Server.jar")))
            val refused = SuwayomiServerManager(root.resolve("data-2"), { SuwayomiRuntime(suwayomi.resolve("jre/bin/java"), suwayomi.resolve("Suwayomi-Server.jar")) })
            try {
                // Named by hand, past the locator: it is still refused at once, with the reason.
                val began = System.currentTimeMillis()
                val failure = assertFailsWith<SuwayomiUnavailable> { refused.start(1) }
                assertTrue(failure.message!!.contains("folder of its own"), failure.message)
                assertTrue(System.currentTimeMillis() - began < 5_000, "nothing is started that cannot work")
                assertIs<ServerState.Failed>(refused.state.value)
            } finally {
                refused.close()
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun theRuntimeThisBuildTestsWithIsLaidOutSafely() {
        val runtime = SuwayomiTestRuntime.runtimeOrSkip()
        assertNull(SuwayomiRuntimeLocator.layoutProblem(runtime.java, runtime.jar))
        assertFailsWith<SuwayomiUnavailable> { SuwayomiRuntimeLocator.bundled(Files.createTempDirectory("debridone-empty")) }
    }
}
