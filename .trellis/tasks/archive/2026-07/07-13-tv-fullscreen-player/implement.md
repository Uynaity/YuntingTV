# 执行计划 — TV 全屏播放界面

## 有序清单

1. **加依赖**：`gradle/libs.versions.toml` 加 `palette-ktx`（androidx.palette:palette-ktx:1.0.0），`app/build.gradle.kts` `implementation(libs.palette.ktx)`。Sync/编译确认可解析。
2. **取色工具**：新增 `ui/components/PaletteBackground.kt`（或就近放 PlayerPanel.kt），实现 `rememberPaletteColor(url, fallback)`：Coil 64px 取图 + Palette 选色 + 亮度收敛 + 失败降级。
3. **全屏 composable**：在 `PlayerPanel.kt` 新增 `FullScreenPlayer(...)`（签名见 design.md）。
   - 背景三层（底色 / API31+ blur 图 / scrim）。
   - 内容：右上角 `ClockText`；居中大 LOGO（AsyncImage 圆角）；标题（含收藏★）、副标题（节目名/缓冲态）复用 PlayerPanel 里的文案拼装逻辑；`PlaybackProgressBar`；`PlayPauseButton`（默认焦点）。
   - `BackHandler { onExit() }` 或由父层统一处理（择一，避免双重）。此处让父层 RadioScreen 统一处理返回，全屏内不再单独 BackHandler。
4. **入口**：`PlayerPanel` 增参 `onOpenFullscreen: () -> Unit = {}`；竖排封面 Box 加 `focusable()+clickable(enabled = channel != null){ onOpenFullscreen() }`（TV 可聚焦）。
5. **接线 RadioScreen**：加 `showFullscreen` 状态；`playerPane` 传 `onOpenFullscreen = { showFullscreen = true }`；最外层包 Box，末尾加 `AnimatedVisibility(showFullscreen, fadeIn/out tween300){ FullScreenPlayer(... onExit={showFullscreen=false}) }`；`BackHandler` 最前面加 `if (showFullscreen){ showFullscreen=false; return }`。
6. **自检**：编译 + adb 装机，验证进入/退出动画、白底与黑底 LOGO 可读性、回放 seek、直播只读、时间刷新。

## 验证命令

```bash
./gradlew :app:assembleDebug
# 装机后遥控走查（见验收标准）
```

## 评审门槛

- 编译通过；无新增 lint error。
- 白底/黑底 LOGO 两个真实电台目视对比可读。
- 返回键不误触退出应用对话框。

## 回滚点

- 每步独立可回退；最终回滚 = revert 本任务改动 + 移除 palette 依赖。
