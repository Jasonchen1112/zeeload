package com.zeeload.car

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File

class CarApp : Application() {
    lateinit var services: Services
        private set

    override fun onCreate() {
        super.onCreate()
        services = Services(this)
        getSharedPreferences("install-pending", MODE_PRIVATE).all.values.forEach { value ->
            try {
                val path = JSONObject(value as String).getString("path")
                services.cache.pin(File(path))
            } catch (_: Exception) { }
        }
        services.cache.cleanStale()
        services.logs.trimAsync()
        services.logs.schedulePeriodic()
        CoroutineScope(Dispatchers.IO).launch {
            val packageName = this@CarApp.packageName
            if (services.db.apps().get(packageName) == null) {
                val info = services.packages.find(packageName)
                if (info != null) {
                    services.db.apps().put(
                        ManagedApp(
                            packageName = packageName, name = info.name,
                            origin = "安装器自身", versionCode = info.versionCode,
                            versionName = info.versionName,
                            certificateSha256 = info.certificateSha256,
                            installedAt = System.currentTimeMillis()
                        )
                    )
                }
            }
        }
    }
}
