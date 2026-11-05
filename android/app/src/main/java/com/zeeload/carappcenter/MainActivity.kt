package com.zeeload.carappcenter

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings as AndroidSettings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private data class Candidate(val apk: ApkInfo, val file: File, val sha256: String, val url: String)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val services = (application as CarApp).services
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(Modifier.fillMaxSize()) {
                    CarScreen(
                        services,
                        openPermissions = { name ->
                            startActivity(Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                data = Uri.parse("package:$name")
                            })
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun CarScreen(services: Services, openPermissions: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    val managed by services.db.apps().observe().collectAsState(initial = emptyList())
    val latestResult by services.db.apps().latestRecord().collectAsState(initial = null)
    var selected by remember { mutableStateOf("") }
    var tab by remember { mutableStateOf(0) }
    var address by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("就绪") }
    var busy by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf("") }
    var candidate by remember { mutableStateOf<Candidate?>(null) }
    var activeDownload by remember { mutableStateOf<Job?>(null) }
    var permissionRefresh by remember { mutableStateOf(0) }
    var installed by remember { mutableStateOf(emptyList<InstalledApp>()) }
    var showSystem by remember { mutableStateOf(false) }
    var cacheSize by remember { mutableStateOf(0L) }
    var pendingLogs by remember { mutableStateOf(0) }
    var cacheLimit by remember { mutableStateOf(services.settings.cacheLimitMb.toString()) }
    var logAddress by remember { mutableStateOf(services.settings.logUrl) }
    var remoteEnabled by remember { mutableStateOf(services.settings.remoteEnabled) }
    var debugEnabled by remember { mutableStateOf(services.settings.debugUntil > System.currentTimeMillis()) }

    fun refreshStorage() {
        scope.launch(Dispatchers.IO) {
            val used = services.cache.sizeBytes()
            val pending = services.db.events().pendingCount()
            withContext(Dispatchers.Main) { cacheSize = used; pendingLogs = pending }
        }
    }

    LaunchedEffect(Unit) {
        installed = withContext(Dispatchers.IO) { services.packages.installed() }
        refreshStorage()
    }
    LaunchedEffect(tab) {
        while (tab == 2) {
            debugEnabled = services.settings.debugUntil > System.currentTimeMillis()
            refreshStorage()
            delay(5_000)
        }
    }

    Row(Modifier.fillMaxSize()) {
        Column(Modifier.width(210.dp).fillMaxHeight().padding(12.dp)) {
            Text("ZEEload 应用中心", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(18.dp))
            listOf("我的应用", "导入应用", "设置").forEachIndexed { index, label ->
                TextButton(onClick = { tab = index }, modifier = Modifier.fillMaxWidth()) {
                    Text(if (tab == index) "● $label" else label)
                }
            }
            Spacer(Modifier.weight(1f))
            Text(status, style = MaterialTheme.typography.bodySmall)
            latestResult?.let {
                Spacer(Modifier.height(8.dp))
                Text("最近安装：${it.packageName} · ${it.result}",
                    style = MaterialTheme.typography.bodySmall)
            }
        }
        HorizontalDivider(Modifier.width(1.dp).fillMaxHeight())
        when (tab) {
            0 -> Row(Modifier.fillMaxSize()) {
                LazyColumn(Modifier.weight(0.42f).fillMaxHeight().padding(16.dp)) {
                    item {
                        Text("已管理应用 · ${managed.size}", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(12.dp))
                        Button(onClick = {
                            selected = ""
                            address = ""
                            candidate?.let { services.cache.unpin(it.file) }
                            candidate = null
                        }) { Text("添加 APK 地址") }
                        Spacer(Modifier.height(12.dp))
                    }
                    items(managed, key = { it.packageName }) { record ->
                        TextButton(
                            onClick = {
                                selected = record.packageName
                                address = record.url
                                candidate?.let { services.cache.unpin(it.file) }
                                candidate = null
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(Modifier.fillMaxWidth()) {
                                Text(record.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    "${record.packageName} · ${record.origin}",
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                        HorizontalDivider()
                    }
                }
                HorizontalDivider(Modifier.width(1.dp).fillMaxHeight())
                val record = managed.find { it.packageName == selected }
                val system = installed.any { it.info.packageName == selected && it.system }
                Column(Modifier.weight(0.58f).fillMaxHeight().padding(20.dp)) {
                    Text(record?.name ?: "通过地址安装", style = MaterialTheme.typography.titleLarge)
                    if (record != null) {
                        Text("${record.packageName} · ${record.versionName} (${record.versionCode})")
                        Text("来源：${record.origin} · 最近结果：${record.lastResult.ifBlank { "无" }}")
                    }
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        address, { address = it }, label = { Text("APK 在线地址（HTTP/HTTPS）") },
                        modifier = Modifier.fillMaxWidth(), singleLine = true
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            enabled = !busy && !system && address.isNotBlank(),
                            onClick = {
                                candidate?.let { services.cache.unpin(it.file) }
                                candidate = null
                                busy = true
                                progress = "连接中"
                                activeDownload = scope.launch {
                                    var downloadedFile: File? = null
                                    try {
                                        if (record != null && record.url != address) {
                                            withContext(Dispatchers.IO) {
                                                services.db.apps().put(record.copy(url = address))
                                            }
                                        }
                                        val download = services.downloader.download(address) { done, total ->
                                            scope.launch(Dispatchers.Main) {
                                                progress = if (total > 0) "${done / 1024} / ${total / 1024} KB"
                                                else "${done / 1024} KB"
                                            }
                                        }
                                        downloadedFile = download.file
                                        val apk = withContext(Dispatchers.IO) {
                                            services.packages.fromArchive(download.file)
                                        } ?: error("文件不是可解析的单体 APK")
                                        val current = withContext(Dispatchers.IO) { services.packages.find(apk.packageName) }
                                        if (selected.isNotBlank() && selected != apk.packageName) {
                                            services.cache.unpin(download.file)
                                            error("包名与所选应用不同：${apk.packageName}")
                                        }
                                        if (current != null && apk.versionCode <= current.versionCode) {
                                            services.cache.unpin(download.file)
                                            error("版本未提高：已安装 ${current.versionCode}，下载 ${apk.versionCode}")
                                        }
                                        services.cache.pin(download.file)
                                        candidate = Candidate(apk, download.file, download.sha256, address)
                                        status = "已检查：${apk.name} ${apk.versionName}，等待安装确认"
                                        services.logs.log("apk-checked", status, apk.packageName, address,
                                            apk.versionCode, download.httpStatus)
                                    } catch (_: CancellationException) {
                                        status = "下载已取消；可再次检查以续传"
                                    } catch (error: Exception) {
                                        status = error.message ?: "检查更新失败"
                                        services.logs.log("download", status, selected, address,
                                            errorCode = error.javaClass.simpleName, level = "ERROR")
                                    } finally {
                                        if (candidate == null) downloadedFile?.let {
                                            services.cache.unpin(it)
                                            services.cache.trim()
                                        }
                                        busy = false
                                        activeDownload = null
                                        refreshStorage()
                                    }
                                }
                            }
                        ) { Text(if (record == null) "检查 APK" else "检查更新") }
                        if (activeDownload != null) {
                            OutlinedButton(onClick = { activeDownload?.cancel() }) { Text("取消下载") }
                        }
                        if (candidate != null) {
                            Button(enabled = !busy, onClick = {
                                val ready = candidate ?: return@Button
                                if (!services.installer.canInstall()) {
                                    status = "请先允许安装未知来源应用，然后返回点安装"
                                    services.installer.openUnknownSources()
                                } else {
                                    busy = true
                                    scope.launch {
                                        try {
                                            val id = withContext(Dispatchers.IO) {
                                                services.installer.install(
                                                    ready.file, ready.apk, ready.url, ready.sha256
                                                )
                                            }
                                            status = "安装会话 $id 已提交；等待系统结果"
                                            services.cache.unpin(ready.file)
                                            candidate = null
                                        } catch (error: Exception) {
                                            status = error.message ?: "提交安装失败"
                                        } finally { busy = false }
                                    }
                                }
                            }) { Text("安装") }
                        }
                    }
                    if (busy) Text("下载进度：$progress")
                    candidate?.let {
                        Spacer(Modifier.height(12.dp))
                        Text("包名：${it.apk.packageName}")
                        Text("版本：${it.apk.versionName} (${it.apk.versionCode})")
                        Text("SHA-256：${it.sha256}", style = MaterialTheme.typography.bodySmall)
                        Text("签名证书：${it.apk.certificateSha256.ifBlank { "未读取" }}",
                            style = MaterialTheme.typography.bodySmall)
                    }
                    if (record != null) {
                        Spacer(Modifier.height(20.dp))
                        Text("权限与功能", style = MaterialTheme.typography.titleMedium)
                        OutlinedButton(onClick = { openPermissions(record.packageName) }) {
                            Text("打开系统权限设置")
                        }
                        OutlinedButton(onClick = { permissionRefresh++ }) { Text("刷新权限状态") }
                        val permissions = remember(record.packageName, permissionRefresh) {
                            services.packages.permissions(record.packageName)
                        }
                        LazyColumn {
                            items(permissions) { permission ->
                                Text(
                                    "${permission.name.substringAfterLast('.')} · ${permission.status}",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    }
                }
            }
            1 -> Column(Modifier.fillMaxSize().padding(20.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("导入车机原有应用", style = MaterialTheme.typography.titleLarge)
                    TextButton(onClick = { showSystem = !showSystem }) {
                        Text(if (showSystem) "隐藏系统应用" else "显示系统应用")
                    }
                }
                Text("系统应用仅供查看；更新和权限操作由车机系统控制。")
                Spacer(Modifier.height(12.dp))
                LazyColumn {
                    items(installed.filter { showSystem || !it.system }, key = { it.info.packageName }) { item ->
                        if (managed.none { it.packageName == item.info.packageName }) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Column(Modifier.weight(1f)) {
                                    Text(item.info.name)
                                    Text(
                                        "${item.info.packageName} · ${item.info.versionName}",
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                                if (!item.system) {
                                    OutlinedButton(onClick = {
                                        scope.launch(Dispatchers.IO) {
                                            services.db.apps().put(
                                                ManagedApp(
                                                    packageName = item.info.packageName,
                                                    name = item.info.name,
                                                    origin = "车机原有",
                                                    versionCode = item.info.versionCode,
                                                    versionName = item.info.versionName,
                                                    certificateSha256 = item.info.certificateSha256,
                                                    installedAt = System.currentTimeMillis()
                                                )
                                            )
                                            services.logs.log(
                                                "import", "导入现有应用", item.info.packageName,
                                                versionCode = item.info.versionCode
                                            )
                                        }
                                    }) { Text("导入") }
                                }
                            }
                            HorizontalDivider()
                        }
                    }
                }
            }
            else -> Column(Modifier.fillMaxSize().padding(20.dp)) {
                Text("存储与诊断", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(14.dp))
                Text("APK 缓存：${cacheSize / 1048576} MB · 待上传事件：$pendingLogs")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        cacheLimit, { cacheLimit = it }, label = { Text("缓存上限 MB（64-2048）") },
                        modifier = Modifier.width(240.dp), singleLine = true
                    )
                    Button(onClick = {
                        services.settings.cacheLimitMb = cacheLimit.toIntOrNull() ?: 512
                        services.cache.trim()
                        refreshStorage()
                    }) { Text("保存上限") }
                    OutlinedButton(onClick = {
                        val removed = services.cache.clear()
                        status = "已清理 ${removed / 1048576} MB"
                        refreshStorage()
                    }) { Text("清理缓存") }
                }
                Spacer(Modifier.height(20.dp))
                OutlinedTextField(
                    logAddress, { logAddress = it }, label = { Text("日志接收地址（HTTP/HTTPS）") },
                    modifier = Modifier.fillMaxWidth(), singleLine = true
                )
                Text("127.0.0.1 是车机本机。完整 URL 可能包含访问参数。")
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = {
                        if (logAddress.isBlank() || !(logAddress.startsWith("http://")
                                    || logAddress.startsWith("https://"))) {
                            status = "请输入有效的 HTTP/HTTPS 地址"
                        } else {
                            services.settings.logUrl = logAddress
                            status = "日志地址已保存"
                            services.logs.scheduleOnce()
                        }
                    }) { Text("保存地址") }
                    Text("远程推送")
                    Switch(checked = remoteEnabled, onCheckedChange = {
                        if (it && (services.settings.logUrl.isBlank())) {
                            status = "请先保存日志接收地址"
                        } else if (it) {
                            remoteEnabled = true
                            services.settings.remoteEnabled = true
                            services.logs.scheduleOnce()
                        } else {
                            remoteEnabled = false
                            services.logs.stopRemote()
                            debugEnabled = false
                        }
                    })
                    Text("实时调试 30 分钟")
                    Switch(checked = debugEnabled, enabled = remoteEnabled, onCheckedChange = {
                        debugEnabled = it
                        services.settings.debugUntil =
                            if (it) System.currentTimeMillis() + 30L * 60 * 1000 else 0
                        services.logs.scheduleOnce()
                    })
                }
                Text("最近上传：${services.settings.lastUpload}")
                OutlinedButton(onClick = {
                    scope.launch(Dispatchers.IO) {
                        services.logs.clear()
                        withContext(Dispatchers.Main) {
                            status = "本地诊断日志已清空"
                            refreshStorage()
                        }
                    }
                }) { Text("清空本地日志") }
            }
        }
    }
}
