# 技术设计 — 播放器进度条

## 数据流总览

```
RadioPlayer(ExoPlayer, service)  ──position/duration──┐
                                                       │ (MediaController)
RadioViewModel                                         ▼
  ├─ progress ticker (每 500ms while 有台且未拖动)  →  ProgressState StateFlow
  │     回放: pos=controller.currentPosition, dur=controller.duration
  │     直播: pos=now-winStart, dur=winEnd-winStart (窗口来自节目单)
  ├─ liveWindow 解析 (playNow 时后台拉当天节目单找 now 覆盖档)
  └─ seekTo(ms) → controller.seekTo   (仅回放)
                                                       │
RadioScreen ── progressState ──▶ PlayerPanel ──▶ PlaybackProgressBar (新组件)
```

## 1. ProgressState（新，独立于 RadioUiState）

放在 RadioViewModel，单独的 `StateFlow<ProgressState>`，**不并进 RadioUiState** —— 避免每 500ms `copy()` 大状态、触发 `favoriteIds/displayedChannels` 惰性重算与 Grid 重组。

```kotlin
data class ProgressState(
    val positionMs: Long = 0,
    val durationMs: Long = 0,   // 0 = 未知/隐藏进度条
    val seekable: Boolean = false,   // 回放 true，直播 false
)
```

## 2. 进度 ticker

- 单协程，`controller` 就绪后启动；`while(isActive)` 每 500ms 取一次。
- 模式判定：`_uiState.value.playingProgramTitle != null` → 回放；否则直播。
- 回放：`pos=c.currentPosition`；`dur=c.duration.takeIf{ it>0 && it!=C.TIME_UNSET } ?: 0`；seekable=true。
- 直播：读 VM 内 `liveWindowStart/End`（Long，见 §3）；`dur=end-start`；`pos=(now-start).coerceIn(0,dur)`；seekable=false。now≥end 时触发重解析下一档窗口。
- 无 currentChannel：durationMs=0（面板隐藏进度条）。
- 拖动期间不需要 VM 停 ticker：拖动预览是组件本地态，组件拖动时忽略传入 position（见 §5）。ticker 照常更新，seek 提交后自然对上。

## 3. 直播节目窗口解析

BaseRadioSource 加默认方法（复用各源已实现的 `fetchPlaybill`，零新接口/DTO）：

```kotlin
/** 覆盖 now 的当前节目窗口；无节目单或未覆盖返回 null。 */
open suspend fun currentProgramWindow(channel: Channel, todayStartMillis: Long): LongRange? {
    val now = System.currentTimeMillis()
    return runCatching { fetchPlaybill(channel, todayStartMillis) }.getOrNull()
        ?.firstOrNull { now >= it.startTime && now < it.endTime }
        ?.let { it.startTime..it.endTime }
}
```

VM：
- 字段 `liveWindowStart/liveWindowEnd: Long`（0 表示走 24h 回退）。
- `playNow(channel)` 起播后 `viewModelScope.launch { resolveLiveWindow(channel) }`。
- `resolveLiveWindow`：调 `currentProgramWindow`；成功存窗口；失败或 null → 置 0（ticker 回退：start=当天0点, end=start+86400_000）。
- 回放 (`playReplay`)：清空 liveWindow（回放不看它）。
- ticker 检测直播 now≥end → 再调一次 resolveLiveWindow 推进到下一档。

## 4. Seek 入口（VM）

```kotlin
fun seekTo(ms: Long) {                 // 仅回放调用；直播 seekable=false 不会触发
    viewModelScope.launch {
        val c = controller()
        val dur = c.duration.takeIf { it > 0 } ?: return@launch
        c.seekTo(ms.coerceIn(0, dur))
    }
}
```

15s 步进/长按/防抖都在组件内算，最终只调一次 `seekTo(目标ms)`。

## 5. PlaybackProgressBar（新组件，components/PlayerPanel.kt 内私有）

参数：
```kotlin
@Composable fun PlaybackProgressBar(
    positionMs: Long, durationMs: Long, seekable: Boolean,
    capsuleThumb: Boolean,            // 手机 true：胶囊显示时间；TV false：圆点
    onSeekTo: (Long) -> Unit,
    onDragPreview: (Long?) -> Unit = {},   // TV 封面遮罩用；非拖动传 null
    modifier: Modifier = Modifier,
)
```

本地态（回放才有）：
- `dragTarget: Long?`（null=未拖动，跟随 positionMs；非 null=拖动预览值）。
- `touched: Boolean`。

输入：
- TV 方向键（`onKeyEvent`，仅 seekable）。焦点用 `focusable()`+`onFocusChanged`（seekable=false 不加 focusable，直播不可聚焦）。
  - 本地态 `holdDir: Int`（-1/0/+1）。
  - `KeyDown` Left/Right 且 `nativeKeyEvent.repeatCount==0` → 立即 `step(±15_000)`（单击 15s），并 `holdDir=∓1/±1`。
  - `KeyUp` → `holdDir=0`。
  - `LaunchedEffect(holdDir)`：holdDir!=0 → `delay(400)`（长按阈值，快速单击此前已 KeyUp 归零，循环不启动）→ `while(active){ step(holdDir*5_000); delay(200) }`。即：单击 15s、长按 15s 后转每 200ms +5s 平滑连续。
  - `step(delta)`：`dragTarget=((dragTarget?:positionMs)+delta).coerceIn(0,dur)`；`touched=true`。
- 手机触摸（`detectHorizontalDragGestures`，仅 seekable&capsuleThumb）：按 x/width 比例算 `dragTarget`。
- 防抖提交：`LaunchedEffect(dragTarget)`：dragTarget!=null 且 touched → `delay(1000)` → `onSeekTo(dragTarget!!)`、`onDragPreview(null)`、`dragTarget=null`。dragTarget 再变会取消重启（天然防抖，同 SleepTimerSlider）。
- 拖动中 `onDragPreview(dragTarget)`（TV 封面遮罩显示大字）。

渲染（Canvas 画轨道）：
- 轨道 `Color(0x66FFFFFF)`；已播 `YtmRed`。
- 进度比例 = `(dragTarget ?: positionMs)/durationMs`。
- thumb：capsuleThumb 且拖动中 → 胶囊（圆角矩形，内嵌 `当前/总` 文本，用 drawText/Text 叠加）；否则圆点。TV 聚焦时 thumb 外圈白环（同 SleepTimerSlider）。
- durationMs<=0 → 不渲染（return）。

时间格式 helper：
```kotlin
fun fmt(ms: Long): String { val s=ms/1000; val h=s/3600; val m=s%3600/60; val sec=s%60
    return if(h>0) "%d:%02d:%02d".format(h,m,sec) else "%02d:%02d".format(m,sec) }
```

## 6. PlayerPanel 接线

新增参数：`positionMs`、`durationMs`、`seekable`、`onSeekTo`。

- horizontal=false（TV/横屏左面板）：
  - 封面 Box 内叠加拖动遮罩：`var coverPreview by remember { mutableStateOf<Long?>(null) }`；非 null 时封面上覆盖半透明黑 + 居中大字 `fmt(preview)/fmt(dur)`。
  - 进度条插在「副标题 Text」与「按钮 Box」之间，`capsuleThumb=false`，`onDragPreview={coverPreview=it}`。
- horizontal=true（手机底部条）：把现有 `Row` 包进 `Column`，进度条置于 Row 之上，`capsuleThumb=true`，`onDragPreview` 忽略（胶囊自带时间）。

## 7. RadioScreen 接线

两处 PlayerPanel 调用各加 `positionMs/durationMs/seekable/onSeekTo`，值来自 `progressState`（`viewModel.progressState.collectAsStateWithLifecycle()`）与 `viewModel::seekTo`。

## 兼容/风险

- 直播不可聚焦：seekable=false 时进度条无 `focusable`，D-pad 焦点仍在城市栏/播放键间流转，不受影响。
- TV 焦点新增一个可聚焦点（回放进度条）：位于副标题与播放键之间，上下键可达；不破坏现有横向焦点。
- ticker 500ms 只更新独立小 StateFlow，不触发 Grid 重组。
- 蜻蜓回放时长为 CBR 估算，可能与真实有几秒误差 —— 可接受（进度条非精确剪辑工具）。

## 自检

- `formatTime` 边界：0→"00:00"、3600_000→"1:00:00"、1425_000→"23:45"。放一个 `@VisibleForTesting`/本地 assert demo 或 `test_*` 验证格式化与 seek coerce 边界。
