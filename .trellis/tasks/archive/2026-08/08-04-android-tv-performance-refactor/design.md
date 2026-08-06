# 技术设计

## 现状证据与边界

当前 `RadioViewModel` 同时拥有偏好流、来源切换、频道分页、搜索、节目单、播放控制、进度 ticker 和更新下载。`RadioScreen` 在根节点收集 `uiState`、`progress` 和更新状态，导致 500ms 进度变更进入整个页面的重组入口。网络数据源已经使用 `Dispatchers.IO`，因此主要问题是请求拓扑、生命周期和渲染范围，而不是把 Retrofit 再包一层线程切换。

重构保持 `PlaybackService` 持有 ExoPlayer 和 MediaSession 的职责；服务连接、频道浏览和节目数据通过明确的 repository/coordinator 边界交给 ViewModel。DataStore 仍只保存用户偏好和收藏快照，不承载远端频道大列表。

## 目标结构

```text
MainActivity
  -> RadioScreen
       -> BrowsePane (只收集 BrowseUiState / LazyPagingItems)
       -> PlayerPane (只收集 PlayerUiState)
       -> FullscreenPlayer (只收集 PlayerUiState + ArtworkState)
       -> Overlay/Settings panes

RadioViewModel
  -> BrowseCoordinator -> ChannelRepository -> GatewaySource -> GatewayApi
  -> ProgramRepository (keyed cache + single-flight + lifecycle refresh)
  -> PlaybackCoordinator -> PlaybackConnection -> MediaController
  -> UpdateRepository/Installer
```

不强行把所有功能拆成多个 ViewModel；先以协作者隔离副作用，再让 UI 按子树收集状态。只有当 Settings/Update 生命周期独立且测试证明有收益时才拆出独立 ViewModel。

## 浏览与搜索

定义不可变 `BrowseQuery(source, provinceCode, categoryId, mode, query)`。`ChannelRepository` 为每个 query 创建 Paging 3 `PagingSource`，服务端 `offset/limit` 直接映射 `LoadResult.Page`，`nextKey` 由返回条数和 page size 决定。`flatMapLatest`/`cachedIn(viewModelScope)` 管理 query 生命周期，取消旧 query 的 Retrofit 请求，不再维护 `channelsGeneration`、`loadMoreJob` 和 UI 预取守卫的多套手写协议。

搜索输入只在 debounce 后改变 query；切来源/筛选立即替换 query。收藏视图使用带 `source` 的 `FavoriteItem` 独立列表，所有 UI key、收藏查找和播放路由都使用 `(source, contentId)` 复合标识，禁止丢失来源后再从裸 `Channel` 猜来源。

## 节目单与当前节目

`ProgramRepository` 以 `(source, contentId, dayStartMillis)` 为键做短 TTL 内存缓存和 single-flight。已过期日期、节目边界和回前台只触发一个当前键刷新；旧请求取消或结果不再匹配当前键时丢弃。网关新增批量当前节目/按 ID 查询契约后，收藏和已加载频道的 subtitle 刷新走批量接口；接口缺失、超时或返回空时保留旧快照，不回退到用户路径中的 `limit=0` 全量下载。

## 播放与服务连接

`PlaybackConnection` 把 `ListenableFuture` 转为带失败状态的可取消连接，连接回调不在主线程直接调用未保护的 `get()`。连接按需启动：没有自动播放且没有后台会话时不阻塞首屏；用户播放、播放控制或检测到已有会话时连接。`controller()` 不再永久等待，调用方收到 `Disconnected/Failed` 并更新 UI。

`PlaybackCoordinator` 接收 `PlaybackIntent`，用 `collectLatest`/递增请求 token 取消旧的流解析和回放解析，并在提交 MediaItem 前验证 token 与当前频道仍一致。MediaController 操作在其 application looper 串行执行。播放错误按可恢复网络错误与不可恢复媒体错误分类；只对前者做有限的指数退避和 jitter，禁止每 3 秒无条件重建 MediaSource。

进度改为冷 `Flow`，仅当 PlayerPane/FullscreenPlayer 可见、存在当前媒体且有订阅时以 1s 左右粒度运行；回放拖动预览使用组件本地状态，提交 seek 时才调用播放器。直播窗口由 ProgramRepository 提供，避免在 ticker 中直接启动多个刷新协程。

## 图片与渲染

新增 `ArtworkRepository`/`ArtworkState`：一次加载有界尺寸的 software bitmap，后台线程同时计算 Palette 和 256/320px 预模糊位图，结果按 URL 放入有界 `LruCache`。所有 API 级别统一使用静态预模糊背景，不在 API 31+ 对全屏原图调用 `Modifier.blur(60.dp)`；中心封面复用同一请求/缓存，明确关闭 crossfade。设备为低内存或全屏转场时使用无重叠/短动画策略，避免 Grid、全屏背景和中心封面在同一帧形成高峰。

频道卡片和播放器封面使用按布局尺寸的 Coil request，保留磁盘缓存但限制内存缓存；清缓存动作只在设置页主动触发。

## 启动顺序

1. 创建轻量 ViewModel，读取偏好并渲染筛选/列表 loading 状态。
2. 并发加载筛选元数据和首个 Paging page；首屏完成后再启动低优先级更新检查。
3. 仅在 auto-play 开启或用户触发时连接 MediaController、解析流并播放；播放与浏览请求互不阻塞。
4. 当前节目/收藏刷新由 repository 的生命周期和 TTL 驱动，取消旧的半点循环和重复启动路径。

## 外部接口前置条件

- `GET /v1/channels/by-ids?source=<key>&contentIds=<...>` 或等价批量 endpoint，返回最新频道快照/当前节目。
- 明确响应 code、空数据和限流语义；客户端将 404/未部署视为能力缺失，保留本地快照并不重试风暴。
- 服务端应对列表/节目响应提供合适的 `Cache-Control`；客户端仍以 query/TTL 做正确性控制。

## 验证模型

- 单元：TestDispatcher + Turbine/MockWebServer，验证 query latest-wins、取消传播、分页 nextKey、节目 TTL/single-flight、播放器连接失败和复合 key。
- 集成：Media3 fake controller/service 验证播放 intent 不会旧请求回写；图片 pipeline 验证同 URL 单次解码和缓存淘汰。
- 性能：增加 Macrobenchmark/Perfetto 场景（冷启动、快速筛选、连续 D-pad 滚动、进入/退出全屏、断网恢复）。记录 frame time P50/P95/P99、长帧、CPU、Java/native heap、GPU/bitmap、GC 次数、网络请求峰值。

