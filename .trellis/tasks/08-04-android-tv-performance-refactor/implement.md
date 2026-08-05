# 实施计划

## 阶段 0：基线与观测

- [ ] 记录当前 release/debug 版本、无测试现状和代表性低端 TV 配置。
- [ ] 加入仅 debug/benchmark 使用的请求计数、Compose 重组计数和内存/帧时长采集入口，不在 release 打 BODY 日志。
- [ ] 建立 Macrobenchmark/Perfetto 场景与基线数据；若暂时没有实体设备，先完成可运行的 benchmark harness 并记录阻塞。

回滚点：只删除 benchmark/观测模块即可，不触碰运行时行为。

## 阶段 1：状态与请求拓扑重构

- [ ] 引入 `BrowseQuery`、`FavoriteItem` 和 repository 接口，补复合 ID/key。
- [ ] 用 Paging 3 替换频道/搜索手写分页；先保持 UI 交互和收藏语义不变。
- [ ] 将来源/筛选流改为 `flatMapLatest`，移除命令式 generation/job 组合；确保取消向 Retrofit 传播。
- [ ] 引入 ProgramRepository 的 keyed TTL/single-flight，删除刷新路径中的全量扫描；在网关接口未具备前保留旧快照降级。

验证：repository/ViewModel 单测、快速筛选 MockWebServer 逆序响应、分页滚动和收藏跨源 key 测试。

回滚点：可临时把 PagingSource 接回旧 `RadioSource` 实现，UI 不需回退到全量列表。

## 阶段 2：播放生命周期与失败隔离

- [ ] 实现可取消且可失败的 PlaybackConnection，修复 future 回调未保护 `get()` 和永久等待。
- [ ] 让 PlaybackCoordinator 统一处理直播、回放、切台和切回直播，使用 latest-wins + 串行 MediaController 操作。
- [ ] 将自动播放/流类型解析从首屏关键路径移出；更新检查在首屏后低优先级启动。
- [ ] 将播放器重试按错误类型和网络状态分类，采用有限指数退避+jitter，并在 release/媒体切换时清理所有 callback。
- [ ] 将进度 Flow 迁移到 PlayerPane/FullscreenPlayer 的最小收集范围，前后台和无订阅时停止 ticker。

验证：fake MediaController/播放器错误矩阵、快速切台、连接失败、后台/回前台、断网恢复和取消测试。

回滚点：PlaybackService 保持原 MediaSession 协议；协调器可以切回现有 `MediaController` 命令路径。

## 阶段 3：图片与全屏渲染重构

- [ ] 实现一次解码的 ArtworkRepository（Palette + 低分辨率模糊图共享），限制 bitmap 尺寸和缓存容量。
- [ ] 去除 API 31+ 全屏原图 `Modifier.blur`，统一静态背景策略；低内存设备禁用重叠屏幕转场。
- [ ] 为 ChannelCard、PlayerPanel、FullScreenPlayer、UpdateDialog 设置明确的 Coil 尺寸、缓存和无 crossfade 配置。

验证：全屏进入/退出 GPU/bitmap 内存、长帧、GC 和图片请求数量对比。

回滚点：ArtworkRepository 可回退到无模糊纯色背景，不影响播放功能。

## 阶段 4：收口与交付

- [ ] 在代表性低端 TV 运行完整手工流程和 benchmark，确认无请求风暴、无 key 崩溃、无主线程异常。
- [ ] 运行 `./gradlew testDebugUnitTest lintDebug assembleRelease`；修复新增错误，评估并记录现有 warning。
- [ ] 更新相关 Trellis spec（状态收集、取消/错误、批量接口、性能测试约定），写入验证数据和已知外部前置条件。
- [ ] 最后一次全范围代码审查，分阶段提交并保留回滚点。

