package com.zeeload.car

import android.app.PendingIntent
import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings as AndroidSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File

class Installer(
    private val context: Context,
    private val db: CarDatabase,
    private val cache: CacheManager,
    private val logs: Diagnostics
) {
    fun canInstall(): Boolean = context.packageManager.canRequestPackageInstalls()

    fun openUnknownSources() {
        context.startActivity(Intent(AndroidSettings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
            data = Uri.parse("package:${context.packageName}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    }

    suspend fun install(file: File, apk: ApkInfo, url: String, sha256: String): Int {
        check(canInstall()) { "请先允许此应用安装未知来源应用" }
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            .apply {
                setAppPackageName(apk.packageName)
                setSize(file.length())
                if (Build.VERSION.SDK_INT >= 31) {
                    val policy = context.getSystemService(DevicePolicyManager::class.java)
                    val managed = policy?.isDeviceOwnerApp(context.packageName) == true
                    setRequireUserAction(
                        if (managed) PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED
                        else PackageInstaller.SessionParams.USER_ACTION_REQUIRED
                    )
                }
            }
        val packageInstaller = context.packageManager.packageInstaller
        val sessionId = packageInstaller.createSession(params)
        val session = packageInstaller.openSession(sessionId)
        cache.pin(file)
        try {
            file.inputStream().use { input ->
                session.openWrite("base.apk", 0, file.length()).use { output ->
                    input.copyTo(output, 128 * 1024)
                    session.fsync(output)
                }
            }
            val pending = JSONObject()
                .put("packageName", apk.packageName).put("name", apk.name)
                .put("versionCode", apk.versionCode).put("versionName", apk.versionName)
                .put("certificateSha256", apk.certificateSha256).put("apkSha256", sha256)
                .put("url", url).put("path", file.absolutePath)
                .put("at", System.currentTimeMillis())
            context.getSharedPreferences("install-pending", Context.MODE_PRIVATE)
                .edit().putString(sessionId.toString(), pending.toString()).commit()
            val intent = Intent(context, InstallReceiver::class.java).putExtra("sessionId", sessionId)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            val callback = PendingIntent.getBroadcast(context, sessionId, intent, flags)
            session.commit(callback.intentSender)
            logs.log("install", "安装会话已提交", apk.packageName, url, apk.versionCode)
            return sessionId
        } catch (error: Exception) {
            session.abandon()
            cache.unpin(file)
            cache.trim()
            db.apps().record(
                InstallRecord(packageName = apk.packageName, at = System.currentTimeMillis(),
                    versionCode = apk.versionCode, result = error.message ?: "安装会话失败", apkSha256 = sha256)
            )
            logs.log("install", error.message ?: "安装会话失败", apk.packageName, url,
                apk.versionCode, errorCode = error.javaClass.simpleName, level = "ERROR")
            throw error
        } finally {
            session.close()
        }
    }
}

class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            val confirmation = if (Build.VERSION.SDK_INT >= 33) {
                intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
            }
            confirmation?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (confirmation != null) context.startActivity(confirmation)
            return
        }
        val result = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val app = context.applicationContext as CarApp
                val services = app.services
                val sessionId = intent.getIntExtra("sessionId", -1)
                val prefs = context.getSharedPreferences("install-pending", Context.MODE_PRIVATE)
                val text = prefs.getString(sessionId.toString(), null) ?: return@launch
                val pending = JSONObject(text)
                val name = pending.getString("packageName")
                val url = pending.getString("url")
                val path = File(pending.getString("path"))
                val version = pending.getLong("versionCode")
                val sha = pending.getString("apkSha256")
                val success = status == PackageInstaller.STATUS_SUCCESS
                val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                    ?: if (success) "安装成功" else "安装失败 ($status)"
                val old = services.db.apps().get(name)
                if (success) {
                    val actual = services.packages.find(name)
                    services.db.apps().put(
                        ManagedApp(
                            packageName = name,
                            name = actual?.name ?: pending.getString("name"),
                            url = url,
                            origin = old?.origin ?: "本应用安装",
                            versionCode = actual?.versionCode ?: version,
                            versionName = actual?.versionName ?: pending.getString("versionName"),
                            certificateSha256 = actual?.certificateSha256
                                ?: pending.getString("certificateSha256"),
                            apkSha256 = sha,
                            installedAt = old?.installedAt ?: System.currentTimeMillis(),
                            updatedAt = System.currentTimeMillis(),
                            lastResult = message
                        )
                    )
                    path.delete()
                } else if (old != null) {
                    services.db.apps().put(old.copy(lastResult = message))
                }
                services.db.apps().record(
                    InstallRecord(packageName = name, at = System.currentTimeMillis(),
                        versionCode = version, result = message, apkSha256 = sha)
                )
                services.logs.log("install-result", message, name, url, version,
                    errorCode = status.toString(), level = if (success) "INFO" else "ERROR")
                services.cache.unpin(path)
                services.cache.trim()
                prefs.edit().remove(sessionId.toString()).apply()
            } finally {
                result.finish()
            }
        }
    }
}
