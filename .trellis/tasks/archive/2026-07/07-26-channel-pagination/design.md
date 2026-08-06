# 设计：电台列表分页加载

## 边界

改动跨服务端与 APP，但两侧耦合面只有一处：`/v1/channels` 多两个 query 参数。
响应体形状不变（仍是 `List<Channel>`），因此没有新增 DTO、没有序列化改动。

```
APP  RadioScreen(滚动到底) → RadioViewModel.loadMoreChannels()
       → GatewaySource.fetchChannels(offset) → GatewayApi(offset, limit)
                              ↓ HTTP
网关  gateway.go:fetchChannels → Source.Channels(全量) → 切片 → 缓存 → 返回
```

## 服务端

### 切片放在 gateway 层，不下沉到 Source

`Source.Channels` 保持返回全量。四个来源里三个是内存/单次请求出全量，切片是纯
内存操作；下沉会让每个来源各写一遍相同的边界处理。radiobrowser 的 `limit=300`
是上游搜索参数（控制上游返回多少），与本次的展示分页是两回事，不动它。

```go
// fetchChannels 内，拿到 all []Channel 之后
offset := parseInt64(q.Get("offset"), 0)
limit := parseInt64(q.Get("limit"), defaultPageSize)
return paginate(all, offset, limit), nil
```

`paginate` 的边界规则：

| 输入 | 行为 | 理由 |
|---|---|---|
| `limit` 缺省 | 60 | 安全默认，任何调用方都不会意外拉到 3.7MB |
| `limit=0` | 全量 | 收藏刷新需要，显式表达「我要全部」 |
| `limit>300` | 夹到 300 | 单页 300 条约 74KB，gzip 后 11KB，仍算快 |
| `limit<0` | 当 0（全量） | 负数没有合理语义，与 0 合并处理 |
| `offset<0` | 当 0 | 同上 |
| `offset>=len` | 空数组 | 翻过头是正常终止，不是错误 |
| `offset+limit>len` | 截到末尾 | 最后一页不足整页 |

返回空切片而非 `nil`：`json.Marshal(nil)` 出 `null`，客户端 `List<Channel>`
反序列化会炸。这是最容易漏的一条，测试必须覆盖。

### 「还有下一页」不单独返回

判据是「本页条数 == 请求的 limit」，客户端自己算。代价是最后一页正好整除时会
多发一次拿到空数组的请求——一次 200 字节的请求，不值得为它引入分页包装对象、
改契约形状、四来源都要算总数。

### 缓存

`cache.go:70` 的 key 是 `r.URL.RequestURI()`，已含全部 query，分页页各自成键。
不动缓存层。副作用：key 数量从 `source×省×分类` 变成再乘页数，仍然有限，
且 `respCache` 是懒过期的（TTL 5min），不会无限涨。

### Caddy 压缩

```caddyfile
@api path /v1/* /healthz /stations
encode @api gzip zstd
```

只对 JSON 路径生效。`/proxy`、`/seg`、`/img` 不进 matcher —— 音频已压缩，
gzip 无收益且会给长连接引入缓冲延迟（这正是原来整体禁用 encode 的理由，
现在把它收窄到该生效的范围）。

即使分页做了，压缩仍然值得：单页 60 条约 15KB → 2-3KB。

## APP

### 接口签名

```kotlin
// RadioSource
suspend fun fetchChannels(
    categoryId: String,
    provinceCode: Long,
    offset: Int = 0,
    limit: Int = PAGE_SIZE,
): List<Channel>
```

带默认值，所有既有调用点不用改。`limit` 也暴露出来是为了让收藏刷新能传 0
取全量——不暴露的话那条路径没法表达「我要全部」。

### ViewModel 状态

```kotlin
val channels: List<Channel>       // 已加载的全部页拼接
val isLoadingChannels: Boolean    // 首页加载（整屏 loading）
val isLoadingMore: Boolean        // 追加下一页（底部小指示器）
val hasMoreChannels: Boolean      // 上一页返回条数 == PAGE_SIZE
```

两个 loading 分开，因为 UI 表现完全不同：首页是整屏 `LoadingIndicator()`，
追加是列表底部一行。合成一个会让追加时整个列表被 loading 替换掉。

### 竞态

`loadChannels()` 已有 `channelsGeneration` 代次机制（`RadioViewModel.kt:343`）
和 `loadChannelsJob.cancel()`。`loadMoreChannels()` 复用同一个代次：切筛选时
`loadChannels()` 自增代次，飞行中的 loadMore 回来发现代次变了就丢弃，不会把
上一个筛选的第二页追加到新列表里。

追加时用 `it.channels + page` 而非按 offset 写入固定位置——代次校验已经保证了
只有当前筛选的页会落地，顺序天然正确。

幂等：`loadMoreChannels()` 首行检查 `isLoadingMore || !hasMoreChannels` 就返回。
滚动会连续触发十几次，没这个守卫会同一页发十几次请求。

### 滚动触发

```kotlin
val gridState = rememberLazyGridState()
LaunchedEffect(gridState, state.hasMoreChannels) {
    snapshotFlow { gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
        .distinctUntilChanged()
        .collect { last ->
            if (last >= state.displayedChannels.size - PREFETCH_DISTANCE) {
                viewModel.loadMoreChannels()
            }
        }
}
```

`snapshotFlow` + `distinctUntilChanged` 而非在 composition 里判断：后者每帧
重组都会跑一次判断。`PREFETCH_DISTANCE` 取一屏左右（12，约两行），让加载在
用户滑到底之前就开始，滚动不断档。

D-pad 走同一条路径：焦点移动会滚动列表，`visibleItemsInfo` 一样变化。不需要
为 TV 单独写触发逻辑。

收藏视图（`showFavorites`）不分页——收藏是本地数据，全量在手。触发前需判断。

### 收藏刷新取全量

`BaseRadioSource.refreshFavoritePrograms`（`RadioSource.kt:80`）改为
`fetchChannels(ALL_CATEGORY_ID, code, limit = 0)`。

不改的后果：收藏了排在第 61 名之后的台，副标题永远不刷新，且没有任何报错。
这是本次改动里唯一的静默失败点。

## 兼容性与回滚

- 服务端：不带参数 = 第一页 60 条（已确认的选择）。已安装的旧版 APP 会只看到
  60 台。当前只有本机调试在用，可接受。
- 回滚：服务端把 `defaultPageSize` 改成 0（= 全量）即可恢复旧行为，APP 侧不用动。
- APP 回滚：`loadMoreChannels` 不调用即退化为「只有第一页」，不会崩。

## 不做

- 分页包装对象（`{items, total, hasMore}`）：契约形状变更波及四来源和所有调用点，
  换来的只是省掉最后一次空请求。
- 下拉刷新：本次不涉及，现有筛选切换已能重新加载。
- image 字段瘦身（占 56%）：分页后单页体积已经很小，先不动。若之后全量场景
  仍嫌大，再考虑把 `/img?url=` 前缀移到客户端拼。
