package com.zeeload.car

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkerParameters
import androidx.work.WorkManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.net.URL
import java.util.UUID
import java.util.concurrent.TimeUnit

class Diagnostics(
    private val context: Context,
    private val db: CarDatabase,
    private val settings: Settings
) {
    private val scope = CoroutineScope(Dispatchers.IO)
    private val sessionId = UUID.randomUUID().toString()

    fun log(
        stage: String, message: String, packageName: String = "", url: String = "",
        versionCode: Long = 0, httpStatus: Int = 0, errorCode: String = "",
        level: String = "INFO"
    ) {
        val bytes = listOf(stage, message, packageName, url, errorCode).sumOf {
            it.toByteArray(Charsets.UTF_8).size
        } + 256
        scope.launch {
            db.events().add(
                DiagnosticEvent(
                    ts = System.currentTimeMillis(), level = level, stage = stage,
                    packageName = packageName, versionCode = versionCode, url = url,
                    httpStatus = httpStatus, errorCode = errorCode, message = message,
                    sizeBytes = bytes
                )
            )
            trim()
            scheduleOnce()
        }
    }

    fun trimAsync() { scope.launch { trim() } }

    private suspend fun trim() {
        val events = db.events()
        events.deleteBefore(System.currentTimeMillis() - 7L * 24 * 60 * 60 * 1000)
        var size = events.totalBytes()
        while (size > 20L * 1024 * 1024) {
            val id = events.oldestId() ?: break
            events.delete(id)
            size = events.totalBytes()
        }
    }

    fun scheduleOnce() {
        if (!settings.remoteEnabled || settings.logUrl.isBlank()) return
        val delay = if (settings.debugUntil > System.currentTimeMillis()) 5L else 30L
        val request = OneTimeWorkRequestBuilder<LogUploadWorker>()
            .setInitialDelay(delay, TimeUnit.SECONDS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork("upload-on-event", ExistingWorkPolicy.KEEP, request)
    }

    fun schedulePeriodic() {
        val request = PeriodicWorkRequestBuilder<LogUploadWorker>(15, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork("upload-periodic", ExistingPeriodicWorkPolicy.KEEP, request)
    }

    fun stopRemote() {
        WorkManager.getInstance(context).cancelUniqueWork("upload-on-event")
        settings.remoteEnabled = false
        settings.debugUntil = 0
    }

    suspend fun clear() { db.events().clear() }

    fun sessionId(): String = sessionId
}

class LogUploadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as CarApp
        val settings = app.services.settings
        if (!settings.remoteEnabled || settings.logUrl.isBlank()) return Result.success()
        val url = try { URL(settings.logUrl) } catch (_: Exception) {
            settings.lastUpload = "日志地址无效"
            return Result.failure()
        }
        if (url.protocol != "http" && url.protocol != "https") {
            settings.lastUpload = "仅支持 HTTP/HTTPS"
            return Result.failure()
        }
        repeat(20) {
            if (!settings.remoteEnabled) return Result.success()
            val events = app.services.db.events().pending(50)
            if (events.isEmpty()) return Result.success()
            val body = JSONObject()
                .put("deviceId", settings.deviceId)
                .put("sessionId", app.services.logs.sessionId())
                .put("events", JSONArray().apply {
                    events.forEach { event ->
                        put(JSONObject()
                            .put("ts", event.ts).put("level", event.level)
                            .put("stage", event.stage).put("packageName", event.packageName)
                            .put("versionCode", event.versionCode).put("url", event.url)
                            .put("httpStatus", event.httpStatus)
                            .put("errorCode", event.errorCode).put("message", event.message))
                    }
                })
            val code = try {
                LogTransport.post(url.toString(), body.toString().toByteArray(Charsets.UTF_8))
            } catch (error: Exception) {
                settings.lastUpload = "上传失败：${error.javaClass.simpleName}"
                return Result.retry()
            }
            settings.lastUpload = "HTTP $code · ${System.currentTimeMillis()}"
            if (code !in 200..299) return if (code >= 500 || code == 429) Result.retry() else Result.failure()
            app.services.db.events().deleteBatch(events.map { it.id })
        }
        return if (app.services.db.events().pendingCount() > 0) Result.retry() else Result.success()
    }
}

class Services(context: Context) {
    val db = CarDatabase.open(context)
    val settings = Settings(context)
    val cache = CacheManager(context, settings)
    val logs = Diagnostics(context, db, settings)
    val downloader = Downloader(cache)
    val packages = Packages(context)
    val installer = Installer(context, db, cache, logs)
}
