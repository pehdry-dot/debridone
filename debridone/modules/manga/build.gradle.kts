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

// The Mangayomi bridge's classes (compiled against Suwayomi-Server in :debridone:modules:manga-bridge),
// carried as a resource: DebridOne packs them, with one source's code, into an extension jar that the
// manga server installs. They are never loaded by DebridOne itself.
val mangayomiBridge: Configuration by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
    isTransitive = false
}

dependencies {
    mangayomiBridge(project(":debridone:modules:manga-bridge"))
    api(projects.debridone.moduleApi)
    // Suwayomi-Server's GraphQL API and the metadata providers speak JSON.
    api(libs.kotlinx.serialization.json)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlin.testJunit)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}

tasks.processResources {
    from(mangayomiBridge) {
        into("mangayomi")
        rename { "bridge.jar" }
    }
}

// ---- Suwayomi-Server (the Manga backend) -----------------------------------------------------------
// scripts/debridone/fetch-suwayomi-runtime.sh puts the pinned server jar and a Java 21 runtime under
// build/suwayomi-runtime/. Tests that start the real server use them; without them those tests are
// skipped, unless DEBRIDONE_REQUIRE_SUWAYOMI=1 (CI), which makes a missing runtime a failure.
val suwayomiPlatform: String = run {
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
val suwayomiRuntimeDir = layout.buildDirectory.dir("suwayomi-runtime")
val suwayomiJar: File = suwayomiRuntimeDir.get().file("Suwayomi-Server.jar").asFile
val suwayomiJava: File = suwayomiRuntimeDir.get().file("$suwayomiPlatform/jre/bin/java").asFile

tasks.withType<Test>().configureEach {
    if (suwayomiJar.isFile && suwayomiJava.isFile) {
        systemProperty("debridone.suwayomi.jar", suwayomiJar.absolutePath)
        systemProperty("debridone.suwayomi.java", suwayomiJava.absolutePath)
    }
    inputs.property("suwayomiPresent", suwayomiJar.isFile && suwayomiJava.isFile)
    environment("DEBRIDONE_REQUIRE_SUWAYOMI", System.getenv("DEBRIDONE_REQUIRE_SUWAYOMI") ?: "")
    inputs.property("requireSuwayomi", System.getenv("DEBRIDONE_REQUIRE_SUWAYOMI") ?: "")
    // DEBRIDONE_LIVE_TESTS=1 also runs the tests that talk to real services (an extension store,
    // the Komga demo server). Off by default: a normal run must not depend on someone else's server.
    environment("DEBRIDONE_LIVE_TESTS", System.getenv("DEBRIDONE_LIVE_TESTS") ?: "")
    inputs.property("liveTests", System.getenv("DEBRIDONE_LIVE_TESTS") ?: "")
    // The extension store the live extension test adds. No address is written anywhere in
    // DebridOne: whoever runs the test names the store.
    environment("DEBRIDONE_TEST_EXTENSION_STORE", System.getenv("DEBRIDONE_TEST_EXTENSION_STORE") ?: "")
    inputs.property("testExtensionStore", System.getenv("DEBRIDONE_TEST_EXTENSION_STORE") ?: "")
    if (System.getProperty("os.name").contains("linux", ignoreCase = true)) {
        environment("LC_ALL", "C.UTF-8")
    }
    testLogging { events("failed", "skipped") }
}
