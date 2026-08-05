# 技术设计：TuneIn(radiotime OPML) 来源

## 边界与契约

新来源作为 `Source` 接口的第四个实现接入，不改动统一契约（`Channel` / `Province` / `Category` / `Program`）与 `/v1/*` 端点形状。APP 只多认一个 `source=tunein` 取值。

注册点：`gateway.go:15 initSources` 加一行 `"tunein": &tuneInSource{publicBase: publicBase}`。`pickSource` 无需改动。

## 唯一需要改动的共享代码：起播分派

现状 `handleProxy` → `resolveStreamURL(uuid)` 硬绑 radio-browser 的 `byuuid` 端点（`radiobrowser.go:64`）。两个来源共用 `/proxy/{id}` 路径，必须按 id 分派。

**方案：在 `resolveStreamURL` 内按 id 形态分派，不改 URL 契约。**

radiotime 的 guide_id 形如 `s355090`（`s` + 数字），radio-browser 是 UUID（36 位含连字符），形态天然可判：

```
resolveStreamURL(id):
  urlCache 命中 → 直接返回        # 两来源共用缓存，键是 id 本身，形态不重叠故不串号
  isTuneInID(id) → tuneInResolve(id)   # Tune.ashx
  否则           → rbResolve(id)       # byuuid（现有逻辑原样搬移）
```

选它的理由：`/proxy/{id}` 的 URL 形状不变，APP 侧、HLS 改写（`serveRewrittenPlaylist`）、`/seg` 全部无感。替代方案是加 `?source=` 参数或 `/proxy/tunein/{id}` 前缀，都要动 APP 下发的地址和 HLS 改写里的 URI 拼接，收益为零。

`isTuneInID` 判据：`^s\d+$`。UUID 必含连字符，不会误判。

## 数据流

```
后台（启动即跑，之后每 6h）
  → Browse.ashx?id=r0 (7 大洲) → 每洲 Browse → 国家列表
  → 每国递归 walk 收全部台 → 装配 tiTree → 健康阈值校验 → 原子发布 + 落盘快照

APP /v1/provinces?source=tunein
  → tuneInSource.Provinces → 读内存树 t.provinces（未就绪则 errTuneInWarming）

APP /v1/channels?source=tunein&provinceCode=N&categoryId=g26
  → 读 t.byProvince[N]，再按 t.genreOf 过滤 genre —— 零外部请求

APP 起播 /proxy/s355090
  → resolveStreamURL → Tune.ashx?id=s355090 → body[0].url → 透传
```

### provinceCode 的稳定性

契约里 `ProvinceCode` 是 `int64`，radiotime 的国家节点是 `r101215` 这类字符串。取 `guide_id` 数字部分作 code（`r101215` → `101215`），天然稳定、可逆，无需维护映射表。`0` 保留给「全部」哨兵。

## 目录树：定时全量预热，不按需遍历

实测层级不定深：百慕大 3 层到台，美国 5 层（洲→国→州→都会区→台）；且中间层混排——美国那层同时有 33 个全国台和 56 个州节点。

实测成本（并发 8）：欧洲一洲 635 次 Browse / 6270 台 / 44s。全球外推约 3000–4000 次 Browse、3–5 分钟、25k–40k 台、8–12 MB。

按需遍历被否决：这个代价会落在「某国第一个访问者」头上，而冷启动、重启、TTL 过期都会重新制造那个倒霉的第一个。改为后台定时全量爬取常驻内存，客户端每次请求都是内存命中。代价是常驻十几 MB。

```
walk(guideID, depth):
  depth > tiMaxDepth(6) → return          # 防环，实测最深 5
  visited[guideID] → return               # 同一节点只爬一次
  d = Browse.ashx?id=guideID
  for o in d.body:
    o.children 非空 → 收 children 里 item==station 的项    # "Stations" 分组
    o.item == station → 收 o                                # 直挂的台
    o.type == link    → 并发 walk(o.guide_id, depth+1)      # 上限 tiCrawlPar=8
```

- `stations` 用 `map[guideID]` 收，天然去重——不同都会区可能重复挂同一台。
- 单节点失败只记日志跳过。宁可少几个台，不要整个国家白屏。
- 每国列表按 Title 排序：`map` 遍历顺序随机，不排会导致同一请求两次返回次序不同。

## 发布与持久化

**双缓冲**：后台构建完整新树后整体替换指针（`tiStore.publish`），绝不边爬边替换，否则用户会看到列表忽多忽少。

**健康阈值**：新树台数低于旧树 `tiMinKeepPct`(70%) 则拒绝发布，保留旧树。整洲抓取失败时不该用残缺树覆盖可用数据。

**磁盘快照**：发布成功即落盘（先写 `.tmp` 再 `rename`，避免被杀时留半截文件）。启动先 `loadTuneInSnapshot()` 立即可服务，再后台刷新——否则每次重启都有 3–5 分钟空窗，期间该来源用户看到空列表。快照路径可用 `TUNEIN_SNAPSHOT` 覆盖，默认落 `os.TempDir()`。

**刷新周期** `tiRefreshInterval` = 6h，启动时先跑一次。电台目录变化很慢，再频繁只是白给上游压力。

**未就绪语义**：无快照的首次启动，目录读取返回 `errTuneInWarming` 而非空列表——否则 APP 会把「还没爬完」显示成「这个国家没有电台」。

## 分类：本地聚合，零额外抓取

位置树上的 station 自带 `genre_id`（抽样 38/38 命中），故分类直接从已爬到的树上聚合，不需要第二棵树。

genre 名称无现成映射：分类树只覆盖 11 个 genre 且多用 `c...` id，而 station 上出现 230 个不同 `genre_id`，`Describe.ashx` 对 genre 返回空 body。可用的取名方式是 `Browse.ashx?id=g26` 的 `head.title`——约 230 次一次性查询、并发下约 30s，随全树一起缓存。

`tiGenreZh` 把常见分类名本地化，未命中的保留英文原名：宁可显示英文，也不要为了凑中文给出错误译名。

全树在内存，`Channels` 可做真正的「省 + 分类」交叉筛选，零外部调用——radio-browser 那边按 tag 筛要另发一次搜索请求。

## 缓存层次

| 层 | 键 | TTL | 说明 |
|---|---|---|---|
| `/v1/*` 响应 | 现有 `cachedHandler` | 沿用（provinces 6h / channels 5min） | 不改 |
| 目录树 | 全局单例 | 6h 后台刷新 + 磁盘快照 | 新增 |
| 流地址 | station id | 现有 `urlCacheTTL` 6h | 复用，键不重叠 |

`Channels` 阶段同样调 `urlCache.warm`：Browse 响应里不含流地址（要 Tune 才有），故此处**无法预热**——这是与 radio-browser 的差异，起播仍需一跳 Tune（实测 1.15s，仍快于 rb 的 2.2–5.7s）。

## HLS 判定

Tune 响应显式给出 `media_type`（`mp3`/`aac`/…）与 `is_hls_advanced`。但 `Channels` 阶段拿不到这些（未调 Tune），而 `.m3u8` 后缀要在下发列表时就决定。

取舍：**列表阶段不补后缀**，一律下发 `/proxy/{id}`。APP 侧本轮已改为「mimeType 优先、URI 后缀兜底」，而 `handleProxy` 透传时会把上游 `Content-Type` 一并回传，HLS 台会带 `application/vnd.apple.mpegurl`——ExoPlayer 的 `ProgressiveMediaSource` 拿到该 Content-Type 仍会失败。

故 `handleProxy` 对 radiotime 的 HLS 台走现有 `serveRewrittenPlaylist` 分支（该函数已按响应 Content-Type 判定，不依赖 URL 后缀）。若实测仍有误判，回退方案是在 `Channels` 阶段对 station 并发预调 Tune 拿 `media_type`——代价是列表变慢，仅在必要时启用。

## 兼容性与回滚

- 纯增量：不改既有三来源代码路径，`resolveStreamURL` 的改动对 UUID 输入行为完全不变。
- 回滚 = 从 `initSources` 摘掉一行 + APP 枚举去掉一项，无数据迁移、无持久化结构变更。
- APP 侧若用户已选中 `tunein` 而网关回滚，`pickSource` 返回 `errUnknownSource`，APP 需能容错退回默认来源（验收项）。

## 自检

`go test` 覆盖不依赖网络的纯逻辑：

- `stationsOf` 的三种分支（children 分组 / 直挂 station / 可下钻 link）与混排层。
- `isTuneInID` 对 `s355090` / UUID / 空串 / `r101215` 的判定。
- provinceCode ↔ guide_id 互转。
- `tiStore.publish` 的健康阈值：台数腰斩时保留旧树。
