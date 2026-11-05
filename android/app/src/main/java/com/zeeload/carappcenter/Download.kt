package com.zeeload.carappcenter

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

data class DownloadedApk(val file: File, val sha256: String, val httpStatus: Int)

class Downloader(private val cache: CacheManager) {
    suspend fun download(address: String, onProgress: (Long, Long) -> Unit): DownloadedApk =
        withContext(Dispatchers.IO) {
            require(address.startsWith("http://") || address.startsWith("https://")) {
                "仅支持 HTTP/HTTPS 地址"
            }
            val part = cache.partFor(address)
            val output = cache.apkFor(address)
            cache.pin(part)
            cache.pin(output)
            cache.trim()
            var completed = false
            try {
                var next = URL(address)
                var connection: HttpURLConnection? = null
                for (hop in 0..5) {
                    val current = next.openConnection() as HttpURLConnection
                    current.connectTimeout = 15_000
                    current.readTimeout = 30_000
                    current.instanceFollowRedirects = false
                    current.setRequestProperty("Accept-Encoding", "identity")
                    if (part.length() > 0) current.setRequestProperty("Range", "bytes=${part.length()}-")
                    val code = current.responseCode
                    if (code in 300..399) {
                        val location = current.getHeaderField("Location")
                            ?: error("重定向缺少 Location")
                        if (hop == 5) error("重定向次数过多")
                        next = URL(next, location)
                        require(next.protocol == "http" || next.protocol == "https") {
                            "不支持该重定向协议"
                        }
                        current.disconnect()
                    } else {
                        connection = current
                        break
                    }
                }
                val active = connection ?: error("下载连接失败")
                try {
                    val code = active.responseCode
                    if (code !in 200..299) error("HTTP $code")
                    val range = active.getHeaderField("Content-Range") ?: ""
                    val resumed = code == 206 && range.startsWith("bytes ${part.length()}-")
                    if (code == 206 && !resumed) error("服务器返回的断点位置不匹配")
                    if (!resumed && part.exists()) part.delete()
                    var received = if (resumed) part.length() else 0L
                    val total = active.contentLengthLong.takeIf { it > 0 }?.plus(received) ?: -1
                    if (total > 0 && !cache.enoughSpace(total - received)) error("存储空间不足")
                    active.inputStream.use { input ->
                        FileOutputStream(part, resumed).use { outputStream ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                coroutineContext.ensureActive()
                                val count = input.read(buffer)
                                if (count < 0) break
                                outputStream.write(buffer, 0, count)
                                received += count
                                if (!cache.enoughSpace(0)) error("存储空间不足")
                                onProgress(received, total)
                            }
                            outputStream.fd.sync()
                        }
                    }
                    if (total > 0 && received != total) error("下载长度不完整")
                    if (output.exists()) output.delete()
                    check(part.renameTo(output)) { "保存 APK 失败" }
                    output.setLastModified(System.currentTimeMillis())
                    val digest = MessageDigest.getInstance("SHA-256")
                    output.inputStream().use { input ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            digest.update(buffer, 0, count)
                        }
                    }
                    completed = true
                    DownloadedApk(output, digest.digest().joinToString("") { "%02x".format(it) }, code)
                } finally {
                    active.disconnect()
                }
            } finally {
                cache.unpin(part)
                if (!completed) cache.unpin(output)
                cache.trim()
            }
        }
}
