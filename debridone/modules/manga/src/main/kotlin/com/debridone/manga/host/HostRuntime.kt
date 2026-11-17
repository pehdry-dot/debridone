package com.debridone.manga.host

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/** A failure reported by the extension host, or about it. [code] is stable; [message] is for people. */
class HostException(
    val code: String,
    message: String,
    /** The system's network error name (ENOTFOUND, ECONNREFUSED…), when there is one. */
    val network: String? = null,
    val detail: String? = null,
) : Exception(message) {
    /** True when trying again later could work without changing anything. */
    val temporary: Boolean get() = code in setOf("NETWORK", "TIMEOUT", "HOST_STOPPED")
}

/** Where the Node.js runtime and DebridOne's host script are. */
data class HostRuntime(
    val node: Path,
    /** Folder with `src/host.js` and the host's own `node_modules`. */
    val hostDir: Path,
    /** "bundled" (inside the app) or "development" (pointed at explicitly). */
    val origin: String,
)

/**
 * Finds the runtime for the extension host.
 *
 * The packaged app uses only the copy inside the application bundle. A Node.js found on the
 * computer's PATH is never used: an installed app must not depend on it. During development and in
 * tests the runtime is named explicitly, through system properties or environment variables.
 */
object MangaHostRuntime {
    const val NODE_PROPERTY = "debridone.manga.node"
    const val HOST_DIR_PROPERTY = "debridone.manga.hostDir"
    const val NODE_ENV = "DEBRIDONE_MANGA_NODE"
    const val HOST_DIR_ENV = "DEBRIDONE_MANGA_HOST_DIR"

    /** Compose sets this in a packaged app to the folder holding the app's bundled resources. */
    private const val APP_RESOURCES_PROPERTY = "compose.application.resources.dir"

    fun locate(
        property: (String) -> String? = System::getProperty,
        environment: (String) -> String? = System::getenv,
    ): HostRuntime {
        val explicitNode = property(NODE_PROPERTY)?.takeIf { it.isNotBlank() } ?: environment(NODE_ENV)?.takeIf { it.isNotBlank() }
        val explicitHost = property(HOST_DIR_PROPERTY)?.takeIf { it.isNotBlank() } ?: environment(HOST_DIR_ENV)?.takeIf { it.isNotBlank() }
        if (explicitNode != null || explicitHost != null) {
            if (explicitNode == null || explicitHost == null) {
                throw HostException("NO_RUNTIME", "Both the Node.js program and the host folder have to be given ($NODE_ENV and $HOST_DIR_ENV).")
            }
            return checked(HostRuntime(Paths.get(explicitNode), Paths.get(explicitHost), "development"))
        }
        val resources = property(APP_RESOURCES_PROPERTY)?.takeIf { it.isNotBlank() }
        if (resources != null) {
            val root = Paths.get(resources).resolve("manga-host")
            val node = root.resolve(if (isWindows()) "node.exe" else "node")
            if (Files.isRegularFile(node)) return checked(HostRuntime(node, root.resolve("host"), "bundled"))
        }
        throw HostException(
            "NO_RUNTIME",
            "This build of DebridOne does not include the extension runtime, so online sources cannot run. " +
                "Local files can still be read.",
        )
    }

    private fun checked(runtime: HostRuntime): HostRuntime {
        if (!Files.isRegularFile(runtime.node)) {
            throw HostException("NO_RUNTIME", "The extension runtime is missing: ${runtime.node}")
        }
        if (!Files.isRegularFile(runtime.hostDir.resolve("src").resolve("host.js"))) {
            throw HostException("NO_RUNTIME", "The extension host is missing from ${runtime.hostDir}")
        }
        if (!Files.isDirectory(runtime.hostDir.resolve("node_modules").resolve("aki-plugin-manager"))) {
            throw HostException("NO_RUNTIME", "The extension host in ${runtime.hostDir} has not been prepared (its packages are missing).")
        }
        return runtime
    }

    private fun isWindows(): Boolean = System.getProperty("os.name").orEmpty().contains("windows", ignoreCase = true)
}
