# 技术设计：睡眠定时交互重做

## 影响面
- `app/.../ui/components/PlayerPanel.kt`：`SleepTimerButton` 重做，新增 `SleepTimerBottomSheet`（手机），删除 `SleepTimerOverlay`（居中弹窗）。`SleepTimerSlider` 保留供手机复用。
- `app/.../ui/RadioViewModel.kt`：`RadioUiState` 增 `sleepTimerTotalMinutes`，`setSleepTimer` 写入总时长（供环形进度算比例）。
- `app/.../ui/RadioScreen.kt`：两处 `PlayerPanel(...)` 传入 `sleepTimerTotalMinutes`。
- `PlayerPanel` 增参 `sleepTimerTotalMinutes`，透传给 `SleepTimerButton`。

## VM 契约
- 新增 `sleepTimerTotalMinutes: Int`：本次定时的总时长（分钟）；`setSleepTimer(m)` 时 = m（m<=0 归 0）；倒计时结束或取消归 0。
- 环形进度比例 = `remaining / total`（total>0 时），分钟粒度（沿用现有每分钟回写，环每分钟收缩一格，非逐秒平滑）。

## SleepTimerButton 行为分叉（新增参数 `phone: Boolean`）
- 公共：44dp 圆形按钮；`active = remaining>0` 显示剩余分钟，否则显示待定值或 ⏰。
- 公共环形进度：`active && total>0` 时，按钮内沿画一圈弧（从 12 点顺时针，弧长 = remaining/total），track 半透明、progress 用深色，聚焦白描边不受影响。
- 手机（phone=true）：点击 → 打开 `SleepTimerBottomSheet`。不处理上下键。
- 电视（phone=false）：
  - `pending: Int?` 待定值（null=无修改）。聚焦时按 上/下 键：`base = pending ?: remaining`，上 `+15` 封顶 120，下 `-15` 保底 0；`pending` 更新并 `return true`（消费按键，焦点不移走）。
  - 显示：`pending != null` 显示 pending（0 显示「关」，否则数字）；否则 active 显示 remaining，否则 ⏰。
  - 确认（DPAD_CENTER/Enter 走 `focusableChrome` 的 onClick）：`onSelect(pending ?: remaining)`，清空 pending。
  - `onFocusChanged(false)`：`pending = null`（还原为已生效值）。
  - 左右键不消费（return false），保留 Row 内左右导航。

## SleepTimerBottomSheet（手机，仿 PlaybillBottomSheet 脚手架）
- `Dialog(usePlatformDefaultWidth=false, decorFitsSystemWindows=false)` + 关闭窗口 dim + 自绘遮罩 + 滑入/滑出动画（复用 render/visible/anim 三态模式）。
- 底部面板 Column：`align(BottomCenter).fillMaxWidth().wrapContentHeight()`（非固定 50%），圆角顶部，padding。内容：标题「睡眠定时」+ `SleepTimerSlider`。
- 关闭路径与 PlaybillBottomSheet 一致：点遮罩/返回键 → 滑出动画结束回调 `onDismiss`。
- 滑块提交（`onCommit`）后同时触发关闭动画。

## 取舍
- 环形进度分钟粒度，不做逐秒平滑动画（YAGNI；与现有每分钟 tick 一致）。ponytail 注释标注上限。
- 复用 `SleepTimerSlider` 原样（含 TV 按键/防抖逻辑），手机触屏拖动仍工作，重复提交幂等无害。
