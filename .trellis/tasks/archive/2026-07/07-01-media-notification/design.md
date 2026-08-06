# 技术设计：手机媒体通知与后台播放

## 架构总览
从「ViewModel 持有裸 ExoPlayer」迁移到 Media3 标准的 **MediaSessionService**：
播放器由前台服务独占，UI/ViewModel 通过 `MediaController` 连接控制与观察。

```
RadioScreen/PlayerPanel (不改)  ← 只读 UiState / 调 viewModel 方法
        │
RadioViewModel  ── MediaController(异步连接) ──▶ PlaybackService(MediaSessionService)
        │                                              │ 持有
        └── 读 PlaybackBridge.retrySeconds ◀────── RadioPlayer(ExoPlayer + 断流重试)
```

关键收窄：UI 全程只依赖 `UiState.{isPlaying,isBuffering,retrySeconds,currentChannel}` 与
`viewModel.{playChannel,togglePlayPause,...}`（已核实），故 UI 层零改动，
迁移完全封装在 ViewModel + 新 service + RadioPlayer。

## 组件设计

### 1. PlaybackService : MediaSessionService（新增）
- `onCreate`：创建 `RadioPlayer`（内部 ExoPlayer 按 HLS 定制），`MediaSession.Builder(this, radioPlayer.exoPlayer).build()`。
- 通知：用 Media3 默认 `DefaultMediaNotificationProvider`（自动按 MediaMetadata 渲染电台名/节目名/封面 + 播放暂停），封面由默认 `DataSourceBitmapLoader` 拉取 image URL。不写自定义通知。
- `onGetSession` 返回该 session；`onDestroy` release session + player。
- 前台服务类型 `mediaPlayback`，Media3 播放时自动进入前台并发通知。

### 2. RadioPlayer（重构，改由 service 持有）
- ExoPlayer 用 `HlsMediaSource.Factory(customHttp).setExtractorFactory(H264 忽略工厂)` 作为 MediaSource 工厂传入 `ExoPlayer.Builder`，使经由 session 设置的 MediaItem（仅 URI）自动按现有定制解析为 HLS（保留 UA/超时/忽略 H264 视频流）。所有流均 HLS，安全。
- 保留断流重试逻辑（Player.Listener + 60s 窗口 / 3s 间隔 + 倒计时），retry 简化为 `exoPlayer.prepare()` 重新准备当前 MediaItem（不再重建 URL）。
- `retrySeconds` 写入进程内 `PlaybackBridge`（见下），供 ViewModel 读取。
- 删除 `play(url)/togglePlayPause()`：播放控制统一走 Player 接口（由 MediaController 驱动）。仅保留构造 + 重试 + release。

### 3. PlaybackBridge（新增，进程内单例 object）
- `val retrySeconds = MutableStateFlow(0)`：唯一需要跨 service↔UI 的自定义状态（不属于标准 Player 接口）。
- 同进程，直接共享 StateFlow，无需 SessionExtras/自定义 SessionCommand 的 IPC 仪式。
- ponytail: 单进程 App，共享单例即正确；跨进程才需 session extras，YAGNI。

### 4. RadioViewModel（重构播放层）
- 用 `MediaController` 取代 `RadioPlayer` 字段：
  `MediaController.Builder(app, SessionToken(app, ComponentName(app, PlaybackService::class.java))).buildAsync()`。
  以 `suspend fun awaitController()` 暴露，播放/续播前 await 连接就绪。
- `playChannel`：构造带元数据的 MediaItem
  （`setUri(playUrlLow)` + `MediaMetadata{ title=channel.title, artist=channel.subtitle, artworkUri=Uri.parse(channel.image) }`），
  `controller.setMediaItem(item); prepare(); play()`。跨源路由/saveLastPlayed/playingProvinceCode 等业务逻辑原样保留。
- `togglePlayPause`：沿用 loadedUrl 首次加载逻辑，改用 controller.setMediaItem/prepare/play 与 play()/pause()。
- 状态观察：给 controller 挂 `Player.Listener`（onIsPlayingChanged→isPlaying，onPlaybackStateChanged→isBuffering）。
  `isBuffering = (playbackState==BUFFERING) || retrySeconds>0`；`retrySeconds` 直接来自 `PlaybackBridge`。
- `loadSource` 的「切源保持播放」逻辑不变（loadedUrl==null 守卫仍在 VM 层）。
- `onCleared`：`MediaController.releaseFuture(controllerFuture)`（释放控制器，**不** release 服务里的 player —— 后台继续放）。

### 5. MainActivity
- Android 13+（API 33）用 `ActivityResultContracts.RequestPermission` 请求 `POST_NOTIFICATIONS`，
  在 onCreate 首次进入时请求一次（未授权则媒体卡片不显示，但不影响播放）。

### 6. Manifest / 依赖
- 权限：`FOREGROUND_SERVICE`、`FOREGROUND_SERVICE_MEDIA_PLAYBACK`、`POST_NOTIFICATIONS`。
- service：`.player.PlaybackService`，`android:foregroundServiceType="mediaPlayback"`，
  intent-filter action `androidx.media3.session.MediaSessionService`。
- 依赖新增 `androidx.media3:media3-session:1.10.1`（版本对齐现有 media3=1.10.1）。

## 数据流：播放一个电台
1. UI `playChannel(channel)` → VM await controller → setMediaItem(带元数据)+prepare+play。
2. Service 的 ExoPlayer 播放，MediaSession 发布 PlaybackState+Metadata → 系统画媒体卡片（含封面）。
3. Player.Listener 回写 isPlaying/isBuffering 到 UiState；断流时 RadioPlayer 重试并写 retrySeconds→Bridge→UiState。

## 兼容性 / 风险
- TV：新增 service/权限对 TV 无害；MediaSession 额外带来媒体键处理。App 仍照常进 MainActivity/Compose。启动流程不变。
- minSdk 28：`foregroundServiceType` 属性于 API<29 被忽略；`FOREGROUND_SERVICE_MEDIA_PLAYBACK`/`POST_NOTIFICATIONS` 仅 API 34/33+ 生效，低版本声明无害。
- 控制器异步连接：所有播放入口先 await 连接，避免早期空指针。
- 回滚点：改动集中在 RadioViewModel + RadioPlayer + 新 PlaybackService/PlaybackBridge + manifest + build.gradle；UI 不变，回滚即还原这几处。

## 明确不做（YAGNI）
- 自定义通知布局 / 自定义 SessionCommand / 跨进程 extras：单进程 + 标准媒体卡片已满足，不做。
- 上一台/下一台通知按钮：电台列表与 prev/next 语义不直接对应，暂不做（可后续）。
- 媒体键 seek / 进度条：直播流无进度语义，用默认（仅播放/暂停）。
