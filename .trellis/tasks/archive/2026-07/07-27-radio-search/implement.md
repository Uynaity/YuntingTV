# Implement — 电台搜索

先服务端（可独立 curl 验证），再客户端。

## 阶段 1：服务端搜索

- [x] 1.1 `go get github.com/mozillazg/go-pinyin@v0.21.0`
- [x] 1.2 `gateway.go`：加 `scopeCache` + `scopedChannels(ctx, s, province, category)`，
      未命中时调 `s.Channels` 并构建 `[]searchEntry`，TTL 用 `channelsTTL(nil)`
- [x] 1.3 `fetchChannels` 改走 `scopedChannels`（顺带修掉「翻页重新回源」）
- [x] 1.4 `search.go`（新）：`searchEntry` 结构、`buildIndex`、`matchChannels(entries, q)`、
      `fetchSearch(r)`；匹配 = title/initials/full 任一 Contains，前缀命中优先（SliceStable）
- [x] 1.5 `main.go`：注册 `mux.HandleFunc("/v1/search", cachedHandler(channelsTTL, fetchSearch))`
- [x] 1.6 `search_test.go`：拼音首字母（`bj`→北京文艺）、中文子串（`北京`）、
      全拼（`beijing`）、英文（`bbc`）、大小写、空 q、前缀优先排序

验证
```bash
cd radio-proxy && go build ./... && go test ./...
```

## 阶段 2：客户端数据层

- [x] 2.1 `GatewayApi.kt`：`searchChannels(source, provinceCode, categoryId, q, offset, limit)`
- [x] 2.2 `RadioSource.kt`：接口加 `suspend fun searchChannels(q, categoryId, provinceCode, offset, limit): List<Channel>`；
      `BaseRadioSource` 给默认实现返回 `emptyList()`（非网关源不支持搜索）
- [x] 2.3 `GatewaySource.kt`：实现之，`dataOrThrow("搜索")`

## 阶段 3：ViewModel

- [x] 3.1 `RadioUiState` 加 5 个搜索字段；`displayedChannels` 加搜索分支
- [x] 3.2 `searchTrigger` StateFlow + init 中的 `debounce`/`flatMapLatest` 管道
- [x] 3.3 `openSearch()`（置 searchActive、清 showFavorites/showPlaybill）、
      `closeSearch()`（清 query/results）、`setSearchQuery(q)`、`appendSearchChar`/`backspaceSearch`
- [x] 3.4 `selectProvince`/`selectCategory` 在搜索态下重发 trigger（`typed=false`，不等 1s）
- [x] 3.5 `loadMoreChannels()` 顶部分流出 `loadMoreSearchResults()`

## 阶段 4：UI

- [x] 4.1 `SearchButton`（放进 `FilterRow.kt`，与 `FavoriteFilterChip` 同文件同规格）
- [x] 4.2 `SearchPanel.kt`（新）：搜索栏（回显 + 光标 + 占位「输入电台名 / 拼音首字母」）
      + 6 列键盘（Column/Row 固定网格，键表由 chunked(6) 生成，末行补切换键与 ⌫）
- [x] 4.3 `RadioScreen.kt`：顶部 Row 挂搜索按钮；横屏左栏按 `searchActive` 切换 searchPane；
      BackHandler 在 `searchActive` 时优先 `closeSearch()`；退出后焦点回 grid
- [x] 4.4 竖屏：顶栏原位切换为 Material 3 `SearchBar`，自动聚焦并唤起系统输入法；结果列表
      保持可见可点。手机启用透明系统栏 edge-to-edge，根内容消费 `safeDrawing` insets；
      TV 保持沉浸式状态栏策略。底部导航手势 inset 补绘播放器 `surface` 色，让小白条背景
      与播放器面板连续，同时控件仍处于安全区内。搜索打开/关闭时，手机顶栏与横屏左侧面板
      分别使用短距离滑动 + 淡入淡出过渡。
- [x] 4.5 右侧空结果文案「未找到相关电台」（复用 `StatusText`）

## 阶段 5：验证

- [x] 5.1 `./gradlew assembleDebug`
- [x] 5.1a `./gradlew testDebugUnitTest lintDebug assembleDebug`（Lint 无本次新增告警；项目暂无单测源）
- [ ] 5.2 装到 TV 走 prd 验收清单（键盘焦点、1s 防抖、返回恢复、收藏态互斥）
- [ ] 5.3 `adb logcat` 确认连打字符期间只发一次搜索请求

## 审查关口

- 阶段 1 结束：`go test` 全绿 + `curl '/v1/search?source=yunting&q=bj'` 出结果，再动客户端
- 阶段 3 结束：确认切筛选/切来源不会残留搜索态
- 阶段 4 结束：TV 真机跑一遍焦点，键盘边缘不吞焦点

## 回滚点

- 阶段 1 独立可回滚（`git revert` 服务端 commit）
- `scopedChannels` 若引入列表异常：`fetchChannels` 改回直接 `s.Channels`
- 客户端阶段 2-4 一起回滚，服务端新端点留着无害
