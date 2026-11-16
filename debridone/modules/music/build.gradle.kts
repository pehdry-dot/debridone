// DebridOne music module engine.
// ISOLATION RULE: this project may depend only on :debridone:platform and :debridone:module-api.
// It must never depend on another entertainment module or on :composeApp (Streaming).
// Enforced by :debridone:architecture-tests.
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
    api(projects.debridone.moduleApi)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlin.testJunit)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
