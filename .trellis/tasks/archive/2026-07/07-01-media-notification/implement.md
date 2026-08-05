# 执行计划：手机媒体通知与后台播放

## 有序检查清单

### 1. 依赖
- [ ] `gradle/libs.versions.toml` 加 `androidx-media3-session`（version.ref = media3）。
- [ ] `app/build.gradle.kts` 加 `implementation(libs.androidx.media3.session)`。

### 2. PlaybackBridge（进程内自定义状态）
- [ ] 新增 `player/PlaybackBridge.kt`：`object { val retrySeconds = MutableStateFlow(0) }`。

### 3. RadioPlayer 重构
- [ ] ExoPlayer.Builder 传入 `HlsMediaSource.Factory(httpDataSourceFactory).setExtractorFactory(hlsExtractorFactory)` 作为 mediaSourceFactory，保留 UA/超时/忽略 H264。
- [ ] 重试 retry() 改为 `if (exoPlayer.currentMediaItem != null) exoPlayer.prepare()`；currentUrl 判空改用 currentMediaItem。
- [ ] retrySeconds 写入 `PlaybackBridge.retrySeconds`（保留本地 StateFlow 或直接写 Bridge）。
- [ ] 删除 `play(url)`、`togglePlayPause()`、`stop()`；保留构造、重试、`exoPlayer` 暴露、`release()`。

### 4. PlaybackService
- [ ] 新增 `player/PlaybackService.kt : MediaSessionService`：onCreate 建 RadioPlayer + MediaSession；onGetSession 返回；onDestroy 释放。
- [ ] `@UnstableApi` 标注。

### 5. RadioViewModel 迁移
- [ ] 移除 `RadioPlayer` 字段，改 `MediaController` 异步连接 + `suspend awaitController()`。
- [ ] `playChannel`：构造带 MediaMetadata 的 MediaItem，controller.setMediaItem+prepare+play；业务路由不变。
- [ ] `togglePlayPause`：loadedUrl 首载逻辑改用 controller。
- [ ] 挂 Player.Listener 回写 isPlaying/isBuffering；收集 `PlaybackBridge.retrySeconds` 回写 UiState 并并入 isBuffering。
- [ ] `loadSource` 切源保持播放逻辑保留（play 改用 controller）。
- [ ] `onCleared` release 控制器（不 release 服务 player）。

### 6. Manifest
- [ ] 加 3 个权限 + `<service>` 声明（foregroundServiceType=mediaPlayback + media3 action）。

### 7. MainActivity
- [ ] API 33+ 请求 POST_NOTIFICATIONS（ActivityResultContracts.RequestPermission）。

## 验证命令
- [ ] `./gradlew :app:compileDebugKotlin -q`（编译）。
- [ ] `./gradlew :app:assembleDebug -q`（打包，验证 manifest merge / 权限）。
- [ ] 真机手动验证（用户执行）：手机播放→通知栏出现媒体卡片（电台名/节目名/封面）→暂停/播放可控→退到后台/息屏仍播放→切直播源当前台继续播。

## 风险文件 / 回滚点
- `RadioViewModel.kt`（播放层大改）、`RadioPlayer.kt`（重构）为主要风险点。
- UI 层不改，回滚只需还原：build.gradle/toml、manifest、RadioViewModel、RadioPlayer、新增 PlaybackService/PlaybackBridge、MainActivity。

## 开工前复核
- [ ] 确认 `MediaController` 异步连接在首次 loadSource 自动续播前已 await，避免早期播放丢失。
- [ ] 确认 image URL 可被 Media3 DataSourceBitmapLoader 直接 http 拉取（Coil 已在用，URL 有效）。
