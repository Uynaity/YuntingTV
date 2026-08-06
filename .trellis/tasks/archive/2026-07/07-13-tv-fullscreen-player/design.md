# 技术设计 — TV 全屏播放界面

## 边界与改动面

- `ui/RadioScreen.kt`：新增 `showFullscreen` 状态；顶层叠加全屏 overlay（fade 进出）；`BackHandler` 增加最高优先级分支；把 `onOpenFullscreen` 传给横屏左侧 `PlayerPanel`，把全屏所需数据传给新 overlay。
- `ui/components/PlayerPanel.kt`：竖排封面 Box 增加可聚焦/可点击 → `onOpenFullscreen`；**在同文件新增 `FullScreenPlayer` composable**（复用本文件私有的 `PlaybackProgressBar`/`PlayPauseButton`/`PlayGlyph`，无需改可见性）。
- `ui/components/`：新增取色工具 `rememberPaletteColor`（Coil 载图 + Palette 生成主色，含亮度收敛与降级）。
- `app/build.gradle.kts` + `gradle/libs.versions.toml`：新增 `androidx.palette:palette-ktx`。

不改：ViewModel、播放服务、数据层、竖屏形态。全屏复用 `RadioScreen` 已有的 `state`/`progress`。

## 契约（新 composable 签名）

```kotlin
@Composable
fun FullScreenPlayer(
    channel: Channel?,
    isPlaying: Boolean,
    isBuffering: Boolean,
    retrySeconds: Int,
    isFavorite: Boolean,
    positionMs: Long,
    durationMs: Long,
    seekable: Boolean,
    playingProgramTitle: String?,
    onTogglePlayPause: () -> Unit,
    onSeekTo: (Long) -> Unit,
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
)
```

入口回调：`PlayerPanel(..., onOpenFullscreen: () -> Unit = {})`，仅竖排封面 Box 用。

## 背景取色与可读性

取色工具：
```kotlin
@Composable
fun rememberPaletteColor(imageUrl: String?, fallback: Color): Color
```
- `LaunchedEffect(imageUrl)`：`ImageRequest(data=url).allowHardware(false).size(64)` → `context.imageLoader.execute` → `BitmapDrawable.bitmap` → `Palette.from(bitmap).generate()`。
- 选色顺序：`darkVibrant ?: darkMuted ?: vibrant ?: dominant ?: fallback`。
- 亮度收敛：若 `luminance > 0.5` 则与黑色按 0.5 混合，避免白底 LOGO 出浅色底导致白字不可读。
- 失败/加载中返回 `fallback`（主题深色）。

背景分层（自底向上）：
1. 取色底色填满。
2. API ≥ 31：放大 1.5× 的 LOGO `AsyncImage` + `Modifier.blur(60.dp)`，`alpha 0.6`。（`android.os.Build.VERSION.SDK_INT >= 31` 判断；<31 跳过）
3. 暗色垂直渐变 scrim：`Brush.verticalGradient(0f→tint.copy(alpha=0.30), 1f→Color.Black.copy(alpha=0.75))`——**可读性保证层**，与 LOGO 明暗无关。
4. 内容层。

结论：可读性只依赖第 3 层 scrim + 白色文字（必要处加轻微阴影），不依赖模糊；因此 API<31 无 blur 也满足验收。

## 动画与交互

- overlay：`AnimatedVisibility(visible = showFullscreen, enter = fadeIn(tween(300)), exit = fadeOut(tween(300)))`，包住 `FullScreenPlayer`，放在 `RadioScreen` 最外层 Box 顶部（盖住一切）。
- 焦点：进入后 `LaunchedEffect` 请求播放键焦点；进度条 `focusable` 复用现有左右键 seek 逻辑。
- 返回：`BackHandler(enabled = !showExitDialog && !showSettings)` 内最前面加
  `if (showFullscreen) { showFullscreen = false; return@BackHandler }`（现为多分支 if，改为最前置判断）。

## 兼容 / 回滚

- 依赖新增仅 palette-ktx，纯本地 UI，无网络/协议变更。
- 回滚：移除 overlay 与入口回调、删 palette 依赖与取色文件即可，PlayerPanel 竖排封面恢复不可点击。

## 权衡

- 全屏 composable 放进 `PlayerPanel.kt` 而非独立文件：为直接复用私有进度条/播放键，避免改可见性或重复实现（最短 diff）。文件会增长，可接受。
- blur 用 Compose `Modifier.blur` 而非 RenderScript/自绘：官方、API31+ 原生，低版本降级由 scrim 兜底。
