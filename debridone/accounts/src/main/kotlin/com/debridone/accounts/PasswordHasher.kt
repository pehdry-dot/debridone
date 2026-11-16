package com.debridone.accounts

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters

/**
 * Argon2id password hashing. Hashes are stored in the PHC-style form
 * `argon2id$v=19$m=<KiB>,t=<iterations>,p=<lanes>$<salt>$<hash>` so the cost can be raised later
 * without invalidating existing accounts (see [needsRehash]).
 *
 * Only the hash is ever stored. There is no way to recover a forgotten password.
 */
class PasswordHasher(
    private val memoryKiB: Int = 64 * 1024,
    private val iterations: Int = 3,
    private val parallelism: Int = 1,
    private val random: SecureRandom = SecureRandom(),
) {
    init {
        require(memoryKiB >= 8 && iterations >= 1 && parallelism >= 1)
    }

    fun hash(secret: CharArray): String {
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val digest = derive(secret, salt, memoryKiB, iterations, parallelism, HASH_BYTES)
        return listOf(
            ALGORITHM,
            "v=$VERSION",
            "m=$memoryKiB,t=$iterations,p=$parallelism",
            ENCODER.encodeToString(salt),
            ENCODER.encodeToString(digest),
        ).joinToString("$")
    }

    fun verify(secret: CharArray, encoded: String?): Boolean {
        val parsed = parse(encoded) ?: return false
        val candidate = derive(secret, parsed.salt, parsed.memoryKiB, parsed.iterations, parsed.parallelism, parsed.hash.size)
        return MessageDigest.isEqual(candidate, parsed.hash)
    }

    /** True when [encoded] was produced with weaker settings than this hasher uses now. */
    fun needsRehash(encoded: String?): Boolean {
        val parsed = parse(encoded) ?: return true
        return parsed.memoryKiB < memoryKiB || parsed.iterations < iterations || parsed.parallelism < parallelism
    }

    private fun derive(secret: CharArray, salt: ByteArray, memoryKiB: Int, iterations: Int, parallelism: Int, length: Int): ByteArray {
        val parameters = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
            .withVersion(Argon2Parameters.ARGON2_VERSION_13)
            .withMemoryAsKB(memoryKiB)
            .withIterations(iterations)
            .withParallelism(parallelism)
            .withSalt(salt)
            .build()
        val output = ByteArray(length)
        Argon2BytesGenerator().apply { init(parameters) }.generateBytes(secret, output)
        return output
    }

    private class Parsed(val memoryKiB: Int, val iterations: Int, val parallelism: Int, val salt: ByteArray, val hash: ByteArray)

    private fun parse(encoded: String?): Parsed? {
        val parts = encoded?.split('$') ?: return null
        if (parts.size != 5 || parts[0] != ALGORITHM || parts[1] != "v=$VERSION") return null
        return runCatching {
            val costs = parts[2].split(',').associate { entry ->
                val (key, value) = entry.split('=', limit = 2)
                key to value.toInt()
            }
            val memory = costs.getValue("m")
            val time = costs.getValue("t")
            val lanes = costs.getValue("p")
            // Bound the work a tampered record can demand.
            require(memory in 8..MAX_MEMORY_KIB && time in 1..MAX_ITERATIONS && lanes in 1..MAX_PARALLELISM)
            val salt = DECODER.decode(parts[3])
            val hash = DECODER.decode(parts[4])
            require(salt.size >= 8 && hash.size >= 16)
            Parsed(memory, time, lanes, salt, hash)
        }.getOrNull()
    }

    companion object {
        private const val ALGORITHM = "argon2id"
        private const val VERSION = 19
        private const val SALT_BYTES = 16
        private const val HASH_BYTES = 32
        private const val MAX_MEMORY_KIB = 1024 * 1024
        private const val MAX_ITERATIONS = 16
        private const val MAX_PARALLELISM = 8
        private val ENCODER = Base64.getEncoder().withoutPadding()
        private val DECODER = Base64.getDecoder()

        /** Cheaper settings for short profile PINs, which are rate-limited rather than brute-force resistant. */
        fun forPins(): PasswordHasher = PasswordHasher(memoryKiB = 16 * 1024, iterations = 2, parallelism = 1)
    }
}
