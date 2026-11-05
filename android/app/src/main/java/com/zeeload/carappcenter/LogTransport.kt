package com.zeeload.carappcenter

import java.net.HttpURLConnection
import java.net.URL

object LogTransport {
    fun post(address: String, body: ByteArray): Int {
        val url = URL(address)
        require(url.protocol == "http" || url.protocol == "https") { "仅支持 HTTP/HTTPS" }
        val connection = url.openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 10_000
            connection.readTimeout = 15_000
            connection.instanceFollowRedirects = false
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.outputStream.use { it.write(body) }
            return connection.responseCode
        } finally {
            connection.disconnect()
        }
    }
}
