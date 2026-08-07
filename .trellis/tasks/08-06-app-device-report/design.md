# 技术设计：App 启动上报设备 + 服务端记录

## 边界与契约

- **不新增接口、不改响应结构**。`GET /v1/activation/status` 的请求参数与响应体一字不改，
  只是从「纯读」变成「读 + 顺带落一条设备记录」。旧版 App 调它照常工作（并且从此也会被记录）。
- **不改数据库 schema**。`devices` 表三个字段够用，本任务只是让更多设备进得来。
- **两个仓库各自提交**：服务端在 [yuntingtv-radio-proxy](https://github.com/Uynaity/yuntingtv-radio-proxy)
  （本机 `radio-proxy/`），App 在主仓库。两边无 git 归属关系。
- **不改动**：`activation.go` 的 `redeemCode`/`lookupActivation`/`checkActivation` 核心逻辑、
  `gateway.go` 的 `/v1/stream`、`handlers.go` 的 `/proxy` `/seg`。

### 新增/修改文件

| 文件 | 动作 | 内容 |
|---|---|---|
| `radio-proxy/device_report.go` | 新增 | 设备落记录 + 按 IP 限流 + 取真实客户端 IP |
| `radio-proxy/gateway.go` | 改 | `handleActivationStatus` 里加一次落记录调用 |
| `radio-proxy/device_report_test.go` | 新增 | 落记录、限流、XFF 解析、失败隔离 |
| `app/.../ui/RadioViewModel.kt` | 改 | `init` 里加一次 `refreshActivation()` |

---

## 服务端：落记录的时机与语义

### 为什么不能直接复用 `touchDevice`

现有的 `touchDevice`（`activation.go:137`）是 `INSERT ... ON CONFLICT DO UPDATE`，一条语句同时
覆盖「新建」和「更新」。但本任务要求**只对新建行限流**（老设备更新 `last_seen_at` 不该被挡），
一条 upsert 语句分不出这两种情况。

所以走「先更新、失败才插入」：

```go
// reportDevice 在状态查询时记录设备。返回值只用于测试断言,调用方不关心。
//
// 拆成 UPDATE→INSERT 两步而不是直接 upsert:限流只该拦「新设备建行」这一种情况。
// 老设备刷新 last_seen_at 是纯更新、行数固定,再怎么调用也撑不爆表,拿限流去挡它
// 只会让常用设备的活跃时间莫名停止更新。
func reportDevice(deviceHash, clientIP string, now int64) error {
    res, err := activationDB.Exec(
        `UPDATE devices SET last_seen_at = $1 WHERE device_hash = $2`, now, deviceHash)
    if err != nil {
        return err
    }
    if n, _ := res.RowsAffected(); n > 0 {
        return nil // 老设备,到此为止
    }
    if !allowNewDevice(clientIP) {
        return nil // 限流:不落新行,但这不是错误 —— 见下方「限流不该影响用户」
    }
    // ON CONFLICT DO NOTHING:两个请求同时判定「是新设备」时,后到的那个不该报错。
    _, err = activationDB.Exec(
        `INSERT INTO devices (device_hash, first_seen_at, last_seen_at) VALUES ($1, $2, $2)
         ON CONFLICT (device_hash) DO NOTHING`, deviceHash, now)
    return err
}
```

常见路径（老设备启动）是**一次走主键索引的 UPDATE**，新设备才多一次 INSERT。放在 App 启动
路径上可接受。

### 为什么同步做而不是丢进 goroutine

异步能让状态查询完全不受写入影响，但代价是错误不可观测、测试要靠 sleep 赌时序。这是一条走
主键索引的单行 UPDATE，同步执行的延迟远小于这次请求本身的网络往返。**同步，但错误不外抛**
（见下）。

### 失败隔离

```go
// handleActivationStatus 里:
if err := reportDevice(deviceHash, clientIP(r), time.Now().Unix()); err != nil {
    // 落记录是运营统计,不是这个接口的职责。写不进去也照常返回激活状态 ——
    // 这是 App 启动路径上的调用,不能因为统计写失败就让用户看到「查询失败」。
    log.Printf("设备记录写入失败: %v", err) // 注意:不打 deviceHash(logging-guidelines)
}
```

限流命中时 `reportDevice` 返回 `nil` 而不是错误：那是**预期内的正常分支**，不是失败，更不该
变成 429。用户装了 App 打开，不该因为跟他共用出口 IP 的人在刷接口而看到报错。

---

## 服务端：取真实客户端 IP（本设计最容易做错的一点）

```go
// clientIP 取限流用的客户端标识。
//
// **必须取 X-Forwarded-For 的最右一段,不是最左**。Caddy 的 reverse_proxy 是把它看到的
// 对端 IP **追加**到已有 XFF 之后,所以:
//
//     客户端伪造 "X-Forwarded-For: 1.2.3.4"
//     → Caddy 转发时变成 "1.2.3.4, <真实客户端IP>"
//
// 取最左 = 取到客户端自己写的那段,攻击者每次请求换一个随机值就能让限流形同虚设
// (每个 key 都是全新的,永远撞不到阈值)。取最右 = 取我们自己的可信代理写的那段。
//
// 没有 XFF 时(本地直连、compose 内部调试)回落到 RemoteAddr。
func clientIP(r *http.Request) string {
    if xff := r.Header.Get("X-Forwarded-For"); xff != "" {
        parts := strings.Split(xff, ",")
        if ip := strings.TrimSpace(parts[len(parts)-1]); ip != "" {
            return ip
        }
    }
    host, _, err := net.SplitHostPort(r.RemoteAddr)
    if err != nil {
        return r.RemoteAddr // 没有端口的形态,原样用
    }
    return host
}
```

**前提**：本服务只在 Caddy 之后对外（`docker-compose.yml` 里 `expose` 而非 `ports`，容器网络内
才可达）。这个前提一旦变了（直接暴露 8080），最右段就变成客户端可控 —— 到时候必须回头改这里。

---

## 服务端：新设备限流

照搬 `activation.go` 里 `redeemLimiter` 的形状（互斥锁 + map + 滑动窗口 + 写入点顺带清理），
**但是独立一份**：key 空间不同（IP vs 设备哈希）、语义不同（拦新建 vs 拦爆破），混用会让两边
互相消耗配额。

```go
const (
    newDeviceWindow  = time.Hour
    newDeviceLimit   = 20    // 每 IP 每小时最多落 20 台新设备
    newDeviceMaxKeys = 10000 // 同 redeemLimiterMaxKeys:被刷时才会触发清理
)
```

阈值取 20/小时的理由：一个家庭出口 IP 一小时内新增 20 台**从未见过**的设备是不现实的（这是
新设备，不是请求数，老设备重复启动完全不计数）；而枚举脚本一分钟就能撞穿。真遇到大型 NAT
出口（学校/公司）导致误伤，后果也只是少记几台设备的统计，不影响任何人使用。

清理策略与 `recordRedeemFailure` 一致：只在写入点、且只在 key 数超阈值时遍历删过期项，不起
后台 GC —— 攻击者换 IP 的成本远高于换设备哈希，正常情况这个 map 长不大。

---

## App：在哪儿发这一次请求

**选 `RadioViewModel.init`，不选 `RadioApp.onCreate`。**

- `RadioApp.onCreate` 是 `Application`，没有生命周期绑定的 `CoroutineScope`，要么自己造一个
  裸 `GlobalScope`（无人取消），要么引一个新的作用域概念 —— 为一行网络调用不值得。
- 更实质的理由：进程可能由 `PlaybackService` 拉起（系统恢复媒体会话），那种情况下用户并没有
  「打开 App」。`RadioViewModel` 的构造与主界面一一对应，**它被创建 = 用户真的打开了界面**，
  正是需求里说的那件事。
- `viewModelScope` 自带取消，请求随界面销毁一起断，不留孤儿。

改动本身极小 —— `refreshActivation()` 已经存在、已经自己 `launch`、已经把结果写回 `UiState`：

```kotlin
// init 块内,与其余 viewModelScope.launch 并列:
// 启动时查一次激活状态。两个作用:①服务端借这次调用把本设备记进 devices 表
// (它是唯一会记录「装了 App 打开过」的设备的写入点);②设置页打开时不必再等
// 一次网络往返才显示正确的开关状态。失败静默 —— ActivationRepository.status()
// 已按 error-handling.md 的 B 类降级为「未激活」,不打扰用户。
refreshActivation()
```

`ActivationRepository.status()` 已有的两条保证正好够用，**不需要改它**：
- `deviceHash` 为空串时直接返回 `Inactive`，不发请求（低端盒子取不到标识的情况）。
- `runCatching { ... }.getOrNull()` 吞掉所有网络异常，降级为未激活。

### App 侧不需要知道「是不是新设备」

需求原话是「App 需要验证该设备是不是新设备」。实际上**「是不是新设备」由服务端判定并落库，
App 不需要、也不该知道**：它拿到这个信息之后没有任何要做的事（新设备和老设备的界面表现完全
一样，都是「未激活」）。响应体因此不加字段。App 要的那半件事 ——「有没有激活」—— 现有响应
已经给了。

---

## 兼容性与回滚

- **旧版 App**：不发启动请求，行为与现在完全一致；一旦用户打开设置页仍会触发状态查询，从那
  一刻起也会被记录。不存在需要客户端配合才不出错的改动。
- **新版 App 配旧网关**：状态查询照常返回，只是不落记录。也不报错。
- **回滚**：服务端 `git revert` 即可，纯增量、无 schema 迁移、无需清库。已经落进去的设备行留着
  无害（管理页照常展示）。
- **数据起点**：App 改动要发版才对存量用户生效，**统计从新版本铺开那天才开始积累**，历史设备
  补不回来。这是产品上要接受的事实，不是缺陷。

## 风险 / 实现阶段关注

- **`devices` 表增长**：从「兑换过码的设备」变成「打开过 App 的设备」，量级会明显上一个台阶。
  每行约百字节量级，即使一万台也是 MB 级，1C1G 机器无压力；真正要防的是恶意刷表，那由 IP
  限流兜住。
- **`activation_codes.bound_device` 有外键指向 `devices`**（`db.go` schema），本任务只增行不删行，
  不影响既有约束。
- **管理页无需改动**：它按 `GET /admin/devices` 展示，列名已经是「首次上报 / 最近上报」，本任务
  落地后这两个名字的语义自然变准（从「最近兑换」变成「最近打开 App」）。
