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

rootProject.name = "DebridOne"
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
