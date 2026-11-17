package com.debridone.manga.plugins

import kotlinx.serialization.Serializable

/** One archive to download and where it goes, relative to the package's own folder ("" is the package itself). */
@Serializable
data class PlannedPackage(
    val path: String,
    val name: String,
    val version: String,
    val tarball: String,
    /** The registry's checksum, `sha512-<base64>`. Downloads are refused without one. */
    val integrity: String? = null,
    val shasum: String? = null,
)

/** Everything one extension package needs, with the checksum of every archive. */
@Serializable
data class InstallPlan(
    val name: String,
    val version: String,
    val registry: String,
    val license: String? = null,
    val packages: List<PlannedPackage>,
)

enum class PackageKind {
    /** `@tiyo/core`: the one package current Houdoku loads; it carries all of its sources. */
    TIYO,

    /** `@houdoku/extension-*`: Houdoku's older format, one source per package. */
    LEGACY,
}

enum class CompatibilityLevel { VERIFIED, UNTESTED, UNSUPPORTED }

data class Compatibility(val level: CompatibilityLevel, val explanation: String)

/**
 * Which packages are Houdoku extension packages at all, and what is known about each version.
 *
 * "Verified" is reserved for versions that were actually loaded and exercised (sources listed,
 * search, series details, chapters, pages) by DebridOne's own tests; see debridone/docs/MANGA_PLUGINS.md.
 */
object PluginRules {
    const val TIYO_PACKAGE = "@tiyo/core"
    private val LEGACY_PACKAGE = Regex("^@houdoku/extension-[a-z0-9][a-z0-9-]*$")
    private val VERSION = Regex("^(\\d+)\\.(\\d+)\\.(\\d+)(?:-([0-9A-Za-z.-]+))?(?:\\+[0-9A-Za-z.-]+)?$")

    /** Versions of `@tiyo/core` that passed DebridOne's compatibility tests. */
    val VERIFIED_TIYO_VERSIONS = setOf("1.0.3")

    fun kindOf(name: String): PackageKind? = when {
        name == TIYO_PACKAGE -> PackageKind.TIYO
        LEGACY_PACKAGE.matches(name) -> PackageKind.LEGACY
        else -> null
    }

    fun isVersion(text: String): Boolean = VERSION.matches(text)

    /** Negative, zero or positive as [a] is older than, equal to or newer than [b]. Pre-releases sort before their release. */
    fun compareVersions(a: String, b: String): Int {
        val left = VERSION.matchEntire(a)
        val right = VERSION.matchEntire(b)
        if (left == null || right == null) return a.compareTo(b)
        for (index in 1..3) {
            val difference = left.groupValues[index].toLong().compareTo(right.groupValues[index].toLong())
            if (difference != 0) return difference
        }
        val leftPre = left.groupValues[4]
        val rightPre = right.groupValues[4]
        return when {
            leftPre.isEmpty() && rightPre.isEmpty() -> 0
            leftPre.isEmpty() -> 1
            rightPre.isEmpty() -> -1
            else -> leftPre.compareTo(rightPre)
        }
    }

    fun compatibility(name: String, version: String): Compatibility {
        val kind = kindOf(name)
            ?: return Compatibility(
                CompatibilityLevel.UNSUPPORTED,
                "$name is not a Houdoku extension package. Houdoku loads one package, $TIYO_PACKAGE; the older per-source packages are named @houdoku/extension-….",
            )
        val match = VERSION.matchEntire(version)
            ?: return Compatibility(CompatibilityLevel.UNSUPPORTED, "\"$version\" is not a version number.")
        return when (kind) {
            PackageKind.TIYO -> when {
                match.groupValues[1] != "1" -> Compatibility(
                    CompatibilityLevel.UNSUPPORTED,
                    "DebridOne supports the 1.x extension interface of Tiyo; version $version uses a different one.",
                )
                version in VERIFIED_TIYO_VERSIONS -> Compatibility(
                    CompatibilityLevel.VERIFIED,
                    "Tested with DebridOne: it loads, lists its sources, and searching, series details, chapters and pages were exercised.",
                )
                else -> Compatibility(
                    CompatibilityLevel.UNTESTED,
                    "Uses the supported 1.x interface, but this exact version has not been tested with DebridOne.",
                )
            }
            PackageKind.LEGACY -> Compatibility(
                CompatibilityLevel.UNTESTED,
                "Older Houdoku format (one source per package). Current Houdoku no longer loads these; DebridOne runs them through an adapter and they are not individually tested.",
            )
        }
    }
}
