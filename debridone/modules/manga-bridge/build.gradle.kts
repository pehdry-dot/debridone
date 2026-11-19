// The Mangayomi bridge: the classes of the extension that lets Suwayomi-Server run a Mangayomi
// JavaScript source. They are compiled here against Suwayomi-Server's own jar (its source API,
// OkHttp, jsoup and GraalJS) and never run inside DebridOne: the manga module packs them, with one
// source's code, into an extension jar that the manga server installs and runs. The JavaScript
// runs in a GraalJS context of its own with no access to Java, files, processes or the network
// except through the bridge's HTTP calls.
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

plugins {
    alias(libs.plugins.kotlinJvm)
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        // The classes run with the Kotlin standard library inside Suwayomi-Server (2.4.10).
        apiVersion.set(KotlinVersion.KOTLIN_2_4)
        languageVersion.set(KotlinVersion.KOTLIN_2_4)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

// scripts/debridone/fetch-suwayomi-runtime.sh puts the pinned server and its Java runtime here (CI
// does it before building). A path, not a dependency on the Manga module: the bridge needs nothing
// of Manga's, only the server's own classes it will run among.
val suwayomiRuntime = rootProject.layout.projectDirectory.dir("debridone/modules/manga/build/suwayomi-runtime")
val suwayomiJar = suwayomiRuntime.file("Suwayomi-Server.jar")

dependencies {
    compileOnly(files(suwayomiJar))

    testImplementation(files(suwayomiJar))
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlin.testJunit)
    testImplementation(libs.junit)
}

// The tests run the bridge as the server does: with the server's own classes and its Java 21
// runtime (GraalJS needs it), not with the Java Gradle happens to run on.
val suwayomiPlatform: String = run {
    val os = System.getProperty("os.name").lowercase()
    val arch = System.getProperty("os.arch").lowercase()
    val osPart = if (os.contains("mac")) "darwin" else "linux"
    val archPart = (providers.gradleProperty("nuvio.macos.arch").orNull ?: arch).let { if (it == "aarch64" || it == "arm64") "arm64" else "x64" }
    "$osPart-$archPart"
}
val suwayomiJava = suwayomiRuntime.file("$suwayomiPlatform/jre/bin/java")

tasks.test {
    onlyIf("the manga server's Java runtime is present") { suwayomiJava.asFile.isFile }
    executable = suwayomiJava.asFile.absolutePath
    environment("DEBRIDONE_LIVE_TESTS", System.getenv("DEBRIDONE_LIVE_TESTS") ?: "")
    inputs.property("liveTests", System.getenv("DEBRIDONE_LIVE_TESTS") ?: "")
    environment("DEBRIDONE_MANGAYOMI_SOURCES", System.getenv("DEBRIDONE_MANGAYOMI_SOURCES") ?: "")
    inputs.property("mangayomiSources", System.getenv("DEBRIDONE_MANGAYOMI_SOURCES") ?: "")
    testLogging { events("failed", "skipped"); showStandardStreams = System.getenv("DEBRIDONE_LIVE_TESTS") == "1" }
}

tasks.jar {
    archiveFileName.set("mangayomi-bridge.jar")
    // Only the bridge's own classes and its JavaScript glue: everything else comes from the server.
    exclude("META-INF/*.kotlin_module")
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    doFirst {
        check(suwayomiJar.asFile.isFile) {
            "The Mangayomi bridge is compiled against Suwayomi-Server. Run ./scripts/debridone/fetch-suwayomi-runtime.sh first."
        }
    }
}
