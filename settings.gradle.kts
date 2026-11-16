pluginManagement {
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

// DebridOne: this is Gradle's internal project name only (the app is named by `packageName` in
// composeApp/build.gradle.kts). It must not equal "debridone" ignoring case: the type-safe project
// accessors generate one Java file per project, and a root project called "DebridOne" next to the
// `:debridone` project produced DebridOneProjectDependency.java and DebridoneProjectDependency.java,
// which are the same file on macOS's case-insensitive file system and broke every Mac build.
rootProject.name = "debridone-desktop"
enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

dependencyResolutionManagement {
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
    }
}

include(":composeApp")
include(":androidApp")
include(":desktopSentry")
if (!System.getProperty("os.name").contains("win", ignoreCase = true)) {
    include(":composeMediaPlayer")
    project(":composeMediaPlayer").projectDir = file("vendor/compose-media-player/mediaplayer")
}
include(":baselineprofile")

// DebridOne: shared infrastructure and the independent entertainment-module engines.
// The Streaming engine is upstream Nuvio in :composeApp. See docs/debridone/ARCHITECTURE.md.
include(":debridone:platform")
include(":debridone:module-api")
include(":debridone:accounts")
include(":debridone:modules:music")
include(":debridone:modules:gaming")
include(":debridone:modules:manga")
include(":debridone:architecture-tests")
