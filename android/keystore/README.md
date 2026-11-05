# 发布签名（刻意入库）

`zeeload-release.jks` 是本项目的**正式签名密钥**，连同口令一起提交在本仓库里，这是有意为之：

- 车机只允许**升级安装**，不允许重装。签名一旦变化，新车机版本会 `INSTALL_FAILED_UPDATE_INCOMPATIBLE`，
  用户只能卸载重装（丢应用内历史与日志）。因此签名材料不能“存在某台机器上忘了备份”。
- 本仓库是私有仓库，签名与口令放在这里，等于给发布链路留了一份可追溯的副本。

| 项 | 值 |
|---|---|
| keystore | `android/keystore/zeeload-release.jks`（PKCS12） |
| alias | `zeeload` |
| 口令 / key 口令 | 见 `android/keystore.properties` |
| 密钥 | RSA 4096，有效期 10950 天 |
| 证书 SHA-256 | `9995a84962b6ea70651fe17adc60ecea43a38a4fb0436588dcad78877aabcb6d` |
| 签名方案 | v1 + v2 + v3（AGP 默认出 v3；发布脚本用 apksigner 全量重签） |

打包：

```bash
cd android
./gradlew :app:assembleRelease -x lintVitalAnalyzeRelease -x lintVitalRelease --offline
# 再用 build-tools 里的 apksigner 显式重签（v1/v2/v3 全开），产物放仓库 release/
```

> 提醒：本文件包含私钥口令，仓库若改为公开或加了外部协作者，请先把 `android/keystore*` 移出仓库。
