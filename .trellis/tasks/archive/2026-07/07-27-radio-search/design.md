# Design — 电台搜索

## 边界

| 层 | 文件 | 职责 |
|---|---|---|
| 网关 | `radio-proxy/search.go`（新） | `/v1/search` handler + 匹配/排序 |
| 网关 | `radio-proxy/gateway.go` | 共享的「范围内全量电台」数据缓存 |
| 客户端 | `data/remote/GatewayApi.kt` | `searchChannels` 端点 |
| 客户端 | `data/source/RadioSource.kt` + `GatewaySource.kt` | 契约 + 实现 |
| 客户端 | `ui/RadioViewModel.kt` | 搜索态、1s 防抖、结果分页 |
| 客户端 | `ui/components/SearchPanel.kt`（新） | 搜索栏 + 6 列键盘 |
| 客户端 | `ui/components/MobileSearchBar.kt`（新） | 竖屏 Material 3 输入栏 + 系统输入法 |
| 客户端 | `ui/RadioScreen.kt` | 入口按钮、左栏切换、返回键、竖屏输入框 |
| 客户端 | `MainActivity.kt` | TV/手机分流的系统栏与 edge-to-edge 策略 |

## 服务端

### 契约

```
GET /v1/search?source=&provinceCode=&categoryId=&q=&offset=&limit=
→ ApiResponse{code:0, data:[]Channel}
```

`q` 为空 → `data: []`（客户端此时本就不展示搜索结果，服务端不必额外语义）。
分页复用 `paginate`，缺省 `defaultPageSize=60`，与 `/v1/channels` 同口径。
handler 用现有 `cachedHandler(channelsTTL, fetchSearch)` 包裹：key 含 `q`，同词重查直接 HIT。

### 数据缓存（关键）

现状：`Channels()` 每次都回源，`cachedHandler` 只缓存**已编码响应**且 key 含 `offset`。
所以今天翻第 2 页就是一次完整上游拉取；搜索按字符触发会把这个放大 N 倍。

新增一层按范围的**数据**缓存，`/v1/channels` 与 `/v1/search` 共用：

```go
// gateway.go
type scopeKey struct{ source string; province int64; category string }
type scopeEntry struct{ entries []searchEntry; exp time.Time }
var scopeCache sync.Map // scopeKey -> scopeEntry
```

`scopedChannels(ctx, s, province, category)` 命中即返回，未命中调 `s.Channels` 并
**顺带构建搜索索引**（见下），TTL 用现有 `channelsTTL(nil)`（对齐 :00/:30）。
懒过期，无后台 GC —— 与 `respCache` / `imgCache` 同策略，key 组合有限。

改造后 `fetchChannels` 也走它，顺带修掉「翻页 = 重新回源」。

### 搜索索引与匹配

```go
type searchEntry struct {
    ch       Channel
    title    string // 小写原文，去空格
    initials string // 汉字拼音首字母连写：北京文艺 → bjwy
    full     string // 全拼连写：beijingwenyi
}
```

- 用 `github.com/mozillazg/go-pinyin v0.21.0`（固定版本，纯码表，无传递依赖）。
  `pinyin.NewArgs()` 默认 `Normal` 风格（无声调），非汉字原样保留。
- 非汉字字符（英文/数字，TuneIn 台）在三个字段里都是同一份小写原文，
  故英文子串匹配天然生效，不需要分支。
- 索引在 `scopedChannels` 未命中时一次性构建，随缓存条目复用 → 打字过程中零重算。
  最大规模 TuneIn 全球「全部」约 2.4 万台，构建一次约几十毫秒，可接受。

匹配（`q` 先小写去空格）：命中条件 = `title/initials/full` 任一 `Contains(q)`。
排序：前缀命中优先（`HasPrefix` 任一），组内保持原列表次序（稳定 `sort.SliceStable`）。
理由：输入 `bj` 时「北京广播」应排在「河北京津台」之前。

## 客户端

### 状态（`RadioUiState` 新增）

```kotlin
val searchActive: Boolean = false,       // 搜索界面是否打开
val searchQuery: String = "",
val searchResults: List<Channel> = emptyList(),
val isSearching: Boolean = false,
val hasMoreSearchResults: Boolean = false,
```

`channels` **不被搜索结果覆盖** —— 这是「返回键退出后不重新请求即恢复原列表」的前提。
`displayedChannels` 增一条分支：`searchActive && searchQuery.isNotBlank() → searchResults`。
`showFavorites` 与 `searchActive` 互斥：开搜索时置 `showFavorites=false`（PRD 11）。
开搜索时置 `showPlaybill=false` —— 节目单占的是右栏，与搜索结果同一块位置。

### 防抖

```kotlin
private data class SearchTrigger(val q: String, val province: Long, val category: String, val typed: Boolean)
private val searchTrigger = MutableStateFlow<SearchTrigger?>(null)
```

init 中：`searchTrigger.filterNotNull().debounce { if (it.typed) 1000 else 0 }
.distinctUntilChanged { a, b -> a.copy(typed=false) == b.copy(typed=false) }
.flatMapLatest { … }` —— 打字延迟 1s，切筛选立即重搜。
`flatMapLatest` 天然丢弃过期响应，不需要再造一个代次计数器
（`channelsGeneration` 那套是给命令式 `launch` 用的，这里不复用）。

### 分页

`loadMoreChannels()` 顶部按 `searchActive && searchQuery.isNotBlank()` 分流到
`loadMoreSearchResults()`，其余守卫（isLoadingMore / hasMore）各自一套字段。
RadioScreen 的滚到底预取逻辑不动。

### UI

- 入口：顶部 Row 中 `FavoriteFilterChip` / `CompactFilter` 所在 Box 之后加 `SearchButton`
  （复用 `focusableChrome`，圆形 chip，与设置按钮同规格），两种筛选态下都常驻。
- 横屏：`playerPane` 位置改为 `if (state.searchActive) searchPane else playerPane`。
- 竖屏：不渲染键盘；`searchActive` 时用 Material 3 `SearchBar` 原位替换普通顶栏，
  `FocusRequester` + `LocalSoftwareKeyboardController` 唤起系统输入法。搜索结果仍由下方
  Grid 承载，不加阻断触摸的全屏遮罩；IME 搜索键只收键盘，返回箭头/系统返回键退出搜索。
- 手机窗口：`enableEdgeToEdge` 配透明深色系统栏；`RadioScreen` 根内容消费
  `WindowInsets.safeDrawing`，让背景延伸到系统栏下方而交互内容避让状态栏、cutout 与手势区。
  TV 仍隐藏状态栏并保持原沉浸式行为。
- 键盘：`Column { Row … }` 固定 6 列而非 LazyVerticalGrid —— 键位是固定 30/12 格，
  不需要虚拟化，Column/Row 的 D-pad 焦点行为也更可预期。
  键表由 `"ABCDEFGHIJKLMNOPQRSTUVWXYZ".chunked(6)` 生成，末行补 `123`、`⌫`；
  数字态 `"1234567890".chunked(6)` 末行补 `ABC`、`⌫`。
- 焦点：进入搜索 → `FocusRequester` 落到首键；退出 → 落回 grid 首项。
  态切换（123/ABC）不动焦点：两态列数一致，同坐标键位仍存在。

## 兼容与回滚

- 服务端新端点是纯新增；旧客户端不受影响。
- 客户端调 `/v1/search` 失败（旧网关 404）→ `searchResults` 空 + 提示，不影响其他功能。
- 回滚点：服务端与客户端可分别独立回滚；`scopedChannels` 改造若出问题，
  `fetchChannels` 退回直接调 `s.Channels` 即恢复原行为。
