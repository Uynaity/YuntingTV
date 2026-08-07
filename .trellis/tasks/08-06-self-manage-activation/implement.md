# 执行计划：用户自助管理激活码 + 去掉换绑冷却

**先服务端、再 App，且服务端必须先部署**：新版 App 会调 `POST /v1/activation/unbind`，旧网关上
这个路径不存在。

服务端在 `radio-proxy/`（独立仓库），App 在主仓库，各自 `git add`/`commit`。

## 执行顺序

### 1. 服务端：修有效期重算的洞（先做这个，它是安全前提）

- [ ] `activation.go` 的 `redeemCode`：`switch` 的第一个分支判据从
      `!boundDeviceStr.Valid || boundDeviceStr.String == ""` 改成 **`!activatedAt.Valid`**，
      并把注释写清为什么不能用 `bound_device`（会被解绑清空 → 老码被当新码重算 → 无限续期）。
- [ ] 换绑分支（`default`）确认 `expires_at`、`activated_at` 一律不动。
- [ ] 新增用例：**已激活的码解绑后重新兑换，到期时间与解绑前一致**（这条是本任务的核心断言，
      对照 `prd.md` 第一条验收项）。
- [ ] 新增用例：管理员强制解绑后重新兑换，到期时间同样不重算。
- [ ] 新增用例：已过期的码解绑后仍不能兑换（不能靠解绑复活）。
- [ ] `go test ./...` 全绿。

### 2. 服务端：移除换绑冷却

- [ ] `activation.go`：删 `rebindCooldown`、`errRebindCooldown`、`activationStatus.NextRebindAt`、
      `redeemCode` 换绑分支里的冷却判定。
- [ ] `gateway.go`：删 `codeErrRebindCooling = 1005` 与 redeem handler 里对应的 `case` 分支。
      **不要把 1006 挪上来填这个空位** —— 线上旧版 App 认的是数值，重排会让限流错误被当成冷却错误。
- [ ] `admin.go`：强制解绑那段注释里「否则……又会被冷却期挡住」的理由已不成立，改掉。功能保留。
- [ ] `last_rebind_at` **继续写入**，但注释说明它已降级为纯审计字段、不参与任何判定。
- [ ] 改写 `activation_test.go` 里 4 处依赖冷却的用例：
      - 冷却拒绝 / `NextRebindAt` 的用例 → 删，替换为「连续换绑不受任何拦截」。
      - **并发用例（`afterCodeRead` 钩子那套）要保留并发保护的断言** —— 它验的是「同一个码被两台
        设备同时兑换只有一个能成」，那是 `FOR UPDATE` 行锁的职责，与冷却无关，别一起删掉。
      - `TestAdminUnbindDevice` 的前置条件（「冷却期内换绑本该被拒」）不再成立，改成验
        「解绑后原设备失权、新设备能兑换、且到期时间不变」。
- [ ] 全项目搜残留：`rebindCooldown` / `errRebindCooldown` / `NextRebindAt` / `codeErrRebindCooling`
      应当只剩零处。

### 3. 服务端：自助解绑接口

- [ ] `activation.go` 加 `unbindDevice(deviceHash) error` —— 只 `SET bound_device = NULL
      WHERE bound_device = $1`，不动 `expires_at`/`activated_at`/`revoked_at`/`last_rebind_at`。
- [ ] `gateway.go` 加 `handleActivationUnbind`：方法校验 → 解析 body → `validDeviceHash` →
      `unbindDevice` → 返回 `activationStatus{}`（未激活）。
- [ ] `main.go` 注册 `mux.HandleFunc("/v1/activation/unbind", handleActivationUnbind)`。
- [ ] **不新增限流器**（Caddy 的 `api_limit` zone 按 `path /v1/*` 已覆盖，理由见 `design.md`）。
- [ ] 用例：解绑后该设备 `checkActivation` 立即为 false；未绑定任何码的设备解绑 → 幂等成功、
      不报错；非法 deviceHash → 400；GET → 405。
- [ ] `go build ./... && go vet ./... && gofmt -l . && go test ./...` 全绿。

### 4. 服务端：提交与部署

- [ ] `radio-proxy/` 内提交并 push。
- [ ] **部署到 `radio.hku.wtf`**：`git pull && docker compose up -d --build`。
- [ ] 部署后 curl 验一遍：兑换 → 解绑 → 重新兑换，确认到期时间没变；再连续换绑几次确认无拦截。

### 5. App：数据层

- [ ] `GatewayApi.kt`：删 `ActivationStatusDto.nextRebindAt` 与 `ActivationCodes.REBIND_COOLING`；
      加 `@POST("v1/activation/unbind")` 与 `UnbindRequest(deviceHash)`。
- [ ] `ActivationRepository.kt`：删 `RedeemResult.Cooling` 与 1005 解析分支；加 `unbind()`。
      `unbind()` 按 error-handling.md 的 **A 类**处理 —— 用户主动发起的破坏性操作，失败必须如实
      告知，**不要**照抄 `status()` 的静默降级。
- [ ] `deviceHash` 为空串时 `unbind()` 直接返回失败提示，不发请求（与 `redeem()` 同款前置）。

### 6. App：ViewModel 与 UI

- [ ] `RadioViewModel`：删「换绑冷却中」文案分支（`RedeemResult` 是 sealed interface，
      编译器会报出遗漏分支，不会静默漏改）；加 `unbindActivation(onResult: (String) -> Unit)`，
      成功后就地把 `activation` 置为 `Inactive`。
- [ ] 新建 `ui/components/ActivationManageDialog.kt`：单个 `Dialog` 内两态（选择 / 解绑确认），
      **不叠两层 Dialog**（TV 焦点管理坑）。确认态默认焦点落在「取消」，照 `ExitConfirmDialog`
      的防误触约定。按钮复用 `DialogButton` + `focusableChrome`，不要用 tv-material3 的 `Button`。
- [ ] `SettingsScreen.kt`：已激活时标题改「管理激活码」并打开管理弹窗；未激活时维持「输入激活码」
      直接进码输入弹窗。管理弹窗选「更新激活码」→ 关掉自己、打开现有 `ActivationCodeDialog`。
- [ ] 解绑确认文案必须包含「**剩余有效期不变**」—— 这是用户最担心的点，也是第 1 步修完那个洞
      之后才敢作出的承诺。
- [ ] `./gradlew testDebugUnitTest assembleDebug` 全绿。

### 7. 联调验证（真机/模拟器，服务端已部署）

- [ ] 已激活设备 → 设置页显示「管理激活码」→ 点开看到两个选项。
- [ ] 选「解绑本设备」→ 出现确认 → 取消，状态不变。
- [ ] 再来一次并确认 → 提示成功、TuneIn 代理开关立刻置灰、条目变回「输入激活码」。
- [ ] 用同一个码在另一台设备兑换 → 成功，**且有效期与解绑前一致**（在管理页设备/激活码列表核对）。
- [ ] 两台设备来回换绑数次 → 每次都成功，无冷却提示。
- [ ] 未激活状态下点条目 → 直接进码输入弹窗，不经管理弹窗。
- [ ] 断网时点解绑 → 有明确失败提示（不是静默无反应）。

### 8. Quality check

- [ ] 对照 `prd.md` 的 Acceptance Criteria 逐条核对。
- [ ] `git diff` 确认没动 `/proxy`、`/seg`、`/v1/stream` 的门禁逻辑。
- [ ] 确认 `FOR UPDATE` 行锁还在（并发保护与冷却无关，别被一起删掉）。

## 验证命令汇总

```bash
cd radio-proxy && go build ./... && go vet ./... && gofmt -l . && go test ./...
```

```bash
./gradlew testDebugUnitTest assembleDebug
```

## 回滚点

- 第 1 步（有效期修复）可独立存在：即使后面几步全不做，它也只是让「解绑后重兑换」不再送时长，
  是纯粹的修正。
- 第 2 步（移除冷却）与第 3 步（自助解绑）**应当一起发布**：只移除冷却而不给自助解绑，用户体验
  没变化；只给自助解绑而不移除冷却，用户解绑后反而被冷却挡住，比现在更糟。
- App 侧改动可整体 revert 回到「更换激活码」的现状，服务端多出来的解绑接口留着无害。
