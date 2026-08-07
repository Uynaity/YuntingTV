# 执行计划：App 启动上报设备 + 服务端记录

两个仓库分别提交：服务端在 `radio-proxy/`（独立仓库，本机 `/Users/ai/YuntingTV/radio-proxy`），
App 在主仓库。**先做服务端并部署，再发 App** —— 反过来的话新版 App 上报了但服务端还没落记录，
那段时间的设备白丢了。

## 执行顺序

### 1. 服务端：取客户端 IP + 新设备限流

- [ ] 新建 `radio-proxy/device_report.go`：`clientIP`、`allowNewDevice`、`reportDevice`
      （三者的实现要点见 `design.md`）。
- [ ] `clientIP` **取 X-Forwarded-For 最右一段**，不是最左。这是本任务最容易写错、写错了
      限流会完全失效且没有任何报错的一处 —— 注释要把原因写在代码里。
- [ ] `allowNewDevice` 独立一份限流器，不复用 `redeemLimiter`（key 空间与语义都不同）。
      清理策略照 `recordRedeemFailure`：只在写入点、只在超 `newDeviceMaxKeys` 时遍历删过期项。
- [ ] `reportDevice` 走 UPDATE → 命中就返回、未命中才过限流再 INSERT（`ON CONFLICT DO NOTHING`）。

### 2. 服务端：接到状态查询上

- [ ] 改 `gateway.go` 的 `handleActivationStatus`：在 `deviceHash` 格式校验**之后**、返回之前
      调一次 `reportDevice`。
- [ ] 错误只记日志、不改变响应。**日志里不得出现 deviceHash**（logging-guidelines.md）。
- [ ] 确认没有给 `/proxy`、`/seg`、`/v1/stream` 加任何写入 —— 收尾时用 `git diff` 核一遍。

### 3. 服务端：测试

新建 `radio-proxy/device_report_test.go`，复用 `activation_test.go` 既有的 `setupTestDB`
与 `devA`/`devB` 常量，**不要另起一套 DB fixture**：

- [ ] 新设备走一次 `/v1/activation/status` → 出现在 `devices`，`activeCode` 为空。
- [ ] 同一设备再走一次 → `last_seen_at` 更新、`first_seen_at` 不变、**不新增行**。
- [ ] 限流：同一 IP 连续用 `newDeviceLimit + 1` 个随机新设备哈希请求 → 最后一个不落库，
      但接口仍返回 **200 且激活状态正确**（不是 429、不是错误）。
- [ ] 限流耗尽后，**老设备的 `last_seen_at` 仍能更新**（限流只拦新建）。
- [ ] `clientIP`：
      - 有 XFF `"1.2.3.4, 5.6.7.8"` → 取 `5.6.7.8`（最右）。
      - 伪造最左段但最右段相同的两个请求 → 限流 key 相同（即伪造无效）。
      - 无 XFF → 从 `RemoteAddr` 剥掉端口。
- [ ] 写入失败不影响状态返回（可临时把 `devices` 表改名或用取消的事务制造失败；若难以构造，
      至少断言 `reportDevice` 的错误不会传播到响应码）。
- [ ] `go test ./...` 全绿、`go vet ./...`、`gofmt -l .` 无输出。

### 4. 服务端：提交与部署

- [ ] 在 `radio-proxy/` 内提交（不进主仓库）。
- [ ] push 到 `yuntingtv-radio-proxy`。
- [ ] **部署到 `radio.hku.wtf`**：`git pull && docker compose up -d --build`（该仓库无 CI/CD）。
      本任务不新增环境变量/卷，部署步骤与现状一致。
- [ ] 部署后用 curl 验一次真实生效：拿一个没见过的设备哈希请求
      `GET /v1/activation/status?deviceHash=<64位十六进制>`，然后在管理页设备列表里确认它出现了。

### 5. App：启动时查一次

- [ ] `RadioViewModel.init` 里加 `refreshActivation()`（与其余 `viewModelScope.launch` 并列，
      注释说明这同时承担「让服务端记录本设备」与「预热设置页开关状态」两件事）。
- [ ] **不要改** `ActivationRepository.status()` —— 空 `deviceHash` 跳过与失败静默降级都已具备。
- [ ] 确认没有引入启动阻塞：这一跳在 `viewModelScope` 里异步跑，不在任何 `runBlocking`/主线程
      同步路径上。
- [ ] `./gradlew testDebugUnitTest` 全绿（既有 11 个单测不能被打破）。
- [ ] `./gradlew assembleDebug` 能过。

### 6. 联调验证（真机/模拟器）

`RadioViewModel` 的构造依赖 Media3/Android 运行时，单测里搭不起来，**这一步只能手动验**：

- [ ] 装一个全新的（或清了数据的）App，**只打开、什么都不做**，然后在管理页设备列表里看到它，
      `当前生效码` 为空 —— 这就是本任务的核心验收项。
- [ ] 再次启动 App → `最近上报` 更新、`首次上报` 不变、列表里仍是一行。
- [ ] 关掉网络启动 App → 界面正常、无任何提示、能正常进入（失败静默）。
- [ ] 在该设备上兑换一个码 → 管理页该设备出现生效码，且**仍是同一行**（没有因为上报而分裂成两行）。

### 7. Quality check

- [ ] 对照 `prd.md` 的 Acceptance Criteria 逐条核对。
- [ ] `git diff` 确认服务端只动了 `gateway.go` 的 `handleActivationStatus` + 两个新文件；
      `activation.go` 的核心逻辑与 `/proxy` `/seg` 一行未改。
- [ ] 确认日志里没有 deviceHash、没有 IP 明文之外的敏感信息。

## 验证命令汇总

```bash
cd radio-proxy && go build ./... && go vet ./... && gofmt -l . && go test ./...
```

```bash
./gradlew testDebugUnitTest assembleDebug
```

## 回滚点

- 服务端：`git revert` 即可（纯增量、无 schema 迁移）。已落库的设备行留着无害。
- App：改动只有一行 `refreshActivation()`，revert 后回到「只有打开设置页才查状态」的现状。
- 应急下线：服务端 revert 并重新部署即可；不需要单独的 feature flag —— 这个功能没有「开着但
  出问题」的中间态，写不进去本来就已经是静默降级。
