# 执行计划 — 播放器进度条

## 顺序 checklist

1. **数据层：直播窗口解析**
   - [ ] `RadioSource.kt` BaseRadioSource 加 `open suspend fun currentProgramWindow(channel, todayStartMillis): LongRange?`（复用 fetchPlaybill 找覆盖 now 的节目）。

2. **VM：ProgressState + ticker + seek**
   - [ ] `RadioViewModel.kt` 顶部加 `data class ProgressState`。
   - [ ] 加 `private val _progress = MutableStateFlow(ProgressState())`，`val progressState = _progress.asStateFlow()`。
   - [ ] 加字段 `liveWindowStart/liveWindowEnd: Long`。
   - [ ] controller 就绪后启动 ticker 协程（500ms 循环，按回放/直播分支算 pos/dur/seekable，写 _progress）。
   - [ ] `resolveLiveWindow(channel)`：调 currentProgramWindow，成功存窗口，失败/null 置 0。
   - [ ] `playNow` 起播后 launch resolveLiveWindow；`playReplay` 清空 liveWindow。
   - [ ] `fun seekTo(ms)`：coerceIn(0,duration) 后 controller.seekTo。
   - [ ] ticker 直播分支：now≥end 时触发 resolveLiveWindow 推进下一档；liveWindow=0 时回退当天0点+24h。

3. **组件：PlaybackProgressBar + 时间格式化**
   - [ ] `PlayerPanel.kt` 加私有 `PlaybackProgressBar(...)`（Canvas 轨道 + 方向键/触摸输入 + 1s 防抖提交 + 胶囊/圆点 thumb）。
   - [ ] 长按：`holdDir` + `LaunchedEffect(holdDir)`（单击 15s；长按 400ms 后每 200ms +5s 平滑步进；KeyUp 归零停）。
   - [ ] 加 `formatTime(ms)` helper。
   - [ ] 进度红色用主题 `MaterialTheme.colorScheme.primary`（=YtmRed），轨道 `Color(0x66FFFFFF)`。

4. **PlayerPanel 接线**
   - [ ] 函数签名加 `positionMs/durationMs/seekable/onSeekTo`。
   - [ ] horizontal=false：封面 Box 叠加拖动遮罩（大字 current/total）；进度条插在副标题与按钮 Box 之间。
   - [ ] horizontal=true：Row 外包 Column，进度条置顶；capsuleThumb=true。

5. **RadioScreen 接线**
   - [ ] `val progress by viewModel.progressState.collectAsStateWithLifecycle()`（确认已有该依赖，否则用 collectAsState）。
   - [ ] 两处 PlayerPanel 调用传入 positionMs/durationMs/seekable/onSeekTo=viewModel::seekTo。

6. **验证**
   - [ ] `./gradlew :app:compileDebugKotlin -q` 通过。
   - [ ] formatTime/seek 边界自检（本地 assert 或 test）。
   - [ ] 真机：回放拖动（TV 遥控 ±15s、长按连续、封面大字、停手 1s 加载）；手机触摸胶囊；直播只读推进、不可聚焦；蜻蜓+云听各测。

## 校验命令

```bash
./gradlew :app:compileDebugKotlin -q
```

## 回滚点

- 每步独立可编译；出问题按 §1→§5 逆序回退。数据层 currentProgramWindow 为纯新增默认方法，回退无副作用。

## Review gate

- 直播绝不读 player.currentPosition（HLS 无意义）。
- seek 仅回放路径触发；直播 seekable=false。
- 500ms ticker 只写独立 StateFlow，不进 RadioUiState。
