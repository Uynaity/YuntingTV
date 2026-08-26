# Implement — Android 6 内置 ISRG 根证书

## 执行清单

- [x] 1. 从 macOS 系统钥匙串导出 ISRG Root X1 / X2 PEM，核对 SHA-256 指纹与
      `design.md` 所列一致（已导出至 scratchpad，实施时重新核对一次）
- [x] 2. 新建 `app/src/main/java/cn/radio/tv/data/remote/LegacyTls.kt`
      - 两个 PEM 字符串常量
      - `CompositeTrustManager`：先系统、`CertificateException` 后回落内置
      - `socketFactory` / `trustManager`：`SDK_INT <= 24` 时非空，否则 null
      - `install()`：`HttpsURLConnection.setDefaultSSLSocketFactory(...)`，高版本 no-op
- [x] 3. `NetworkModule`：`gatewayClient`、`yecaoClient` 两个 builder 各加 `applyLegacyTls()`；
      `UpdateInstaller` 的裸 client 一并加
- [x] 4. `RadioApp`：`onCreate` 调 `LegacyTls.install()`（需早于 `PlaybackService` 启动）；
      `newImageLoader()` 给 Coil 换上带 legacy TLS 的 `OkHttpClient`
      需早于 `PlaybackService` 启动
- [x] 5. 单元测试 `LegacyTlsTest`：
      - 内置 PEM 解析出 2 张证书，subject 为 ISRG Root X1 / X2，指纹匹配
      - `CompositeTrustManager` 委托语义：系统过→不碰内置；系统抛→试内置；两个都抛→抛出
- [x] 6. `./gradlew :app:testDebugUnitTest :app:lintDebug`

## 验证命令

```bash
./gradlew :app:testDebugUnitTest
./gradlew :app:lintDebug
```

## 设备验证（Android 6.0 / API 23 / arm64 模拟器，2026-08-26）

环境先自证了诊断：该镜像 `/system/etc/security/cacerts/` 有 158 个根证书，
**0 个 ISRG**，但有 GlobalSign（`yecao.app` 那条链的根）。

修复前的 APK 装上去 → 复现，与用户在电视盒子上拍到的报错逐字一致
（同一页面、同一句 `CertPathValidatorException: Trust anchor for certification path not found.`）。

修复后：

- [x] 「地区」「类型」列表正常加载 —— `/v1/channels` `/v1/stream` `/v1/programs` 全 200
- [x] 选台起播 —— 「环球资讯广播 / 大话体坛」进度条正常走（Media3 那条 `HttpURLConnection` 路径）
- [x] 列表图标正常显示（Coil 自建 OkHttp 那条路径）
- [x] 设置页检查更新 —— `https://yecao.app/api/v1/client/resolve/...` 200，
      系统信任链没被误伤，委托方向正确
- [x] 全程 logcat 中 `CertPath` / `Trust anchor` / `SSLHandshake` 命中数为 **0**

三个注入点各自被独立的一条路径覆盖到了，没有互相顶替。

## 真机验证（Android 6 电视盒子）

- [ ] 留给用户在实际盒子上确认（模拟器已覆盖同一 API level 与同一份系统信任库）

## 回归验证（Android 7+ 设备）

- [ ] 接口 / 播放 / 更新检查行为不变

## 回滚点

步骤 2-4 是一次提交的整体；出问题直接 `git revert` 该 commit，无数据/服务端副作用。

## 实测记录（2026-08-26）

只用内置的两个 ISRG 根、走 JSSE 的 PKIX（与 Android 同一套实现）连真实服务器：

```
anchor: CN=ISRG Root X1, O=Internet Security Research Group, C=US
anchor: CN=ISRG Root X2, O=Internet Security Research Group, C=US
OK   https://radio.hku.wtf/healthz -> HTTP 200
FAIL https://yecao.app/ -> SSLHandshakeException: PKIX path building failed
```

两条都是预期结果，且正好互为佐证：内置根确实能验通 `radio.hku.wtf`（修复有效），
而 `yecao.app` 用内置根验不通 —— 这就是为什么必须做「系统优先、内置兜底」的委托，
不能拿内置根整个替换掉系统信任。

协议层无障碍：`openssl s_client -tls1_2 -cipher ECDHE-ECDSA-AES128-GCM-SHA256` 握手成功，
Android 6 支持该套件。

`./gradlew :app:testDebugUnitTest :app:lintDebug` 通过；`LegacyTlsTest` 6 个用例全过；
lint 在改动涉及的文件上无告警（`CustomX509TrustManager` 已带理由抑制）。

真机验证待用户在 Android 6 盒子上跑。
