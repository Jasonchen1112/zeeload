package com.zeeload.car

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket
import kotlin.concurrent.thread

class LogTransportTest {
    @Test fun postsJsonAndReturnsHttpStatus() {
        ServerSocket(0).use { server ->
            val observed = mutableListOf<String>()
            val worker = thread {
                server.accept().use { socket ->
                    val input = socket.getInputStream().bufferedReader()
                    val headers = mutableListOf<String>()
                    while (true) {
                        val line = input.readLine() ?: break
                        if (line.isEmpty()) break
                        headers += line
                    }
                    val length = headers.first { it.startsWith("Content-Length:", true) }
                        .substringAfter(':').trim().toInt()
                    val chars = CharArray(length)
                    var read = 0
                    while (read < length) {
                        val count = input.read(chars, read, length - read)
                        if (count < 0) break
                        read += count
                    }
                    observed += headers.joinToString("\n")
                    observed += String(chars, 0, read)
                    socket.getOutputStream().write(
                        "HTTP/1.1 200 OK\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                            .toByteArray()
                    )
                }
            }
            val json = """{"deviceId":"fixture","events":[{"url":"http://example.test/a.apk?token=x"}]}"""
            assertEquals(200, LogTransport.post("http://127.0.0.1:${server.localPort}/test", json.toByteArray()))
            worker.join(3000)
            assertTrue(observed[0].contains("POST /test HTTP/1.1"))
            assertTrue(observed[0].contains("application/json"))
            assertEquals(json, observed[1])
            assertFalseContains(observed[1], "apkBody")
        }
    }

    private fun assertFalseContains(text: String, value: String) {
        assertTrue(!text.contains(value))
    }
}
