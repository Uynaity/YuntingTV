# 用户自助管理激活码 + 去掉换绑冷却

## Goal

让用户在 App 设置页里自己管理激活码，不必再找管理员。用户原话：

> 我希望在 App 中用户可以自己解绑激活码，在设置界面中把当前的「更换激活码」换成「管理激活码」，
> 点击后弹窗中可以选择更新或者解绑。
>
> 我其实现在想去掉冷却这个限制了，我就允许用户轮流在多台设备上使用，你能不能清理掉当前有关冷却的代码。

因此本任务包含四件互相耦合的事：设置页新 UI、新增自助解绑接口、**移除换绑冷却**、以及修复一个
只有在「用户能自助解绑」之后才会变成漏洞的既有缺陷。

## 背景 / 已确认事实（代码库证据）

### 缺陷：解绑后重新兑换会把有效期重算成满时长（实测确认，非推测）

`redeemCode`（[activation.go:269-282](../../../radio-proxy/activation.go:269)）用
**`bound_device IS NULL` 判断「首次激活」**，进这个分支就重算 `expires_at = now + durationDays*86400`。
而解绑恰好把 `bound_device` 清成 NULL。

实测（一次性探针，已删除）：

```
一个 30 天的码 → 兑换 → 人为改成「只剩 2 天」→ 解绑 → 重新兑换
→ 有效期又变回满 30 天（比「剩 2 天」多了 28.0 天）
```

现状下影响有限：解绑是管理员手动操作，有人把关。但**一旦用户能自助解绑，这就是无限续期**——
解绑 → 立刻重新兑换 → 满血 30 天，每天来一次即永久有效。且现有解绑还会清掉 `last_rebind_at`，
连冷却都拦不住。

**结论**：自助解绑与这个修复必须同时落地，不能分开发布。

### 换绑冷却的现状与波及面

`rebindCooldown = 7 * 24 * time.Hour`（[activation.go:19](../../../radio-proxy/activation.go:19)），
在 `redeemCode` 的换绑分支里判定。相关代码分布在**两个仓库共 8 个文件**：

| 位置 | 内容 |
|---|---|
| `activation.go` | `rebindCooldown` 常量、`errRebindCooldown`、`activationStatus.NextRebindAt`、换绑分支的判定 |
| `gateway.go` | `codeErrRebindCooling = 1005`、redeem handler 的冷却错误分支 |
| `admin.go` | 强制解绑的注释与「一并清 `last_rebind_at`」的理由（该理由随冷却移除而消失） |
| `db.go` | `last_rebind_at` 列 |
| `activation_test.go` | 4 处依赖冷却的用例（含并发用例与 `TestAdminUnbindDevice` 的前置条件） |
| `GatewayApi.kt` | `nextRebindAt` 字段、`REBIND_COOLING = 1005` |
| `ActivationRepository.kt` | `RedeemResult.Cooling`、1005 的解析分支 |
| `RadioViewModel.kt` | 「该激活码换绑冷却中，X 后可换绑」的提示文案 |

### 设置页现状

[SettingsScreen.kt:187-199](../../../app/src/main/java/cn/radio/tv/ui/SettingsScreen.kt:187)：
`ActionSettingRow` 的标题按激活状态二选一 —— 已激活显示「更换激活码」、未激活显示「输入激活码」，
点击都直接打开 `ActivationCodeDialog`（码输入弹窗，TV 走网格键盘、触摸设备走系统输入法）。
整块 UI 只在选中 TuneIn 来源时出现。

## 关键产品决策（已与用户确认）

| 决策点 | 结论 | 理由 |
|---|---|---|
| 换绑冷却 | **完全移除**，允许一码在多台设备间自由轮流使用 | 用户明确要求。冷却原本是为了挡「一码多机轮流用」，而用户现在就是想允许这件事 —— 留着一套已经不服务于任何目标的机制只是负担 |
| 解绑后重新兑换的有效期 | **不重算**，沿用原到期时间 | `expires_at` 只在「从未激活过」时计算。否则自助解绑 = 无限续期，激活码的有效期形同虚设。同时堵上管理员解绑那条路上的同一个洞 |
| 自助解绑的入口 | 设置页「管理激活码」→ 弹窗选「更新激活码」或「解绑本设备」 | 用户指定的形态 |
| 未激活时的入口 | 保持现状：「输入激活码」直接开输入弹窗 | 没有绑定关系可管理时，多一层「管理」弹窗只是多一次点击 |

## Requirements

### 服务端：移除换绑冷却

1. 删除 `rebindCooldown`、`errRebindCooldown`、`codeErrRebindCooling`，以及 `redeemCode` 换绑分支里
   的冷却判定和 `gateway.go` 里对应的错误响应分支。
2. `activationStatus.NextRebindAt` 字段删除。旧版 App 读不到该字段时按 Kotlin 默认值 `0L` 处理，
   语义正是「不在冷却中」，**不会出错**（`GatewayApi.kt` 的 `nextRebindAt` 有默认值）。
3. `last_rebind_at` **列保留、继续写入**，但不再参与任何判定 —— 它降级为纯审计信息（这个码最近
   一次换设备是什么时候），管理接口的 `lastRebindAt` 字段照常输出。不做 schema 迁移。

### 服务端：有效期不再重算

4. `redeemCode` 改用 **`activated_at IS NULL`** 判断「从未激活过」，只有这种情况才计算
   `expires_at = now + durationDays*86400`；已激活过的码换绑时 `expires_at` 与 `activated_at` 一律不动。
5. 已过期的码仍不能兑换（现有 `expires_at <= now` 判定保留）。

### 服务端：自助解绑接口

6. 新增 `POST /v1/activation/unbind`，body `{deviceHash}`，公开无鉴权（与兑换/状态查询同一信任模型）。
7. **只解绑该设备自己绑定的码**（`WHERE bound_device = $1`），不能通过传别人的 `deviceHash` 之外的
   参数影响其他设备。
8. 解绑**不改动** `expires_at`、`activated_at`、`revoked_at` —— 只清 `bound_device`。这份授权的剩余
   时长不因解绑而消失或重置。
9. **不新增设备级限流器**，复用 Caddy 已有的 `/v1/*` 每 IP 300/分钟限流（`Caddyfile` 的
   `api_limit` zone 按 `path /v1/*` 匹配，新接口天然被覆盖）。兑换接口那个按设备的失败计数器是
   为了挡「爆破枚举激活码」——解绑没有可枚举的秘密，而「知道你 deviceHash 的人恶意解绑你」
   一次调用就够了，加限流也拦不住。为一个挡不住目标威胁的机制多写一套状态不划算。
10. 设备未绑定任何码时解绑是**幂等成功**（返回未激活状态），不报错 —— 用户点两次不该看到错误。

### App：设置页「管理激活码」

11. 已激活时入口标题改为「管理激活码」，副标题保持现有的「已激活，有效期至 X」。
12. 点击后先弹「管理」弹窗，两个选项：**更新激活码**（进现有的码输入弹窗）、**解绑本设备**。
13. 解绑需二次确认，文案要说清后果：本设备将立即失去 TuneIn 代理权限，激活码回到未使用状态、
    **剩余有效期不变**，可在任意设备重新兑换。
14. 解绑成功后就地更新状态为未激活（代理开关随之置灰），不必等下次查询。
15. 未激活时维持现状：标题「输入激活码」，点击直接开码输入弹窗。
16. 移除所有冷却相关的客户端代码与文案（`RedeemResult.Cooling`、1005 分支、「换绑冷却中」提示）。

## Non-goals（明确不做）

- 不改设备标识的取法、不改激活码格式与生成逻辑。
- 不做「解绑历史」「操作记录」等审计功能（`last_rebind_at` 保留为单一时间戳即可）。
- 不做管理页 UI 改动（强制解绑按钮保留，只更新代码注释里已经过时的理由说明）。
- 不给自助解绑加冷却/次数上限之外的门槛（用户明确要允许自由轮换）。
- 不改 `/proxy`、`/seg`、`/v1/stream` 门禁逻辑。
- 不做 schema 迁移（`last_rebind_at` 列留着）。

## Acceptance Criteria

- [ ] 一个已激活的码，在设备 A 上解绑后立刻在设备 B 兑换 → 成功，且**到期时间与解绑前一致**
      （不是 `now + durationDays`）。这是本任务最关键的一条。
- [ ] 同一个码在 A→B→A→B 之间连续换绑 → 每次都成功，**没有任何冷却拦截**，且到期时间自始至终不变。
- [ ] 从未激活过的码首次兑换 → 到期时间为 `now + durationDays`（首次激活的行为不变）。
- [ ] 已过期的码解绑后重新兑换 → 仍被拒（`errCodeExpired`），不能借解绑复活。
- [ ] 管理员强制解绑后重新兑换 → 到期时间同样不重算（同一个洞在管理路径上也被堵上）。
- [ ] `POST /v1/activation/unbind` 只解绑调用方设备绑定的码；未绑定任何码的设备调用 → 幂等成功，
      返回未激活状态，不报错。
- [ ] 解绑后该设备的 `/proxy`、`/seg` 门禁立即拒绝（权限即时失效）。
- [ ] 设置页已激活时显示「管理激活码」；点击出现弹窗，可选「更新激活码」与「解绑本设备」；
      解绑有二次确认；确认后状态立刻变未激活、TuneIn 代理开关置灰。
- [ ] 设置页未激活时仍显示「输入激活码」，点击直接进码输入弹窗（不经管理弹窗）。
- [ ] 全项目搜不到 `rebindCooldown` / `errRebindCooldown` / `NextRebindAt` / `nextRebindAt` /
      `codeErrRebindCooling` / `REBIND_COOLING` / `RedeemResult.Cooling` 的残留。
- [ ] `cd radio-proxy && go test ./...` 全绿（含改写后的既有冷却相关用例）。
- [ ] `./gradlew testDebugUnitTest assembleDebug` 全绿。

## 需要接受的权衡

- **去掉冷却后，激活码泄露的后果变严重**。此前 7 天冷却是一层缓冲；现在任何拿到码的人都能立刻
  绑到自己设备上，把原用户踢下来。码本身是 12 位、31 字符集（约 7.9e17 组合），枚举不可行，
  所以真正的风险是用户自己把码发出去。这是「允许多设备轮流使用」的必然代价，用户已明确接受。
- **`deviceHash` 就是身份凭证**。自助解绑凭 `deviceHash` 认人，知道某台设备哈希的人就能解绑它。
  这与既有的状态查询/兑换是同一信任模型（都只凭 `deviceHash`），本任务不改变它，但因为多了一个
  破坏性操作（让设备立即失权），风险面确实扩大了一点。缓解手段是按设备限流。

## 部署提醒

服务端改动在 [yuntingtv-radio-proxy](https://github.com/Uynaity/yuntingtv-radio-proxy)，push 后需手动
部署到 `radio.hku.wtf`（无 CI/CD）。**先部署服务端再发 App**：新版 App 会调用
`POST /v1/activation/unbind`，旧网关上这个路径不存在，解绑会失败。
