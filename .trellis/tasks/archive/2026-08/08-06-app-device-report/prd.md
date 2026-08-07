# App 启动上报设备 + 服务端记录

## Goal

让**装了 App 并打开过**的设备都能被服务端记录下来，从而在管理页
（[08-06-activation-admin-web](../08-06-activation-admin-web/prd.md)）的设备列表里看到全貌——
包括还没兑换过任何激活码的潜在用户。用户原话：

> 我想实现只要设备装了 APP 并打开过，就记录下这个设备，也就是启动时 APP 需要验证该设备是不是
> 新设备，有没有激活。

## 背景 / 已确认事实（代码库证据）

### 现状：这类设备服务端一行记录都没有

`touchDevice`（[activation.go:137](../../../radio-proxy/activation.go:137)）全项目**只有一个调用点**——
[activation.go:265](../../../radio-proxy/activation.go:265)，在 `redeemCode` 事务内、且在「码不存在 /
已吊销 / 已过期」三道校验**之后**。于是：

| 用户行为 | 服务端现在记什么 | 走哪个接口 |
|---|---|---|
| 装了 App、只听云听/蜻蜓 | **什么都不记** | 完全不碰激活相关接口 |
| 打开设置页（App 查一次激活状态） | **什么都不记**（纯读） | `GET /v1/activation/status` |
| 开着 TuneIn 代理想播、被门禁拒（403） | **什么都不记**（纯读） | `/proxy`、`/seg` |
| 成功兑换/换绑激活码 | ✅ 写入 `devices` | `POST /v1/activation/redeem` |

所以 `devices` 表当前的真实语义是「**曾经成功兑换过码的设备**」，`last_seen_at` 是「最近一次成功
兑换/换绑」。（这条已沉淀进
[quality-guidelines.md](../../spec/backend/quality-guidelines.md)。）

### App 侧现状

- **设备哈希在启动时已经算好了**：`RadioApp.onCreate` 调 `DeviceIdProvider.hash(this)` 灌进
  `NetworkModule.deviceHash`（[RadioApp.kt](../../../app/src/main/java/cn/radio/tv/RadioApp.kt)）。
  取值是毫秒级本地调用、不涉网，且必须早于 `PlaybackService` 构造 DataSource 工厂。
  **本任务不需要改设备标识的取法**，上报时直接用这个现成值。
- 取不到标识时 `deviceHash` 是**空串**（低端盒子无 Widevine 且 ANDROID_ID 也拿不到），
  `ActivationRepository` 的既有约定是空串就跳过请求、表现为「未激活」，不崩溃。
- **激活状态目前只在打开设置页时查一次**：`RadioScreen.kt:373` 把
  `viewModel::refreshActivation` 传给设置页，`RadioViewModel.refreshActivation` →
  `ActivationRepository.status()` → `GET /v1/activation/status`。启动时不查。
- `ActivationRepository.status()` 已经是「失败静默降级为未激活」的写法
  （error-handling.md 的 B 类：非关键路径失败不打扰用户），本任务复用这个约定。

### 服务端侧现状

- `handleActivationStatus`（[gateway.go:499](../../../radio-proxy/gateway.go:499)）是**纯读**：
  校验 `deviceHash` 格式 → `deviceStatus` → 输出。**公开、无鉴权**，`deviceHash` 直接来自 query 参数。
- 既有的写入点 `redeemCode` 有两道门槛：必须持有效码 + 按设备哈希的失败限流
  （`allowRedeemAttempt`/`recordRedeemFailure`，内存计数、`redeemLimiterMaxKeys` 兜底清理）。
  状态查询接口**一道都没有**。

## 关键产品决策（已与用户确认）

| 决策点 | 结论 | 理由 |
|---|---|---|
| 记录时机 | **App 启动时**主动调一次 `GET /v1/activation/status`，服务端在该接口内 `touchDevice` | 一次调用同时办两件事：让服务端记下这台设备 + 拿到激活状态（可顺带预热设置页/代理开关的展示）。不需要新增专门的「上报」接口 |
| 不在门禁路径记录 | `/proxy`、`/seg` 的 `checkActivation` **保持纯读** | 那是热路径，每个 HLS 分片请求都会过；在 1C1G 机器上每分片加一次 DB 写是明显负担 |
| 防刷表 | **按客户端 IP 限流**新设备写入 | 状态查询是公开无鉴权接口，一旦变成写接口，任何人都能用随机 64 位十六进制串刷爆 `devices` 表。已存在的设备只更新 `last_seen_at`，不受限流影响 |
| 拆分方式 | 与管理页任务**平级独立**，不并入 | 本任务跨 App + 服务端两个仓库、改动公开接口语义、还涉及防滥用，与「加个管理页」是两件可独立验收的事 |

## Requirements

### 服务端（`radio-proxy`）

1. `handleActivationStatus` 在返回状态前记录设备：已存在的设备更新 `last_seen_at`；不存在的设备
   **在限流允许时**插入新行。
2. 新设备写入按**客户端 IP** 限流，参照既有 `redeemLimiter` 的内存计数写法（滑动窗口 + 超过阈值
   时顺带清理过期项，不起后台 GC）。命中限流时**不报错**——照常返回激活状态，只是不落记录：
   这是运营统计，不该让防滥用逻辑影响用户能不能用。
3. 取客户端 IP 要考虑服务在 Caddy 反代之后（`X-Forwarded-For`），不能直接用 `RemoteAddr`，
   否则所有请求会被算成同一个 IP、限流退化成全局阀门。
4. 写入失败（DB 错误、限流）**不能影响状态查询本身**的返回——它是 App 启动路径上的调用。

### App（`cn.radio.tv`）

1. 启动时调一次激活状态查询，把设备报给服务端；失败静默（沿用 `ActivationRepository.status()`
   的既有降级约定），**不得阻塞启动、不得弹任何提示**。
2. `deviceHash` 为空串时跳过，不发无意义的请求。
3. 拿到的激活状态写进 `UiState`，设置页打开时不必再等一次网络往返才显示正确开关状态
   （原有的「打开设置页刷新一次」保留，两者不冲突）。

## Non-goals（明确不做）

- 不新增专门的「设备上报」接口（复用状态查询即可，多一个接口就多一份要维护的契约）。
- 不在 `/proxy`、`/seg` 门禁校验里写库（热路径，理由见上表）。
- 不改设备标识的取法（`DeviceIdProvider` 的 Widevine → ANDROID_ID 优先级不动）。
- 不记录 IP、机型、系统版本等设备画像字段——本任务只让**已有的** `devices` 三个字段覆盖到更多设备，
  加字段要另外立项并单独想清楚隐私口径。
- 不做启动时的激活状态轮询/长连接（状态只在兑换、到期、被吊销时变化）。
- 不改管理页 UI（它按 `GET /admin/devices` 展示，数据变多自动生效；列名已按「首次上报/最近上报」
  措辞，本任务落地后语义自然对齐）。

## Acceptance Criteria

- [ ] 全新设备装 App 后**只打开、不做任何操作**，管理页设备列表就能看到它，`activeCode` 为空。
- [ ] 该设备再次启动 App 时，`最近上报` 时间更新，`首次上报` 不变，且不会新增重复行。
- [ ] `deviceHash` 取不到（空串）时 App 不发请求，服务端不会因此收到脏数据。
- [ ] 启动上报失败（服务端不可达 / 超时 / 500）时 App 照常启动、无任何提示、不影响播放。
- [ ] 同一 IP 高频用随机设备哈希请求状态接口时，超过阈值后**不再插入新设备行**，但接口仍正常
      返回激活状态（不返回 429、不报错）。
- [ ] 已存在设备的 `last_seen_at` 更新**不受**上述 IP 限流影响。
- [ ] 服务在 Caddy 反代之后时，限流按真实客户端 IP 生效，而不是把所有请求算成反代的 IP。
- [ ] `/proxy`、`/seg` 的门禁路径**没有**新增任何数据库写入（`git diff` 可核对）。
- [ ] `cd radio-proxy && go test ./...` 全绿，新增覆盖：新设备落记录、重复启动只更新 last_seen、
      限流后不插入新行但接口仍 200、DB 写失败不影响状态返回、`X-Forwarded-For` 解析。
- [ ] App 侧 `./gradlew testDebugUnitTest`（或项目既有测试命令）全绿。

## 部署提醒

服务端改动在独立仓库 [yuntingtv-radio-proxy](https://github.com/Uynaity/yuntingtv-radio-proxy)，
push 后仍需手动同步部署到 `radio.hku.wtf`（无 CI/CD）。App 改动需要发版才能让存量用户生效——
也就是说**这个功能的数据积累从新版本铺开那天才开始**，历史存量设备补不回来。
