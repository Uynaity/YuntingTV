# 技术设计 — 睡眠定时暂停播放

## 边界与分层

- **计时逻辑**：`RadioViewModel`。一个可取消的 `Job`（`viewModelScope.launch`）。
- **状态透出**：`RadioUiState` 新增 `sleepTimerRemainingMinutes: Int`（0 = 未设定，>0 = 剩余分钟）。
- **UI**：`PlayerPanel` 新增定时按钮 + 档位弹层，纯展示，回调上抛。
- 不碰 `PlaybackService` / `PlaybackBridge`：暂停走已有 `MediaController` 路径。

## 数据流

```
用户点档位 → PlayerPanel 回调 onSetSleepTimer(minutes)
  → RadioViewModel.setSleepTimer(minutes)
      cancel 旧 timerJob
      minutes==0 → remaining=0，结束
      否则启动新 Job：
        var left = minutes
        while (left > 0) { update{remaining=left}; delay(60_000); left-- }
        controller().pause(); update{remaining=0}
  → uiState.sleepTimerRemainingMinutes 驱动按钮显示
```

- 倒计时用**分钟粒度** `delay(60_000)`：与"每分钟刷新剩余分钟"需求一致，够用，避免秒级 State 抖动。
  - ponytail：先设定就先显示满档（如 30），第一次 delay 后变 29 …… 到 1 后再 delay 一分钟才 pause，总时长 = 档位分钟，符合直觉。
- 计时 Job 独立于播放状态：暂停/恢复/切台都不 touch 它（满足"不重置"与"暂停不影响计时"）。
- `onCleared` 无需特殊处理：`viewModelScope` 随 VM 销毁自动取消。

## UI 契约

`PlayerPanel` 新增参数：
- `sleepTimerRemainingMinutes: Int`（0 = 未设定）
- `onSetSleepTimer: (Int) -> Unit`（0 = 关闭）

定时按钮（新私有 Composable `SleepTimerButton`）：
- 放在 `PlayPauseButton` **左侧**：
  - 横排布局：现有 `Row` 里，在 `PlayPauseButton` 之前插入，中间加间距。
  - 竖排布局：`PlayPauseButton` 当前独占一行居中；改为把定时按钮与播放按钮并排放进一个 `Row`（播放按钮仍居中参照，定时按钮在其左）。
- 视觉：未设定 = 时钟图标（`⏰` 文本或 Canvas 简画）；设定中 = 显示剩余分钟数字（如 `28`）。
- 点击展开：就地弹出**滑块**面板；`BackHandler` 收起（参照 `CityDropdown` 展开/返回处理）。

滑块（新私有 Composable `SleepTimerSlider`）：
- 值域：0–120 分钟，步长 15（9 个档位：0=关闭 / 15 / 30 / … / 120）。内部用档位索引 `0..8`，展示时 ×15。
- 自绘：一条轨道（`Box`/`Canvas`）+ 一个滑块圆点；已填充部分金色（`Accent`），滑块圆点白色；上方显示当前值文本（`关闭` 或 `N 分钟`）。
- **触屏**：`pointerInput { detectHorizontalDragGestures(...) }`，按拖动位置换算到最近档位；`onDragEnd`（松手）→ `onSelect(当前分钟)` 并收起。
- **遥控**：`focusable()` + `onKeyEvent`，`DPAD_LEFT/RIGHT` 使档位 ±1（`coerceIn 0..8`）；聚焦态滑块高亮描边（与 `focusableChrome` 风格一致）。
- **提交（两路统一防抖）**：`LaunchedEffect(档位)` 每次值变启一个 `delay(1000)`，到点未再变则 `onSelect(当前分钟)` 收起；触屏 `onDragEnd` 额外立即提交（不等 1 秒）。
- 打开时把焦点落到滑块（`FocusRequester`），使遥控可立即左右调。

## 兼容性 / 回滚

- 纯增量：新增 State 字段默认 0、新增可选 UI 参数带默认值，旧调用点仅需补传两个参数。
- 回滚 = 撤销 PlayerPanel 与 ViewModel 的改动，无持久化、无 schema 变更。

## 取舍

- 不持久化定时设置：睡眠定时是即时意图，重启后清零合理，省一套 prefs 读写（YAGNI）。
- 档位写死常量数组，不做可配置。
