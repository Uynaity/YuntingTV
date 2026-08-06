# 当前版本性能与稳定性审查

审查基线：`gateway-edition` HEAD `4819a83`，只读源代码审查；未修改生产代码。旧报告
`.trellis/tasks/archive/2026-07/07-15-android-tv-performance-audit/audit.md` 仅作差异参考。

## Findings

### P0/P1：存在直接闪退或错误状态写回路径

1. **P0（连接失败可主线程崩溃）**：`RadioViewModel.kt:209-215,444-451` 在构造 ViewModel 时立即
   `MediaController.Builder.buildAsync()`，主线程 listener 内直接调用 `controllerFuture.get()`。
   服务创建/绑定/ExoPlayer 初始化失败时 future 会以异常完成，该异常没有转成 UI 状态，可能从主线程
   未捕获退出进程；连接失败时所有 `controller()` 调用又会永久等待 `controllerState`。
   这是低端设备冷启动时最危险的稳定性问题。

2. **P1（跨来源收藏的 LazyGrid key 可能崩溃）**：`RadioViewModel.kt:139-145` 把不同来源的收藏
   投影成裸 `Channel`，`RadioScreen.kt:582-598` 以 `channel.contentId` 作为唯一 key。不同来源的
   ID 空间未声明全局唯一；一旦相同 ID 同时收藏，Compose 会拒绝重复 key，且
   `toggleFavorite/playChannel` 也会按裸 ID 误路由来源。必须让来源和频道 ID 一直成对存在。

### P1：低性能 TV 上会放大卡顿、等待和网络峰值

3. **P1（进度 ticker 触发根屏重组）**：`RadioViewModel.kt:452-457` 在 ViewModel 整个生命周期每
   500ms 更新 `progress`；`RadioScreen.kt:103-106` 在根节点收集该 flow，随后把值传给
   `playerPane`/`FullScreenPlayer`（`RadioScreen.kt:630-684`）。即使 Compose 能跳过部分稳定子项，
   每次更新仍重新执行根页面的状态读取、筛选映射、AnimatedContent 分支和 Grid DSL；低 CPU TV 会
   在持续播放时承担不必要的布局/重组压力。现有 spec 要求“面板单独收集”，当前实现没有满足。

4. **P1（启动关键路径被播放器串行阻塞且请求扇出）**：`RadioViewModel.kt:444-527` 同时建立服务
   连接、启动 ticker、收集偏好、开始来源加载和检查更新；`loadSource` 在
   `RadioViewModel.kt:646-677` 中先等待自动播放的流解析（`playNow`），再并发拉筛选项并启动频道
   列表。Activity 前台后 `RadioScreen.kt:199-206` 又立即触发节目/列表刷新。一次冷启动可能同时
   运行 MediaSession/ExoPlayer、流解析、筛选 2 请求、首屏频道、节目单/全量列表和更新检查，网络
   慢时首屏会被播放链路拖住，弱设备的线程、JSON 解析和 GC 峰值叠加。

5. **P1（来源、播放和节目单请求没有统一 latest-wins）**：
   - `RadioViewModel.kt:486-492,625-678` 的来源 collector 顺序等待 `loadSource`；来源快速切换
     时旧来源的筛选和频道 job 仍可运行，旧 job 在新来源状态下写回。
   - `RadioViewModel.kt:965-988` 每次选台都新建 `playNow` 协程，没有取消旧的 stream 解析；慢响应
     可能覆盖后来选中的电台，随后 `resolveLiveWindow`（`369-381`）继续为错误频道写进度窗口。
   - `RadioViewModel.kt:1033-1063,1067-1078` 的节目单/回放解析只用局部 token 或无 token，且
     `runCatching` 会吞掉取消；跨频道/日期快切会浪费请求并允许旧任务继续运行。
   这些路径在低端 TV 的慢网络上更容易长期堆积并反复触发主线程播放器操作。

6. **P1（节目刷新存在全量扫描热路径）**：`RadioViewModel.kt:703-743` 的半点/节目边界刷新先拉当前
   筛选的已加载页，找不到播放台时再对播放地区 `limit = RadioSource.NO_LIMIT`；收藏刷新
   `RadioSource.kt:122-155` 还按每个地区发全量频道请求。TuneIn 等大目录会造成大 JSON、映射、
   HashMap 和内存峰值；该刷新在前台循环和启动续播路径可能重复发生。应以批量按 ID/当前节目接口
   取代，而不是继续扩大客户端分页或增加定时器。

7. **P1（全屏 GPU/内存峰值）**：`PlayerPanel.kt:862-884` 在 API 31+ 对铺满屏幕的原图执行
   `scale(1.5f).blur(60.dp)`；`RadioScreen.kt:663-685` 用 `AnimatedContent` 同时保留首页 Grid
   与全屏画面完成转场。弱 GPU/低内存 TV 进入全屏时可能产生大 offscreen layer、长帧或 bitmap/GPU
   OOM；中心封面还会再次请求同一 URL（`PlayerPanel.kt:927-933`）。应统一有界尺寸预模糊并按
   设备能力禁用重型重叠转场。

### P2：持续成本、错误恢复和可维护性风险

8. **P2（永久错误每 3 秒重建媒体源）**：`RadioPlayer.kt:164-203` 对所有 `PlaybackException`
   都进入 60 秒恢复窗口，每 3 秒 `setMediaItem + prepare`。不支持的格式、404、永久鉴权/解析错误
   也会重复消耗网络、TS 解析和主线程工作；应区分可重试错误并使用有界指数退避+jitter。

9. **P2（全屏视觉重复解码）**：`PlayerPanel.kt:769-778,1037-1095` 分别以 64px 和 256px 请求
   同一封面计算 Palette/模糊图，API 31+ 还改为第三次全屏原图渲染。虽然 Coil 有缓存，尺寸不同仍
   可能产生多份 bitmap 和两次解码/调色；应由单一 artwork pipeline 产出共享结果。

10. **P2（列表热路径有可避免的分配）**：`RadioScreen.kt:486-516` 每次根重组重新构建筛选
    `FilterItem` 列表；收藏视图在 `RadioScreen.kt:586-588` 对每个可见 item 线性查找来源；状态
    同时保留 `channels`、`searchResults` 和收藏频道快照。单项成本不大，但在 P1 根重组和大收藏量
    下会累计，应由 repository/view-state 预计算稳定 projection。

11. **P2（取消语义不一致）**：`RadioViewModel.kt:497-503,605-618,703-781,1039-1063`
    多处 `runCatching` 捕获所有 Throwable，包括 `CancellationException`；取消后的协程仍可能执行
    状态更新或后续降级逻辑。当前不一定崩溃，但会使请求取消、loading 标志和错误显示难以证明正确。

12. **P2（缺少可重复验证）**：仓库没有 `app/src/test`、`androidTest` 业务测试或 Macrobenchmark；
    现有 `app/build/reports/lint-results-debug.txt`（2026-07-27）为 0 errors/15 warnings，早于
    当前 HEAD，不能证明本次代码已通过验证。`./gradlew testDebugUnitTest lintDebug` 本轮因用户级
    Gradle 缓存权限审批返回 503 未能运行。

## 已存在的正向措施

- 频道首屏分页和 `channelsGeneration` 取消/代次校验已在 `82701f0` 引入，避免旧筛选覆盖和全量
  首屏；新设计会用 Paging 3 统一替代手写 job，而不是回退到全量列表。
- APK 下载已绑定 OkHttp `Call.cancel()`、节流百分比回调并保留取消异常（`UpdateInstaller.kt:27-69`）。
- 收藏 JSON 已先对原始串 `distinctUntilChanged()` 再解码（`UserPreferences.kt:95-105`）。
- MediaSource 已按 HLS/渐进式类型分派，并保留忽略无效 H.264 PID 的定制。

## 需要设备测量才能定量的项目

- API 31+ `RenderEffect` 的实际 GPU 内存和长帧占比；须与统一预模糊图对照。
- 根屏进度重组的实际 recomposition 次数与布局耗时；用 Compose tracing/重组计数确认跳过范围。
- 冷启动到首屏/首声、滚动 5 分钟的 Java/native/GPU heap、GC、帧 P95/P99 和网络峰值。
- 低端 TV 上 Media3 解码器错误类别，确定哪些错误可重试以及退避上限。
