package com.debridone.mangayomi.bridge

import android.content.SharedPreferences
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.io.IOException

/**
 * What a Mangayomi source's code can ask of the outside, under the names Mangayomi's bridge uses:
 * HTTP requests (made by the manga server's own client, with its cookies and proxy settings),
 * HTML parsing and selection (jsoup, whose selector syntax Mangayomi itself imitates), the source's
 * own settings, and a few helpers. Nothing else is reachable from the code.
 */
internal class HostCalls(
    private val client: () -> OkHttpClient,
    private val defaultHeaders: () -> Headers,
    private val preferences: () -> SharedPreferences,
    /** The settings the source defines, as JSON (Mangayomi's getSourcePreferences()). */
    private val preferenceDefinitions: () -> JsonArray,
    private val sourceName: String,
) {
    private val elements = HashMap<Int, Element?>()
    private var nextKey = 0
    private val parsed = object : LinkedHashMap<String, Document>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Document>?) = size > 6
    }
    private var networkFailure: IOException? = null
    private var browserCheck: String? = null

    fun beginCall() {
        elements.clear()
        nextKey = 0
        networkFailure = null
        browserCheck = null
    }

    /** The site that answered with a browser check (Cloudflare and the like) during the last call, if one did. */
    fun lastBrowserCheck(): String? = browserCheck

    fun endCall() {
        elements.clear()
        parsed.clear()
    }

    /** The network failure that broke the last call, if one did. */
    fun lastNetworkFailure(): IOException? = networkFailure

    fun call(name: String, argsJson: String): Any? {
        val args = kotlinx.serialization.json.Json.parseToJsonElement(argsJson).jsonArray
        fun s(i: Int): String = args.getOrNull(i)?.let { if (it is JsonPrimitive) it.contentOrNull else it.toString() }.orEmpty()
        fun key(i: Int): Int = args.getOrNull(i)?.jsonPrimitive?.intOrNull ?: -1
        return when (name) {
            "log" -> { System.err.println("[Mangayomi: $sourceName] " + s(0).take(2000)); null }
            "http_head", "http_get", "http_post", "http_put", "http_delete", "http_patch" -> http(name.removePrefix("http_").uppercase(), args)

            "get_doc_element" -> register(document(s(0)).let { doc ->
                when (s(1)) {
                    "body" -> doc.body()
                    "documentElement" -> doc.children().firstOrNull()
                    "head" -> doc.head()
                    else -> null
                }
            })
            "get_doc_string" -> document(s(0)).let { if (s(1) == "text") it.wholeText() else it.outerHtml() }
            "get_element_string" -> elementString(s(0), elements[key(1)])
            "doc_select_first" -> register(select(document(s(0)), s(1)).firstOrNull())
            "doc_select" -> keys(select(document(s(0)), s(1)))
            "ele_selectFirst" -> register(elements[key(1)]?.let { select(it, s(0)).firstOrNull() })
            "ele_select" -> keys(elements[key(1)]?.let { select(it, s(0)) }.orEmpty())
            "ele_element_sibling" -> register(elements[key(1)]?.let { if (s(0) == "nextElementSibling") it.nextElementSibling() else it.previousElementSibling() })
            "ele_attr" -> elements[key(1)]?.attr(s(0)).orEmpty()
            "doc_attr" -> document(s(0)).attr(s(1))
            "ele_has_attr" -> elements[key(1)]?.hasAttr(s(0)) ?: false
            "doc_has_attr" -> document(s(0)).hasAttr(s(1))
            "doc_get_elements_by" -> keys(listBy(document(s(0)), s(1), s(2)))
            "ele_get_elements_by" -> keys(elements[key(2)]?.let { listBy(it, s(0), s(1)) }.orEmpty())
            "doc_get_element_by_id" -> register(document(s(0)).getElementById(s(1)))
            "doc_xpath_first" -> xpath(document(s(0)), s(1)).firstOrNull().orEmpty()
            "doc_xpath" -> JsonArray(xpath(document(s(0)), s(1)).map { JsonPrimitive(it) }).toString()
            "ele_xpathFirst" -> elements[key(1)]?.let { xpath(it, s(0)).firstOrNull() }.orEmpty()
            "ele_xpath" -> JsonArray(elements[key(1)]?.let { xpath(it, s(0)) }.orEmpty().map { JsonPrimitive(it) }).toString()

            "pref_get" -> preferenceValue(s(0)).toString()
            "pref_getString" -> preferences().getString(STRING_PREFIX + s(0), s(1)) ?: s(1)
            "pref_setString" -> { preferences().edit().putString(STRING_PREFIX + s(0), s(1)).apply(); true }

            "parseDates" -> JsonArray(DateParsing.parseDates(args.getOrNull(0) as? JsonArray ?: JsonArray(emptyList()), s(1), s(2)).map { JsonPrimitive(it) }).toString()
            "cryptoHandler" -> Crypto.cryptoHandler(s(0), s(1), s(2), args.getOrNull(3)?.jsonPrimitive?.booleanOrNull ?: false)
            "encryptAESCryptoJS" -> Crypto.encryptCryptoJs(s(0), s(1))
            "decryptAESCryptoJS" -> Crypto.decryptCryptoJs(s(0), s(1))
            "decryptAESGCM" -> Crypto.decryptGcm(s(0), s(1), s(2), s(3))
            "unpackJs" -> Unpacker.unpack(s(0)) ?: ""
            "unpackJsAndCombine" -> Unpacker.unpackAndCombine(s(0)) ?: ""
            "deobfuscateJsPassword" -> throw MangayomiSourceException("$sourceName uses deobfuscateJsPassword, which DebridOne's Mangayomi bridge does not have.")
            "evaluateJavascriptViaWebview" -> throw MangayomiSourceException("$sourceName needs a web browser to run part of its code (evaluateJavascriptViaWebview); the manga server has none.")
            "unsupported" -> throw MangayomiSourceException("$sourceName uses ${s(0)}, which is for anime or novels and not part of Manga.")
            else -> throw MangayomiSourceException("$sourceName asked for \"$name\", which DebridOne's Mangayomi bridge does not know.")
        }
    }

    // ---- HTTP -------------------------------------------------------------------------------------

    private fun http(method: String, args: JsonArray): String {
        val options = args.getOrNull(1) as? JsonObject
        val url = args.getOrNull(2)?.jsonPrimitive?.contentOrNull ?: throw MangayomiSourceException("$sourceName made a request without an address.")
        val headers = (args.getOrNull(3) as? JsonObject)?.mapValues { (_, v) -> if (v is JsonPrimitive) v.contentOrNull.orEmpty() else v.toString() }.orEmpty()
        val body = args.getOrNull(4)
        val request = Request.Builder().url(url)
        // The source's own headers (getHeaders) apply unless the request names its own.
        val merged = LinkedHashMap<String, String>()
        defaultHeaders().forEach { (k, v) -> merged[k] = v }
        headers.forEach { (k, v) -> merged.keys.firstOrNull { it.equals(k, ignoreCase = true) }?.let { merged.remove(it) }; merged[k] = v }
        merged.forEach { (k, v) -> runCatching { request.header(k, v) } }
        val contentType = merged.entries.firstOrNull { it.key.equals("Content-Type", ignoreCase = true) }?.value
        val requestBody = when {
            method == "GET" || method == "HEAD" -> null
            body == null || body is JsonNull -> ByteArray(0).toRequestBody(contentType?.toMediaTypeOrNull())
            contentType?.contains("json", ignoreCase = true) == true -> body.toString().toRequestBody(contentType.toMediaTypeOrNull())
            body is JsonPrimitive -> (body.contentOrNull ?: "").toRequestBody((contentType ?: "text/plain; charset=utf-8").toMediaTypeOrNull())
            body is JsonObject -> FormBody.Builder().apply { body.forEach { (k, v) -> add(k, if (v is JsonPrimitive) v.contentOrNull.orEmpty() else v.toString()) } }.build()
            body is JsonArray -> ByteArray(body.size) { body[it].jsonPrimitive.intOrNull?.toByte() ?: 0 }.toRequestBody(contentType?.toMediaTypeOrNull())
            else -> body.toString().toRequestBody(contentType?.toMediaTypeOrNull())
        }
        request.method(method, requestBody)
        var http = client()
        if (options?.get("followRedirects")?.jsonPrimitive?.booleanOrNull == false) http = http.newBuilder().followRedirects(false).followSslRedirects(false).build()
        val response = try {
            http.newCall(request.build()).execute()
        } catch (failure: IOException) {
            networkFailure = failure
            throw failure
        }
        return response.use {
            val text = if (method == "HEAD") "" else try {
                it.body?.string().orEmpty()
            } catch (failure: IOException) {
                networkFailure = failure
                throw failure
            }
            if ((it.code == 403 || it.code == 503 || it.code == 429) && (it.header("cf-mitigated") != null ||
                    (it.header("Server").orEmpty().contains("cloudflare", ignoreCase = true) && BROWSER_CHECK.any { marker -> text.contains(marker, ignoreCase = true) }) ||
                    text.contains("ddos-guard", ignoreCase = true))
            ) {
                browserCheck = it.request.url.host
            }
            val responseHeaders = JsonObject(it.headers.names().associate { name -> name.lowercase() to JsonPrimitive(it.headers.values(name).joinToString(",")) })
            val sent = JsonObject(it.request.headers.names().associate { name -> name.lowercase() to JsonPrimitive(it.request.headers[name].orEmpty()) })
            JsonObject(
                mapOf(
                    "body" to JsonPrimitive(text),
                    "headers" to responseHeaders,
                    "isRedirect" to JsonPrimitive(it.isRedirect),
                    "persistentConnection" to JsonPrimitive(true),
                    "reasonPhrase" to JsonPrimitive(it.message),
                    "statusCode" to JsonPrimitive(it.code),
                    "request" to JsonObject(
                        mapOf(
                            "contentLength" to JsonPrimitive(requestBody?.contentLength() ?: 0),
                            "finalized" to JsonPrimitive(true),
                            "followRedirects" to JsonPrimitive(http.followRedirects),
                            "headers" to sent,
                            "maxRedirects" to JsonPrimitive(5),
                            "method" to JsonPrimitive(method),
                            "persistentConnection" to JsonPrimitive(true),
                            "url" to JsonPrimitive(it.request.url.toString()),
                        ),
                    ),
                ),
            ).toString()
        }
    }

    // ---- HTML -------------------------------------------------------------------------------------

    private fun document(html: String): Document = parsed.getOrPut(html) {
        Jsoup.parse(html).also { it.outputSettings().prettyPrint(false) }
    }

    private fun register(element: Element?): Int {
        val key = ++nextKey
        elements[key] = element
        return key
    }

    private fun keys(found: List<Element>): String = JsonArray(found.map { JsonPrimitive(register(it)) }).toString()

    private fun select(root: Element, selector: String): List<Element> = try {
        root.select(Selectors.likeMangayomi(selector)).toList()
    } catch (_: Exception) {
        // Mangayomi answers a selector it cannot read with nothing, not with an error.
        emptyList()
    }

    private fun listBy(root: Element, type: String, name: String): List<Element> = when (type) {
        "children" -> root.children().toList()
        "getElementsByTagName" -> root.getElementsByTag(name).filter { it !== root }
        else -> root.getElementsByClass(name).filter { it !== root }
    }

    private fun elementString(type: String, element: Element?): String {
        if (element == null) return ""
        return when (type) {
            "text" -> element.wholeText()
            "innerHtml" -> element.html()
            "outerHtml" -> element.outerHtml()
            "className" -> element.className()
            "localName" -> element.tagName().lowercase()
            "namespaceUri" -> "http://www.w3.org/1999/xhtml"
            "getSrc" -> attributeIn(element.outerHtml(), "src")
            "getImg" -> attributeIn(element.outerHtml(), "img")
            "getHref" -> attributeIn(element.outerHtml(), "href")
            else -> attributeIn(element.outerHtml(), "data-src")
        }
    }

    /**
     * Mangayomi's getSrc / getHref / getImg / getDataSrc: the first `name="…"` anywhere in the
     * element's HTML, exactly as its regular expression finds it. So getSrc also finds a
     * `data-src="…"` (a picture loaded lazily), which sources count on.
     */
    private fun attributeIn(html: String, name: String): String =
        Regex(Regex.escape(name) + "=\"([^\"]+)\"").find(html)?.groupValues?.get(1).orEmpty()

    /** XPath as Mangayomi's sources use it: elements, their text (`text()`) or an attribute (`@name`). */
    private fun xpath(root: Element, expression: String): List<String> {
        val attribute = Regex("/@([\\w:-]+)$").find(expression)?.groupValues?.get(1)
        val text = expression.endsWith("/text()")
        val path = when {
            attribute != null -> expression.substringBeforeLast("/@")
            text -> expression.removeSuffix("/text()")
            else -> expression
        }
        val found = try {
            root.selectXpath(path).toList()
        } catch (_: Exception) {
            return emptyList()
        }
        return found.map { if (attribute != null) it.attr(attribute) else it.wholeText().trim() }
    }

    // ---- settings ---------------------------------------------------------------------------------

    /**
     * A setting's current value, the way Mangayomi gives it: a list's chosen value, a switch's
     * state, a text, or a multiple choice's values. What the person changed is kept by the manga
     * server; until then the source's own default applies.
     */
    private fun preferenceValue(key: String): JsonElement {
        val prefs = preferences()
        val definition = preferenceDefinitions().firstOrNull { it.jsonObject["key"]?.jsonPrimitive?.contentOrNull == key }?.jsonObject
            ?: return prefs.getString(key, null)?.let { JsonPrimitive(it) } ?: JsonNull
        definition["listPreference"]?.jsonObject?.let { list ->
            val values = list["entryValues"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()
            val default = values.getOrNull(list["valueIndex"]?.jsonPrimitive?.intOrNull ?: 0) ?: values.firstOrNull().orEmpty()
            return JsonPrimitive(prefs.getString(key, default) ?: default)
        }
        (definition["switchPreferenceCompat"] ?: definition["checkBoxPreference"])?.jsonObject?.let { switch ->
            return JsonPrimitive(prefs.getBoolean(key, switch["value"]?.jsonPrimitive?.booleanOrNull ?: false))
        }
        definition["editTextPreference"]?.jsonObject?.let { edit ->
            val default = edit["value"]?.jsonPrimitive?.contentOrNull ?: edit["text"]?.jsonPrimitive?.contentOrNull ?: ""
            return JsonPrimitive(prefs.getString(key, default) ?: default)
        }
        definition["multiSelectListPreference"]?.jsonObject?.let { multi ->
            val default = multi["values"]?.jsonArray?.map { it.jsonPrimitive.content }?.toSet().orEmpty()
            return JsonArray((prefs.getStringSet(key, default) ?: default).map { JsonPrimitive(it) })
        }
        return JsonNull
    }

    /**
     * Selectors as Mangayomi reads them. Its selector library (pseudom 1.0.1) matches a class
     * selector `.name` as the regular expression `\bname\b` over the whole class attribute, which
     * is looser than CSS: `.lg` matches `lg:grid-cols-5`. Sources are written against that, so each
     * class selector becomes jsoup's `[class~=\bname\b]`. Text inside quotes, attribute selectors
     * and text pseudo-classes (`:contains(1.5)`) is left as it is.
     */
    object Selectors {
        private val cache = java.util.concurrent.ConcurrentHashMap<String, String>()
        private val TEXT_PSEUDOS = setOf("contains", "containsown", "matches", "matchesown", "containsdata", "containswholetext", "containswholeownText".lowercase(), "matcheswholetext", "matcheswholeowntext")

        fun likeMangayomi(selector: String): String = cache.getOrPut(selector) { rewrite(selector) }

        private fun rewrite(selector: String): String {
            val out = StringBuilder(selector.length + 16)
            var i = 0
            var quote: Char? = null
            var bracket = 0
            val parens = ArrayList<Boolean>() // for each open parenthesis: is its content plain text?
            while (i < selector.length) {
                val c = selector[i]
                when {
                    quote != null -> { out.append(c); if (c == '\\' && i + 1 < selector.length) { out.append(selector[i + 1]); i++ } else if (c == quote) quote = null }
                    c == '"' || c == '\'' -> { quote = c; out.append(c) }
                    c == '[' -> { bracket++; out.append(c) }
                    c == ']' -> { bracket = maxOf(0, bracket - 1); out.append(c) }
                    c == '(' -> {
                        val pseudo = Regex(":([\\w-]+)$").find(out)?.groupValues?.get(1)?.lowercase()
                        parens += pseudo != null && pseudo in TEXT_PSEUDOS
                        out.append(c)
                    }
                    c == ')' -> { if (parens.isNotEmpty()) parens.removeAt(parens.size - 1); out.append(c) }
                    c == '.' && bracket == 0 && parens.none { it } && i + 1 < selector.length && (selector[i + 1].isLetter() || selector[i + 1] == '_' || selector[i + 1] == '-' || selector[i + 1] == '\\') -> {
                        var end = i + 1
                        val name = StringBuilder()
                        while (end < selector.length) {
                            val ch = selector[end]
                            if (ch == '\\' && end + 1 < selector.length) { name.append(selector[end + 1]); end += 2; continue }
                            if (ch.isLetterOrDigit() || ch == '-' || ch == '_') { name.append(ch); end++ } else break
                        }
                        out.append("[class~=\\b").append(java.util.regex.Pattern.quote(name.toString()).replace("]", "\\]")).append("\\b]")
                        i = end
                        continue
                    }
                    else -> out.append(c)
                }
                i++
            }
            return out.toString()
        }
    }

    companion object {
        private val BROWSER_CHECK = listOf("just a moment", "challenge-platform", "cf-browser-verification", "cf_chl_", "attention required")

        /** Where getString/setString keep their values among the source's settings. */
        const val STRING_PREFIX = "debridone.mangayomi.string."
    }
}
