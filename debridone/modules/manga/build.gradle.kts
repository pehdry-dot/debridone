// DebridOne manga module engine.
// ISOLATION RULE: this project may depend only on :debridone:platform and :debridone:module-api.
// It must never depend on another entertainment module or on :composeApp (Streaming).
// Enforced by :debridone:architecture-tests.
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.kotlinxSerialization)
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_11) }
}

java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}

dependencies {
    api(projects.debridone.moduleApi)
    // The extension host protocol, package registry answers and metadata providers are JSON.
    api(libs.kotlinx.serialization.json)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlin.testJunit)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}

// ---- Extension host (Node.js) -------------------------------------------------------------------
// The host is plain JavaScript under host/. Its own packages (aki-plugin-manager, which Houdoku uses
// to load Tiyo, and semver) are installed from the committed lockfile with `npm ci`; no install
// script is run. The result, build/manga-host/, is what the app bundles and what the tests start.
//
// Which Node.js: -Pdebridone.manga.node=<path> or DEBRIDONE_MANGA_NODE, else the runtime fetched by
// scripts/debridone/fetch-node-runtime.sh, else (for development and tests only) `node` on PATH.
// The packaged app never looks at PATH; see MangaHostRuntime.

val mangaNodePlatform: String = run {
    val os = System.getProperty("os.name").lowercase()
    val arch = System.getProperty("os.arch").lowercase()
    val osPart = when {
        os.contains("mac") -> "darwin"
        os.contains("linux") -> "linux"
        else -> "unsupported"
    }
    val archPart = (providers.gradleProperty("nuvio.macos.arch").orNull ?: arch).let {
        when (it) {
            "aarch64", "arm64" -> "arm64"
            "x86_64", "amd64", "x64" -> "x64"
            else -> it
        }
    }
    "$osPart-$archPart"
}
val fetchedNode: File = layout.buildDirectory.file("node-runtime/$mangaNodePlatform/bin/node").get().asFile

fun nodeOnPath(): File? = System.getenv("PATH").orEmpty().split(File.pathSeparator)
    .map { File(it, "node") }
    .firstOrNull { it.isFile && it.canExecute() }

val mangaNode: File? = (providers.gradleProperty("debridone.manga.node").orNull ?: System.getenv("DEBRIDONE_MANGA_NODE"))
    ?.takeIf { it.isNotBlank() }?.let(::File)
    ?: fetchedNode.takeIf { it.isFile }
    ?: nodeOnPath()

val mangaHostDir = layout.buildDirectory.dir("manga-host")

val syncMangaHost = tasks.register<Sync>("syncMangaHost") {
    description = "Copies the extension host's sources next to where its packages are installed."
    from(layout.projectDirectory.dir("host")) {
        include("package.json", "package-lock.json", "src/**")
    }
    into(mangaHostDir)
    preserve { include("node_modules/**") }
}

val prepareMangaHost = tasks.register<Exec>("prepareMangaHost") {
    description = "Installs the extension host's packages from its lockfile (npm ci, no install scripts)."
    dependsOn(syncMangaHost)
    val lockfile = layout.projectDirectory.file("host/package-lock.json")
    inputs.file(lockfile)
    outputs.dir(mangaHostDir.map { it.dir("node_modules") })
    workingDir = mangaHostDir.get().asFile
    val node = mangaNode
    onlyIf { node != null }
    if (node != null) {
        val npmCli = node.canonicalFile.parentFile.parentFile.resolve("lib/node_modules/npm/bin/npm-cli.js")
        val npm = if (npmCli.isFile) listOf(node.absolutePath, npmCli.absolutePath) else listOf("npm")
        commandLine(npm + listOf("ci", "--omit=dev", "--ignore-scripts", "--no-audit", "--no-fund", "--loglevel=error"))
    }
}

// ---- Suwayomi-Server (the Manga backend) -----------------------------------------------------------
// scripts/debridone/fetch-suwayomi-runtime.sh puts the pinned server jar and a Java 21 runtime under
// build/suwayomi-runtime/. Tests that start the real server use them; without them those tests are
// skipped, unless DEBRIDONE_REQUIRE_SUWAYOMI=1 (CI), which makes a missing runtime a failure.
val suwayomiRuntimeDir = layout.buildDirectory.dir("suwayomi-runtime")
val suwayomiJar: File = suwayomiRuntimeDir.get().file("Suwayomi-Server.jar").asFile
val suwayomiJava: File = suwayomiRuntimeDir.get().file("$mangaNodePlatform/jre/bin/java").asFile

tasks.withType<Test>().configureEach {
    if (suwayomiJar.isFile && suwayomiJava.isFile) {
        systemProperty("debridone.suwayomi.jar", suwayomiJar.absolutePath)
        systemProperty("debridone.suwayomi.java", suwayomiJava.absolutePath)
    }
    inputs.property("suwayomiPresent", suwayomiJar.isFile && suwayomiJava.isFile)
    environment("DEBRIDONE_REQUIRE_SUWAYOMI", System.getenv("DEBRIDONE_REQUIRE_SUWAYOMI") ?: "")
    inputs.property("requireSuwayomi", System.getenv("DEBRIDONE_REQUIRE_SUWAYOMI") ?: "")
    // The extension store the live extension test adds (DEBRIDONE_LIVE_TESTS=1). No address is
    // written anywhere in DebridOne: whoever runs the test names the store.
    environment("DEBRIDONE_TEST_EXTENSION_STORE", System.getenv("DEBRIDONE_TEST_EXTENSION_STORE") ?: "")
    inputs.property("testExtensionStore", System.getenv("DEBRIDONE_TEST_EXTENSION_STORE") ?: "")
}

tasks.withType<Test>().configureEach {
    val node = mangaNode
    if (node != null) {
        dependsOn(prepareMangaHost)
        systemProperty("debridone.manga.node", node.absolutePath)
        systemProperty("debridone.manga.hostDir", mangaHostDir.get().asFile.absolutePath)
    }
    // Where the tests keep the real Tiyo package between runs (downloaded once from the registry).
    systemProperty("debridone.manga.fixtureDir", layout.buildDirectory.dir("tiyo-fixture").get().asFile.absolutePath)
    // Without Node.js the extension-host tests are skipped; CI sets DEBRIDONE_REQUIRE_NODE=1 so that a
    // missing runtime fails instead.
    environment("DEBRIDONE_REQUIRE_NODE", System.getenv("DEBRIDONE_REQUIRE_NODE") ?: "")
    inputs.property("requireNode", System.getenv("DEBRIDONE_REQUIRE_NODE") ?: "")
    // DEBRIDONE_LIVE_TESTS=1 also runs the tests that talk to real services (MangaDex, the Komga
    // demo server, AniList). Off by default: a normal run must not depend on someone else's server.
    environment("DEBRIDONE_LIVE_TESTS", System.getenv("DEBRIDONE_LIVE_TESTS") ?: "")
    inputs.property("liveTests", System.getenv("DEBRIDONE_LIVE_TESTS") ?: "")
    if (System.getProperty("os.name").contains("linux", ignoreCase = true)) {
        environment("LC_ALL", "C.UTF-8")
    }
    testLogging { events("failed", "skipped") }
}
