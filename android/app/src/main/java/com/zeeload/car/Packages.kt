package com.zeeload.car

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import java.io.File
import java.security.MessageDigest

data class ApkInfo(
    val packageName: String,
    val name: String,
    val versionCode: Long,
    val versionName: String,
    val certificateSha256: String
)

data class InstalledApp(val info: ApkInfo, val system: Boolean)

data class PermissionState(val name: String, val status: String)

class Packages(private val context: Context) {
    private val pm = context.packageManager

    @Suppress("DEPRECATION")
    fun installed(): List<InstalledApp> =
        pm.getInstalledPackages(PackageManager.GET_PERMISSIONS or PackageManager.GET_SIGNING_CERTIFICATES)
            .mapNotNull { pkg ->
                val appInfo = pkg.applicationInfo ?: return@mapNotNull null
                InstalledApp(toInfo(pkg), appInfo.flags and ApplicationInfo.FLAG_SYSTEM != 0)
            }.sortedBy { it.info.name.lowercase() }

    @Suppress("DEPRECATION")
    fun find(name: String): ApkInfo? = try {
        toInfo(pm.getPackageInfo(name, PackageManager.GET_SIGNING_CERTIFICATES))
    } catch (_: PackageManager.NameNotFoundException) {
        null
    }

    @Suppress("DEPRECATION")
    fun fromArchive(file: File): ApkInfo? {
        val pkg = pm.getPackageArchiveInfo(file.absolutePath, PackageManager.GET_SIGNING_CERTIFICATES)
            ?: return null
        pkg.applicationInfo?.sourceDir = file.absolutePath
        pkg.applicationInfo?.publicSourceDir = file.absolutePath
        return toInfo(pkg)
    }

    @Suppress("DEPRECATION")
    private fun toInfo(pkg: PackageInfo): ApkInfo {
        val name = try {
            pkg.applicationInfo?.loadLabel(pm)?.toString()?.ifBlank { pkg.packageName } ?: pkg.packageName
        } catch (_: Exception) { pkg.packageName }
        val signer = pkg.signingInfo?.apkContentsSigners?.firstOrNull()?.toByteArray()
        val fingerprint = signer?.let {
            MessageDigest.getInstance("SHA-256").digest(it).joinToString("") { byte -> "%02x".format(byte) }
        } ?: ""
        return ApkInfo(
            pkg.packageName, name,
            if (Build.VERSION.SDK_INT >= 28) pkg.longVersionCode else pkg.versionCode.toLong(),
            pkg.versionName ?: "", fingerprint
        )
    }

    @Suppress("DEPRECATION")
    fun permissions(name: String): List<PermissionState> {
        val pkg = try {
            pm.getPackageInfo(name, PackageManager.GET_PERMISSIONS)
        } catch (_: PackageManager.NameNotFoundException) { return emptyList() }
        return pkg.requestedPermissions?.map { permission ->
            val info = try { pm.getPermissionInfo(permission, 0) } catch (_: Exception) { null }
            val type = info?.protectionLevel?.and(PermissionInfoMask) ?: 0
            val granted = pm.checkPermission(permission, name) == PackageManager.PERMISSION_GRANTED
            val status = when {
                granted -> "已授予"
                type == android.content.pm.PermissionInfo.PROTECTION_DANGEROUS -> "待用户授权"
                type == android.content.pm.PermissionInfo.PROTECTION_SIGNATURE -> "签名/系统限制"
                else -> "需在系统设置中查看"
            }
            PermissionState(permission, status)
        } ?: emptyList()
    }

    companion object {
        private const val PermissionInfoMask = android.content.pm.PermissionInfo.PROTECTION_MASK_BASE
    }
}
