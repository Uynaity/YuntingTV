# TuneIn 代理激活码系统

## Goal

「TuneIn 代理」（`/proxy` `/seg` 透传链路）持续消耗服务端带宽（按并发听众线性增长），且当前完全无鉴权、任何人拿到
URL 都能直接调用。引入**激活码 + 设备绑定**机制：未激活设备不能使用该透传能力，用可控分发的激活码把带宽成本圈定在
被授权设备范围内。为此需要给目前完全无状态的 `radio-proxy` 引入持久化数据库。

## 背景 / 已确认事实（代码库证据）

- **TuneIn 代理开关现状**（[UserPreferences.kt](../../../app/src/main/java/cn/radio/tv/data/prefs/UserPreferences.kt)、
  [SettingsScreen.kt:153-162](../../../app/src/main/java/cn/radio/tv/ui/SettingsScreen.kt)）：设置页仅在选中 TuneIn 来源时展示
  「TuneIn 代理」开关，默认**关闭**（= 直连服务端解析出的上游真实地址，即 `directUrl`）。开启后走 `/proxy/{id}` 透传。
- **`/proxy`、`/seg` 两端点当前完全无鉴权**（[handlers.go](../../../radio-proxy/handlers.go)），且 `/proxy/{id}` 已经是
  **TuneIn 专属**——`resolveStreamURL` 内部用 `isTuneInID` 挡掉非 TuneIn id（[source_tunein.go:229](../../../radio-proxy/source_tunein.go)），
  云听/蜻蜓播放地址本就是上游直链，从不经过 `/proxy`。`/img` 端点是图标透传（容量小、跨用户共享缓存 7 天），不属于本次
  「代理功能」范围。
- **`radio-proxy` 已拆分为独立仓库**：[github.com/Uynaity/yuntingtv-radio-proxy](https://github.com/Uynaity/yuntingtv-radio-proxy)
  （私有，2026-08-06 建仓，默认分支 `main`）。本仓库根 `.gitignore` 的 `/radio-proxy/` 仍然保留（两仓库独立，主仓库不纳管
  该目录），但 radio-proxy 自身改动此后走该独立仓库的正常 commit/push，**不再是完全脱离版本控制、只能手动同步的状态**
  ——线上部署（`radio.hku.wtf`）仍需按该仓库的内容手动拉取/发布，暂无 CI/CD 自动部署，故仍需在实现完成后手动同步一次，
  但至少改动本身已有可追溯的提交历史。本任务不考虑旧版本客户端兼容（沿用
  [08-05-tunein-proxy-toggle](../archive/2026-08/08-05-tunein-proxy-toggle/prd.md) 的先例判定）。
- **radio-proxy 目前完全无状态**：只有内存 TTL 缓存（[cache.go](../../../radio-proxy/cache.go)），进程重启即丢，无数据库/持久化层。
  部署用 Docker 多阶段构建，`CGO_ENABLED=0` 静态编译（[Dockerfile](../../../radio-proxy/Dockerfile)），
  `docker-compose.yml` 里 `radio-proxy` 服务当前**没有任何可写卷**（唯一挂载的 `./data` 是只读的 TuneIn 目录）。
- **App 侧无 Room/SQLite/Hilt**，本地持久化只用 DataStore Preferences，网络层 Retrofit + OkHttp
  （`data/remote/GatewayApi.kt`）。
- **`/v1/stream`**（[gateway.go:handleV1Stream](../../../radio-proxy/gateway.go)）是起播前必经一跳，`source=tunein` 时已经
  内部 resolve 出上游直链并作为 `directUrl` 下发，同时固定下发 `url=/proxy/{id}`（供客户端按本地开关自选）；该处天然是插入
  激活状态下发的位置。

## 设备 UID 方案

- 优先用 **Widevine Device ID**（`MediaDrm(WIDEVINE_UUID).getPropertyByteArray(MediaDrm.PROPERTY_DEVICE_UNIQUE_ID)`）——
  硬件绑定（烧录于设备安全芯片/keybox），恢复出厂设置、重装 App 均不变，普通用户无法自行修改，API 26+ 可用，无需权限。
- 少数不支持 Widevine 的低端盒子，兜底用 `Settings.Secure.ANDROID_ID`（按"应用签名+用户"区分，重装/升级不变，恢复出厂会重置）。
- 服务端只存哈希（SHA-256），不存明文设备标识；客户端本地也只需要保留能重算哈希的原始值。
- 弃用：IMEI/序列号（TV 盒子多无蜂窝模块，Android 10+ 普通应用禁止读取）、MAC 地址（Android 6+ 固定返回假值）、广告 ID（用户可随时重置）。

## 关键产品决策（已与用户确认）

| 决策点 | 结论 | 理由 |
|---|---|---|
| 门禁执行位置 | **服务端强制校验**：`/v1/stream`(tunein)、`/proxy/{id}`、`/seg` 均按设备标识查库放行 | `/proxy` `/seg` 本就完全公开无鉴权，纯客户端隐藏开关形同虚设，真实带宽消耗点必须服务端把关 |
| 激活码生成/管理 | **服务端管理 API**（带鉴权的 HTTP 接口），非本地 CLI | 支持远程操作，不必登录服务器 |
| 有效期模型 | **支持有效期**（非买断制） | 支持订阅/试用等玩法 |
| 有效期计算方式 | **从设备实际激活那一刻起计时**（码带 `durationDays`，到期 = 激活时刻 + 时长） | 码放着没人用不会白白过期，对分发/销售场景更友好 |
| 换绑策略 | **允许自助换绑，带频率限制** | 用户换设备（如换电视盒子）无需联系管理员；频率限制防止一码多机滥用 |
| 激活入口 UX | **设置页文本输入框**（先做，不做二维码配对） | 电视遥控器虽逐字输入体验一般，但实现最简单；要求激活码本身设计得短且好输入 |
| 离线宽限 | **不宽限，每次实时校验** | 起播 TuneIn 本来就要经 `/v1/stream` 网络请求，没网本就播不了，宽限机制零收益、纯增复杂度 |

## 数据库方案

- 引入 **SQLite**（纯 Go 驱动 `modernc.org/sqlite`，免 CGO，兼容现有 `CGO_ENABLED=0` 构建）作为 radio-proxy 持久化层——
  单文件、零额外基础设施，契合现有单二进制 + docker-compose 部署形态。
- `docker-compose.yml` 需新增一个**可写卷**挂给 `radio-proxy` 服务存放 DB 文件（当前完全没有可写挂载）。
- TuneIn 电台目录（[tunein_catalog.go](../../../radio-proxy/tunein_catalog.go)，离线爬虫产物、启动时一次性读入）**保持文件方案不变**，
  不纳入本次数据库改造范围（YAGNI）。
- 新表：`devices`（设备）、`activation_codes`（激活码）——具体字段见 `design.md`。

## Requirements

### 服务端（radio-proxy）

1. 引入 SQLite 持久化层，新增 `devices` / `activation_codes` 表（含迁移/建表逻辑，进程启动时自动执行）。
2. 新增管理 API（鉴权保护，静态 token 走环境变量，风格对齐现有 `PUBLIC_BASE` 等配置）：
   - 生成激活码（指定有效时长 `durationDays`）
   - 查询/列出激活码及其绑定状态
   - 吊销激活码
   - （用于兜底）管理员强制解绑设备
3. 新增面向客户端的公开 API：
   - 兑换/绑定激活码到设备（携带设备哈希）；已绑定其他设备时按换绑频率限制处理
   - 查询当前设备的激活状态（是否已激活、到期时间、下次可换绑时间）
4. `/v1/stream`（`source=tunein`）、`/proxy/{id}`、`/seg` 三处按请求携带的设备标识查库校验：
   - 未激活或已过期：`/proxy`、`/seg` 拒绝服务（4xx），不透传任何流量
   - `/v1/stream` 无论激活与否都正常下发频道元数据与 `directUrl`（直连不受影响，见下方 Non-goals）；额外下发激活状态供客户端更新 UI
5. 换绑与兑换接口需要基本的滥用防护（如按设备/IP 限制尝试频率），防止暴力枚举激活码。

### 客户端（App）

1. 设置页新增「激活码」输入入口（放在 TuneIn 相关设置区域），提交后调用兑换接口，展示成功/失败（含换绑冷却中）提示。
2. 设置页展示当前激活状态（未激活 / 已激活至 `expiresAt`）。
3. 「TuneIn 代理」开关的可用性跟随服务端下发的激活状态：未激活时开关禁用（置灰）并提示需要激活；开关状态本身仍是提示层，
   真实拦截由服务端负责（客户端拦截失败时，播放请求应能优雅展示服务端拒绝的错误，而非崩溃或无提示卡死）。
4. 设备 UID 获取：Widevine 优先，ANDROID_ID 兜底，计算 SHA-256 哈希后使用；哈希在需要时（`/v1/stream` tunein 请求、
   `/proxy`、`/seg`、兑换/查询接口）随请求携带。

## Non-goals（明确不做）

- TuneIn 直连播放（不使用 `/proxy` 透传时）**不受激活状态影响**——本次只圈住「代理」这条透传链路，浏览频道列表、节目元数据、
  直连播放不需要激活。
- 不做二维码/手机扫码激活流程（先做设置页输入框）。
- 不做管理员 Web UI，仅提供 HTTP 管理接口。
- 不做离线宽限/本地缓存激活状态的容错机制。
- 不做旧客户端兼容迁移。
- 不改动 `/img`、`/v1/provinces`、`/v1/categories`、`/v1/channels` 等既有元数据端点的鉴权模型。
- 不做免费试用期设计（未激活 = 不能用代理，无宽限次数）；如需试用，后续单独立项。

## Acceptance Criteria

- [ ] 管理 API 能生成一个指定有效时长的激活码，未认证请求被拒绝（401/403）。
- [ ] 未激活设备：`/proxy/{id}`、`/seg` 请求被服务端拒绝；`/v1/stream`（tunein）仍正常返回频道元数据 + `directUrl`，
      直连播放不受影响。
- [ ] 使用有效激活码在设备 A 上兑换成功后：该设备访问 `/proxy/{id}`、`/seg` 正常透传；设置页展示「已激活至 `expiresAt`」；
      「TuneIn 代理」开关可用。
- [ ] 同一激活码在冷却期内尝试换绑到设备 B：被拒绝并提示下次可换绑时间；超过冷却期后换绑成功，且设备 A 自动失去激活状态
      （同一码同一时间只绑一台设备）。
- [ ] 激活码到期后：原绑定设备的 `/proxy`、`/seg` 请求重新被拒绝，设置页状态更新为「已过期」。
- [ ] 吊销一个激活码后：其绑定设备立即失去代理权限。
- [ ] 兑换接口对错误/不存在的激活码有明确的失败提示，且对暴力枚举有基本限流。
- [ ] `docker-compose.yml` 为 radio-proxy 提供可写卷，容器重启/重建（非删卷）后已激活状态与激活码数据不丢失。
- [ ] 服务端 `go test`、客户端单测/lint/`assembleDebug` 全绿。

## 部署提醒

`radio-proxy` 的开发改动应提交到独立仓库 [yuntingtv-radio-proxy](https://github.com/Uynaity/yuntingtv-radio-proxy)
（私有）。该仓库暂无 CI/CD，本任务的服务端改动完成并 push 后，仍需要额外手动同步部署到线上（`radio.hku.wtf`），
并确认新增的可写卷在生产 compose 配置中一并生效（否则 DB 随容器重建丢失）。
