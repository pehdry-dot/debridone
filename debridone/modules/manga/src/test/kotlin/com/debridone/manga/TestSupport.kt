package com.debridone.manga

import com.sun.net.httpserver.HttpExchange

fun HttpExchange.reply(status: Int, body: ByteArray, contentType: String = "application/octet-stream") {
    responseHeaders.add("Content-Type", contentType)
    sendResponseHeaders(status, if (body.isEmpty()) -1 else body.size.toLong())
    if (body.isNotEmpty()) responseBody.use { it.write(body) } else close()
}
