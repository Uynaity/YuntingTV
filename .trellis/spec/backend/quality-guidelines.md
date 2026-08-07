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

### 不要为「按 contentId 找几个台」去下载整份列表

副标题（当前节目名）刷新有两条按 id 找台的路径：收藏刷新
（`BaseRadioSource.refreshFavoritePrograms`）与正在播电台不在当前筛选范围时的兜底
（`RadioViewModel.refreshPrograms`）。两条都走 `RadioSource.fetchChannelsByIds`
（网关 `GET /v1/channels/by-ids`），**不要**改回按地区拉全量列表再匹配。

- 全量那条路曾是唯一选择（三来源上游都没有「按 id 查单台」接口），代价是为几个副标题
  下载整个目录。实测（radio.hku.wtf）：TuneIn 美国节点全量 2,148,833 字节，而收藏在该地区的
  4 个台按 id 取只要 828 字节（1/2595）；蜻蜓网络台 9170 → 635 字节（1/14）。
  且它发生在打开收藏页、回前台这些用户看得见的时刻。
- 服务端做同一件事便宜得多：`scopeCache` 里本就有该范围的全量索引（列表页与搜索共用），
  by-ids 只是查表。
- **查不到的略过，不是错误**：电台下架、换地区都是常态，调用方保留自己那份旧快照。
  整批失败或把缺失当空结果写回，都会让收藏项凭空消失。
- **能力缺失（旧网关 404）记成进程级开关**（`GatewaySource.byIdsSupported`），不要每次
  刷新都再撞一发 —— 那个 404 是部署事实，不是偶发失败。其他 HTTP 错误照常上抛。

`limit=0`（服务端的「取全量」约定）在客户端已无调用点，`RadioSource.NO_LIMIT` 随之删除。
新增需要整表的场景前先想清楚：多半该在服务端加一个按需查询，而不是把整表搬到 TV 上。

### 分页由 Paging 3 负责，不要手写代次/job 守卫

列表分页是 `ChannelRepository.pagingFlow` + `flatMapLatest`：查询一变即取消旧分页的在途
请求并整体换新。此前的 `channelsGeneration` 代次计数器、`loadMoreJob` 引用、UI 侧预取守卫
与 `isLoadingMore` 复位一族**已全部删除**，不要为新需求把它们加回来。

- 空态/加载态/翻页指示读 `LazyPagingItems.loadState`，预取由 `PagingConfig.prefetchDistance`
  管（=12），不要再写 `snapshotFlow` 预取回调。
- 副标题刷新用叠加层（VM 持 `contentId -> subtitle` 的 Map，在 `cachedIn` **之后**用
  `PagingData.map` 叠上去），**不要**用 `PagingSource.invalidate()`：invalidate 按
  `getRefreshKey()=null` 从 offset 0 整体重来，已翻出的第 2、3 页当场丢掉、滚动位置被甩到末尾。
  顺序也不能反 —— 叠加放在 `cachedIn` 之前，每次副标题更新都会重发一遍网络请求。
- 切筛选时的 `gridState.scrollToItem(0)` 必须保留，省略会连锁自动翻页。

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

- 节目窗口来源：`ProgramRepository.currentWindow`（内部复用 `fetchPlaybill(channel, 当天0点)`，
  取覆盖 `now` 的一档）。两源直播频道列表接口都**不带**当前节目起止时间（蜻蜓
  `current_program` 仅 title、云听 channel 无时间），但节目单接口带 `startTime/endTime`
  （epoch ms）。蜻蜓另有 `v4/channels/{id}.nowplaying` 也含起止。
- 拿不到节目单时回退「当天 24h、进度=已过时间」，不崩溃。
- 回放才读 `player.currentPosition/duration`（可拖动 seek）。见 `RadioViewModel.computeProgress`。

### 高频刷新的播放进度用独立 `StateFlow`，不并入大 `UiState`

进度 ticker 每 500ms 刷新一次。若并进 `RadioUiState` 每次 `copy()`，会连带触发其
惰性字段（`favoriteIds`/`displayedChannels`）在渲染时重算、并可能引起电台 Grid 重组。

- ✅ 单列 `ProgressState` 的 `MutableStateFlow`（`RadioViewModel.progress`），面板单独收集。
- ❌ 把 `positionMs/durationMs` 塞进 `RadioUiState`，靠 500ms `copy()` 驱动。

(其余待团队补充)

---

## Required Patterns

<!-- Patterns that must always be used -->

### `/v1/stream` 的 `directUrl` 只对 tunein 非空，客户端不许按来源分支

`directUrl` 是 TuneIn 起播默认走的上游真实地址（绕开 `/proxy/` 透传省带宽）；设置页的
「TuneIn 代理」开关**开启**时才回到透传。契约的关键在**服务端负责收窄，客户端只看空不空**：

- 服务端：只有 `handleV1Stream` 的 `tunein` 分支走 `writeStreamDirect` 下发非空值，
  `qingting`/`yunting` 经 `writeStream` 固定回空串（它们的播放地址本就是上游直链，无透传可绕）。
  给别的来源塞上非空 `directUrl`，客户端会直接用一个语义不明的地址起播。
- 客户端（`GatewaySource.resolveStream`）：判据只有 `!useProxy && directUrl.isNotEmpty()`，
  **不要**加 `type == TUNEIN` 之类的来源判断 —— 服务端的空值约定已经把这层条件收掉了，
  两边都判会变成两处要同步维护的真值表。
- 空串同时是双向兼容点：旧网关没有该字段（`StreamDto.directUrl` 默认 `""`）、新网关配旧客户端
  （多一个字段被忽略），两种情况都自动回到代理地址，**都不是错误路径**，不该报错或提示。
  也正因如此，默认直连**依赖网关已部署** `directUrl` 下发，否则功能静默失效（播放正常但不省带宽）。
- 直连失败**不自动降级回代理**（无播放器错误监听/重试），由用户去设置里开代理开关。改这条前
  先想清楚：自动来回切会让「到底走了哪条路」不可观测。

### 直播节目名（`subtitle`）要在「回前台」和「节目边界」补刷，别只靠整半点定时

节目名（`Channel.subtitle`）实时来自 `fetchChannels`（无 HTTP 缓存），但自动刷新若只挂
`RadioScreen` 的前台整点/半点循环，会漏两类时机 → 表现为「不更新 / 很久才更新」：

- **回前台**：`repeatOnLifecycle(STARTED)` 循环必须**先刷一次再 `delay`**。后台音频持续播放、
  已换过几档节目，先 `delay` 到下一个半点会让副标题最长约 30 分钟停留旧节目名。
- **节目边界**：进度 ticker 每 500ms 已能精确感知当前节目结束（`liveWindowEnd in 1..now`
  → `maybeRefreshLiveWindow` → `resolveLiveWindow`）。在同一处顺带 `refreshPrograms()`，
  节目名随进度条一起在数秒内更新，不必等下一个整半点。`resolvingLive` 标记 +
  `LIVE_RESOLVE_MIN_INTERVAL_MS` 节流 + `ProgramRepository.currentWindow` 只返回覆盖 `now`
  的一档（成功落未来、失败置 0）保证换档只触发一次，不会每 500ms 重复请求。见
  `RadioViewModel.maybeRefreshLiveWindow` 与 `RadioScreen` 的刷新循环。
- **缓存不能挡住这条重试**：`ProgramRepository` 给节目单加了 TTL 缓存，但 `currentWindow`
  额外要求「缓存里得有一档覆盖此刻」才算命中，否则无视 TTL 重取。节目刚切档时后端常还
  没发布下一档，只看 TTL 会把副标题钉死在上一节目 —— 那正是这条补刷规则要修的毛病。

### `radio-proxy` 的 `devices` 表 = 「兑换过码的设备」，不是「见过的设备」

`touchDevice`（`activation.go`）全项目**只有一个调用点**：`redeemCode` 事务内，且在「码不存在 /
已吊销 / 已过期」三道校验**之后**。门禁校验（`/proxy`、`/seg`、`/v1/stream`）和
`/v1/activation/status` 都是**纯读**，一行都不写。由此：

- `devices` 表里**不存在**「装了 App、打开过、但从未兑换」的设备 —— 服务端从没记过它们。
- `last_seen_at` 的语义是**「最近一次成功兑换/换绑」**，不是「最近活跃 / 最近在听」。管理面把它
  展示成「最近活跃」会让运维据此误判用户是否还在用（这个坑在 `08-06-activation-admin-web`
  真实踩过：PRD 按「每次校验都会 touch」写了验收项，实现阶段才发现不可达）。
- 想让这张表覆盖「打开过 App 的设备」，必须新增写入点（App 启动上报 + 服务端在状态接口落记录），
  且要留意 `/v1/activation/status` 是**公开无鉴权**接口，`deviceHash` 直接来自 query —— 变成写接口
  就等于开了个任人插行的入口，必须配限流。

### 管理面的「当前生效码」判定必须与 `lookupActivation` 同口径

判据是 `bound_device = 该设备 AND revoked_at IS NULL AND expires_at IS NOT NULL AND
expires_at > now`，多条取 `expires_at` 最晚的一条（`admin_devices.go` 用 `LEFT JOIN LATERAL`
在 SQL 层保证每设备只出一行）。

换绑**不清空旧码的 `bound_device`**，所以「设备 → 码」不是天然一对一，不能省掉这个挑选逻辑。
口径一旦和 `lookupActivation` 分叉，管理面显示的激活状态就会和门禁实际放不放行对不上 ——
那比没有管理面更糟，因为它让人拿错误信息做判断。

---

## Testing Requirements

<!-- What level of testing is expected -->

(To be filled by the team)

---

## Code Review Checklist

<!-- What reviewers should check -->

(To be filled by the team)
