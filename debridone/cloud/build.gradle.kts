// Connected cloud accounts (Real-Debrid, TorBox), their file listings and the one download manager.
// Application-level shared infrastructure: it knows no entertainment module, and no module engine
// may depend on it. Each module is handed only its own kind of file by the application.
// Enforced by :debridone:architecture-tests.
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.kotlinxSerialization)
    `java-test-fixtures`
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_11) }
}

java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}

dependencies {
    api(projects.debridone.platform)
    api(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)

    testFixturesImplementation(libs.kotlinx.serialization.json)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlin.testJunit)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}

// Prints the classpath of the stand-in providers (see StandInMain.kt), for running them beside the application.
tasks.register("standInClasspath") {
    val classpath = sourceSets["testFixtures"].runtimeClasspath
    dependsOn(tasks.named("testFixturesClasses"))
    doLast { println(classpath.asPath) }
}
