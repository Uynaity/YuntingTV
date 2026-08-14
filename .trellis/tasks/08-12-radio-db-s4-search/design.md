# 第4步:跨来源搜索 — 技术设计

> 需求:[prd.md](./prd.md) · 父任务:[08-12-radio-catalog-db](../08-12-radio-catalog-db/prd.md)
> 事实来源:[radio-proxy/docs/radio-catalog-db.md](../../../radio-proxy/docs/radio-catalog-db.md)
> 范围(2026-08-13 定):**只做服务端**,APP 那一半另开一步。

前三步做完之后,三个来源的完整目录都在库里、读取路径也统一了 —— 这一步才谈得上
「一次搜遍」。现有搜索的形状不动,只加一档。

## 0. 现状(读代码得来,不是推断)

```
GET /v1/search?source=&provinceCode=&categoryId=&q=&offset=&limit=
  → pickSource                      来源适配器
  → scopedChannels(s, 省, 分类)      scopeCache:按「来源+地区+分类」缓全量 + 搜索索引
  → matchChannels(entries, q)        中文原文子串 / 拼音首字母 / 全拼,前缀命中排前
  → paginate
```

搜索索引(`searchEntry`)跟着 `scopeCache` 的条目一起活:同一范围内用户连打多少字符
都只建一次。拼音多音字靠逐字候选 + 回溯(`syllablesOf` / `initialsIndex` / `fullIndex`),
**这套东西下推不到 SQL**,是本步的主要约束。

## 1. 决策 1:`source=all` 只在搜索端点认,不进 `pickSource`

`pickSource` 是全部端点共用的。让它认 `all`,`/v1/channels?source=all` 就成了
「一次倒出 26118 台」的下载端点 —— 而 `by-ids` 与分页存在的意义恰恰是消灭全量下载
(见 `gateway.go` 的 `maxByIDs` 注释)。

所以 `fetchSearch` 自己判一句,别的端点一个字不改。不认的 `source` 仍然报错,
现有 `/v1/search?source=yunting&...` 的响应逐字节不变。

## 2. 决策 2:「整份目录」是 Source 的新方法,不是 `Channels(0, "0")`

**云听没有「全部地区」这个视图**:它的 `provinceCode=0` 是上游真实的「国家」桶
(实测 19 台,且这 19 个不出现在任何省下 —— 契约 12)。所以:

| 来源 | `Channels(0, "0")` 的含义 | 整份目录怎么取 |
|---|---|---|
| 蜻蜓 / TuneIn | 「全部地区」哨兵 → 不过滤 | 就是它 |
| 云听 | 上游真实的「国家」桶,19 台 | **必须另走** `queryChannels(source, nil, "0")` |

给 `Source` 接口加一个 `AllChannels(ctx)`:蜻蜓 / TuneIn 一行转调,云听自己走 `nil`。
差异留在各来源自己的方法里 —— 这正是契约 12 定的做法(读取路径不留 `if source ==`)。

> ⚠️ 弄错的代价是**静默**的:用 `Channels(0,"0")` 拼云听,跨源搜索只能搜到它的 19 个
> 国家台,另外 924 台一个都搜不着,而且不会有任何报错。

## 3. 决策 3:复用 `scopeCache`,不建第二套缓存

union 索引就是 `scopeKey{source: "all", province: 0, category: "0"}` 的一条普通条目,
TTL 同 `channelsTTL`(对齐 :00/:30,与列表页一致)。`clearMetaCaches` 也照旧清它。

**额外内存没有想象中大**:拼接 `[]searchEntry` 只复制 struct 数组,里面的字符串与
`syllables` 切片都与三份来源索引共享底层字节 —— 多的是 26118 个 struct header,
几 MB 量级(部署机 1C1G,`db` 限 256m,这个量级可接受)。真嫌大再谈变参匹配,
那要引入「province=-1 表示不过滤」这类哨兵,把刚理清的契约 12 又搅浑,不值。

拼接顺序 **云听 → 蜻蜓 → TuneIn**:`matchChannels` 组内保持原序,于是中文台天然排在
两万多个英文台前面,对中文用户是对的默认。**排序逻辑一行都不用改。**

## 4. 决策 4:结果里带 `source`,但只在这一档带

`Channel` 加一个 `Source string \`json:"source,omitempty"\`` —— 跨源结果不带它,
APP 无从知道该走哪条起播路径(云听要问 `/v1/stream`、蜻蜓由 id 推、TuneIn 走 `/proxy`)。

`omitempty` 是关键:现有端点不填这个字段 → **它们的 JSON 一个字节都不变**,
respCache 与等价性都不受影响。只有 `source=all` 的结果逐条填上。

> APP 侧的 `Json { ignoreUnknownKeys = true }`(`NetworkModule.kt:37`)保证旧版本
> 拿到多出来的字段也不会崩 —— 但它**不会**用这个字段,所以旧 APP 不该发 `source=all`。
> 这是「APP 那一半另开一步」的分界线。

## 5. 决策 5:不做的事

- **不做 SQL 全文检索 / 不把拼音下推**:`syllablesOf` 的多音字回溯在 Go 里,
  下推等于重写一遍并放弃多音字(「音乐之声」要么 ylzs 要么 yyzs,SQL 里只能选一个)。
- **不给 `/v1/channels` 支持 `source=all`**(决策 1)。
- **不做跨源去重**:三个来源的 `content_id` 各自成体系,同一个台在两个源里是两条不同的
  记录、两条不同的起播路径,合并只会让「点了没反应」变成新的一类 bug。
- **不做相关性打分**:现有的「前缀命中排前 + 组内原序」够用,而排序规则是要跟 APP
  一起定的东西 —— 那属于 APP 那一步。

## 6. 影响面

| 文件 | 动作 |
|---|---|
| `model.go` | `Channel` 加 `Source`(omitempty);`Source` 接口加 `AllChannels` |
| `source_yunting.go` | 实现 `AllChannels`(走 `nil`,唯一一个不同的) |
| `source_qingting.go` / `source_tunein.go` | 实现 `AllChannels`(转调 `Channels(0,"0")`) |
| `gateway.go` | `scopedAllChannels`:拼三份 + 建索引,存进 `scopeCache` |
| `search.go` | `fetchSearch` 认 `source=all`,结果逐条填 `source` |
| `search_test.go` | 跨源命中、云听取到全量而不是 19 台、现有范围搜不变 |
| `README.md` | `/v1/search` 那段补 `source=all` |

## 7. 验收怎么量

- `/v1/search?source=all&q=<中文>` 能同时命中三个来源,每条带 `source`
- **云听那部分是 943 台的全量,不是 19 台**(决策 2 的静默陷阱,要专门验)
- 拼音全拼 / 首字母 / 中文子串 / 英文子串行为与现状一致(同一套 `matchChannels`)
- 现有 `/v1/search?source=yunting&provinceCode=&categoryId=` 的响应**逐字节不变**
- 首次构建耗时实测记下来(参考:全量 23215 行取回 36ms;拼音索引只对约 2000 个中文台建,
  TuneIn 那两万多是英文名,`syllablesOf` 直接返回 nil)
