# 第3步:TuneIn 统一形态 — 技术设计

> 需求:[prd.md](./prd.md) · 父任务:[08-12-radio-catalog-db](../08-12-radio-catalog-db/prd.md)
> 事实来源:[radio-proxy/docs/radio-catalog-db.md](../../../radio-proxy/docs/radio-catalog-db.md)
> 参考实现:备份分支提交 `4250a5a`(读取切库那部分,**早于 `sort_order` 与 `catalog_read.go`,
> 不能照搬**;它的 SQL 形状与删除清单仍然有用)

前两步已经把地基铺好:`writeCatalog`(整份替换)、`catalog_read.go`(三来源共用的查库 +
排序)、`catalogJobs`(调度)、`/admin/catalog`(状态)都在。TuneIn 这一步只剩三件自己的事:
**把爬虫搬进服务**、**把读取从内存树换成查库**、**把调度挂上去**。

---

## 0. 动手前要量的两件事(不改代码)

s1/s2 的经验:每一次设计变更都来自实测推翻假设,没有一次是在线上发现的。

**① Browse 的电台节点今天还给什么字段。**
现有产物(`data/tunein_catalog.json`,爬于 2026-07-27)实测:30081 行里
`subtext` 30081 有值、`image` 30081 有值、**`current_track` 0 有值、`genre_id` 0 有值**。
动手前拿几个国家节点现拉一次,确认这个分布没变 —— 决策 1 和决策 2 都建在它上面。

**② 一次全量爬要多久、会不会 403。**
`TUNEIN_PARTNER_ID` 在 compose 里已配。挑 8 并发跑一轮完整的,记:总请求数、耗时、
国家数、台数。这个数字决定「每周一次的后台作业」是几分钟还是几小时,也决定
决策 5 里「空库时要不要同步装载」有没有争议。

> ⚠️ 别拿这一轮的耗时和 Python 爬虫的耗时比出「谁更快」的结论(第四节测法陷阱:
> 上一轮就把「探测脚本 fork curl」的开销当成过性能基线)。要量的只有一件事:
> **一轮能不能跑完、要多久**。

---

## 1. 决策 1:爬虫用 Go 重写进服务,`genre` 那一套不移植

`tools/tunein_crawl.py` 是可用的,但它跑在仓库外:运行镜像是 alpine + 一个 Go 二进制,
要在容器里跑它就得装 python3(镜像 +50MB)、把 `tools/` COPY 进去、把 `./data` 从只读
改成可写卷。为了一个每周一次的作业,这是三处部署面的改动换一份不再需要的断点续爬能力。

> ⚠️ **本节原本写着「genre 那一套不移植」,已作废(2026-08-13 阶段 0 实测)。**
> 上游今天照常返 `genre_id`(波兰 231/232、柬埔寨 1/1、卢森堡 13/13),空的是那两份
> 2026-07 的产物本身。**要移植**,否则「入库五项」里的分类对 TuneIn 是空的。
> 这是这一步唯一的功能变化,验收口径见第 7 节。

Go 侧要复刻的:

```
tiCrawl(ctx) (*tiCatalogFile, error)
  ├─ 枚举 7 大洲 → 179 个国家节点        (Python: discover_countries)
  ├─ 逐国 BFS 到 MAX_DEPTH=6,收集电台   (Python: crawl_country / split_nodes / drillable / station_row)
  └─ 每个出现过的 genre 一次 Browse 取名,归入三档 (Python: fill_genres / top_of)
```

归类规则原样搬(两坨正则 + 16 条 `TOP_OVERRIDES` + 「判不出进音乐」的兜底):
判据是 genre **名字**而不是 TuneIn 那三棵编辑精选树 —— 实测那三棵树彼此大量重叠
(talk 树能牵出 12921 台,含大批纯音乐台),按树归类会把上万音乐台判成谈话节目。
名字里中英文关键词都要留:上游按 `Accept-Language: zh-CN` 返回的是中文名。

三条必须原样复刻的判据,漏掉任一条都会整片丢台(Python 侧的注释已经写明代价):

| 规则 | 内容 |
|---|---|
| `drillable` | 只下钻 `r`(地点)/ `c`(台网)/ `a`(品牌);`g` / `p` / `m` 是分类轴与播客,下钻只会重复抓 |
| `split_nodes` | 三种形态都要认:`children` 里挂台(`key=stations`)、直挂台(`item=station`)、`children` 里挂 link(`key=related`,英国 BBC 台网) |
| 请求头 | 装成浏览器 + `Accept-Language: zh-CN` —— 后者是国名/分类名直接是中文的原因,省掉一份译名表 |

**不移植的东西**(都属于「上一轮的 IP 池支线」,`TUNEIN_PARTNER_ID` 让它们失去理由):
断点续爬的状态文件、`done` 清单、403 换 IP、`--status` / `--selftest` 子命令。

## 2. 决策 2:不落 JSON 文件,抓完直接转换入库

抓取产物只在内存里活一轮:`tiCrawl` 返回 `*tiCatalogFile`(结构不变,继续当「原始产物」
的类型),交给**已有的纯函数** `buildFromCatalog` 转换,再 `writeCatalog` 整份替换。
这就是蜻蜓 / 云听的形状(`ytFetch* → ytRows → writeCatalog`),TuneIn 终于同形。

要求 4 的「抓取 / 转换分开」由**函数边界**保证,不是由文件保证:清洗规则改了,
改的是 `buildFromCatalog`,重跑一次装载即可,不碰上游。真要留一份可离线重放的原始
产物,那是「发现」不是「待办」—— 需要时 `-import-tunein -dump /tmp/x.json` 加一行,
现在不做。

代价明说:**一轮爬失败就是整轮作废**(规则 5),库里留上一轮的数据,下周再来。
TuneIn 的目录变化以周月计,这个代价比一个可写卷 + 「文件在哪」的运维面便宜。

## 3. 决策 3:`sort_order` = **全局** `(Title, ContentID)` 下标

规则 9 要求存位次,但 TuneIn 和另外两个来源不一样:**它没有上游策展顺序可复原。**
今天的顺序是服务端自己排的 —— 每国按 `Title` 字节序,「全部地区」把各国合并后再按
`Title` 排一次。

所以位次要这样算:装载时把**全部行**(摊平后含跨国重复行)按 `(Title, ContentID)`
排一次,下标即 `sort_order`。这一个值同时复原两种列表:

- 单个国家 = 全局序的子序列 → 仍是 `Title` 序 ✅(去重后同国内台名唯一,见 `tiDedupe`)
- 全部地区 = 全局序本身 → `Title` 序,同名台按 `ContentID` 定序 ✅

「同名台内部次序与旧实现不同」**不是回归**:旧实现合并 map 后用不稳定的 `sort.Slice`,
实测连跑两次基线导出结果就不同(第四节)。加 `ContentID` 兜底之后才是可复现的。

## 4. 决策 4:读取切库走共用函数,拼音序移到装载侧

三个方法照蜻蜓的样子写,一行自己的 SQL 都不留(契约 8):

```go
Provinces  → queryProvinces(ctx, "tunein")
Categories → queryCategories(ctx, "tunein")
Channels   → queryChannels(ctx, "tunein", filter, categoryID) → decorate
```

三处讲究:

**① 地区过滤传 `nil`,不是 `0`。** TuneIn 的 `0` 和蜻蜓一样是网关合成的「全部」哨兵
(库里没有任何 `province_code=0` 的 TuneIn 行),所以 `provinceCode == allProvinceCode`
时传 `nil`。**云听才是反过来的**(规则 12),那条注释已经在 `queryChannels` 上,别改。

**② 国名的拼音序在装载时排好,写进 `sort_order`。**
今天 `buildFromCatalog` 用 `collate.New(SimplifiedChinese)` 排国名 —— 中文名按码点序
排出来是「东帝汶/中国/丹麦/乌克兰」,用户翻不到目标国。切库后读取侧按整数排
(规则 7),所以这个 collate 排序整个搬到装载侧:排完的下标就是 `sort_order`。
备份分支 `4250a5a` 是在**读取侧**调 collate 的 —— 那是 `sort_order` 出现之前的写法,
不要照抄。

**③ `queryChannels` 要多 SELECT 一列 `subtitle`。**
今天它只取 `content_id / title / image / sort_order`,因为蜻蜓与云听的 subtitle 来自
节目单快照。TuneIn 的 subtitle 是上游的 `subtext`(电台简介,如「Une Voix Pour Tous !」)
—— **静态值,入库不违反契约 9**(会变的是 `current_track`,而它实测恒为空)。
加这一列对另外两个来源无影响:它们随后照旧覆盖成 `nowPlaying(...)`。

> PRD 里「subtitle 来自爬虫产物的 `current_track`」这句是不准的,实测取的是 `subtext`
> 兜底分支。已在 prd.md 更正;`playbill.go` 顶部那句同样的话也顺手改掉。

**`decorate` 在读取时拼 publicBase**(契约 2):`image` 存上游原始 URL,读取时包成
`/img?url=`;`playUrlLow` 是 `publicBase + "/proxy/" + contentID`,库里根本没有这一列。

## 5. 决策 5:调度器加两个开关,不加第二套调度

`catalogJobs` 现在是「每天 00:05,先目录再节目单」。TuneIn 两处不同,都只是调度差异
(契约 8 允许的那种),用两个 bool 表达,不用第二个调度器、不用 cron 容器:

```go
type catalogJob struct {
    src       Source
    load      func(*sql.DB) error
    weekly    bool // TuneIn:目录以周月计变化,一轮爬几千次请求,不值得日更
    playbills bool // TuneIn 没有节目单接口,跳过
}
```

- `runCatalogRefresh`:`weekly` 的作业只在**周一**那一轮跑(判据是星期几而不是「距上次
  7 天」—— 后者要存状态,而进程重启会把它冲掉)。
- `startCatalogRefresh` 的「空库先同步装一轮」**跳过 `weekly` 作业**:那一轮是几千次
  上游请求,把它挡在监听端口前面,等于每次冷启动都先停服几分钟。代价是首次部署
  头几分钟 TuneIn 是空目录且会被缓存住(最长 5 分钟)—— 只发生在空库那一次,
  用一次手动 `-import-tunein` 就能避开。
- `playbills=false` 时不调 `refreshPlaybills`:否则它会对 23215 台各调一次返回空的
  `Programs`,然后按「一台都没拉到」判定整轮失败,天天刷一条假告警。

告警沿用 `/admin/catalog`:它按 source 分组,TuneIn 入库后自动多一行,不用改代码。
**只有陈旧度阈值要在文档里说清**:TuneIn 是周更,`staleSeconds` 正常可到 7 天多,
别拿蜻蜓那条「大于一天多一点就是刷新在失败」的标准套它(和规则 13 是同一类错)。
不做推送告警 —— 在真漏过一次故障之前,那是把一个发现做成一套机制。

## 6. 决策 6:失败判据分三层

规则 5 是「任一子请求失败即整轮放弃」,但 Python 爬虫在实践里分了层,这个分法是对的,
照搬:

| 层级 | 失败处理 | 理由 |
|---|---|---|
| 洲 / 国家清单 | **整轮放弃** | 缺一洲 = 永久漏掉那洲全部国家,而且看起来完全正常 |
| 某个国家整棵子树被 403 | **整轮放弃** | 配额用尽后继续爬只会得到一堆空国家桶 |
| 单个叶子节点非 403 失败 | 记日志跳过 | 宁可少几个台,也不要一个坏节点废掉整个国家 |

再加一条 TuneIn 特有的:**任一国家爬出 0 台 → 整轮放弃**。实测 179 个桶最小的也有
1 台、没有空桶,所以「空桶」只可能是被限流的表现。这是「一台都没拉到」的逐国版,
**不是按比例判**(规则 13 禁的是比例)。

## 7. 等价性验证与已知差异

方法同 s1/s2:切库**之前**在当前提交上导一份基线,切库后再导一份,逐字节 diff
(`catalog_baseline_test.go` 的 `TestExportBaseline`,要加 TuneIn 分支;切库前那次仍走
文件加载路径,所以基线要在改动前的 worktree 里导)。

范围:`Provinces` / `Categories` / 三个具体国家的 `Channels`(挑大中小各一)/
`Channels(全部地区)`。

**等价性一律拿 2026-07-27 那份旧产物比,不拿现爬的数据比。** 这份旧产物恰好把两处
有意的变化都归零,于是量到的差异只可能来自读取路径本身:

- 它的 `genre` 全空 → 两边的分类都只有「全部」一档
- 它的 `current_track` 全空 → 两边的 subtitle 都取 `subtext`

做法:装载侧临时加一个从文件读的入口(或直接把 `tiCrawl` 换成读文件),比完撤掉。

**预期一致**:地区清单(含拼音序)、分类清单、具体国家的电台列表逐字节一致。

**预期差异,且都不是回归**:

1. 「全部地区」里同名台的内部次序不同 —— 见决策 3,旧实现本身不可复现。
2. **换成现爬数据后**会多出两处有意的变化,单独验、不混进上面那次 diff:
   - **分类从 1 档变成 3~4 档**(功能变化,见决策 1 的更正)。验法:抽查几个台的归类
     是否合理、每档点进去非空。
   - **约 4% 的台 subtitle 会从「正在播的曲目」变成电台简介**:实测波兰 9/232 台的
     `current_track` 非空,而它带时效(契约 9)、日更都嫌旧,何况周更 —— 装载时一律丢弃,
     subtitle 只取 `subtext`。

## 8. 影响面

| 文件 | 动作 |
|---|---|
| `tunein_crawl.go` | **新增**,BFS 爬取 |
| `tunein_import.go` | **新增**,`importTuneIn` + `tiCatalogRows`(参考 `4250a5a`,补 `sort_order`) |
| `source_tunein.go` | 三个读取方法改查库;`toChannel` 拆成「装载侧不带 publicBase」+ `decorate` |
| `tunein_catalog.go` | 只剩转换侧:删 `loadTuneInCatalog` / `tuneInCatalogPath`,`buildFromCatalog` 里的国名排序改成产出下标 |
| `catalog_read.go` | `queryChannels` 多取一列 `subtitle` |
| `catalog_refresh.go` | `catalogJob` 加 `weekly` / `playbills`,两处循环各加一个判断 |
| `main.go` | 加 `-import-tunein`;删启动时的 `loadTuneInCatalog` |
| `tunein_tree.go` | **整个删除**(内存树、读写锁、`tiCatalog`) |
| `docker-compose.yml` | 删 `./data:/data:ro` 卷与 `TUNEIN_CATALOG` |
| `tools/tunein_crawl*.py` 等 | **退休**:两版 Python、两个 shell、5 份 markdown、`test_catalog.json`、`__pycache__` |
| `README.md` / 交接文档 | 更新 TuneIn 那几段 |

APP 侧零改动:`/v1/*` 的出参形状一个字节都没变。

## 9. 没做的事

- **不做跨来源搜索** —— 那是第 4 步。
- **不给读取加内存缓存**:实测 23215 行 36ms、单国 16ms,前面还压着两层缓存,
  每范围每 30 分钟才落到这里一次(第四节)。
- **不做 IP 池 / 断点续爬 / 换 IP 重试**:`TUNEIN_PARTNER_ID` 已经让单 IP 一次爬完成立。
- **不做推送告警**:`/admin/catalog` 先有能看的地方。
- **不动 `Tune.ashx` 起播路径与 `urlCache`**:那部分现状本来就对(播放地址不进库)。
- **不清理 `tunein_genre.go` 里的 `tiDedupe`**:它是装载侧的清洗,继续用。
