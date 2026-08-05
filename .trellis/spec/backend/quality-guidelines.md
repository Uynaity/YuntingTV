# Quality Guidelines

> Code quality standards for backend development.

---

## Overview

<!--
Document your project's quality standards here.

Questions to answer:
- What patterns are forbidden?
- What linting rules do you enforce?
- What are your testing requirements?
- What code review standards apply?
-->

(To be filled by the team)

---

## Forbidden Patterns

<!-- Patterns that should never be used and why -->

### 不要让服务端定时全量爬 radiotime（TuneIn）目录

上游按 **IP** 计配额。全球 3000+ 次 Browse 在一台机器上爬不到一半就连根节点 `r0`
都返回 403，且封禁以小时/天计 —— 服务端一旦踩上，tunein 来源整体不可用。

- ✅ 离线断点续爬（`tools/tunein_crawl.py`）+ 换 IP，产物 `data/tunein_catalog.json`
  只读挂进容器，网关启动时读入内存。更新目录 = 换文件重启。
- ❌ 服务端 `refreshLoop` 定时全量刷新（已删除，别再加回来）。
- 爬虫侧的两个坑：下钻要走 `r`/`c`/`a` 白名单（`g` 是分类轴、`p`/`m` 是播客，
  不挡会重复抓且拖长十几倍）；children 里的 `link` 同样要下钻，否则整个 BBC 台网会丢。

### `fetchChannels` 不再返回全量：所有旧调用点都要重新审视

`fetchChannels(categoryId, provinceCode)` 默认只返回一页（`RadioSource.PAGE_SIZE`=60，
与服务端 `gateway.go:defaultPageSize` 对齐）。`limit = RadioSource.NO_LIMIT`（0）才取全量。
分页化时三个既有调用点各踩一个坑，且**两个是静默失败**（无报错、无日志）：

- **按 `contentId` 在整表里匹配单台的路径必须传 `NO_LIMIT`**。被页大小截断后，排名 60
  之后的电台永远匹配不到 → 收藏副标题不刷新（`BaseRadioSource.refreshFavoritePrograms`）、
  正在播电台副标题不刷新（`RadioViewModel.refreshPrograms` 的 `playingSource` 兜底）。
  两处都无任何报错。
- **静默刷新不要整表替换 `channels = latest`**。`latest` 只有一页，用户已翻出的第 2、3 页
  会被吞掉：列表突然缩短、滚动位置跳走。改为按 `contentId` 就地更新
  （`s.channels.map { byId[it.contentId] ?: it }`），长度不变。刷新范围用
  `limit = channels.size.coerceAtLeast(PAGE_SIZE)` —— `coerceAtLeast` 不能省，
  `channels` 为空时 `limit=0` 会被服务端当成「取全量」。
- **翻页的 `isLoadingMore` 复位要放在首页加载入口**。过期页靠代次校验 `return@launch`
  丢弃时不复位标志，卡在 `true` 就永久锁死后续翻页。放在 `loadChannels()` 起手统一归零，
  同时覆盖「被 cancel」和「被丢弃」两条路径。

竞态复用既有的 `channelsGeneration` 代次机制，不要为翻页新造一套。

### 不要在协程/`Dispatchers.IO` 下共享 `SimpleDateFormat` 实例

`SimpleDateFormat` 非线程安全。数据源里若把它作为 `companion object` 字段复用，
并发调用 `format()`（如 TV 遥控快速切换日期触发多个 `fetchPlaybill` 协程）会抛异常
或产出错乱的日期串 → 取错当天数据甚至崩溃。

- ✅ 每次调用时就地 `new`（`fetchPlaybill` 每次日期选择只调几次，不是热循环，代价可忽略）。
- ❌ 用共享的 `companion object` `SimpleDateFormat` 字段跨协程复用。
- 备选：`java.time.DateTimeFormatter`（线程安全，可安全共享）。

### 不要把 `HlsMediaSource.Factory` 设为 ExoPlayer 的唯一 `MediaSourceFactory`

`ExoPlayer.Builder.setMediaSourceFactory(HlsMediaSource.Factory(...))` 会让**所有**
MediaItem 都按 HLS 播放列表解析。直播是 `.m3u8` 能过，但渐进式音频文件（回放
`.aac`、`.mp3` 等）会被当成 m3u8 解析失败 → 触发 RadioPlayer 的重试循环 → 永远卡
「缓冲中」。curl 验证 URL 能返回数据**不能**暴露此问题（那只测 HTTP 层，不测 ExoPlayer 解析）。

- ✅ 按「显式 `mimeType` 优先，其次 `Util.inferContentType(uri)`」分派：HLS 走定制的
  `HlsMediaSource.Factory`（保留忽略蜻蜓直播幽灵 H.264 流的 `DefaultHlsExtractorFactory`），
  其余走 `ProgressiveMediaSource.Factory`。见 `player/RadioPlayer.kt`。
- ❌ 用单一 `HlsMediaSource.Factory` 覆盖所有播放地址。
- ❌ 只靠 URI 后缀判类型：经网关透传的地址（`/proxy/{id}`）无后缀，会一律落到渐进式分支，
  HLS 台因此卡死。网关对 HLS 台补 `.m3u8` 后缀，APP 侧再用 `mimeType` 兜一层。

### 直播进度不要读 `player.currentPosition`

直播是 HLS 连续流，`ExoPlayer` 的 `currentPosition`/`duration` 对直播无业务意义
（不是「当前节目已播时长/节目总长」）。直播进度条要用**当前节目窗口 + 墙钟**算：
`position = now - programStart`、`duration = programEnd - programStart`。

- 节目窗口来源：复用各源已有的 `fetchPlaybill(channel, 当天0点)`，取覆盖 `now` 的一档
  （`RadioSource.currentProgramWindow` 默认实现）。两源直播频道列表接口都**不带**当前节目
  起止时间（蜻蜓 `current_program` 仅 title、云听 channel 无时间），但节目单接口带
  `startTime/endTime`（epoch ms）。蜻蜓另有 `v4/channels/{id}.nowplaying` 也含起止。
- 拿不到节目单时回退「当天 24h、进度=已过时间」，不崩溃。
- 回放才读 `player.currentPosition/duration`（可拖动 seek）。见 `RadioViewModel.updateProgress`。

### 高频刷新的播放进度用独立 `StateFlow`，不并入大 `UiState`

进度 ticker 每 500ms 刷新一次。若并进 `RadioUiState` 每次 `copy()`，会连带触发其
惰性字段（`favoriteIds`/`displayedChannels`）在渲染时重算、并可能引起电台 Grid 重组。

- ✅ 单列 `ProgressState` 的 `MutableStateFlow`（`RadioViewModel.progress`），面板单独收集。
- ❌ 把 `positionMs/durationMs` 塞进 `RadioUiState`，靠 500ms `copy()` 驱动。

(其余待团队补充)

---

## Required Patterns

<!-- Patterns that must always be used -->

### 直播节目名（`subtitle`）要在「回前台」和「节目边界」补刷，别只靠整半点定时

节目名（`Channel.subtitle`）实时来自 `fetchChannels`（无 HTTP 缓存），但自动刷新若只挂
`RadioScreen` 的前台整点/半点循环，会漏两类时机 → 表现为「不更新 / 很久才更新」：

- **回前台**：`repeatOnLifecycle(STARTED)` 循环必须**先刷一次再 `delay`**。后台音频持续播放、
  已换过几档节目，先 `delay` 到下一个半点会让副标题最长约 30 分钟停留旧节目名。
- **节目边界**：`updateProgress` 每 500ms 已能精确感知当前节目结束（`liveWindowEnd in 1..now`
  → `resolveLiveWindow`）。在同一处顺带 `refreshPrograms()`，节目名随进度条一起在数秒内更新，
  不必等下一个整半点。`resolvingLive` 标记 + `currentProgramWindow` 只返回覆盖 `now` 的一档
  （成功落未来、失败置 0）保证换档只触发一次，不会每 500ms 重复请求。见
  `RadioViewModel.updateProgress` 与 `RadioScreen` 的刷新循环。

---

## Testing Requirements

<!-- What level of testing is expected -->

(To be filled by the team)

---

## Code Review Checklist

<!-- What reviewers should check -->

(To be filled by the team)
