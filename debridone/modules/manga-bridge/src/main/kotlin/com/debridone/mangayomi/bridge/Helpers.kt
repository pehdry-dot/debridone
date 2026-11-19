package com.debridone.mangayomi.bridge

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.security.MessageDigest
import java.text.ParsePosition
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.OffsetDateTime
import java.util.Base64
import java.util.Calendar
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Mangayomi's parseDates: chapter dates as the sites write them ("March 3, 2024", "3 days ago",
 * "hace 2 horas", a timestamp) into milliseconds since 1970, as text. A date that cannot be read
 * becomes "now", as in Mangayomi.
 */
internal object DateParsing {
    fun parseDates(values: JsonArray, format: String, locale: String): List<String> =
        values.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { text -> text.isNotEmpty() } }
            .map { parse(it, format, locale).toString() }

    fun parse(text: String, format: String, locale: String, now: Long = System.currentTimeMillis()): Long {
        val date = text.trim()
        if (date.isEmpty()) return now
        if (date.all { it.isDigit() }) {
            date.toLongOrNull()?.let { if (date.length == 10) return it * 1000 else if (date.length in 12..14) return it }
        }
        if ('T' in date) runCatching { return OffsetDateTime.parse(date).toInstant().toEpochMilli() }.getOrNull()
        if ('T' in date) runCatching { return Instant.parse(date).toEpochMilli() }.getOrNull()
        val lower = date.lowercase()
        fun startOfDay(daysAgo: Int): Long = Calendar.getInstance().apply {
            timeInMillis = now
            add(Calendar.DAY_OF_MONTH, -daysAgo)
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        if (TODAY.any { lower.startsWith(it) }) return startOfDay(0)
        if (YESTERDAY.any { lower.startsWith(it) }) return startOfDay(1)
        relative(lower, now)?.let { return it }

        val cleaned = date.replace(Regex("(\\d+)(st|nd|rd|th|º|ª|er|e)\\b", RegexOption.IGNORE_CASE), "$1")
        val locales = listOfNotNull(locale.takeIf { it.isNotBlank() }?.let(::toLocale), Locale.ENGLISH, Locale.US, Locale("es"), Locale("pt", "BR"), Locale.FRENCH, Locale("id"), Locale.ITALIAN, Locale("tr"))
        if (format.isNotBlank()) {
            for (l in locales.take(1).ifEmpty { listOf(Locale.ENGLISH) }) tryFormat(cleaned, format, l)?.let { return it }
        }
        val hasLetters = cleaned.any { it.isLetter() }
        val formats = if (hasLetters) TEXTUAL else NUMERIC
        for (l in if (hasLetters) locales.distinct() else listOf(Locale.ENGLISH)) {
            for (pattern in formats) tryFormat(cleaned, pattern, l)?.let { return it }
        }
        return now
    }

    private fun tryFormat(text: String, pattern: String, locale: Locale): Long? = runCatching {
        val parser = SimpleDateFormat(pattern, locale).apply { isLenient = false }
        val position = ParsePosition(0)
        val parsed = parser.parse(text, position) ?: return null
        if (position.index < text.length - 1) return null
        parsed.time
    }.getOrNull()

    private fun toLocale(code: String): Locale {
        val parts = code.replace('-', '_').split('_').filter { it.isNotBlank() }
        return when (parts.size) {
            0 -> Locale.ENGLISH
            1 -> Locale(parts[0])
            else -> Locale(parts[0], parts[1].uppercase())
        }
    }

    /** "3 days ago", "hace 2 horas", "há 5 minutos", "il y a 1 semaine", "2 hari lalu"… */
    private fun relative(lower: String, now: Long): Long? {
        if (AGO.none { it in lower }) return null
        val words = lower.split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotBlank() }
        val amount = words.firstNotNullOfOrNull { it.toIntOrNull() } ?: if (words.any { it in ONE }) 1 else return null
        // Whole words only: "an hour ago" is an hour, not a year.
        val field = UNITS.entries.firstOrNull { (prefixes, _) -> words.any { word -> word !in ONE && prefixes.any { word.startsWith(it) } } }?.value ?: return null
        return Calendar.getInstance().apply { timeInMillis = now; add(field, -amount) }.timeInMillis
    }

    private val TODAY = listOf("today", "hoy", "hoje", "aujourd'hui", "hari ini", "oggi", "bugün", "сегодня", "今天")
    private val YESTERDAY = listOf("yesterday", "ayer", "ontem", "hier", "kemarin", "ieri", "dün", "вчера", "昨天")
    private val AGO = listOf("ago", "hace", "há", "atrás", "il y a", "lalu", " fa", "önce", "назад", "前")
    private val ONE = setOf("a", "an", "one", "un", "una", "um", "uma", "une", "uno", "satu", "bir")
    private val UNITS: Map<List<String>, Int> = linkedMapOf(
        listOf("year", "año", "ano", "anos", "tahun", "anno", "anni", "yıl") to Calendar.YEAR,
        listOf("month", "mes", "mês", "meses", "mois", "bulan", "mese", "mesi") to Calendar.MONTH,
        listOf("week", "semana", "semaine", "minggu", "settiman", "hafta") to Calendar.WEEK_OF_YEAR,
        listOf("day", "día", "dia", "jour", "hari", "giorn", "gün") to Calendar.DAY_OF_MONTH,
        listOf("hour", "hora", "heure", "jam", "ora", "ore", "saat") to Calendar.HOUR_OF_DAY,
        listOf("min", "menit", "dakika") to Calendar.MINUTE,
        listOf("sec", "seg", "detik", "saniye") to Calendar.SECOND,
    )
    private val NUMERIC = listOf("yyyy-MM-dd", "dd/MM/yyyy", "MM/dd/yyyy", "dd-MM-yyyy", "yyyy/MM/dd", "dd.MM.yyyy", "MM-dd-yyyy", "yyyy.MM.dd", "dd/MM/yy", "MM/dd/yy")
    private val TEXTUAL = listOf("MMMM d, yyyy", "MMM d, yyyy", "d MMMM yyyy", "d MMM yyyy", "MMMM dd, yyyy", "dd MMMM yyyy", "dd MMM yyyy", "MMM dd, yyyy", "d 'de' MMMM 'de' yyyy", "MMMM d yyyy", "d MMMM, yyyy", "yyyy MMMM d")
}

/** The ciphers Mangayomi's sources ask for. Like Mangayomi, a failure gives back the input. */
internal object Crypto {
    /** AES-CBC with the key and the IV given as text (Mangayomi's cryptoHandler). */
    fun cryptoHandler(text: String, iv: String, key: String, encrypt: Boolean): String = runCatching {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(if (encrypt) Cipher.ENCRYPT_MODE else Cipher.DECRYPT_MODE, SecretKeySpec(key.toByteArray(), "AES"), IvParameterSpec(iv.toByteArray()))
        if (encrypt) Base64.getEncoder().encodeToString(cipher.doFinal(text.toByteArray()))
        else String(cipher.doFinal(Base64.getDecoder().decode(text)), Charsets.UTF_8)
    }.getOrDefault(text)

    /** CryptoJS.AES.encrypt(text, passphrase): OpenSSL's "Salted__" format, key and IV from the passphrase. */
    fun encryptCryptoJs(text: String, passphrase: String): String = runCatching {
        val salt = ByteArray(8).also { java.security.SecureRandom().nextBytes(it) }
        val (key, iv) = keyAndIv(passphrase.toByteArray(), salt)
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding").apply { init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv)) }
        Base64.getEncoder().encodeToString("Salted__".toByteArray() + salt + cipher.doFinal(text.toByteArray()))
    }.getOrDefault(text)

    fun decryptCryptoJs(encrypted: String, passphrase: String): String = runCatching {
        val data = Base64.getDecoder().decode(encrypted.trim())
        require(String(data, 0, 8, Charsets.ISO_8859_1) == "Salted__")
        val salt = data.copyOfRange(8, 16)
        val (key, iv) = keyAndIv(passphrase.toByteArray(), salt)
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding").apply { init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv)) }
        String(cipher.doFinal(data.copyOfRange(16, data.size)), Charsets.UTF_8)
    }.getOrDefault(encrypted)

    fun decryptGcm(encrypted: String, keyHex: String, ivHex: String, tagHex: String): String = runCatching {
        val data = Base64.getDecoder().decode(encrypted) + hex(tagHex)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, SecretKeySpec(hex(keyHex), "AES"), GCMParameterSpec(128, hex(ivHex))) }
        String(cipher.doFinal(data), Charsets.UTF_8)
    }.getOrDefault(encrypted)

    /** OpenSSL's EVP_BytesToKey with MD5, as CryptoJS uses it: a 32-byte key and a 16-byte IV. */
    private fun keyAndIv(password: ByteArray, salt: ByteArray): Pair<ByteArray, ByteArray> {
        val md5 = MessageDigest.getInstance("MD5")
        var block = ByteArray(0)
        var out = ByteArray(0)
        while (out.size < 48) {
            md5.reset()
            block = md5.digest(block + password + salt)
            out += block
        }
        return out.copyOfRange(0, 32) to out.copyOfRange(32, 48)
    }

    private fun hex(text: String): ByteArray = text.trim().chunked(2).filter { it.length == 2 }.map { it.toInt(16).toByte() }.toByteArray()
}

/** Dean Edwards' packer ("eval(function(p,a,c,k,e,d)…"), which some sites wrap their scripts in. */
internal object Unpacker {
    private val PACKED = Regex("""}\s*\(\s*'(.*?)'\s*,\s*(\d+)\s*,\s*(\d+)\s*,\s*'(.*?)'\.split\('\|'\)""", RegexOption.DOT_MATCHES_ALL)

    fun unpack(script: String): String? {
        val match = PACKED.find(script) ?: return null
        val (payload, radixText, countText, wordsText) = match.destructured
        val radix = radixText.toInt()
        val count = countText.toInt()
        val words = wordsText.split('|')
        if (words.size != count) return null
        val unescaped = payload.replace("\\'", "'").replace("\\\\", "\\")
        return Regex("\\b\\w+\\b").replace(unescaped) { word ->
            val index = decode(word.value, radix)
            words.getOrNull(index)?.takeIf { index >= 0 && it.isNotEmpty() } ?: word.value
        }
    }

    fun unpackAndCombine(script: String): String? {
        val blocks = Regex("eval\\(function\\(p,a,c,k,e,[rd]\\)").findAll(script).map { it.range.first }.toList()
        if (blocks.isEmpty()) return null
        return blocks.mapIndexedNotNull { i, start -> unpack(script.substring(start, blocks.getOrNull(i + 1) ?: script.length)) }.joinToString(" ").ifBlank { null }
    }

    private fun decode(word: String, radix: Int): Int {
        val alphabet = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"
        if (radix <= 36) return word.toIntOrNull(radix) ?: -1
        var value = 0
        for (c in word) {
            val digit = alphabet.indexOf(c)
            if (digit < 0 || digit >= radix) return -1
            value = value * radix + digit
        }
        return value
    }
}
