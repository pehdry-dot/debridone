package com.debridone.mangayomi.bridge

import org.graalvm.polyglot.Context
import org.graalvm.polyglot.Engine
import org.graalvm.polyglot.EnvironmentAccess
import org.graalvm.polyglot.HostAccess
import org.graalvm.polyglot.PolyglotAccess
import org.graalvm.polyglot.PolyglotException
import org.graalvm.polyglot.Source
import org.graalvm.polyglot.io.IOAccess
import org.graalvm.polyglot.proxy.ProxyExecutable

/** The source's code reported an error of its own. [message] is what it said. */
class MangayomiSourceException(message: String) : Exception(message)

/**
 * One Mangayomi source's code, running in a GraalJS context of its own.
 *
 * The context has no access to Java classes, files, processes, threads, the environment or other
 * languages: the code reaches the outside only through [HostCalls] (HTTP through the manga
 * server's own client, HTML parsing, its own settings). Calls are made one at a time.
 */
internal class JsRuntime(private val meta: SourceMeta, private val host: HostCalls) : AutoCloseable {
    private val lock = Any()
    private var depth = 0
    private val context: Context

    init {
        context = Context.newBuilder("js")
            .engine(ENGINE)
            .allowHostAccess(HostAccess.NONE)
            .allowHostClassLookup { false }
            .allowIO(IOAccess.NONE)
            .allowCreateThread(false)
            .allowNativeAccess(false)
            .allowCreateProcess(false)
            .allowEnvironmentAccess(EnvironmentAccess.NONE)
            .allowPolyglotAccess(PolyglotAccess.NONE)
            .build()
        val bindings = context.getBindings("js")
        bindings.putMember("__host", ProxyExecutable { args -> host.call(args[0].asString(), args.getOrNull(1)?.takeIf { !it.isNull }?.asString() ?: "[]") })
        val glue = javaClass.classLoader.getResourceAsStream("debridone-mangayomi/runtime.js")?.use { String(it.readBytes(), Charsets.UTF_8) }
            ?: error("This extension is incomplete: its JavaScript glue is missing.")
        context.eval(Source.newBuilder("js", glue, "debridone-mangayomi/runtime.js").buildLiteral())
        context.eval("js", "const __source = ${meta.sourceObject()};")
        try {
            context.eval(Source.newBuilder("js", meta.code + "\nvar extention = new DefaultExtension();\n", "${meta.name}.js").buildLiteral())
        } catch (failure: PolyglotException) {
            throw MangayomiSourceException("The code of ${meta.name} could not be loaded: ${failure.message}")
        }
    }

    /**
     * Calls `extention.<expression>` (which may be async) and returns its result as JSON.
     * A failure of the network inside the call is rethrown as itself, so the manga server and
     * DebridOne see "No route to host" or "timeout" rather than a JavaScript error.
     */
    fun call(expression: String): String = synchronized(lock) {
        // A call made while another is running (the source reading a setting asks for its
        // definitions) shares the running call's state instead of clearing it.
        val outermost = depth++ == 0
        if (outermost) host.beginCall()
        try {
            context.eval("js", "__call(() => extention.$expression);")
            val bindings = context.getBindings("js")
            if (!bindings.getMember("__done").asBoolean()) {
                throw MangayomiSourceException("${meta.name} is waiting for something this server does not provide (a timer or a web browser).")
            }
            val error = bindings.getMember("__error")
            if (error != null && !error.isNull) {
                host.lastNetworkFailure()?.let { throw it }
                throw MangayomiSourceException(cleanError(error.asString()))
            }
            val result = bindings.getMember("__result").asString()
            // The source met a browser check and, as most do, answered with nothing: that is not "no results".
            if (outermost && host.lastBrowserCheck() != null && looksEmpty(result)) {
                throw java.io.IOException("HTTP error 403: ${host.lastBrowserCheck()} answered with a browser check (Cloudflare or similar) instead of its page")
            }
            result
        } catch (failure: PolyglotException) {
            host.lastNetworkFailure()?.let { throw it }
            throw MangayomiSourceException(cleanError(failure.message.orEmpty()))
        } finally {
            depth--
            if (outermost) host.endCall()
        }
    }

    /** Calls a property or a function of the source that is not async; null when the source does not define it. */
    fun callOptional(expression: String): String? = try {
        call(expression)
    } catch (failure: MangayomiSourceException) {
        if (failure.message.orEmpty().contains("not implemented")) null else throw failure
    }

    override fun close() {
        synchronized(lock) { runCatching { context.close(true) } }
    }

    private fun looksEmpty(result: String): Boolean {
        val trimmed = result.trim()
        return trimmed == "[]" || trimmed == "null" || Regex("\\\"list\\\"\\s*:\\s*\\[\\s*]").containsMatchIn(trimmed) || Regex("\\\"chapters\\\"\\s*:\\s*\\[\\s*]").containsMatchIn(trimmed)
    }

    private fun cleanError(text: String): String {
        val first = text.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }.orEmpty()
        return first.removePrefix("Error: ").removePrefix("TypeError: ").ifBlank { "The source's code failed without saying why." }.take(500)
    }

    companion object {
        /** One engine for the sources of this extension (it keeps parsed code between contexts). */
        private val ENGINE: Engine by lazy {
            Engine.newBuilder("js").option("engine.WarnInterpreterOnly", "false").build()
        }
    }
}
