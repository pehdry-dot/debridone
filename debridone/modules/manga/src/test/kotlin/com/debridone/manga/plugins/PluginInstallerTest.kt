package com.debridone.manga.plugins

import com.debridone.manga.FakeTiyo
import com.debridone.manga.StandInRegistry
import com.debridone.manga.TarBuilder
import com.debridone.manga.sha512Integrity
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PluginInstallerTest {
    private lateinit var root: Path
    private lateinit var plugins: Path
    private lateinit var registry: StandInRegistry
    private lateinit var installer: PluginInstaller

    @BeforeTest
    fun setUp() {
        root = Files.createTempDirectory("manga-installer")
        plugins = root.resolve("plugins")
        registry = StandInRegistry()
        installer = PluginInstaller(plugins, root.resolve("work"), registry.url)
    }

    @AfterTest
    fun tearDown() {
        registry.close()
        PluginInstaller.deleteRecursively(root)
    }

    private fun dependencyArchive(name: String, version: String): ByteArray = TarBuilder()
        .file("package/package.json", """{"name":"$name","version":"$version","main":"index.js"}""")
        .file("package/index.js", "module.exports = '$name';")
        .build()

    private fun plan(version: String, rootBytes: ByteArray, vararg extra: PlannedPackage): InstallPlan = InstallPlan(
        name = "@tiyo/core",
        version = version,
        registry = registry.url,
        packages = listOf(
            PlannedPackage("", "@tiyo/core", version, registry.archive("@tiyo/core", version, rootBytes), sha512Integrity(rootBytes)),
        ) + extra,
    )

    private fun dependency(path: String, name: String, version: String): PlannedPackage {
        val bytes = dependencyArchive(name, version)
        return PlannedPackage(path, name, version, registry.archive(name, version, bytes), sha512Integrity(bytes))
    }

    @Test
    fun installsInTheLayoutHoudokusPluginManagerUses(): Unit = runBlocking {
        val bytes = FakeTiyo.archive("1.0.99")
        val staged = installer.stage(
            plan("1.0.99", bytes, dependency("node_modules/left", "left", "1.2.3"), dependency("node_modules/left/node_modules/@scope/inner", "@scope/inner", "0.1.0")),
            pinned = false,
        )
        // Nothing is in use until the package is activated.
        assertTrue(installer.installed().isEmpty())
        assertEquals(3, staged.packages)
        installer.activate(staged)

        assertTrue(Files.isRegularFile(plugins.resolve("@tiyo/core/package.json")))
        assertTrue(Files.isRegularFile(plugins.resolve("@tiyo/core/src/index.js")))
        assertTrue(Files.isRegularFile(plugins.resolve("@tiyo/core/node_modules/left/index.js")))
        assertTrue(Files.isRegularFile(plugins.resolve("@tiyo/core/node_modules/left/node_modules/@scope/inner/index.js")))
        val installed = installer.installed().single()
        assertEquals("@tiyo/core", installed.name)
        assertEquals("1.0.99", installed.version)
        assertEquals(PackageKind.TIYO, installed.kind)
        // No staging folders are left behind.
        assertTrue(Files.newDirectoryStream(root.resolve("work")).use { it.none() })
    }

    @Test
    fun aDownloadThatDoesNotMatchItsChecksumIsDiscarded(): Unit = runBlocking {
        val bytes = FakeTiyo.archive("1.0.99")
        val tampered = plan("1.0.99", bytes).let { it.copy(packages = listOf(it.packages[0].copy(integrity = sha512Integrity("something else".toByteArray())))) }
        val failure = assertFailsWith<PackageRejectedException> { installer.stage(tampered, pinned = false) }
        assertTrue(failure.message!!.contains("checksum"))
        assertTrue(installer.installed().isEmpty())
        assertFalse(Files.exists(plugins.resolve("@tiyo")))
    }

    @Test
    fun aDependencyThatFailsItsChecksumStopsTheWholeInstall(): Unit = runBlocking {
        val good = dependency("node_modules/left", "left", "1.2.3")
        val bad = good.copy(integrity = sha512Integrity(byteArrayOf(1)))
        assertFailsWith<PackageRejectedException> { installer.stage(plan("1.0.99", FakeTiyo.archive("1.0.99"), bad), pinned = false) }
        assertTrue(Files.newDirectoryStream(root.resolve("work")).use { it.none() }, "the staging folder is removed")
    }

    @Test
    fun archivesWithoutASha512AreRefused(): Unit = runBlocking {
        val bytes = FakeTiyo.archive("1.0.99")
        val weak = plan("1.0.99", bytes).let { it.copy(packages = listOf(it.packages[0].copy(integrity = null, shasum = "abc"))) }
        val failure = assertFailsWith<PackageRejectedException> { installer.stage(weak, pinned = false) }
        assertTrue(failure.message!!.contains("SHA-512"))
    }

    @Test
    fun pathsThatLeaveThePackageAreRefused(): Unit = runBlocking {
        val evil = TarBuilder()
            .file("package/package.json", """{"name":"@tiyo/core","version":"1.0.99","main":"./src/index.js"}""")
            .file("package/../../escaped.js", "boom")
            .build()
        val failure = assertFailsWith<PackageRejectedException> { installer.stage(plan("1.0.99", evil), pinned = false) }
        assertTrue(failure.message!!.contains("leaves its folder"))
        assertFalse(Files.exists(root.resolve("escaped.js")))
        assertFalse(Files.exists(root.resolve("work/escaped.js")))
    }

    @Test
    fun absolutePathsAreRefused(): Unit = runBlocking {
        val target = root.resolve("absolute.js")
        // Two leading components so that one survives the strip and the path is still absolute.
        val evil = TarBuilder()
            .file("package/package.json", """{"name":"@tiyo/core","version":"1.0.99","main":"./src/index.js"}""")
            .file(target.toString(), "boom")
            .build()
        assertFailsWith<PackageRejectedException> { installer.stage(plan("1.0.99", evil), pinned = false) }
        assertFalse(Files.exists(target))
    }

    @Test
    fun linksAreRefused(): Unit = runBlocking {
        val evil = TarBuilder()
            .file("package/package.json", """{"name":"@tiyo/core","version":"1.0.99","main":"./src/index.js"}""")
            .symlink("package/src", "/etc")
            .build()
        val failure = assertFailsWith<PackageRejectedException> { installer.stage(plan("1.0.99", evil), pinned = false) }
        assertTrue(failure.message!!.contains("link"))
    }

    @Test
    fun anArchiveThatIsAnotherPackageIsRefused(): Unit = runBlocking {
        val other = FakeTiyo.archive("1.0.99", name = "left-pad")
        val failure = assertFailsWith<PackageRejectedException> { installer.stage(plan("1.0.99", other), pinned = false) }
        assertTrue(failure.message!!.contains("left-pad"))
        val wrongVersion = FakeTiyo.archive("1.0.98")
        assertFailsWith<PackageRejectedException> { installer.stage(plan("1.0.99", wrongVersion), pinned = false) }
    }

    @Test
    fun onlyHoudokuExtensionPackagesAreAccepted(): Unit = runBlocking {
        val bytes = FakeTiyo.archive("1.0.0", name = "express")
        val foreign = InstallPlan("express", "1.0.0", registry.url, packages = listOf(PlannedPackage("", "express", "1.0.0", registry.archive("express", "1.0.0", bytes), sha512Integrity(bytes))))
        val failure = assertFailsWith<PackageRejectedException> { installer.stage(foreign, pinned = false) }
        assertTrue(failure.message!!.contains("not a Houdoku extension package"))
        assertTrue(registry.requests.isEmpty(), "nothing is downloaded for a package that is refused by name")
    }

    @Test
    fun aTiyoVersionWithAnotherInterfaceIsRefused(): Unit = runBlocking {
        val failure = assertFailsWith<PackageRejectedException> { installer.stage(plan("2.0.0", FakeTiyo.archive("2.0.0")), pinned = false) }
        assertTrue(failure.message!!.contains("1.x"))
    }

    @Test
    fun archivesFromOutsideTheRegistryAreRefused(): Unit = runBlocking {
        val bytes = FakeTiyo.archive("1.0.99")
        val elsewhere = plan("1.0.99", bytes).let { it.copy(packages = listOf(it.packages[0].copy(tarball = "https://example.org/core-1.0.99.tgz"))) }
        val failure = assertFailsWith<PackageRejectedException> { installer.stage(elsewhere, pinned = false) }
        assertTrue(failure.message!!.contains("other than the package registry"))
    }

    @Test
    fun unsafePlanPathsAreRefused(): Unit = runBlocking {
        val bytes = FakeTiyo.archive("1.0.99")
        for (path in listOf("../left", "node_modules/../../left", "/node_modules/left", "node_modules/other")) {
            val entry = dependency("node_modules/left", "left", "1.2.3").copy(path = path)
            assertFailsWith<PackageRejectedException>("path $path") { installer.stage(plan("1.0.99", bytes, entry), pinned = false) }
        }
    }

    @Test
    fun anUpdateReplacesTheInstalledVersionAndAFailedOneLeavesItAlone(): Unit = runBlocking {
        installer.activate(installer.stage(plan("1.0.98", FakeTiyo.archive("1.0.98")), pinned = false))
        assertEquals("1.0.98", installer.installed().single().version)

        // A broken update is rejected while staging: the installed version is untouched.
        val broken = TarBuilder().file("package/package.json", """{"name":"@tiyo/core","version":"1.0.99","main":"./missing.js"}""").build()
        assertFailsWith<PackageRejectedException> { installer.stage(plan("1.0.99", broken), pinned = false) }
        assertEquals("1.0.98", installer.installed().single().version)
        assertTrue(Files.isRegularFile(plugins.resolve("@tiyo/core/src/index.js")))

        installer.activate(installer.stage(plan("1.0.99", FakeTiyo.archive("1.0.99")), pinned = false))
        assertEquals("1.0.99", installer.installed().single().version)
    }

    @Test
    fun aStagedPackageCanBeDeclined(): Unit = runBlocking {
        val staged = installer.stage(plan("1.0.99", FakeTiyo.archive("1.0.99")), pinned = false)
        assertTrue(Files.isDirectory(staged.packageDir))
        installer.discard(staged)
        assertFalse(Files.exists(staged.root))
        assertTrue(installer.installed().isEmpty())
    }

    @Test
    fun removingDeletesThePackageAndItsEmptyScopeFolder(): Unit = runBlocking {
        installer.activate(installer.stage(plan("1.0.99", FakeTiyo.archive("1.0.99")), pinned = false))
        assertTrue(installer.remove("@tiyo/core"))
        assertFalse(Files.exists(plugins.resolve("@tiyo")))
        assertFalse(installer.remove("@tiyo/core"))
        assertFailsWith<PackageRejectedException> { installer.remove("../../etc") }
    }

    @Test
    fun anImportedFileIsCheckedAgainstThePublishedArchive(): Unit = runBlocking {
        val published = FakeTiyo.archive("1.0.99")
        val file = root.resolve("core-1.0.99.tgz")
        Files.write(file, published)
        val info = installer.inspectArchive(file)
        assertEquals("@tiyo/core", info.name)
        assertEquals("1.0.99", info.version)
        assertEquals(sha512Integrity(published), info.sha512)

        val same = installer.stage(plan("1.0.99", published), pinned = false, rootArchive = file)
        assertEquals(true, same.matchesPublished)
        installer.discard(same)

        // A file that is not what the registry publishes still installs, but is flagged.
        val rebuilt = FakeTiyo.archive("1.0.99", indexSource = "exports.TiyoClient = class {};")
        Files.write(file, rebuilt)
        val different = installer.stage(plan("1.0.99", published), pinned = false, rootArchive = file)
        assertEquals(false, different.matchesPublished)
        assertEquals("exports.TiyoClient = class {};", Files.readString(different.packageDir.resolve("src/index.js")))
    }

    @Test
    fun importingSomethingThatIsNotAnExtensionPackageFails() {
        val file = root.resolve("notes.tgz")
        Files.writeString(file, "this is not an archive")
        assertFailsWith<PackageRejectedException> { installer.inspectArchive(file) }
        Files.write(file, TarBuilder().file("package/readme.txt", "hello").build())
        assertFailsWith<PackageRejectedException> { installer.inspectArchive(file) }
        Files.write(file, FakeTiyo.archive("1.0.0", name = "lodash"))
        assertFailsWith<PackageRejectedException> { installer.inspectArchive(file) }
    }

    @Test
    fun interruptedInstallsAreCleanedUp(): Unit = runBlocking {
        val staged = installer.stage(plan("1.0.99", FakeTiyo.archive("1.0.99")), pinned = false)
        assertTrue(Files.exists(staged.root))
        installer.cleanUp()
        assertFalse(Files.exists(staged.root))
    }

    @Test
    fun thePinnedPlanForTiyoIsCompleteAndChecksummed() {
        val pinned = assertNotNull(PluginInstaller(plugins, root.resolve("work")).pinnedPlan("@tiyo/core", "1.0.3"))
        assertEquals("@tiyo/core", pinned.packages.first().name)
        assertEquals("", pinned.packages.first().path)
        assertTrue(pinned.packages.size > 50)
        assertTrue(pinned.packages.all { it.integrity!!.startsWith("sha512-") && it.tarball.startsWith("https://registry.npmjs.org/") })
        assertTrue(pinned.packages.any { it.name == "jsdom" && it.version == "23.2.0" })
        assertTrue(pinned.packages.any { it.name == "node-fetch" && it.version == "2.6.7" })
        assertNull(PluginInstaller(plugins, root.resolve("work")).pinnedPlan("@tiyo/core", "9.9.9"))
    }

    @Test
    fun versionsAndCompatibilityAreReportedHonestly() {
        assertEquals(CompatibilityLevel.VERIFIED, PluginRules.compatibility("@tiyo/core", "1.0.3").level)
        assertEquals(CompatibilityLevel.UNTESTED, PluginRules.compatibility("@tiyo/core", "1.0.2").level)
        assertEquals(CompatibilityLevel.UNSUPPORTED, PluginRules.compatibility("@tiyo/core", "0.9.0").level)
        assertEquals(CompatibilityLevel.UNTESTED, PluginRules.compatibility("@houdoku/extension-mangadex", "1.6.1").level)
        assertEquals(CompatibilityLevel.UNSUPPORTED, PluginRules.compatibility("@tiyo/common", "1.0.0").level)
        assertEquals(CompatibilityLevel.UNSUPPORTED, PluginRules.compatibility("@houdoku/Extension-X", "1.0.0").level)
        assertTrue(PluginRules.compareVersions("1.0.10", "1.0.9") > 0)
        assertTrue(PluginRules.compareVersions("1.0.0-alpha.1", "1.0.0") < 0)
        assertEquals(0, PluginRules.compareVersions("1.2.3", "1.2.3"))
    }

    @Test
    fun theRegistryListsVersionsNewestFirst(): Unit = runBlocking {
        registry.document(
            "/@tiyo%2Fcore",
            """{"name":"@tiyo/core","description":"sources","dist-tags":{"latest":"1.0.3"},"versions":{"1.0.2":{},"1.0.3":{"license":"MIT"},"0.0.0":{}},
               "time":{"1.0.2":"2024-04-01T00:00:00.000Z","1.0.3":"2024-05-05T00:00:00.000Z"}}""",
        )
        val info = installer.registryInfo("@tiyo/core")
        assertEquals("1.0.3", info.latest)
        assertEquals(listOf("1.0.3", "1.0.2", "0.0.0"), info.versions.map { it.version })
        assertEquals("2024-05-05T00:00:00.000Z", info.versions.first().publishedAt)
        assertEquals("MIT", info.license)
        assertFailsWith<PackageRejectedException> { installer.registryInfo("left-pad") }
    }
}
