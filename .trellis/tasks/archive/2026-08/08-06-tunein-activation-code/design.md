# 技术设计：TuneIn 代理激活码系统

## 边界与契约

- **服务端改动全在 `radio-proxy`**，现已拆分为独立仓库 [yuntingtv-radio-proxy](https://github.com/Uynaity/yuntingtv-radio-proxy)
  （私有）。该仓库暂无 CI/CD，改动 push 后仍需单独手动同步部署到 `radio.hku.wtf`。
- **门禁点**：`/v1/stream`（`source=tunein`）、`/proxy/{id}`、`/seg`。`/img`、`/v1/provinces` 等既有元数据端点不变。
- **新增两类对外 HTTP 面**：
  - 公开面（客户端用）：设备状态查询、激活码兑换/换绑。
  - 管理面（`Authorization: Bearer <ADMIN_TOKEN>`）：生成/查询/吊销激活码、强制解绑设备。
- **客户端改动**：`data/prefs`、`data/remote`、`data/source`、`ui/SettingsScreen.kt` 及 `RadioViewModel.kt`；新增一个设备 ID 工具类。

---

## 数据模型（Postgres）

> **选型修订（实现阶段）**：原定 SQLite（`modernc.org/sqlite`），实测在 1C1G 的部署机上
> 无法接受——该驱动是把 SQLite 的 C 代码整体转写成 Go 的产物，`sqlite/lib` 单包 149 万行、
> 加 `modernc.org/libc` 共约 290 万行生成代码，编译跑满 CPU 十几分钟且有 OOM 风险；
> 而 Dockerfile 当时没挂 build cache，每次改业务代码都要全量重编。
>
> 原设计只权衡了「单文件、零额外基础设施」与当前这两张小表，**漏算了编译成本**，
> 也没考虑到后续要做管理后台（发码记录、客户实体、有效期变更历史、临时统计查询）——
> 那些正是关系库赚回成本的地方。改用 Postgres 独立容器 + pgx 驱动（正常体量，秒级编译）。
> 顺带解决了原设计里记的一个隐患：SQLite 侧为规避 `SQLITE_BUSY` 把连接池限死 1 条，
> 而门禁校验在 `/proxy`、`/seg` 上是每个 HLS 分片一次的热路径，是串行的。
>
> 代价：多一个容器 + 卷 + 凭据，运行时多占约 50–100MB（已调小 `shared_buffers`
> 并设 `mem_limit`），且网关必须容忍启动期数据库未就绪。

```sql
CREATE TABLE devices (
    device_hash   TEXT PRIMARY KEY,   -- SHA-256(hex) of Widevine device id / ANDROID_ID
    first_seen_at BIGINT NOT NULL,    -- unix seconds
    last_seen_at  BIGINT NOT NULL
);

CREATE TABLE activation_codes (
    code            TEXT PRIMARY KEY,   -- 展示态明文（如 "K7X9-4QRT-8MNP"）
    duration_days   BIGINT NOT NULL,    -- 有效时长，激活时刻起算
    bound_device    TEXT REFERENCES devices(device_hash),  -- NULL = 未激活
    activated_at    BIGINT,             -- 首次/最近一次绑定时刻；NULL = 未激活
    expires_at      BIGINT,             -- activated_at + duration_days*86400；NULL = 未激活
    last_rebind_at  BIGINT,             -- 上一次换绑时刻，用于冷却计算；初次激活时等于 activated_at
    revoked_at      BIGINT,             -- 非空 = 已吊销
    note            TEXT,               -- 管理员备注（可选，生成时填）
    created_at      BIGINT NOT NULL
);
CREATE INDEX idx_activation_codes_bound_device ON activation_codes(bound_device);
```

时间戳用 `BIGINT` 存 unix 秒而非 `TIMESTAMPTZ`：这些值要原样下发给客户端
（见 `activationStatus` 的 JSON 字段），存成时间类型只会在每个读写点多两次转换，
还引入时区口径问题。

**并发**：`redeemCode` 读取码时必须带 `FOR UPDATE` 行锁。SQLite 时代靠「连接池只有 1 条」
天然串行，换到 Postgres 后并发是真的——默认 READ COMMITTED 下两个事务会双双读到
「未绑定」再各自写入，后写的覆盖先写的，等于绕过「一码只绑一台」与换绑冷却。
锁必须加在**读**上：`UPDATE` 自己虽然也加锁，但那时本事务已经读到了过期值，
阻塞完照样按旧值写下去。

- 激活码存明文而非哈希：这是许可证密钥，不是密码；管理 API 需要「列出所有码及其状态」这一常规运维动作，
  哈希后无法反查明文，需要额外维护映射，收益（防 DB 泄露后码被盗用）在当前规模下不成比例。DB 文件本身
  只有服务器持有者可访问，威胁模型与用户密码库不同。
- 「一码同一时间只绑一台设备」通过 `bound_device` 单值字段天然表达；换绑 = 覆盖该字段 + 按新设备重算
  `activated_at`/`expires_at`（“从激活时刻起算”意味着换绑等价于对新设备重新开始计时——见下方换绑语义确认）。
- `devices` 表当前只用于外键与运维可见性（活跃设备数、`last_seen_at`），不额外承载业务逻辑。

> **换绑语义待实现时二次确认的细节**（不阻塞设计，属于实现阶段的小决策）：换绑到新设备时，是否重置
> `expires_at`（新设备从此刻起重新计满 `duration_days`），还是保留原到期时间不变、只换绑定设备？
> 推荐**保留原到期时间不变**（`expires_at` 不因换绑重置，只更新 `bound_device`/`last_rebind_at`）——
> 换绑是「同一份授权换个设备用」，不应变相续期；重置会让「频繁换绑」成为无限续期漏洞，与频率限制的
> 防滥用初衷矛盾。`implement.md` 按此实现，若需求理解有误在 review 时纠正。

---

## 服务端 API

### 公开面

| 方法/路径 | 说明 |
|---|---|
| `POST /v1/activation/redeem` | body `{ code, deviceHash }`。校验码存在且未吊销：未绑定 → 绑定 + 计时；已绑定同设备 → 幂等返回当前状态；已绑定其他设备 → 检查冷却期（`now - last_rebind_at >= cooldown`），未到冷却拒绝并返回可换绑时间，到冷却则换绑。 |
| `GET /v1/activation/status?deviceHash=...` | 返回 `{ activated, expiresAt, code }`（`code` 用于设置页展示"当前绑定码"，可选打码）。未激活/已过期返回 `activated=false`。 |

- `deviceHash` 校验格式（如固定长度十六进制），拒绝异常输入，避免把非哈希垃圾值当 key 写入 `devices` 表。
- 兑换接口限流：按 `deviceHash`（其次按来源 IP）计数，如「每设备每小时 10 次失败尝试」，超限临时拒绝（429）。
  内存计数器足够（`sync.Map`，参考 `cache.go` 现有懒过期风格），重启清零可接受——目标是挡自动化枚举，不是审计。

### 管理面（`Authorization: Bearer <ADMIN_TOKEN>`，`ADMIN_TOKEN` 走环境变量，未配置则整组路由 404/不注册）

| 方法/路径 | 说明 |
|---|---|
| `POST /admin/codes` | body `{ durationDays, note? }`。服务端生成随机码、写入未绑定记录，返回明文码（仅此一次以外也能查到，因为明文本就存库）。 |
| `GET /admin/codes` | 列表，支持 `?status=unused|active|expired|revoked` 过滤。 |
| `POST /admin/codes/{code}/revoke` | 置 `revoked_at`，绑定设备立即失去权限（门禁校验读到 `revoked_at != NULL` 即拒绝）。 |
| `POST /admin/devices/{deviceHash}/unbind` | 强制清空该设备当前绑定的码的 `bound_device`（用于用户换绑冷却期内确有需要时的人工兜底）。 |

- 鉴权用常量时间比较（`crypto/subtle.ConstantTimeCompare`）防时序攻击，实现成本几乎为零。
- 不做多管理员/权限分级——单一 owner 场景，一个 token 足够，符合项目当前运维规模。

### 门禁改动（现有端点）

- **`handleV1Stream`（`source=tunein` 分支）**：新增读取请求 `deviceHash`（query 或 header，建议 header
  `X-Device-Hash` 与其余两个端点统一）。查库得到激活状态，在响应里新增 `proxyActivated: bool` 与可选
  `proxyExpiresAt`。**`url`/`directUrl` 照常下发，不因未激活而阉割**——直连不受影响（见 PRD Non-goals），
  这个新字段只用于客户端同步 UI 状态（开关是否可用、到期提示），不是唯一防线。
- **`handleProxy`（`/proxy/{id}`）与 `handleSeg`（`/seg`）**：新增必需的 `X-Device-Hash` 请求头（或 `/proxy`
  用 query 更方便，`/seg` 因为参数已经是 `?url=`，追加 `&device=` 亦可；统一用同名 header 更简单，两处都读
  header）。查库：设备未激活/码已过期/已吊销 → 直接 `403`，不发起任何上游请求（避免做无用功浪费带宽）。
  该校验是**内联同步查询**（SQLite 单文件、本机磁盘，延迟可忽略，不引入额外网络跳）。
- 三处校验共用一个辅助函数 `checkActivation(deviceHash string) (ok bool, expiresAt int64)`，避免逻辑分叉。

### 数据库接入

- 驱动：`github.com/jackc/pgx/v5`（经 `stdlib` 走 `database/sql`，保持既有写法；纯 Go，
  不影响 `CGO_ENABLED=0` 静态构建）。
- 启动时（`main.go`）：读 `DATABASE_URL`（缺失即退出），执行建表 DDL
  （`CREATE TABLE IF NOT EXISTS`，无需迁移框架，符合当前项目规模）。
  **连不上要重试**而不是立刻 `log.Fatal`：数据库是独立容器，谁先就绪不由本进程决定。
- `docker-compose.yml`：新增 `db` 服务（`postgres:17-alpine`）+ 具名卷 `postgres_data`
  + healthcheck + `depends_on: condition: service_healthy`。为小内存机器调小
  `shared_buffers` 并设 `mem_limit`，避免它把网关挤掉。密码与 `ADMIN_TOKEN` 走 `.env`。
- `Dockerfile`：给 `go mod download` 与 `go build` 各挂 cache mount。
  原本没有，导致改一行业务代码就要把所有依赖重编一遍——在小机器上是分钟级代价。

### 测试策略

DB 相关用例跑**真 Postgres**而不是 mock：要验的恰恰是 SQL 层语义（行锁、外键、事务回滚），
mock 掉存储等于把要测的东西测没了。`TestMain` 优先用 `TEST_DATABASE_URL`，否则用
`embedded-postgres`（仅测试依赖，自带 PG 二进制，不需要 docker 或本机 PG，
也不进生产构建）现起一个，保证 `go test ./...` 自包含。

并发不变量（一码只绑一台）需要一个**能钉死交错点**的测试：单纯起一堆 goroutine 抢是无效的
——本地库太快，竞态窗口撞不上，去掉 `FOR UPDATE` 照样绿。为此在 `redeemCode` 读完码、
写回之前留了测试钩子 `afterCodeRead`（生产路径是空函数调用）。
**并发/锁相关的测试都要先验证「去掉修复后它会变红」**，否则等于没测。

---

## 客户端改动

### 设备 ID 获取（新增 `data/device/DeviceIdProvider.kt`，暂定位置）

```kotlin
// 优先 Widevine（MediaDrm.PROPERTY_DEVICE_UNIQUE_ID），失败/不支持时退化 ANDROID_ID；
// 两种情况都对原始字节/字符串做 SHA-256，转 hex 输出，服务端与本地都只用这个哈希。
suspend fun deviceHash(context: Context): String
```

- `MediaDrm(WIDEVINE_UUID)` 需要 try/catch（部分设备不支持会抛 `UnsupportedSchemeException`），
  失败时静默回退，不能让激活功能因单一设备的 DRM 缺失而崩溃（沿用项目「失败降级不崩溃」的一贯约定，
  见 `error-handling.md`）。
- 计算一次后可考虑本地缓存（DataStore 存一份，避免每次都重新起 `MediaDrm` 会话），但**服务端始终以
  请求携带的哈希为准**，本地缓存只是性能优化，不改变契约。

### `GatewayApi.kt` / 新增 `ActivationApi.kt`（或并入 `GatewayApi`）

```kotlin
@POST("v1/activation/redeem")
suspend fun redeemActivation(@Body body: RedeemRequest): ApiResponse<ActivationStatusDto>

@GET("v1/activation/status")
suspend fun getActivationStatus(@Query("deviceHash") deviceHash: String): ApiResponse<ActivationStatusDto>
```

- `getStream` 增加 `@Header("X-Device-Hash") deviceHash: String` 参数（TuneIn 来源才有意义，其余来源
  服务端忽略该 header 即可，不必客户端按来源分支——沿用项目「服务端负责收窄，客户端不按来源判断」的
  既有约定，见 `quality-guidelines.md` 的 `directUrl` 一节）。
- `StreamDto` 新增 `proxyActivated: Boolean = false`、`proxyExpiresAt: Long = 0`（默认值保证旧网关兼容）。

### `RadioSource.kt` / `GatewaySource.kt`

- `resolveStream` 内部调用 `getStream` 时附带 `deviceHash`；`/proxy`、`/seg` 的调用点在播放器层
  （`RadioPlayer`/`PlaybackService` 构造 MediaItem 时的 URL），需要把 `deviceHash` 作为 query 参数或
  自定义 header 附加到播放请求——**技术选型**：ExoPlayer 的 `DataSource.Factory`（`OkHttpDataSource` 或
  等价）支持自定义 header，比拼 URL query 更干净，且不会把设备哈希写进日志里常见的 URL 打印。放
  `player/RadioPlayer.kt` 统一加，不要散落到多处 MediaItem 构造点。

### `UserPreferences.kt`

- 新增本地缓存字段（可选，减少设置页每次打开都请求）：`activationStatus: Flow<ActivationSnapshot?>`，
  但**不作为门禁依据**，仅用于设置页展示上次已知状态时的占位（避免刷新前短暂显示"未激活"）。
- 不新增激活码本身的持久化——码只在兑换请求里发一次，服务端返回的状态是唯一权威源。

### `ui/SettingsScreen.kt`

- 「TuneIn 代理」开关旁新增激活入口：未激活时开关 `enabled = false` 并在 subtitle 提示"需先激活"；
  新增一行 `ActionSettingRow`「输入激活码」/ 已激活时显示「已激活至 yyyy-MM-dd」+「更换激活码」。
  位置紧贴 `tuneInProxy` 开关下方，同样只在 `selectedSource == RadioSourceType.TUNEIN` 时展示
  （沿用既有约定，见 `compose-ui-guidelines.md`「只对单一来源生效的设置项」）。
- 激活码输入 UI 按平台复用现有模式，不新造一套：
  - **TV（D-pad）**：复用 `SearchPanel.kt` 的 5 列网格虚拟键盘组件做字符输入（该组件已解决"遥控器
    逐字输入"的问题），弹出方式参照 `compose-ui-guidelines.md`「全屏居中弹窗」用 `Dialog` +
    `usePlatformDefaultWidth=false`。
  - **手机（触摸）**：普通 `OutlinedTextField` + 系统输入法即可，参照 `MobileSearchBar.kt` 的
    `LocalSoftwareKeyboardController` 用法。
  - 两端共用同一个提交逻辑（ViewModel 方法），只是输入控件按 `LocalConfiguration`/设备类型分流，
    与现有「TV 与手机共用 Activity」的分流约定一致。
- 激活码格式建议：**12 位，3 组 4 字符，大写字母+数字，剔除易混淆字符（0/O、1/I/L）**，如
  `K7X9-4QRT-8MNP`——兼顾遥控器输入效率与猜测抗性。生成逻辑在服务端管理 API 侧实现。

### `RadioViewModel.kt`

- `UiState` 新增 `proxyActivated: Boolean`、`proxyExpiresAt: Long?`，随 `/v1/stream` 响应或独立
  `status` 查询更新（设置页打开时主动查一次，不必轮询）。
- 新增 `redeemActivationCode(code: String)`：调用兑换接口，成功后刷新状态；失败区分"码无效/已吊销"
  与"换绑冷却中（附下次可换绑时间）"两类提示文案。
- `resolveStream` 调用点透传 `deviceHash`（从 `DeviceIdProvider` 取，可在 VM 初始化时算一次缓存）。

---

## 数据流（起播 TuneIn 电台，开启代理场景）

```
Settings: 用户输入激活码
  → RadioViewModel.redeemActivationCode(code)
  → GatewayApi.redeemActivation({code, deviceHash})
  → radio-proxy: 查/写 activation_codes（含冷却判断）
  → 返回 ActivationStatusDto{activated, expiresAt}
  → UiState 更新，「TuneIn 代理」开关变为可用

起播（代理开启）:
  RadioViewModel.resolveStream(channel, useProxy=true)
  → GatewaySource.resolveStream → GatewayApi.getStream(header: X-Device-Hash)
  → radio-proxy handleV1Stream: 查库得 proxyActivated，随 directUrl/url 一并下发
  → 客户端选中 url=/proxy/{id}（因 useProxy=true）
  → RadioPlayer 用该 URL 起播，DataSource 请求带 X-Device-Hash header
  → radio-proxy handleProxy: 校验 header 对应设备已激活 → 放行透传
    （未激活/过期 → 403，客户端走现有播放失败提示路径，不新增错误 UI）
```

---

## 兼容性与回滚

- **旧客户端 + 新网关**：`/v1/stream` 响应多两个字段，旧客户端忽略即可；旧客户端从不带 `X-Device-Hash`
  访问 `/proxy`/`/seg` → 服务端视为未激活设备 → 一律拒绝。**这是一次破坏性变更**：所有存量用户的
  「TuneIn 代理」开关会在网关升级后立即失效，直到升级到带激活功能的新版本 App 并完成激活。
  PRD 已确认「不考虑旧版本兼容」，此处需在部署时机上留意：**服务端与客户端应尽量同步发布**，
  避免中间状态让已经在用代理开关的用户（如有）体验骤降。直连播放不受影响。
- **回滚**：服务端可通过环境变量整体关闭门禁（如 `ENFORCE_ACTIVATION=false` 时 `/proxy`/`/seg`
  跳过校验，回到当前行为），作为部署出问题时的应急开关，成本很低、建议实现。数据库表本身是纯增量，
  回滚不需要删表。
- **`radio-proxy` 部署提醒**：改动提交到 [yuntingtv-radio-proxy](https://github.com/Uynaity/yuntingtv-radio-proxy) 仓库后，
  仍需手动同步到 `radio.hku.wtf`（暂无 CI/CD），包含 `docker-compose.yml` 的新卷、`ADMIN_TOKEN`/`DB_PATH` 等新环境变量。

## 风险 / 待实现阶段关注

- SQLite 单文件无内建高可用；当前单实例部署下无所谓，若日后要多实例横向扩展需换 Postgres
  （PRD 已确认本轮不做）。
- 门禁校验在 `/proxy`、`/seg` 两个高频路径上新增一次同步 DB 查询，SQLite 本机读性能足够，但仍建议
  对 `activation_codes` 按 `bound_device` 建索引（已在 schema 中列出），避免全表扫描。
- 换绑冷却期时长（如 7 天）、限流阈值（如每小时 10 次）作为可调常量在实现阶段先给出合理默认值，
  非阻塞性决策，后续可按实际滥用情况调整。
