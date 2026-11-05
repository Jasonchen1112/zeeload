package com.zeeload.carappcenter

import android.content.Context
import android.os.StatFs
import java.io.File
import java.security.MessageDigest

class CacheManager internal constructor(
    val directory: File,
    private val limitBytes: () -> Long
) {
    constructor(context: Context, settings: Settings) : this(
        File(context.cacheDir, "apks"),
        { settings.cacheLimitMb * 1024L * 1024L }
    )

    init { directory.mkdirs() }
    private val pinned = mutableMapOf<String, Int>()

    @Synchronized fun pin(file: File) {
        pinned[file.absolutePath] = (pinned[file.absolutePath] ?: 0) + 1
    }
    @Synchronized fun unpin(file: File) {
        val path = file.absolutePath
        val count = pinned[path] ?: return
        if (count <= 1) pinned.remove(path) else pinned[path] = count - 1
    }
    @Synchronized fun isPinned(file: File) = (pinned[file.absolutePath] ?: 0) > 0

    fun partFor(url: String): File = File(directory, key(url) + ".part")
    fun apkFor(url: String): File = File(directory, key(url) + ".apk")
    fun sizeBytes(): Long = directory.listFiles()?.sumOf { it.length() } ?: 0

    fun enoughSpace(expectedBytes: Long): Boolean {
        val free = StatFs(directory.path).availableBytes
        return free > expectedBytes + 32L * 1024 * 1024
    }

    @Synchronized fun trim(limit: Long = limitBytes()) {
        var total = sizeBytes()
        val candidates = directory.listFiles()
            ?.filter { !isPinned(it) }
            ?.sortedBy { it.lastModified() }
            ?: emptyList()
        for (file in candidates) {
            if (total <= limit) break
            val length = file.length()
            if (file.delete()) total -= length
        }
    }

    @Synchronized fun clear(): Long {
        var removed = 0L
        directory.listFiles()?.forEach {
            if (!isPinned(it)) {
                val length = it.length()
                if (it.delete()) removed += length
            }
        }
        return removed
    }

    @Synchronized fun cleanStale() {
        val cutoff = System.currentTimeMillis() - 24L * 60 * 60 * 1000
        directory.listFiles()?.forEach {
            if (it.name.endsWith(".part") && it.lastModified() < cutoff && !isPinned(it)) it.delete()
        }
        trim()
    }

    companion object {
        fun key(value: String): String = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}
