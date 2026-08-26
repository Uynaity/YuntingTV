# 第5步:APP 接入全源搜索 — 技术设计

> 需求:[prd.md](./prd.md) · 父任务:[08-12-radio-catalog-db](../08-12-radio-catalog-db/prd.md)
> 事实来源:[radio-proxy/docs/radio-catalog-db.md](../../../radio-proxy/docs/radio-catalog-db.md)

## 0. 现状(读代码得来,不是推断)

**服务端**

```
GET /v1/search?source=&provinceCode=&categoryId=&q=&offset=&limit=
  → searchScope        source=all 走 union;否则 scopedChannels(来源+地区+分类)
  → matchChannels      中文子串 / 拼音首字母 / 全拼
  → paginate
```

第 4 步已经给每个 `Source` 加了 `AllChannels(ctx)`(整份目录,云听走 `nil` 而不是
`provinceCode=0`),并把 `scopeCache` 的读写抽成了 `cachedScope(key, load)`。
**本步服务端要做的,就是把这两样东西接到「单来源整份目录」这个新范围上。**

**APP**

```
RadioViewModel.updateQuery → BrowseQuery(source, provinceCode, categoryId, query)
  → ChannelPagingSource   isSearch ? searchChannels(q,分类,地区) : fetchChannels(分类,地区)
  → GatewaySource         每请求带 source=type.key
```

跨源路由**已经是现成的**(收藏页早就跨源合并展示):`playChannel(channel, source)`、
`toggleFavorite(channel, source)`、`gridKeyOf(source, channel)`、`playingSource`
全是「来源 + 频道」成对传。分页列表那一支写死 `state.selectedSource` ——
而本步搜索**不跨源**,那一支恰好就是对的。

> **这是本步比第 4 步 design.md 预想的小得多的原因。** 那份设计假定的是跨来源结果,
> 才需要 `Channel.source` 和逐项来源路由。范围定成「只搜当前来源」之后,
> **`Channel` 模型不用改,UI 的来源路由一行都不用动。**

## 1. 决策 1:新增 `scope=catalog`,而不是让客户端传 `provinceCode=0`

「整个来源的目录」这件事,客户端拼不出来(见 prd.md 那张表):云听的 0 是国家桶,
蜻蜓的 0 被 APP 过滤掉了,TuneIn 压根没有 0 这一项。

所以服务端出一个**显式的范围参数**:

```
GET /v1/search?source=yunting&scope=catalog&q=…
GET /v1/channels/by-ids?source=yunting&scope=catalog&contentIds=…
```

- `scope` 缺省 = 今天的行为(按 `provinceCode` + `categoryId` 过滤),**旧客户端一个字节不变**。
- `scope=catalog` = 整份目录,**忽略** `provinceCode` 与 `categoryId`。
- 只有这两个端点认。`/v1/channels` **不认** —— 理由同第 4 步决策 1:那会变成全量下载端点。

为什么不用 `provinceCode=-1` 这类哨兵:契约 12 刚把「0 对三个来源意思不同」这件事理清,
再塞一个哨兵进同一个参数,等于把刚擦干净的地方又抹一把。范围是范围,地区是地区。

**实现**(复用第 4 步的两块现成东西,新代码只有几行):

```go
// catalogScopeProvince 只做 scopeCache 的键,永远不会流进 queryChannels ——
// 那里的 province 仍然只有 nil(不过滤)和真实地区码两种取值,契约 12 不受影响。
const catalogScopeProvince int64 = -1

func scopedCatalog(ctx context.Context, s Source) ([]searchEntry, error) {
	return cachedScope(
		scopeKey{source: s.Key(), province: catalogScopeProvince, category: allCategoryID},
		func() ([]Channel, error) { return s.AllChannels(ctx) },
	)
}
```

`searchScope` 与 `fetchChannelsByIDs` 各加一句 `if scope == catalogScope`。

## 2. 决策 2:收藏刷新走同一个 `scope=catalog`,不给结果补 `provinceCode`

跨地区搜到的台,APP 手上没有它真正的地区(prd.md 里那个「在北京搜到广东交通」的例子)。
两条路:

| | 代价 |
|---|---|
| 服务端在搜索结果里补 `provinceCode` | 多一个字段进 `Channel` 契约;而 0 是合法值,`omitempty` 用不了,得想别的兼容办法 |
| **by-ids 也认 `scope=catalog`** | **不加字段**,复用同一份已经缓好的目录索引 |

选后者。收藏刷新与「正在播的台单独刷副标题」(`RadioViewModel:958`)两条路都靠
`fetchChannelsByIds(provinceCode, ids)`,加上 `scope=catalog` 之后,**地区填得对不对
不再影响刷新结果** —— 那个静默退化从根上没有了。

`FavoriteChannel.provinceCode` 本身**不动**:它还有别的用处(`playingProvinceCode`、
旧网关下的分组刷新),而且它是持久化字段,改它要数据迁移。本步只是让刷新不再依赖它的正确性。

**APP 侧的分组逻辑(`BaseRadioSource.refreshFavoritePrograms` 按 provinceCode 分组并发)
也不动。** 新网关下同一来源的几组请求都命中同一份 catalog 缓存,代价是多几次小请求;
改成「一组一来源」是另一件事,不在本步范围里 —— 而且旧网关下现在这套是对的。

## 3. 决策 3:搜索一律 catalog 范围,不给用户开关

需求就是「搜索跨区域跨分类」,那 `RadioSource.searchChannels` 的 `provinceCode` /
`categoryId` 两个参数**就没有意义了**,删掉:

```kotlin
suspend fun searchChannels(q: String, offset: Int, limit: Int): List<Channel>
```

顺带把 `ChannelPagingSource` 里那条「注意参数顺序:RadioSource 是 (q, categoryId,
provinceCode),而 GatewaySource 内部转成 (provinceCode, categoryId, q)」的注释债一并消灭
—— 参数没了,顺序也就无从写反。

`BrowseQuery` 保留 `provinceCode` / `categoryId`(浏览要用),但**搜索态下把它们归一化**,
否则「搜索中改筛选」会因为 `distinctUntilChanged` 判定为新查询而白发一次请求
(结果还完全一样)。归一化放在 `updateQuery` 里,`_uiState` 那份筛选真值不动,
退出搜索照常恢复。

## 4. 决策 4:搜索态下不显示筛选入口

筛选改了不影响搜索结果 —— 摆着一个点了没反应的控件,比没有它更糟。
搜索态下隐藏「地区|类型」与收藏 chip,退出搜索恢复。搜索框本身已经明确表达
「这是另一种模式」,不额外加「正在搜全部电台」这类文案。

> 竖屏手机走 `MobileSearchBar`(系统输入法),横屏 TV 走自绘键盘 `KeyboardGrid` +
> `SearchPanel`。两条路共用同一份 `searchActive` / `searchQuery` 状态,这一条对两边同时成立。

## 5. 决策 5:不做的事

- **不加 `RadioSourceType.ALL`**:它随 `FavoriteChannel` 持久化、决定 DataStore 键后缀、
  还有 `DEFAULT` 回退语义,加一个进去会漏进收藏存储。
- **不接第 4 步的 `source=all`**:用户把范围定成「只搜当前来源」。那一档留在服务端不动。
- **不改 `Channel` 模型**:本步结果不跨源,来源就是 `state.selectedSource`。
- **不改收藏的 `provinceCode` 记录方式**:持久化字段,改要迁移,而决策 2 已经让它不影响刷新。

## 6. 影响面

| 仓库 | 文件 | 动作 |
|---|---|---|
| radio-proxy | `gateway.go` | `catalogScopeProvince` + `scopedCatalog`;`fetchChannelsByIDs` 认 `scope` |
| radio-proxy | `search.go` | `searchScope` 认 `scope=catalog` |
| radio-proxy | `search_test.go` / `byids_test.go` | 新范围的用例(含云听不是 19 台那条) |
| radio-proxy | `README.md` / `docs/radio-catalog-db.md` | 参数与实测 |
| app | `GatewayApi.kt` | 两个端点加 `@Query("scope")` |
| app | `RadioSource.kt` / `GatewaySource.kt` | `searchChannels` 去掉地区/分类参数;by-ids 带 scope |
| app | `ChannelPagingSource.kt` | 跟着改调用 |
| app | `RadioViewModel.kt` | `updateQuery` 搜索态归一化筛选 |
| app | `RadioScreen.kt` | 搜索态隐藏筛选入口 |

## 7. 上线顺序(**有硬顺序,不能反**)

1. **先部署服务端。**
2. 再发 APP。

反过来的话:新 APP 不再发 `provinceCode` / `categoryId`,旧网关又不认 `scope`,
两个默认值一撞 —— **云听的搜索会静默地只搜 19 个国家台**。
这与第 4 步那个坑是同一个,只是换了个地方冒出来。

回滚:APP 侧回退到上一版即可(服务端的 `scope` 是纯新增,留着不碍事)。
