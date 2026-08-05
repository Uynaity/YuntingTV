# PRD：手机媒体通知与后台播放

## 目标 / 用户价值
App 装到手机上播放电台时，系统通知栏/锁屏没有媒体控制卡片，手机用户无法在通知中心
感知与控制播放。目标：让系统识别正在播放的媒体，展示可交互的媒体控制卡片，并支持真
后台播放（退到后台/息屏/关界面持续播放）。

## 背景与根因
- 系统通知栏的媒体控制卡片由 `MediaSession` 驱动（发布 PlaybackState + MediaMetadata）。
- 本项目 `RadioPlayer`（app/.../player/RadioPlayer.kt）是裸 ExoPlayer，由 `RadioViewModel`
  持有并在 `onCleared()` 释放，全项目无 MediaSession / 前台服务 / 媒体通知（grep 证实）。
  故系统对播放完全无感知，也无法后台播放。

## 确认事实
- media3 = 1.10.1，仅依赖 `media3-exoplayer` + `-hls`，缺 `media3-session`。minSdk 28，targetSdk 36。
- `Channel` 模型已含 title、subtitle（当前节目名）、image（封面 URL），元数据充足；Coil 已在用同一 image URL。
- `RadioPlayer` 含自定义断流重试（RETRY_WINDOW 60s / DELAY 3s + 倒计时），需保留。
- UI 层（RadioScreen/PlayerPanel）仅读 `UiState.{isPlaying,isBuffering,retrySeconds,currentChannel}`、
  调 `viewModel.{playChannel,togglePlayPause,...}` —— 与播放器实现解耦，本次 UI 零改动。
- 原为 TV 应用（androidx.tv + leanback）；新增 service/权限对 TV 无害。

## 决策（已确认）
- D1: 采用 **MediaSessionService + 前台服务** 实现真后台播放（非「仅前台通知」）。
- D2: Android 13+ 首次播放/启动时**主动请求** POST_NOTIFICATIONS 运行时权限。

## 需求
- R1: 播放时系统通知栏/锁屏出现媒体控制卡片，展示电台名（title）、当前节目名（subtitle）、封面（image）。
- R2: 卡片上可播放/暂停，且与 App 内播放状态双向同步。
- R3: App 退到后台、息屏、关闭界面后播放持续不中断（前台服务）。
- R4: Android 13+ 启动/首次播放时请求通知权限；未授权时播放仍正常（仅无卡片）。
- R5: 保留现有行为不回归：断流重试与「缓冲中…Ns」倒计时、切直播源保持当前台继续播放、
  记忆但不自动播放（首次按播放键才加载）、跨源收藏原地播放、启动自动续播。

## 验收标准
- [ ] AC1(R1): 手机播放电台后，下拉通知栏/锁屏出现媒体卡片，显示正确电台名、节目名与封面。
- [ ] AC2(R2): 点通知卡片暂停/播放，App 内 PlayerPanel 状态同步；反之亦然。
- [ ] AC3(R3): 播放中按 Home 退后台或息屏，音频持续；从通知卡片可继续控制。
- [ ] AC4(R4): Android 13+ 首次进入弹出通知权限请求；拒绝后播放正常、无卡片、不崩溃。
- [ ] AC5(R5): 切直播源时当前台继续播放；断流时卡片/面板显示缓冲与倒计时；续播/记忆不自动播放行为不变。
- [ ] AC6: `./gradlew :app:assembleDebug` 通过（manifest merge + 权限声明无误）。

## 暂不在范围（YAGNI）
- 自定义通知布局、自定义 SessionCommand、跨进程 session extras（单进程 + 标准媒体卡片已够）。
- 通知的上一台/下一台按钮、直播流 seek/进度条（语义不匹配，仅播放/暂停）。
