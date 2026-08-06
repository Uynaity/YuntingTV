# 实施计划

> 实际按 阶段0 / A / B / C / D / E 六段提交，与下面的原始分期不完全同名。
> 对应关系：阶段0 = 阶段 0；A = 止血（阶段 1、2 里的 P0/P1）；B = 阶段 2；
> C = 阶段 3；D + E = 阶段 1 的请求拓扑。收尾即阶段 4。

## 阶段 0：基线与观测（`13b5e53`）

- [x] 记录当前 release/debug 版本、无测试现状和代表性低端 TV 配置。
- [x] 加入仅 debug 使用的请求计数、Compose 重组计数入口，不在 release 打 BODY 日志。
      → `perf/PerfCounters.kt`，`BuildConfig.DEBUG` 是编译期常量，release 整段剪掉。
- [ ] **未做**：Macrobenchmark/Perfetto 场景与基线数据。
      阻塞：本项目没有低端 TV 实体设备，帧时长/GPU 指标在模拟器上不具参考性。
      替代方案：改用**与设备无关的确定性计数**（请求扇出、图片解码次数、网格重组次数）
      做前后对比 —— 这三项在任何设备上读数一致，可复现、可断言。
      读法：`adb logcat -s PerfCounters`（onStart 清零，onStop 打印）。

回滚点：只删除观测代码即可，不触碰运行时行为。

## 阶段 1：状态与请求拓扑重构（`417e8ef` `b4ef209` `56ef542` `750e2fb` `e71ed01`）

- [x] 引入 `BrowseQuery`、repository 接口，补复合 ID/key（`gridKeyOf(source, channel)`）。
- [x] 用 Paging 3 替换频道/搜索手写分页；UI 交互与收藏语义不变。
- [x] 来源/筛选流改为 `flatMapLatest`，移除 `channelsGeneration` 代次 + 三个 job 引用的组合；
      取消沿协程边界传到 Retrofit。
- [x] 引入 `ProgramRepository` 的 keyed TTL/single-flight，删除刷新路径中的全量扫描。
      网关新增 `GET /v1/channels/by-ids` 后，两条「按 contentId 找几个台」的全量下载
      路径改走批量查；接口未部署时保留旧快照降级（`GatewaySource.byIdsSupported`）。

验证：66 项单测（分页边界、查询整形虚拟时间、节目单 TTL/single-flight/取消、
收藏刷新降级、能力探测）。

回滚点：可临时把 PagingSource 接回旧 `RadioSource` 实现，UI 不需回退到全量列表。

## 阶段 2：播放生命周期与失败隔离（`866cd2c` `869e035`）

- [x] 可取消且可失败的 `PlaybackConnection`，修复 future 回调未保护 `get()` 与永久等待。
- [x] `PlaybackIntent` + `collectLatest` 统一直播/回放/切台/切回直播，latest-wins。
- [x] 自动播放/流类型解析移出首屏关键路径；更新检查首屏后启动。
- [x] 播放器重试按错误类型分类（`PlaybackErrorPolicy`），有限指数退避 + jitter。
- [x] 进度改冷 `Flow`，收集范围收窄到播放器面板，无订阅/后台时停 ticker。

回滚点：PlaybackService 保持原 MediaSession 协议。

## 阶段 3：图片与全屏渲染重构（`523f7e4`）

- [x] 一次解码的 `ArtworkRepository`（Palette + 预模糊图共享），有界 LruCache（8 条约 1.2MB）。
- [x] 去除 API 31+ 全屏原图 `Modifier.blur`，统一静态预模糊背景；转场改为仅淡入。
- [x] ChannelCard / PlayerPanel / FullScreenPlayer / UpdateDialog 设明确 Coil 尺寸与无 crossfade。

实测（真机）：GL 平台期 231.6 → 73.6MB（-68%），PSS 378 → 233MB（-38%）。

回滚点：ArtworkRepository 可回退到无模糊纯色背景。

## 阶段 4：收口与交付

- [x] 真机走查完整手工流程，确认无请求风暴、无 key 崩溃、无主线程异常。
      设备：PLJ110 / SDK 36（**手机，非低端 TV** —— 见阶段0 的阻塞说明）。
- [x] `./gradlew testDebugUnitTest lintDebug assembleRelease`：66 项单测全过，
      lint 0 error / 15 warning（全部为既有项：IconDuplicates 5、UnusedResources 4、
      ModifierParameter 3、ConstantLocale / ExportedService / OldTargetApi 各 1），release 构建通过。
- [x] 更新 Trellis spec：删掉已被证伪的 `NO_LIMIT` 条目，补「不要为按 id 找几个台下载整份
      列表」「分页由 Paging 3 负责」「缓存不能挡住节目边界重试」，修正 `currentProgramWindow`
      的去向。外部前置条件（`/v1/channels/by-ids`）已在 radio-proxy 实现并部署。
- [x] 分阶段提交并保留回滚点（六段，每段独立可回滚）。
- [ ] **未做**：低端 TV 上的 benchmark。同阶段0 的阻塞，无设备。
