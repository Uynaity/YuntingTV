# 第4步:跨来源搜索 — 执行计划

> 设计:[design.md](./design.md) · 需求:[prd.md](./prd.md)
> 工作目录:`radio-proxy/`(独立 git 仓库) · 分支:从 `feat/radio-db-s3-tunein` 切出 `feat/radio-db-s4-search`

一步就能站住,不必分阶段:它是**新增的一档**,现有搜索一字不改,不部署也不影响任何人。

## 阶段 1:整份目录的取法(先把决策 2 那个静默陷阱堵住)

**改 `model.go`**

- `Source` 接口加 `AllChannels(ctx context.Context) ([]Channel, error)`,注释写清
  「为什么不能用 `Channels(0, "0")`」—— 云听的 0 是国家桶(契约 12)。
- `Channel` 加 `Source string \`json:"source,omitempty"\``。

**实现三个 `AllChannels`**

- 蜻蜓 / TuneIn:`return s.Channels(ctx, allProvinceCode, allCategoryID)`,一行。
- 云听:`queryChannels(ctx, s.Key(), nil, allCategoryID)` + 与 `Channels` 同一套装饰
  (subtitle 走 `nowPlaying`、`playUrlLow` 留空)。**装饰那几行抽成一个小函数两边共用**,
  别复制第二份 —— 复制的那份迟早和主路径漂开。

**测试**:云听 `AllChannels` 的行数 > `Channels(0, "0")` 的行数,且后者仍是国家桶。
这条用例就是决策 2 那个陷阱的守门人。

```bash
cd radio-proxy && go vet ./... && go test ./...
```

## 阶段 2:union 索引 + `source=all`

**改 `gateway.go`**:加 `scopedAllChannels(ctx)`

- key 用 `scopeKey{source: "all", province: allProvinceCode, category: allCategoryID}`,
  命中/过期逻辑与 `scopedChannels` 完全一样(能抽就抽,抽不动就照写,别为它造抽象)。
- 未命中时:按 **云听 → 蜻蜓 → TuneIn** 的顺序调三次 `AllChannels`,逐条填 `Source`,
  拼成一个切片,`buildSearchIndex` 一次,存进 `scopeCache`。
- 任一来源失败即整个失败:半份搜索结果比搜不出来更难查(用户只会以为「这台没了」)。

**改 `search.go`**:`fetchSearch` 开头判 `source=all` 走上面那条,其余原样。

**测试**(`search_test.go`)

- 跨源搜一个三家都有的词 → 结果里三个 `source` 都出现
- 云听那部分是全量而不是 19 个国家台(**决策 2 的静默陷阱**)
- 拼音首字母 / 全拼 / 中文子串 / 英文子串在 union 上行为与单源一致
- 结果顺序:中文台(云听、蜻蜓)排在 TuneIn 前面
- 现有 `source=yunting&provinceCode=…` 的响应不变(回归)
- `/v1/channels?source=all` 仍然报错(决策 1)

```bash
cd radio-proxy && go vet ./... && go test ./...
```

## 阶段 3:实测 + 文档

打真库跑一次(可以用 `TestLiveImport*` 灌好的库,或直接连开发库):

- `/v1/search?source=all&q=beijing`、`q=bj`、`q=北京`、`q=kiss` 各来一次,记命中数与来源分布
- **首次构建耗时**(三次全量取回 + 建索引)与**第二次**(缓存命中)各记一个数
- 云听那部分确认是 943 台的池子,不是 19 台

数字写进 `docs/radio-catalog-db.md` 第四节;`README.md` 的 `/v1/search` 段补 `source=all`。

**提交**:`feat(search): 跨来源搜索` + `docs: 第 4 步的实测`

## 收尾检查(对齐 prd.md 的验收)

- [ ] 一次搜索可命中三个来源,每条结果带 `source`
- [ ] 云听参与的是全量 943 台,不是 19 个国家台
- [ ] 拼音搜索(全拼 / 首字母)行为与现状一致
- [ ] 现有范围搜索的响应逐字节不变
- [ ] 搜索响应耗时可接受(参考:全量 23215 行取回 36ms)
- [ ] `/v1/channels?source=all` 不可用(不把它变成全量下载端点)
