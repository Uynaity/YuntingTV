# 执行计划：睡眠定时交互重做

## 步骤
1. VM：`RadioUiState` 增 `sleepTimerTotalMinutes: Int = 0`；`setSleepTimer` 写入总时长（m>0 时 = m，取消/结束归 0）。
2. `PlayerPanel` 增参 `sleepTimerTotalMinutes: Int = 0`，透传给两处 `SleepTimerButton`（phone 分支 & TV 分支）。
3. `RadioScreen` 两处 `PlayerPanel(...)` 传 `sleepTimerTotalMinutes = state.sleepTimerTotalMinutes`。
4. `SleepTimerButton` 重做：加 `phone`/`totalMinutes` 参数、`pending` 状态、上下键处理、确认提交、失焦还原、环形进度绘制。
5. 新增 `SleepTimerBottomSheet`（手机底部弹窗，wrap 高度，标题+滑块）。
6. 删除 `SleepTimerOverlay`（居中弹窗）；`SleepTimerSlider` 保留。
7. 编译 `./gradlew :app:compileDebugKotlin`。

## 验证
- 编译通过。
- 逻辑自检：pending 调节的封顶/保底、失焦还原、确认提交、环比例 remaining/total（分钟粒度）。
- 人工（用户）：手机底部弹窗高度、TV 上下键+环形。

## 回滚
- 单文件为主（PlayerPanel），`git checkout` 对应文件即可回退；VM/Screen 改动小。
