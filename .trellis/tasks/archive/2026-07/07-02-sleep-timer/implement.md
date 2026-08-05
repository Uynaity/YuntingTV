# 执行计划 — 睡眠定时暂停播放

## 改动清单（3 个文件）

1. `RadioViewModel.kt`
   - `RadioUiState` 加字段 `sleepTimerRemainingMinutes: Int = 0`。
   - 加私有 `private var sleepTimerJob: Job? = null`。
   - 加 `fun setSleepTimer(minutes: Int)`：
     - `sleepTimerJob?.cancel()`
     - `minutes == 0` → `_uiState.update { it.copy(sleepTimerRemainingMinutes = 0) }`，return。
     - 否则 `sleepTimerJob = viewModelScope.launch { var left = minutes; while (left > 0) { update(remaining=left); delay(60_000); left-- }; controller().pause(); update(remaining=0) }`。

2. `PlayerPanel.kt`
   - 新增参数 `sleepTimerRemainingMinutes: Int = 0`、`onSetSleepTimer: (Int) -> Unit = {}`。
   - 新增私有 `@Composable SleepTimerButton(remainingMinutes, onSelect)`：
     - 折叠态：圆形按钮，未设定显示时钟图标（`⏰`），设定中显示剩余分钟（如 `28`）。
     - 展开态：就地弹出 `SleepTimerSlider`；`BackHandler` 收起，管理参照 `SettingsScreen.CityDropdown`。
   - 新增私有 `@Composable SleepTimerSlider(onSelect: (Int) -> Unit)`：
     - 内部 `var step by remember { mutableStateOf(0) }`，档位 `0..8`，分钟 = `step * 15`。
     - 轨道 + 圆点自绘（`Box`/`Canvas`）；金色填充 + 白点；上方文本 `关闭` / `${分钟} 分钟`。
     - 触屏：`pointerInput` + `detectHorizontalDragGestures`，位置→最近档位；`onDragEnd` 立即 `onSelect(分钟)` + 收起。
     - 遥控：`focusRequester` + `focusable()` + `onKeyEvent` 拦 `DPAD_LEFT/RIGHT` 改 `step`（`coerceIn 0..8`）；聚焦高亮。
     - 防抖提交：`LaunchedEffect(step) { delay(1000); onSelect(分钟); 收起 }`（值再变会取消重启）。
     - 打开即 `requestFocus()`。
   - 横排布局：在 `PlayPauseButton` 前插入 `SleepTimerButton` + 间距。
   - 竖排布局：把底部的 `PlayPauseButton` 包进一个 `Row`，`SleepTimerButton` 置于其左。

3. `RadioScreen.kt`
   - 两个 `PlayerPanel(...)` 调用点各补：`sleepTimerRemainingMinutes = state.sleepTimerRemainingMinutes`、`onSetSleepTimer = viewModel::setSleepTimer`。

## 顺序

1. VM 状态 + `setSleepTimer`（先打通逻辑）。
2. `PlayerPanel` 加按钮 + 弹层（横排、竖排各放好）。
3. `RadioScreen` 接线。
4. 编译 + 真机/模拟器验证。

## 验证命令

```bash
./gradlew :app:assembleDebug
```

## 验证要点（对齐 prd 验收）

- 遥控：D-pad 聚焦定时按钮 → OK 展开 → 选 30 分钟 → 返回键可收起。
- 触摸：点按钮展开、点档位生效。
- 选 30 分钟后按钮显示剩余分钟并递减；到 0 播放暂停。
- 选"关闭"取消计时，按钮回图标态。
- 计时中切电台/来源，剩余分钟不变。
- 横排（竖屏底部）与竖排（横屏左侧）两种布局按钮均在暂停键左侧且可用。

## 回滚点

- 每个文件改动独立；出问题按文件 `git checkout -- <file>` 回滚，无持久化/schema 变更。

## 审查门

- 编译通过 + 上述验证要点全绿 → 进入 Phase 3 提交。
