// DebridOne music module engine.
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
    // Playback goes through libmpv (the build the app already bundles) via JNA.
    implementation(libs.jna)
    // Embedded tags and artwork: ID3, Vorbis comments, MP4 atoms, FLAC, WAV.
    implementation(libs.jaudiotagger)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlin.testJunit)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}

tasks.withType<Test>().configureEach {
    // Playback tests need libmpv. Locally they are skipped when it is missing; CI sets
    // DEBRIDONE_REQUIRE_LIBMPV=1 so that a missing library fails instead of silently skipping.
    environment("DEBRIDONE_REQUIRE_LIBMPV", System.getenv("DEBRIDONE_REQUIRE_LIBMPV") ?: "")
    // DEBRIDONE_LIVE_TESTS=1 also runs the tests that talk to the real public services (the
    // Navidrome and Jellyfin demo servers, MusicBrainz, LRCLIB). Off by default: a normal test run
    // must not depend on someone else's server being up.
    environment("DEBRIDONE_LIVE_TESTS", System.getenv("DEBRIDONE_LIVE_TESTS") ?: "")
    inputs.property("liveTests", System.getenv("DEBRIDONE_LIVE_TESTS") ?: "")
    // File names with accents are part of the tests; on Linux the JVM only accepts them in a UTF-8
    // locale (macOS always uses UTF-8 for file names).
    if (System.getProperty("os.name").contains("linux", ignoreCase = true)) {
        environment("LC_ALL", "C.UTF-8")
    }
    // Each test class gets its own JVM so mpv instances never outlive their test.
    forkEvery = 1
    testLogging { events("failed", "skipped") }
}
