# 执行计划：TuneIn 代理激活码系统

参考 `prd.md`（需求/验收）与 `design.md`（技术设计/契约）。按下列顺序实现，服务端先行（客户端依赖其契约）。

## 阶段一：radio-proxy 数据库与门禁基础设施

1. `go.mod` 新增 `modernc.org/sqlite`；新建 `db.go`：打开/创建 `DB_PATH`（默认 `/data/activation.db`）
   指向的 SQLite 文件，启动时执行 `CREATE TABLE IF NOT EXISTS`（`devices`、`activation_codes`，见
   `design.md` schema），注册连接池（`database/sql` 标准接口）。
2. `main.go` 接入：启动时打开 DB（失败则 `log.Fatal`，与现有 `loadTuneInCatalog` 失败处理风格一致），
   新增 `ENFORCE_ACTIVATION`（默认 true）、`ADMIN_TOKEN`、`DB_PATH` 环境变量读取。
3. 新建 `activation.go`：核心校验函数 `checkActivation(deviceHash string) (activated bool, expiresAt int64)`、
   兑换逻辑 `redeemCode(code, deviceHash string) (RedeemResult, error)`（含冷却期判断、幂等同设备重复兑换）。
4. 简单限流：`activation.go` 内 `sync.Map` 按 `deviceHash` 计数的滑动窗口/固定窗口限流（参考 `cache.go`
   懒过期写法），应用到 `/v1/activation/redeem`。

**验证**：`cd radio-proxy && go build ./... && go test ./...`（新增单测覆盖：未激活拒绝、激活后放行、
过期后拒绝、冷却期内换绑被拒、超冷却换绑成功且原设备失效、吊销立即生效）。

## 阶段二：公开 API + 门禁接入

5. `gateway.go`：新增 `handleActivationRedeem`、`handleActivationStatus`，注册路由
   `POST /v1/activation/redeem`、`GET /v1/activation/status`。
6. `handleV1Stream`（tunein 分支）：读取 `X-Device-Hash` header，调用 `checkActivation`，响应新增
   `proxyActivated`/`proxyExpiresAt` 字段（`writeStreamDirect` 签名扩展或新增变体，注意不破坏
   qingting/yunting 分支的现有调用）。
7. `handlers.go`：`handleProxy`、`handleSeg` 头部新增门禁检查（读 `X-Device-Hash` → `checkActivation`
   → 未激活/过期/缺 header 时 `http.Error(w, ..., http.StatusForbidden)` 并 `return`，早于任何上游请求）。
   `ENFORCE_ACTIVATION=false` 时跳过，直接放行（回滚开关）。

**验证**：`go test ./...` 全绿；手动 `curl` 验证三处端点在无 header / 已激活 / 已过期三种场景下的响应码。

## 阶段三：管理 API

8. 新建 `admin.go`：中间件校验 `Authorization: Bearer <ADMIN_TOKEN>`（`crypto/subtle.ConstantTimeCompare`，
   `ADMIN_TOKEN` 未配置时整组路由不注册/返回 404，避免误裸奔）。
9. 路由：`POST /admin/codes`（生成，`design.md` 定的 12 位格式）、`GET /admin/codes`（列表+过滤）、
   `POST /admin/codes/{code}/revoke`、`POST /admin/devices/{deviceHash}/unbind`。

**验证**：`go test ./...`；无 token / 错误 token 均应 401，`curl` 走一遍生成→查询→吊销全流程。

## 阶段四：部署配置

10. `docker-compose.yml`：为 `radio-proxy` 服务新增独立可写卷（如 `activation_data:/data/db`），
    `DB_PATH` 指向该卷内文件；新增 `ADMIN_TOKEN` 环境变量占位（生产值走部署时注入，不进仓库）。
    确认不与现有 `./data:/data:ro` 只读挂载冲突（不同路径）。
11. 更新 `radio-proxy/README.md`：记录新环境变量、管理 API 用法示例（`curl` 片段）。

## 阶段五：客户端 —— 设备标识

12. 新增 `data/device/DeviceIdProvider.kt`：Widevine 优先、`ANDROID_ID` 兜底，SHA-256 hex 输出；
    `runCatching` 包裹 `MediaDrm` 调用，遵循 `error-handling.md` 失败降级约定。
13. 单测：可 mock 覆盖 Widevine 不可用时正确回退 ANDROID_ID 的分支（`MediaDrm` 在 JVM 单测环境不可用，
    走 Robolectric 或直接对回退函数单独抽取测试；不阻塞如果项目当前无 Robolectric 依赖，可退化为仅测试
    哈希函数本身，设备 ID 获取部分走手动/真机验收）。

## 阶段六：客户端 —— 网络层与状态

14. `GatewayApi.kt`：`getStream` 加 `@Header("X-Device-Hash")`；新增 `redeemActivation`/`getActivationStatus`
    及对应 DTO（`ActivationStatusDto`）；`StreamDto` 加 `proxyActivated`/`proxyExpiresAt`（带默认值兼容旧网关）。
15. `RadioSource.kt`/`GatewaySource.kt`：`resolveStream` 透传 `deviceHash`。
16. `player/RadioPlayer.kt`：`/proxy`、`/seg` 播放请求的 `DataSource.Factory` 附加 `X-Device-Hash` header
    （设备哈希从上层传入，不在播放器内部重新计算）。
17. `RadioViewModel.kt`：`UiState` 新增激活状态字段；新增 `redeemActivationCode`；接入设备哈希（初始化时
    算一次并缓存于 VM）；设置页打开时触发一次 `getActivationStatus`。

**验证**：`./gradlew test lint`。

## 阶段七：客户端 —— 设置页 UI

18. `SettingsScreen.kt`：`tuneInProxy` 开关区块下新增激活状态展示 + 入口（未激活/已激活两态文案），
    开关 `enabled` 跟随 `proxyActivated`。
19. 新增激活码输入 UI：
    - TV：复用/改造 `SearchPanel.kt` 风格的网格键盘，包进 `Dialog`（`usePlatformDefaultWidth=false` +
      `decorFitsSystemWindows=false`，参照 `compose-ui-guidelines.md`）。
    - 手机：`OutlinedTextField` + 系统输入法（参照 `MobileSearchBar.kt`）。
20. 失败态提示区分「码无效/已吊销」「换绑冷却中（附时间）」两类 Toast/文案。

**验证**：`./gradlew assembleDebug lint test`；真机/模拟器手动验收 TV 遥控与手机触摸两种输入路径。

## 阶段八：端到端验收（对照 `prd.md` Acceptance Criteria 逐条过）

自动化覆盖（`radio-proxy/activation_test.go`、`app/.../GatewayStreamTest.kt`）：

- [x] 管理 API 生成码、未认证请求被拒绝 —— `TestAdminAuth` / `TestAdminCodeLifecycle`
- [x] 未激活设备 `/proxy`、`/seg` 被拒 —— `TestGateRejectsUnactivatedDevice`
- [x] 兑换成功后代理可用 —— `TestGateAllowsActivatedDevice`
- [x] 冷却期内换绑被拒 + 提示；超冷却换绑成功、原设备失效、**到期时间不重置**
      —— `TestRebindCooldown`
- [x] 到期后原设备重新被拒 / 吊销后立即失效 —— `TestExpiryAndRevoke`
- [x] 无效码/限流场景 —— `TestRedeemBasics` / `TestRedeemRateLimit`
- [x] 应急回滚开关整体生效（含状态查询口径）—— `TestEnforcementOffReportsActivated`
- [x] 旧网关缺字段 / 门禁关闭时到期为 0，客户端都不误判 —— `GatewayStreamTest`
- [x] `go test` + `go vet`、客户端 `test`/`lint`/`assembleDebug` 全绿

- [x] 并发下一码只绑一台（`FOR UPDATE` 行锁）—— `TestRedeemTakesRowLock`，
      已验证「去掉 FOR UPDATE 它会变红」

仍需人工验收（本地无环境，无法自动化）：

- [ ] compose 起 Postgres + 数据跨容器重建留存 —— 本机无 docker，
      compose 只做了静态核对
- [ ] 设置页 UI 真机验收：TV 遥控网格键盘与手机输入法两条输入路径
- [ ] 手动同步部署到 `radio.hku.wtf`（含新增卷与 `ADMIN_TOKEN`/`DB_PATH`），
      线上验证一遍核心路径

## 风险文件 / 回滚点

- `radio-proxy/handlers.go`（`handleProxy`/`handleSeg`）：门禁逻辑加错会导致**已激活用户也被拒**或
  **门禁形同虚设**，两个方向都要各测一次；`ENFORCE_ACTIVATION=false` 是唯一应急开关，部署前确认它能
  真正跳过校验。
- `radio-proxy/gateway.go`（`handleV1Stream`）：不能误改 `qingting`/`yunting` 分支的 `writeStream` 调用。
- `app/.../data/remote/GatewayApi.kt`（`StreamDto`）：新字段必须带默认值，否则旧网关部署（字段缺失）会
  导致客户端反序列化异常——虽然 `kotlinx.serialization` 一般容忍缺字段用默认值，仍需保留 `= false`/`= 0`
  显式默认，不依赖隐式行为。
- `docker-compose.yml`：卷路径写错会导致 DB 随容器重建静默丢失，且不会报错——部署后必须实际重启一次
  容器验证数据留存，不能只看"服务正常启动"。
