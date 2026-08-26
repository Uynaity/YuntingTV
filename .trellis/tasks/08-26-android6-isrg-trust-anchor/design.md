# Design — Android 6 内置 ISRG 根证书

## 方案选型

| 方案 | 结论 |
|---|---|
| Network Security Config `<trust-anchors>` | ✗ `android:networkSecurityConfig` 是 API 24 引入的，Android 6 无效 |
| 引入 Conscrypt | ✗ 新依赖 + APK 增大数 MB，只为补两个根证书不值 |
| **内置 ISRG 根 + 委托式 TrustManager** | ✓ 无新依赖，约 40 行 |

## 证书来源与形态

内置 **ISRG Root X1**（RSA 4096，2035 到期）与 **ISRG Root X2**（ECDSA P-384，2040 到期）两个自签根。
X2 覆盖当前的 ECDSA 链，X1 兜住 Caddy 续期时切回 RSA 链的情况。

指纹（SHA-256，与 Let's Encrypt 官方公布一致，实施时需再次核对）：

- X1 `96:BC:EC:06:26:49:76:F3:74:60:77:9A:CF:28:C5:A7:CF:E8:A3:C0:AA:E1:1A:8F:FC:EE:05:C0:BD:DF:08:C6`
- X2 `69:72:9B:8E:15:A8:6E:FC:17:7A:57:AF:B7:17:1D:FC:64:AD:D2:8C:2F:CA:8C:F1:50:7E:34:45:3C:CB:14:70`

**以 PEM 字符串常量形式写在 Kotlin 源码里**，不放 `res/raw`。理由：省掉 `Context` 的传递链
——`NetworkModule` 是无 Context 的 `object`，若走 `res/raw` 就得像 `deviceHash` 那样再从
`RadioApp` 灌一次。两个根合计约 2.5KB base64 文本，做常量完全可接受。

## 信任合并策略：委托，而不是合并 KeyStore

新建 `data/remote/LegacyTls.kt`：

```
CompositeTrustManager(system, bundled) : X509TrustManager
  checkServerTrusted(chain, authType):
    try   system.checkServerTrusted(...)          // 系统信任的照旧走系统
    catch CertificateException -> bundled.checkServerTrusted(...)  // 只有系统拒了才试内置根
```

另一条路是把 `systemTrustManager.acceptedIssuers` 枚举出来和内置根一起塞进新 `KeyStore`，
再交给 `TrustManagerFactory` 生成原生 `TrustManagerImpl`。放弃它的原因：该做法依赖
`acceptedIssuers` 能完整枚举系统根，一旦某些 ROM 返回空数组，结果就是**只信内置根**，
`yecao.app` 等域名被误伤。委托法没有这个失败模式——系统能验通的链永远先由系统验通。

代价：返回的不是平台原生 `TrustManagerImpl`，OkHttp 拿不到 `X509TrustManagerExtensions`
的 host-aware 校验路径，会退到 `BasicCertificateChainCleaner` + `BasicTrustRootIndex`。
只发生在 API ≤ 24。

### 这里有个已知天花板

Android 的系统 `TrustManagerImpl` 走的是 `TrustedCertificateStore` 而不是 `KeyStore`，
`getAcceptedIssuers()` **恒返回空数组**——这一条同时否掉了上面的 KeyStore 合并方案
（在 Android 上根本枚举不到系统根），也意味着 `CompositeTrustManager.acceptedIssuers`
实际只有内置的两个 ISRG 根。

握手本身不受影响（走 `checkServerTrusted` 委托，系统链照旧由系统验）。受影响的是
`BasicTrustRootIndex`——它按 `acceptedIssuers` 建索引。**当前无害**：反编译
`okhttp-5.5.0` 确认，`CertificatePinner.check` 在没有匹配的 pin 时直接返回，
链清理的 lambda 根本不会被调用，而本项目没有配 `CertificatePinner`。

代码里已用 `ponytail:` 注释标出：将来若引入 `CertificatePinner`，必须先让
`acceptedIssuers` 覆盖系统根（读 `/system/etc/security/cacerts`），
否则 `yecao.app` 这类非 ISRG 链会在链清理阶段失败。

## 启用条件

`Build.VERSION.SDK_INT <= 24`。ISRG Root X1 自 Android 7.1（API 25）进入系统信任库，
API 24 及以下都缺。API ≥ 25 时 `LegacyTls` 的工厂返回 `null`，调用方不做任何改动，
高版本行为与现在完全一致。

## 三个注入点（缺一不可）

OkHttp **不读** `HttpsURLConnection` 的默认工厂（它走 `Platform.get().platformTrustManager()`），
反过来 Media3 也没有 SSLSocketFactory 注入点，所以两套都得管。

1. **OkHttp（接口）** — `NetworkModule` 的 `gatewayClient` / `yecaoClient` 各加 `applyLegacyTls()`
2. **OkHttp（图标）** — Coil 2.7 默认自建 `OkHttpClient`，`/img` 走的是它而不是 Media3。
   在 `RadioApp.newImageLoader()` 里用 `.okHttpClient { ... applyLegacyTls() ... }` 换掉默认的
3. **`HttpsURLConnection.setDefaultSSLSocketFactory(...)`** — 在 `RadioApp.onCreate` 调一次。
   Media3 的 `DefaultHttpDataSource`（[RadioPlayer.kt:46](app/src/main/java/cn/radio/tv/player/RadioPlayer.kt:46)）
   底层是 `HttpURLConnection`，只能改全局默认值。顺带覆盖所有裸 `HttpURLConnection`。

`UpdateInstaller` 的裸 `OkHttpClient()` 只连 `yecao.app`（GTS 链，Android 6 已信任），
但既然是一行的事，一并加上，免得将来下载域名变了又踩一次。

`RadioApp.onCreate` 必须在 `PlaybackService` 起来之前完成设置——注释里已经写明
DataSource 工厂在构造时定死请求头，同样的时序要求在这里也成立。

## 影响面 / 兼容性

- API ≥ 25：零行为变化（工厂返回 null，两个注入点都是 no-op）
- API ≤ 24：系统信任链走系统 TM，仅系统拒绝时才回落到内置 ISRG 根，不放宽任何其它信任
- 不改服务端；服务端将来换 CA 也不冲突（届时内置根变成无害的死代码）

## 回滚

改动集中在一个新文件 + `NetworkModule`/`RadioApp` 各两三行，`git revert` 即可。
