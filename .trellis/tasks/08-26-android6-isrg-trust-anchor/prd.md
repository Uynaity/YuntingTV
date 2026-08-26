# Android 6 内置 ISRG 根证书修复 TLS 信任锚

## Goal

让 Android 6（API 23）电视盒子能正常访问 `radio.hku.wtf`，消除
`java.security.cert.CertPathValidatorException: Trust anchor for certification path not found.`

## Background

上一个 commit 把 `minSdk` 降到 23 后暴露的问题，不是代码 bug：

- `radio.hku.wtf` 由 Caddy 自动申请 Let's Encrypt 证书，当前链为
  `CN=radio.hku.wtf → Let's Encrypt YE2 → ISRG Root YE → ISRG Root X2`
- Android 7.0 以下系统信任库不含 ISRG Root X1/X2；旧的 DST Root CA X3 交叉签兼容路径
  已于 2024 年停用，所以 Android 6 上必然验不通
- 对比：`yecao.app` 走 GTS → GlobalSign Root CA，Android 6 有该根，更新接口不受影响

已实证：仅用 ISRG Root X1 + X2 两个自签根即可验通该链
（`openssl s_client -CAfile isrg-roots.pem -no-CAstore -no-CApath` → `Verify return code: 0`）。

TLS 协议层无障碍：服务端支持 `TLSv1.2 / ECDHE-ECDSA-AES128-GCM-SHA256`，Android 6 支持该套件。

## Requirements

- app 内置 ISRG Root X1、ISRG Root X2 两个自签根证书
- 仅在系统信任库缺失 ISRG 根的旧版本上启用（`Build.VERSION.SDK_INT <= 24`），
  高版本保持系统默认信任行为不变
- 必须同时覆盖两条出网路径：
  - `NetworkModule` 的 `gatewayClient` / `yecaoClient`（OkHttp，`/v1/*` 接口）
  - `RadioPlayer` 的 `DefaultHttpDataSource`（Media3 走 `HttpURLConnection`，
    负责 `/proxy` `/seg` 播放流与 `/img` 图标）
- 内置根只作为系统信任的**补充**，系统已信任的证书链必须仍然通过
  （否则 `yecao.app` 等域名会被误伤）
- 不引入新依赖

## Constraints

- Network Security Config 不可用：`android:networkSecurityConfig` 是 API 24 才引入的
- 不改服务端 `radio-proxy/Caddyfile`（本任务只做客户端修复）
- `UpdateInstaller` 里的裸 `OkHttpClient()` 只连 `yecao.app`，不在必须覆盖范围内，
  但若统一入口能顺带覆盖则更好

## Acceptance Criteria

- [ ] Android 6 真机/模拟器上进入「地区」「类型」页面能正常加载列表，不再报 CertPathValidatorException
- [ ] Android 6 上能正常起播（`/proxy` HLS 流）且图标能加载
- [ ] Android 7+ 设备行为不变，`yecao.app` 更新检查仍正常
- [ ] 单元测试覆盖：内置根能验通 ISRG 链，且系统信任的链不被拒
- [ ] `./gradlew test lint` 通过
