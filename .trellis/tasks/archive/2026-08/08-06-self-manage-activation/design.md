# 技术设计：用户自助管理激活码 + 去掉换绑冷却

## 边界与契约

- 服务端在 [yuntingtv-radio-proxy](https://github.com/Uynaity/yuntingtv-radio-proxy)（本机 `radio-proxy/`），
  App 在主仓库，各自提交。
- **不改数据库 schema**：`last_rebind_at` 列保留（降级为纯审计字段），不做迁移。
- **不改** `/proxy`、`/seg`、`/v1/stream` 的门禁逻辑。解绑后权限即时失效是 `lookupActivation` 查
  `bound_device` 的天然结果，不需要额外的失效通知。

### 改动清单

| 文件 | 动作 |
|---|---|
| `radio-proxy/activation.go` | 删冷却常量/sentinel/`NextRebindAt`；`redeemCode` 换判据；新增 `unbindDevice` |
| `radio-proxy/gateway.go` | 删 1005 错误码与冷却分支；新增 `handleActivationUnbind` |
| `radio-proxy/main.go` | 注册 `/v1/activation/unbind` |
| `radio-proxy/admin.go` | 只改注释（强制解绑的理由随冷却消失而过时） |
| `radio-proxy/activation_test.go` | 改写 4 处依赖冷却的用例 |
| `app/.../GatewayApi.kt` | 删 `nextRebindAt`/`REBIND_COOLING`；加 unbind 接口与请求体 |
| `app/.../ActivationRepository.kt` | 删 `RedeemResult.Cooling` 与 1005 分支；加 `unbind()` |
| `app/.../RadioViewModel.kt` | 删冷却文案；加 `unbindActivation()` |
| `app/.../SettingsScreen.kt` | 入口改名 + 接管理弹窗 |
| `app/.../components/ActivationManageDialog.kt` | 新增 |

---

## 服务端：`redeemCode` 的判据从 `bound_device` 换成 `activated_at`

### 问题的根

现在用 `bound_device IS NULL` 判断「首次激活」，但 `bound_device` 是**会被清空的**（解绑），
于是「解绑过的老码」和「全新的码」在这个判据下长得一模一样 —— 老码因此拿到一份全新的
`expires_at`。

`activated_at` 则是**只写一次、永不清空**的：它只在首次激活分支写入，解绑不动它。用它当判据，
「从未激活过」才是真的从未激活过。

### 改后的三个分支

```go
switch {
case !activatedAt.Valid:
    // 从未激活过 —— 只有这一种情况计算有效期,从此刻起算。
    // 判据是 activated_at 而不是 bound_device:后者会被解绑清空,导致解绑过的老码
    // 被当成新码重算有效期(= 无限续期)。activated_at 只写一次、解绑不动。
    newExpires := now + durationDays*86400
    UPDATE SET bound_device = dev, activated_at = now, expires_at = newExpires, last_rebind_at = now

case boundDeviceStr.Valid && boundDeviceStr.String == deviceHash:
    // 已绑本设备:幂等返回,不刷任何时间戳。

default:
    // 重新绑定。两种来路:解绑后 bound_device 为 NULL,或从别的设备抢过来。
    // expires_at 与 activated_at 一律不动 —— 换设备是「同一份授权换台机器用」,不是续期。
    UPDATE SET bound_device = dev, last_rebind_at = now
}
```

`last_rebind_at` 仍然写，但**不再有任何读者做判定** —— 它降级成「这个码最近一次换设备是什么
时候」的审计信息，管理接口的 `lastRebindAt` 照常输出。保留写入的成本是零，删掉反而要动 schema
和管理接口的响应结构。

### 保留不变

- `FOR UPDATE` 行锁**必须保留**。冷却没了不代表并发没了：同一个码被两台设备同时兑换，不加锁
  依然会双双读到旧的 `bound_device`、后写覆盖先写。锁的理由从来不是冷却，是「读-改-写」窗口本身。
- 已吊销、已过期的判定原样保留。**已过期的码不能靠解绑复活** —— `expires_at <= now` 的检查在
  分支之前，跟 `bound_device` 是不是 NULL 无关。

---

## 服务端：`POST /v1/activation/unbind`

```go
// POST /v1/activation/unbind  body: { deviceHash }
//
// 用户自助解绑:让本设备立即失去权限,激活码回到未使用状态。
//
// 只解绑**调用方设备自己**绑定的码(WHERE bound_device = $1),传不了别的目标。
// 认人只凭 deviceHash —— 与状态查询/兑换是同一信任模型,本接口不改变它。
func handleActivationUnbind(w http.ResponseWriter, r *http.Request) { ... }

// unbindDevice 清掉该设备的绑定。
//
// **不动 expires_at / activated_at / revoked_at**:这份授权的剩余时长不因换台机器而消失,
// 也不因此重置(重置就是无限续期,见 design 开头)。
//
// 也不清 last_rebind_at:它已经不参与任何判定,清不清都不影响行为,留着作为审计更完整。
// (管理面的强制解绑当初清它是为了绕开冷却 —— 那个理由随冷却移除一起消失了。)
func unbindDevice(deviceHash string) error {
    _, err := activationDB.Exec(
        `UPDATE activation_codes SET bound_device = NULL WHERE bound_device = $1`, deviceHash)
    return err
}
```

**幂等**：没绑任何码时 `RowsAffected` 为 0，照样返回成功 + 未激活状态。用户点两次不该看到错误 ——
第二次的结果和他想要的一致（这台设备没有绑定），报错只会让人困惑。

**响应**：直接回 `activationStatus{}`（即 `activated:false`），与状态查询同结构，客户端复用同一条
反序列化路径，不必为解绑单独定义响应模型。

**限流**：不新增。`Caddyfile` 的 `api_limit` zone 按 `path /v1/*` 匹配，新接口天然被 300/分钟/IP
覆盖。兑换那个按设备的失败计数器是为了挡激活码枚举，解绑没有可枚举的秘密；而「知道你
deviceHash 的人恶意解绑」一次调用就够，限流拦不住 —— 为挡不住目标威胁的机制多写一套状态不划算。

---

## 服务端：移除冷却的连带影响

- `activationStatus.NextRebindAt` 删除后，**旧版 App 不会出错**：`GatewayApi.kt` 的
  `nextRebindAt` 有默认值 `0L`，字段缺失时就是 0，语义正是「不在冷却中」。
- `codeErrRebindCooling = 1005` 删除。错误码是稀疏枚举，**不要把 1006 挪上来填坑** —— 线上旧版
  App 认的是数值，重排会让 `codeErrRateLimited` 被当成冷却错误。留个空位是正确的。
- `admin.go` 强制解绑的注释里「否则用户拿着解绑后的码去别的设备兑换，又会被冷却期挡住」这段
  理由已经不成立，要改。功能本身保留（管理员仍可能需要「让某台设备立刻失权」）。

---

## App：设置页与管理弹窗

### 入口

```kotlin
ActionSettingRow(
    title = if (active != null) "管理激活码" else "输入激活码",
    subtitle = ...,  // 不变
    onClick = {
        // 已激活才进「管理」这一层:没有绑定关系可管时,多一层弹窗只是多一次点击。
        if (active != null) showManageDialog = true else showActivationDialog = true
    },
)
```

### 弹窗形态：一个弹窗内两态，不叠两层 Dialog

`ActivationManageDialog` 内部用一个 `var confirmingUnbind by remember { mutableStateOf(false) }`
在「选择态」与「解绑确认态」之间切换：

- **选择态**：两个按钮 —— 「更新激活码」「解绑本设备」。
- **确认态**：说清后果 + 「取消 / 确定解绑」，**默认焦点落在「取消」**（照
  `ExitConfirmDialog` 的防误触约定 —— 遥控器上确定键就在手边，破坏性操作不能默认选中）。

**为什么不叠两层 `Dialog`**：TV 上多层弹窗的焦点管理很容易出问题（上层关闭后焦点不回下层、
返回键层层穿透）。同一个 `Dialog` 内换内容，焦点只需在切换时 `requestFocus` 一次。

按钮复用现有的 `DialogButton` + `focusableChrome`（foundation `clickable`），与
`ExitConfirmDialog`/`ActivationCodeDialog` 保持一致 —— 触屏点按与遥控方向键都要能触发，
tv-material3 的 `Button` 在触屏上表现不一致，项目里已经统一绕开它了。

### 确认文案

> 解绑后本设备将立即失去 TuneIn 代理权限。激活码会回到未使用状态，**剩余有效期不变**，
> 可在任意设备重新兑换。

「剩余有效期不变」必须写出来 —— 这正是用户最会担心的事（怕一解绑码就废了），而这也是本任务
特意修掉那个洞之后才敢承诺的事。

### 数据层

```kotlin
// ActivationRepository
suspend fun unbind(): UnbindResult   // 与 redeem 同款:用户主动发起,失败必须如实告知(A 类)
```

- 失败**不静默**：这是用户主动点的破坏性操作，按 error-handling.md 的 A 类必须给反馈
  （与 `status()` 的 B 类静默降级相反）。
- 成功后 `RadioViewModel` 就地把 `activation` 置为 `Inactive`，TuneIn 代理开关随之置灰，
  不等下次查询。

### 冷却代码清理

`RedeemResult.Cooling`、1005 分支、`nextRebindAt` 字段、「换绑冷却中，X 后可换绑」文案全删。
删 `Cooling` 后 `RadioViewModel` 里那个 `when` 少一个分支 —— 注意 `RedeemResult` 是 sealed
interface，编译器会把遗漏的分支报出来，不会静默漏改。

---

## 兼容性与回滚

- **新版 App 配旧网关**：`/v1/activation/unbind` 不存在 → 404 → 解绑失败并如实提示。不会崩，
  但功能不可用 —— 所以**必须先部署服务端**。
- **旧版 App 配新网关**：冷却字段消失 → 默认值 0 → 旧 App 认为「不在冷却中」，正确；它也永远
  收不到 1005 了，而那个分支只是提示文案，走不到不影响功能。
- **回滚**：服务端 `git revert` 即可。已经因为新逻辑而「没被重算有效期」的码不会因回滚而出错
  （数据本来就是对的，回滚只是让后续兑换又开始重算）。无 schema 迁移。

## 风险 / 实现阶段关注

- **改的是兑换核心逻辑**，`activation_test.go` 里有并发用例（`afterCodeRead` 钩子那套）依赖冷却
  语义，改写时要保证它验的仍是「同一个码被两台设备同时兑换只有一个能成」这件事，别把并发保护
  一起删了。
- **`activated_at` 为 NULL 但 `bound_device` 非空**的数据在正常路径下不存在（两者同时写入），
  只有人工改库能造出来。改后的 `switch` 会把它当成「从未激活」并重算有效期 —— 与改前
  (`bound_device` 非空 → 走换绑) 行为不同。这是人工改库才触发的边角，不值得为它加防御分支，
  但实现时心里要有数。
- **去掉冷却后码泄露的后果变严重**（见 `prd.md` 的权衡一节），这是产品决策，不是可以在实现层
  弥补的事。
