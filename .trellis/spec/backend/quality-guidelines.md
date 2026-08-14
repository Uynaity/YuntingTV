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

### `radio-proxy` 的 `devices` 表有且只有两个写入点，门禁热路径不写

| 写入点 | 何时 | 谁触发 |
|---|---|---|
| `touchDevice`（`activation.go`，`redeemCode` 事务内） | 成功兑换/换绑时 | `POST /v1/activation/redeem` |
| `reportDevice`（`device_report.go`） | 每次查激活状态时 | `GET /v1/activation/status`（App 启动 + 打开设置页） |

**门禁校验（`/proxy`、`/seg`、`/v1/stream`）是纯读，永远不要往里加写入**：那是热路径，每个 HLS
分片请求都会过，在 1C1G 部署机上每分片一次 DB 写是明显负担。要统计「谁在听」得另想办法。

两条踩过的坑：

- **加写入点前先确认现状，别照着直觉推**。`08-06-activation-admin-web` 的 PRD 写了「每次校验/兑换
  都会 `touchDevice`」——错的，当时 `touchDevice` 只有 `redeemCode` 一个调用点，据此推导的一条
  验收项直到实现阶段才发现不可达。
- **`last_seen_at` 的语义随写入点变**。加 `reportDevice` 之前它是「最近一次兑换/换绑」；之后才
  接近「最近打开 App」。管理页的列名因此叫「首次上报 / 最近上报」而不是「最近活跃」——后者会让
  运维误判成「最近在听」。另外 App 改动要发版才生效，**统计从新版本铺开那天才开始积累**。

### 公开接口变成写接口时，限流的 key 必须取 `X-Forwarded-For` 的**最右**一段

`/v1/activation/status` 公开无鉴权、`deviceHash` 直接来自 query，一旦它能写库就等于开了个任人插行
的入口，必须配限流（见 `device_report.go` 的 `allowNewDevice`）。而限流的 key 取错方向会让整套防护
**静默失效**：

Caddy 的 `reverse_proxy` 是把它看到的对端 IP **追加**到已有 XFF 之后：

```
客户端伪造 "X-Forwarded-For: 1.2.3.4"
→ Caddy 转发后变成 "1.2.3.4, <真实客户端IP>"
```

取**最左** = 取到客户端自己写的那段，攻击者每次请求换个随机值，每个 key 都是全新的，**永远撞不到
阈值，而且不会有任何报错**。取**最右** = 取我们自己的可信代理写的那段。没有 XFF 时回落
`RemoteAddr` 并用 `net.SplitHostPort` 剥掉端口。

这条成立的前提是**本服务只在 Caddy 之后对外**（`docker-compose.yml` 里是 `expose` 而非 `ports`）。
前提一旦变了（直接暴露 8080），最右段就变成客户端可控，必须回头改。

配套的两条设计习惯：

- **限流只拦「新建」，不拦「更新」**：`reportDevice` 走 `UPDATE` → 未命中才过限流再 `INSERT`，
  而不是直接 upsert。老设备刷新 `last_seen_at` 是行数固定的纯更新，撑不爆表，拿限流挡它只会让
  常用设备的活跃时间莫名停止更新。
- **限流命中返回 `nil` 而不是错误**：那是预期内的正常分支。统计侧的防滥用不该让用户看到 429 ——
  尤其当他只是跟刷接口的人共用一个 NAT 出口。

### 给 `radio-proxy` 加 `//go:embed` 目录时，必须同步 `Dockerfile` 的 `COPY`

`Dockerfile` 只 `COPY *.go ./`（外加 `go.mod`/`go.sum`），**不是** `COPY . .`。`//go:embed` 要求文件在
**编译期**真实存在，所以新增一个 embed 目录却没加对应 `COPY`，会出现最难受的一种失败：

```
admin_ui.go:15:12: pattern admin_ui: no matching files found
```

**本地 `go build`/`go test` 全绿，只有 Docker 构建炸**——因为本地目录就在那儿。`admin_ui/` 就这么漏过
了本机全部验证，直到 VPS 上部署才暴露。加 embed 目录时顺手改 `Dockerfile`，或者在本地用「只拷
`*.go` + `go.mod`/`go.sum` 到临时目录再 `go build`」复现一次构建上下文。

（不改成 `COPY . .` 是有意的：那会把 `.git`、`data/`、测试数据都塞进构建上下文，且任何无关文件变动
都会击穿 layer 缓存 —— 在 1C1G 机器上重编一次的代价不小。）

### 「是不是第一次」的判据要用**只写一次**的字段，别用会被清空的字段

`redeemCode` 曾用 `bound_device IS NULL` 判断「首次激活」，进这个分支就重算
`expires_at = now + durationDays`。但 `bound_device` 是**会被解绑清空**的，于是「解绑过的老码」
和「全新的码」在这个判据下长得一模一样 —— 老码拿到一份全新的有效期。

实测：一个 30 天的码用到只剩 2 天 → 解绑 → 重新兑换 → **又变回满 30 天**。管理员手动解绑时
这只是个小毛病；一旦用户能自助解绑，它就是「解绑→重兑换」的无限续期漏洞。

正确判据是 `activated_at IS NULL`：它只在首次激活时写入、解绑不动它，「从未激活过」才是真的
从未激活过。

**通用教训**：凡是「第一次才做 X」的分支，判据字段必须是单调的（只写一次、或只增不减）。拿一个
业务上会被重置/清空的字段当「初次标记」，等于把 X 变成了可重复触发 —— 而这类 bug 在正常路径上
完全看不出来，只有走过重置路径才会暴露。写这类分支时问一句：**这个字段会不会被别的操作清掉？**

### 移除一个跨层机制时，按「层」清而不是按「文件」清

移除换绑冷却横跨两个仓库、六层：`activation.go`（常量 + sentinel error + 判定）→ `gateway.go`
（业务错误码 + handler 分支）→ `GatewayApi.kt`（DTO 字段 + 错误码常量）→ `Repository`（结果类型
分支 + 解析）→ `ViewModel`（提示文案）→ 测试。漏掉任何一层都是残留死代码，或者更糟——对不上的
错误码。

两条具体规矩：

- **废弃的业务错误码留空位，不要把后面的挪上来填**。线上旧版客户端认的是数值，重排会让
  「限流」被旧客户端当成「冷却中」。服务端和客户端两侧都要留同一个空位并写明原因。
- **删掉一个字段前先确认客户端的默认值**。`nextRebindAt` 在 Kotlin 侧有 `= 0L` 默认值，服务端
  删掉它之后旧版 App 读到 0，语义正好是「不在冷却中」，因此不会出错。没有默认值的话就是崩溃或
  行为异常 —— 这个必须逐个字段确认，不能想当然。
- **清理时别误伤同处一地的无关机制**。`FOR UPDATE` 行锁的注释里提到过冷却，但它的存在理由是
  「读-改-写」窗口本身，与冷却无关，删冷却时不能顺手带走。

### 客户端「不再发某个参数」时，要问旧服务端拿那个参数的默认值当什么用

新参数与「停发旧参数」是**一对**，而两侧发布节奏不同。新 APP 打旧网关时，旧网关看不见
新参数、只看见旧参数取了默认值 —— 两个默认值一撞就是一次**静默退化**：功能照常出结果，
只是结果悄悄少了一大块，没有任何报错，测试也测不出来（测试里两侧永远同版本）。

实例（第 5 步搜索）：APP 改成发 `scope=catalog` 并停发 `provinceCode`/`categoryId`。
旧网关不认 `scope`，`provinceCode` 缺省成 `0` —— 而 `0` 对云听是上游真实的「国家」桶。
结果：云听 943 台的目录里只搜得到 19 台，界面上一切正常。

三条规矩：

- **新参数一律纯新增**，缺省不传即旧行为，让旧客户端逐字节不变。这只解决一半问题
  （旧 APP + 新网关），另一半只能靠顺序。
- **顺序写进 design.md 并当成硬约束**：先部署服务端，再发 APP。反过来不是「先后都行、
  只是早晚」，而是一段时间内所有新版用户拿到错结果。
- **停发一个参数前，先算一遍它在旧服务端的默认值意味着什么**。默认值语义按来源/按端点
  不同的（本项目的 `provinceCode=0`，见契约 12）尤其危险 —— 三个来源里只有一个会错，
  另两个的对照数据会让人以为没问题。

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
