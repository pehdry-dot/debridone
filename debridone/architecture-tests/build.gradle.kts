// Build-wide guard rails: fails the build when an entertainment module reaches into another one.
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinJvm)
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_11) }
}

java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}

dependencies {
    testImplementation(projects.debridone.moduleApi)
    testImplementation(projects.debridone.modules.music)
    testImplementation(projects.debridone.modules.gaming)
    testImplementation(projects.debridone.modules.manga)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlin.testJunit)
    testImplementation(libs.junit)
}

tasks.withType<Test>().configureEach {
    // The tests scan build files and sources, so they need the repository root and must re-run
    // whenever any of those files change (but not when other projects' build output changes).
    systemProperty("debridone.repo.root", rootProject.projectDir.absolutePath)
    inputs.files(
        rootProject.fileTree("debridone") {
            include("**/build.gradle.kts", "**/src/**/*.kt")
            exclude("**/build/**")
        },
    ).withPropertyName("debridoneSources").withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.files(
        rootProject.fileTree("composeApp/src/debridoneMain") { include("**/*.kt") },
    ).withPropertyName("debridoneUiSources").withPathSensitivity(PathSensitivity.RELATIVE)
}
