# 修复节目名刷新延迟

## Goal

消除直播播放器副标题与电台卡片节目名「不更新 / 长时间才更新」的两个主因，让节目名在正确时机及时刷新。

## Background

节目名（`Channel.subtitle`）自动刷新目前只依赖 `RadioScreen` 前台整点/半点定时循环调 `refreshPrograms()`。经审阅确认数据源实时、网络层无缓存，问题纯为刷新时机：

1. **回前台不立即刷新**：`RadioScreen.kt` 的 `repeatOnLifecycle(STARTED)` 循环先 `delay` 到下一个半点再刷。App 长期挂后台音频继续放，回前台后副标题最长约 30 分钟保持旧节目名。
2. **节目边界不触发节目名刷新**：`RadioViewModel.updateProgress` 每 500ms 已能精确感知当前节目结束（`liveWindowEnd in 1..now` → `resolveLiveWindow`），但只更新进度条窗口，未刷新节目名。

## Requirements

- 主因1：回到前台立即刷新一次节目名，再进入整点/半点循环。
- 主因2：正在播放电台的直播节目边界过后（进度条重解析窗口时），顺带刷新一次节目名；复用 `resolvingLive` 标记避免 500ms 内重复触发。
- 复用现有 `refreshPrograms()`，不新增数据结构。
- 不影响回放态（`playingProgramTitle != null` 时不按直播逻辑刷）。

## Acceptance Criteria

- [ ] 后台挂起若干节目档后回到前台，副标题在回前台后立即（一次网络往返内）更新为当前节目。
- [ ] 前台持续播放，当前节目结束切换到下一档后，副标题在数秒内（≤ 约 1 次 500ms tick + 一次请求）更新，不再等到下一个整半点。
- [ ] 节目边界处不产生每 500ms 的重复刷新请求。
- [ ] 回放态不受影响，进度条与既有整半点循环行为不回归。

## Notes

- 改动集中在 `app/src/main/java/cn/radio/tv/ui/RadioScreen.kt`（循环顺序）与 `RadioViewModel.kt`（`updateProgress` 边界处触发刷新）。
- 次因（快照竞态、空节目名覆盖）本任务不处理。
