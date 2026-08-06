# 执行计划：电台列表分页加载

## A. 服务端切片

- [x] `gateway.go`：加 `paginate(all []Channel, offset, limit int64) []Channel`
- [x] `gateway.go:fetchChannels`：解析 `offset`/`limit`，套用 `paginate`
- [x] 常量：`defaultPageSize = 60`、`maxPageSize = 300`
- [x] 空结果返回 `[]Channel{}` 而非 `nil`（`nil` 会序列化成 `null`，客户端反序列化炸）
- [x] `gateway_test.go`：`paginate` 边界表驱动测试
      （缺省 / limit=0 / 超上限 / 负数 / offset 越界 / 不足整页 / 空输入）
      另加两条：相邻页拼回等于原列表（无重叠无遗漏）、空页 `json.Marshal` 出 `[]`

验证：
```bash
cd radio-proxy && gofmt -l . && go vet ./... && go test ./...
```
→ 通过（gofmt/vet 无输出，`ok radio-proxy 1.634s`）

## B. Caddy 压缩

- [x] `Caddyfile`：`@api path /v1/* /healthz /stations` + `encode @api gzip zstd`
- [x] 注释说明为何只对 JSON 路径生效（音频长连接不压缩的原因保留）

## C. 服务端 curl 验收

本地 `LISTEN_ADDR=:8899 TUNEIN_CATALOG=./data/tunein_catalog.json go run .` 实测：

- [x] `offset=0&limit=60` → 60 条，首条 `s21391`
- [x] `offset=60&limit=60` → 60 条，首条 `s6071`（!= `offset=0` 首条）
- [x] `offset=99999` → `{"code":0,"data":[]}`，HTTP 200（是 `[]` 不是 `null`）
- [x] `limit=0` → 3718 条
- [x] `limit=9999` → 300 条
- [x] `limit=-1` → 3718 条（负数当全量）
- [x] 无参数 → 60 条（默认第一页，符合已接受的行为变更）
- [x] 其余三来源可用：yunting `code=0` 19 条（全量 19，一页装得下）
- [ ] ~~云听/蜻蜓列表短，一页装得下~~ **假设错误**：蜻蜓「全部」全量 300 条、
      全球电台 278 条，都远超 60。不带参数时会被截到 60。
      → 影响：分页不是 tunein 专属，四来源都要能翻页。design.md 的
      `GatewaySource` 统一透传已覆盖，无需改设计；但 G 段走查要把
      「切到蜻蜓/全球电台也能滑到底加载」加进去。
- [ ] `Accept-Encoding: gzip` 时 `/v1/channels` 带 `Content-Encoding: gzip`
      → **本机无 caddy/docker，无法验证**，留到部署后跑。Caddyfile 语法为
      Caddy v2 标准命名 matcher 用法。


## D. APP 数据层

- [x] `GatewayApi.getChannels` 加 `@Query("offset")` / `@Query("limit")`
- [x] `RadioSource.fetchChannels` 加 `offset: Int = 0` / `limit: Int = PAGE_SIZE`
- [x] `GatewaySource.fetchChannels` 透传两参数（override 不带默认值，Kotlin 规则）
- [x] `BaseRadioSource.refreshFavoritePrograms` 传 `limit = NO_LIMIT`（取全量）
      ← 计划说这是唯一的静默失败点，实际有第二处，见下
- [x] `PAGE_SIZE` 定在 `RadioSource` 的 `companion object`（public，ViewModel 也要用；
      `BaseRadioSource` 那个 companion 是 `protected`，够不到）。同处加 `NO_LIMIT = 0`
      给「显式取全量」一个名字，两端契约对齐。

### D 补：计划漏掉的第二个静默失败点

`refreshPrograms` 里给「正在播电台」找最新副标题的兜底路径
（`RadioViewModel.kt:507` 那次 `fetchChannels`）同样是按 `contentId` 在整表里
匹配单台。被默认页大小截断后，排名 60 之后的正在播电台副标题永不刷新，且无报错。
→ 已一并传 `limit = NO_LIMIT`。

代价：该路径对 tunein 会拉全量 918KB。但**改动前它本来就是全量**，不是本次引入的
回归，故不在本任务处理。真要优化：tunein 列表来自静态 catalog（`subtitle` 永不变）、
radiobrowser `subtitle` 恒为空 —— 这两源的整表刷新是纯浪费，可短路掉。留作后续。

## E. ViewModel

- [x] `UiState` 加 `isLoadingMore` / `hasMoreChannels`
- [x] `loadChannels()`：请求首页，落地时设 `hasMoreChannels = size == PAGE_SIZE`
- [x] 新增 `loadMoreChannels()`：
      - 守卫 `isLoadingMore || !hasMoreChannels || isLoadingChannels || showFavorites`
        直接返回（比计划多一个 `isLoadingChannels`：首页还在飞时 offset 未定）
      - offset 取 `channels.size`
      - 复用 `channelsGeneration` 代次校验，过期结果丢弃
      - 追加用 `it.channels + page`
      - 失败不清空已有列表、不弹 error 打断浏览，只关掉 `isLoadingMore`
- [x] 切来源/地区/分类的三个 `loadChannels()` 调用点都会重置分页 —— 在
      `loadChannels()` 起手统一把 `isLoadingMore` / `hasMoreChannels` 归零，
      不在三个调用点各写一遍
- [x] 加 `loadMoreJob`，`loadChannels()` 里一并 cancel（对齐既有
      `loadChannelsJob?.cancel()` 的「中断其网络请求」意图）

### E 补：计划漏掉的分页回归 —— 静默刷新会把已翻的页吞掉

`refreshPrograms`（半点驱动）原本整表替换 `channels = latest`。分页下 `latest`
只有一页，已翻出的第 2、3 页会被吞掉：列表突然缩短、滚动位置跳走。
→ 改为 `limit = channels.size.coerceAtLeast(PAGE_SIZE)` + 按 `contentId` 就地
更新（`s.channels.map { byId[it.contentId] ?: it }`），长度不变、位置不动。
`coerceAtLeast` 不能省：`channels` 为空时 `limit=0` 会被服务端当成「取全量」。
超出服务端单页上限 300 的尾部保留旧快照，不刷新但也不缩短。

**若 `isLoadingMore` 卡在 true 会永久锁死翻页** —— 过期页走 `return@launch`
不复位标志，故复位放在 `loadChannels()` 起手，覆盖「翻页被取消/被丢弃」两种路径。

## F. 列表 UI

- [x] `RadioScreen`：`rememberLazyGridState()` 传给 `LazyVerticalGrid`
- [x] `snapshotFlow` 监听 `visibleItemsInfo.last().index`，距末尾 < `PREFETCH_DISTANCE`
      (12) 时调 `loadMoreChannels()`；阈值用 `layoutInfo.totalItemsCount` 算，
      免得把 `state` 塞进 `LaunchedEffect` 的 key 里反复重启
- [x] 底部加载指示：`isLoadingMore` 时 `item(span = { GridItemSpan(maxLineSpan) })`
      放一个 `SpinningArc`（复用既有组件，24dp，非 `LoadingIndicator` —— 那个
      `fillMaxSize` 会把 footer 撑爆）
- [x] 收藏视图不触发分页（守卫在 ViewModel，UI 只管报告「快到底了」）

### F 补：计划漏掉的第三个坑 —— 切筛选不重置滚动位置会连锁翻页

仓库里原本没有任何 `scrollToItem`。改动前列表整表替换、grid 夹住旧位置，只是显示
位置略怪；分页下变成真 bug：在 TuneIn 美国滚到 index 200 后切分类，新列表只有
60 条，grid 夹到末尾 → 立刻命中预取阈值 → 自动一页页翻到底。
→ 加 `LaunchedEffect(selectedSource, selectedProvinceCode, selectedCategoryId,
showFavorites) { gridState.scrollToItem(0) }`。

验证：
```bash
./gradlew assembleDebug
```
→ `BUILD SUCCESSFUL`

## G. 实机走查

- [ ] TuneIn 美国：首屏秒出
- [ ] 滑到底自动加载，无重复项、无跳动、无闪烁
- [ ] 连续快速滑到底：不重复发同一页请求（看网关日志或 charles）
- [ ] 切地区/分类：列表从头来，不残留上一筛选的项，**且不自动连锁翻页**（见 F 补）
- [ ] 切到蜻蜓（全量 300）/ 全球电台（278）也能滑到底加载 —— 分页不是 tunein 专属
- [ ] 收藏一个排名靠后的台，刷新后副标题正常
- [ ] 播一个排名 60 之后的台，跨半点后播放器面板副标题会刷新（见 D 补）
- [ ] 停在半点前后观察：静默刷新不缩短已翻出的列表、不跳位置（见 E 补）
- [ ] TV D-pad 下键连续按到底部能触发加载
- [ ] 既有三来源无回归（云听/蜻蜓/全球电台列表与起播）


## G. 实机走查

已部署到 `radio.hku.wtf`。用户实机走查后反馈「效果好很多了」（未逐条报告，故下方
只勾用户明确确认的整体结论与我能 curl 复验的部分）。

- [x] 生产环境分页复验（curl `radio.hku.wtf`）：`limit=60`→60 条首条 `s21391`；
      `offset=60`→60 条首条 `s6071`；`limit=0`→3718；`limit=9999`→300；
      `offset=99999`→`[]`
- [x] 手机实测整体：加载明显变快（用户确认）
- [ ] `Accept-Encoding: gzip` 时 `/v1/channels` 带 `Content-Encoding: gzip`
      → **未生效**。生产上直连（绕开本机代理）复验：无 `content-encoding` 响应头，
      压/不压都是 15290 字节。Go 侧分页已是新版，故不是没部署；最可能是 Caddy
      容器没重载配置 —— Caddyfile 是只读 bind mount，`docker compose up -d --build`
      只重建 radio-proxy，caddy 容器不会 recreate，旧配置仍在内存里。
      修复：`docker compose restart caddy`（或 `docker compose exec caddy caddy
      reload --config /etc/caddy/Caddyfile`），然后重跑上面这条 curl。
      不阻塞本次改动：分页已把单页压到 15KB，gzip 是锦上添花的第二层优化。
- [ ] 以下逐条未单独确认，留作后续回归时验：连续快滑不重复发同一页请求、
      切筛选不连锁翻页、排名靠后收藏台副标题刷新、跨半点静默刷新不缩短列表、
      TV D-pad 到底触发

## H. 收尾

- [x] spec 更新：
      - `backend/quality-guidelines.md` 加「`fetchChannels` 不再返回全量」禁用模式
        （两个静默失败点 + 整表替换吞页 + `isLoadingMore` 复位位置）
      - `backend/directory-structure.md` 的 Module Organization 加分页契约
      - `frontend/compose-ui-guidelines.md` 加「切筛选必须 `scrollToItem(0)`」
- [x] 提交：`f3df790 电台列表分页加载：首屏只取一页，滑到底自动续页`
      （APP 侧 5 文件 +165/−9。服务端改动在 `radio-proxy/`，该目录未纳入本仓库
      版本控制，故其内容写在 commit message 里）

## 归档时的遗留项

- **Caddy gzip 仍未生效**（2026-07-26 归档时直连复验：无 `content-encoding`，
  15290 字节）。Go 侧分页在生产上全部正确，故不是没部署；待服务端
  `docker compose restart caddy` 后重验。不阻塞本任务：分页已把单页压到 15KB，
  gzip 是第二层优化。
- **`refreshPrograms` 对 tunein/radiobrowser 的全量刷新是纯浪费**：tunein 的
  `subtitle` 来自静态 catalog（永不变）、radiobrowser 的恒为空。属改动前既有行为，
  非本次引入，未处理。真要优化就在 `GatewaySource` 按 `type` 短路，
  参照已有的 `fetchPlaybill` 短路写法。

## 回滚点

- A 之后若行为异常：`defaultPageSize` 改 0 即恢复全量，APP 侧不用动。
- E/F 之后若滚动触发有问题：不调 `loadMoreChannels` 即退化为「只有第一页」，不崩。
- 全量回滚：三个 query 参数不传即可，服务端 `paginate` 是纯函数无副作用。

## 评审门

- A 完成后先用 curl 跑完 C 的全部条目，服务端确认无误再动 APP。
- F 触及既有列表焦点逻辑（`gridFocusRequester` 在 index==0 上），
  改完必须回归 TV D-pad 焦点，通过后才进 G。
