package com.debridone.mangayomi.bridge

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assume.assumeTrue
import java.io.File
import kotlin.test.Test

/**
 * Runs real Mangayomi JavaScript sources against their real sites: list, open the first series,
 * list a chapter's pages. Only with DEBRIDONE_LIVE_TESTS=1 and DEBRIDONE_MANGAYOMI_SOURCES set to a
 * folder holding index files (`*.json`, as the indexes publish them) and the sources' code
 * (`<name>.js`, as `sourceCodeUrl` names it). Nothing here names a site: whoever runs it chooses.
 * It reports what each source did; a site that is down or has changed is reported, not failed.
 */
class MangayomiLiveTest {
    @Test
    fun realSourcesListOpenAndPage() {
        assumeTrue(System.getenv("DEBRIDONE_LIVE_TESTS") == "1")
        val folder = System.getenv("DEBRIDONE_MANGAYOMI_SOURCES")?.takeIf { it.isNotBlank() }?.let(::File)
        assumeTrue(folder != null && folder.isDirectory)
        val entries = folder!!.listFiles { f -> f.name.endsWith(".json") }!!.flatMap { file ->
            runCatching { Json.parseToJsonElement(file.readText()).jsonArray.map { it.jsonObject } }.getOrDefault(emptyList())
        }.filter { it["sourceCodeLanguage"]?.jsonPrimitive?.intOrNull == 1 && (it["itemType"]?.jsonPrimitive?.intOrNull ?: 0) == 0 }
            // One run per code file; of a source offered in many languages, the English one.
            .sortedBy { if (it["lang"]?.jsonPrimitive?.content == "en") 0 else 1 }
            .distinctBy { it["sourceCodeUrl"]!!.jsonPrimitive.content }
        println("== ${entries.size} JavaScript manga sources")
        for (entry in entries) {
            val name = entry["name"]!!.jsonPrimitive.content
            val code = File(folder, entry["sourceCodeUrl"]!!.jsonPrimitive.content.substringAfterLast('/')).takeIf { it.isFile }?.readText()
            if (code == null) { println("-- $name: no code file"); continue }
            val report = StringBuilder("-- $name (${entry["lang"]!!.jsonPrimitive.content}): ")
            try {
                LoadedSource(code, entry).use { source ->
                    val started = System.currentTimeMillis()
                    val list = source.call("getPopular(1)").jsonObject["list"]!!.jsonArray.map { it.jsonObject }
                    report.append("popular ${list.size}")
                    val first = list.firstOrNull() ?: return@use
                    report.append(" (\"${first["name"]?.jsonPrimitive?.content?.take(40)}\")")
                    val detail = source.call("getDetail(${Json.encodeToString(kotlinx.serialization.json.JsonElement.serializer(), first["link"]!!)})").jsonObject
                    val chapters = (detail["chapters"] ?: detail["episodes"])?.jsonArray.orEmpty().map { it.jsonObject }
                    report.append(", detail ok, ${chapters.size} chapters")
                    val chapter = chapters.lastOrNull() ?: return@use
                    val pages = source.call("getPageList(${Json.encodeToString(kotlinx.serialization.json.JsonElement.serializer(), chapter["url"]!!)})").jsonArray
                    report.append(", ${pages.size} pages; first: ${pages.firstOrNull()?.let { (it as? JsonObject)?.get("url") ?: it }?.toString()?.take(90)}")
                    report.append(" · ${System.currentTimeMillis() - started} ms")
                }
            } catch (failure: Throwable) {
                report.append(" FAILED: ${failure.javaClass.simpleName}: ${failure.message?.take(300)}")
            }
            println(report)
        }
    }
}
