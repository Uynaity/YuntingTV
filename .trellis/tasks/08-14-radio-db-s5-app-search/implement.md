# 第5步:APP 接入全源搜索 — 执行计划

> 设计:[design.md](./design.md) · 需求:[prd.md](./prd.md)
> 两个仓库:`radio-proxy/`(独立 git 仓库,分支从 `feat/radio-db-s4-search` 切
> `feat/radio-db-s5-catalog-scope`)与主仓库 `app/`(分支 `feat/radio-db-s5-app-search`)。

分两阶段,**服务端必须先落地并部署**(design.md 第 7 节:顺序反了云听的搜索会静默退化)。

## 阶段 1:服务端 `scope=catalog`

**改 `gateway.go`**

- `const catalogScopeProvince int64 = -1` —— 注释写清「只做 scopeCache 的键,
  永远不进 `queryChannels`,契约 12 不受影响」。
- `const catalogScope = "catalog"`。
- `scopedCatalog(ctx, s)`:一句 `cachedScope(scopeKey{s.Key(), catalogScopeProvince,
  allCategoryID}, s.AllChannels)`。
- `fetchChannelsByIDs`:`scope=catalog` 时改用 `scopedCatalog`,其余原样。

**改 `search.go`**:`searchScope` 在 `source=all` 之后、范围搜之前加一档 `scope=catalog`。

**测试**(`search_test.go` / `byids_test.go`)

- 云听 `scope=catalog` 的搜索命中数 **> `provinceCode=0`** 的命中数,
  且后者仍是国家桶 —— **这是本阶段的守门用例**(与第 4 步那条同源)。
- `scope=catalog` 时 `provinceCode` / `categoryId` 传什么都不影响结果。
- 不带 `scope` 的老请求逐字节不变(回归)。
- by-ids 带 `scope=catalog` 能查到**不在** `provinceCode` 那一格里的台。
- `/v1/channels?scope=catalog` 不生效(它不认这个参数,照旧按地区分类过滤)。

```bash
cd radio-proxy && gofmt -l . && go vet ./... && go test ./...
```

**实测 + 文档**:`LIVE_IMPORT=1 go test -run TestLiveSearchAll` 那条用例加一档
`scope=catalog` 的数字;`README.md` 的 `/v1/search` 与 `/v1/channels/by-ids` 段补 `scope`。

**提交并部署**,拿真服务器跑一遍:

```bash
curl -s 'https://radio.hku.wtf/v1/search?source=yunting&scope=catalog&q=guangdong&limit=5'
```

> 判据:在**不传** `provinceCode` 的情况下搜得到广东的台。传统路径(`provinceCode=0`)
> 搜同一个词应该是空的 —— 两条一起跑,差异就是本步的全部价值。

## 阶段 2:APP 接入

**改 `GatewayApi.kt`**:`searchChannels` 与 `getChannelsByIds` 各加
`@Query("scope") scope: String?`(null 时 Retrofit 不发这个参数,旧网关路径保持原样)。

**改 `RadioSource.kt` / `GatewaySource.kt`**

- `searchChannels(q, offset, limit)` —— 删掉 `categoryId` / `provinceCode` 两个参数。
  它们在「搜整份目录」下没有意义,留着只会让人以为搜索还受筛选影响。
- `GatewaySource.searchChannels` 固定发 `scope=catalog`,不再发地区与分类。
- `fetchChannelsByIds` 照旧发 `provinceCode`,**额外**带 `scope=catalog`
  (旧网关忽略它,行为不变;新网关据它查整份目录)。

**改 `ChannelPagingSource.kt`**:跟着改调用,顺手删掉那条「注意参数顺序」的注释 ——
参数没了,那条债自动清账。

**改 `RadioViewModel.kt`**:`updateQuery` 在搜索态下把 `provinceCode` / `categoryId`
归一化成固定值,避免「搜索中改筛选」白发一次结果完全相同的请求。
`_uiState` 里的筛选真值不动,退出搜索照常恢复。

**改 `RadioScreen.kt`**:搜索态下隐藏「地区|类型」与收藏 chip(design.md 决策 4)。
注意 TV 上的焦点:隐藏控件会改变焦点顺序,要确认从搜索框按方向键仍能正常进网格。

**测试**(`app/src/test/`)

- `BrowseQueryFlowTest`:搜索态下改筛选**不产生**新查询(归一化生效)。
- `ChannelPagingSourceTest`:搜索分支调的是新签名,不再传地区/分类。
- `FavoriteRefreshTest` / `GatewayByIdsTest`:by-ids 请求带上了 `scope`。

```bash
./gradlew :app:testDebugUnitTest
```

## 阶段 3:真机验收

装到 TV 与手机各跑一遍(两条输入路径:自绘键盘 / 系统输入法):

- 在云听「北京」下搜一个**广东的台**,能搜到、能播 —— 这是整步的核心判据。
- 三个来源各搜一次:拼音首字母 / 全拼 / 中文子串 / 英文子串(TuneIn)。
- 搜到的台收藏后打开收藏页,副标题**能刷出来**(决策 2 那条路)。
- 退出搜索,地区/分类选中态没被改掉,列表回到原来那份。
- TV 遥控:搜索框 ↔ 网格的焦点往返正常。

## 收尾检查(对齐 prd.md 的验收)

- [ ] 任一来源任一筛选下搜索,覆盖该来源整份目录(云听 943 台,不是 19 个国家台)
- [ ] 拼音 / 中文子串 / 英文子串行为与现状一致
- [ ] 搜到的台可播,起播路径与从列表点开一致
- [ ] 搜到的台可收藏,收藏后副标题能刷新
- [ ] 退出搜索后筛选选中态未被改动
- [ ] 旧 APP 打新服务端不受影响(不带 `scope` 的请求逐字节不变)
- [ ] TV + 手机两条输入路径均验过

## 回滚点

- 阶段 1 部署后如有问题:`scope` 是纯新增参数,没有客户端在用,原地放着即可。
- 阶段 2 发版后如有问题:APP 回退上一版,服务端不用动。
