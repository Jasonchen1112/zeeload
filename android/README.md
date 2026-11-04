# ZEEload 车机应用中心

车机端 Android 9+ 应用，用于从 APK URL 下载、检查、安装和更新应用。

## 构建

~~~bash
cd android
cp local.properties.example local.properties
# 将 sdk.dir 改为本机 Android SDK 路径
./gradlew :app:testDebugUnitTest :app:assembleDebug
~~~

本机没有 Gradle wrapper 时，可使用已安装的 Gradle：

~~~bash
gradle :app:testDebugUnitTest :app:assembleDebug
~~~

Debug APK 输出在：

~~~text
app/build/outputs/apk/debug/app-debug.apk
~~~

发布版必须使用稳定的签名密钥。签名配置通过 Gradle properties 提供，密钥文件和密码不提交到仓库：

~~~text
zeeloadKeystore=/absolute/path/zeeload-release.jks
zeeloadStorePassword=...
zeeloadKeyAlias=...
zeeloadKeyPassword=...
~~~

## 功能

- 车机原有应用导入和本 App 安装应用记录；
- APK HTTP/HTTPS 地址下载、断点续传、SHA-256 和包信息解析；
- 同包名更新走 Android PackageInstaller，失败时保留原应用和数据；
- 默认 512 MB APK LRU 缓存，设置页可调整和清理；
- 本地诊断日志最多 20 MB 或 7 天；
- 可配置 HTTP/HTTPS JSON 日志接收地址；
- 远程日志默认关闭，支持批量上传和 30 分钟实时调试；
- 权限状态查看与系统权限设置入口。

~~~text
127.0.0.1 在车机 App 中表示车机本机。Mac 或局域网接收端应填写车机可访问的 IP 或域名。
~~~

## 首次安装

首次将本 App 安装到车机仍使用仓库现有的 Mac/Windows 侧载工具。后续应用下载和更新由车机端应用中心执行。
