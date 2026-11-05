package com.zeeload.carappcenter

import android.content.Context
import java.util.UUID

class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var cacheLimitMb: Int
        get() = prefs.getInt("cacheLimitMb", 512)
        set(value) { prefs.edit().putInt("cacheLimitMb", value.coerceIn(64, 2048)).apply() }

    var logUrl: String
        get() = prefs.getString("logUrl", "") ?: ""
        set(value) { prefs.edit().putString("logUrl", value.trim()).apply() }

    var remoteEnabled: Boolean
        get() = prefs.getBoolean("remoteEnabled", false)
        set(value) { prefs.edit().putBoolean("remoteEnabled", value).apply() }

    var debugUntil: Long
        get() = prefs.getLong("debugUntil", 0)
        set(value) { prefs.edit().putLong("debugUntil", value).apply() }

    var lastUpload: String
        get() = prefs.getString("lastUpload", "尚未上传") ?: "尚未上传"
        set(value) { prefs.edit().putString("lastUpload", value).apply() }

    val deviceId: String
        get() {
            val old = prefs.getString("deviceId", null)
            if (old != null) return old
            return UUID.randomUUID().toString().also { prefs.edit().putString("deviceId", it).apply() }
        }
}
