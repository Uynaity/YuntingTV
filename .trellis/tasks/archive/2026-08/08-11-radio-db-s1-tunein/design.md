# 阶段1 技术设计:建表 + TuneIn 入库

## 现状:TuneIn 管线不是「爬虫产出成品」

来源文档把现状描述成「离线爬虫 → 落地产物 → 服务端只读」,但实际读代码后,
**转换逻辑有一半在 Go 里**,爬虫产出的是原始目录而非成品行:

| 环节 | 现在在哪 | 干什么 |
|---|---|---|
| 抓取 | [tools/tunein_crawl_v2.py](../../../radio-proxy/tools/tunein_crawl_v2.py) | Browse 全球国家/电台,断点续爬,产出原始 JSON |
| 台名清洗 | [source_tunein.go](../../../radio-proxy/source_tunein.go) `tiCleanTitle` | `"100 HD2 \| 94.9 The River (Adult Hits)"` → `"94.9 The River"` |
| 同国去重 | [tunein_genre.go](../../../radio-proxy/tunein_genre.go) `tiDedupe` | 同名同分类合并(KYCC 在美国挂 10 条),留 subtitle 最长的 |
| guide_id → code | `source_tunein.go` `tiGuideToCode` | `r101215` → `101215` |
| genre → 顶级分类 | [tunein_catalog.go](../../../radio-proxy/tunein_catalog.go)`:140` | 323 个 genre 收敛到 music/talk/sports |
| 国名拼音排序 | `tunein_catalog.go`:156 | `x/text/collate`,中文国名不能用码点序 |
| 分类清单派生 | `tunein_catalog.go` `tiCategoriesFrom` | 只输出实际有台的档,固定顺序 |

而且 `tunein_catalog.go`:86 有一条**明确的设计意图**:

> 清洗、去重、本地化都在这里,与爬虫解耦:规则变了只需重启服务,不必重爬。

TuneIn 上游按 IP 限配额、全量重爬要手动换 IP、耗时以小时计。把清洗规则搬进爬虫
= 每次改一条清洗规则都要重爬一遍。这条约束是真的,不能无视。

## 决策 1:抽取与转换分成两段,转换不进爬虫

**原则**:抽取(extract)和转换(transform)是两个独立阶段,原始数据不可变地落地,
转换从原始数据派生、可反复重跑而不重新抽取。抽取是唯一不可控的一步(上游限速、限配额、
会挂、会变),转换是纯函数。

把转换焊进抽取阶段,等于让最便宜的一步(改一条清洗规则)去支付最贵的一步的成本——
在 TuneIn 上是「全球 3000+ 次 Browse、手动换 IP、耗时以小时计」,不是理论损失。

**来源文档要解决的真问题是「转换不该发生在服务端」,这是对的**;但它把「移出服务端」
直接等同于「移进爬虫」,跳过了中间那个位置。正确形状是三段:

```
抓取(Python,原始 JSON 不可变)
  → 转换 + 装载(独立一步,读本地 JSON,秒级可重跑)
    → 服务(只 SELECT)
```

文档与本设计在第三段完全一致,分歧只在第二段是否被吸进第一段。

**方案**:Python 爬虫**完全不动**,仍产出原始 JSON。新增一次性 Go 命令
`radio-proxy -import-tunein <catalog.json>`,复用现有转换代码得到成品 `tiTree`,
再 UPSERT 进三张表。附带好处:`tiDedupe` / `tiGuideToCode` 已有测试覆盖
([tunein_test.go](../../../radio-proxy/tunein_test.go)`:58,:77`),不必在 Python 里重写
~150 行未经测试的副本并长期双份维护。

契约不变:爬虫链路(Python 抓 + Go 装载)仍是**唯一写者**,服务端仍**只读**。
父任务约束 1 是「服务端不写这三张表」,不是「必须用 Python 写」。

**代价(如实记录)**:三个来源的抓取都在 Python,而装载在 Go,是两个语言两个入口,
运维上不如全 Python 一致。这是真代价,但换不来重爬成本那一条。若阶段2 的云听/蜻蜓
装载也走 Go(可直接复用 `source_*.go` 里现成的签名函数),装载端反而统一。
本阶段只做 TuneIn,不提前抽象。

## 决策 2:把 publicBase 包装从转换阶段后移到读取阶段

`toChannel`([source_tunein.go](../../../radio-proxy/source_tunein.go)`:159`)在**转换阶段**
就把运行时配置 `publicBase` 拼进了结果:

```go
img        = s.publicBase + "/img?url=" + url.QueryEscape(st.Image)
PlayUrlLow = s.publicBase + "/proxy/" + st.GuideID
```

转换阶段不该知道服务跑在哪个域名下。这两个值存进 DB 就把当时的域名固化了,
换域名 / 多环境部署时整张表作废。

**修法**:包装从 `toChannel` 移到 `tuneInSource.Channels()` 的返回路径。

- `toChannel` 只产出**原始上游 image URL** 和**空 playUrlLow**,不再依赖 `publicBase`
- `Channels()` 返回前统一 `decorate`:套 `/img?url=` 前缀、拼 `/proxy/<contentId>`

连锁收益:

- `toChannel` 不再用 `s`,`buildFromCatalog` 随之可以变成自由函数 `tiBuildFromCatalog(cf)`
  ——导入命令直接调用,**不需要构造一个假的 `tuneInSource`**。这消掉了原方案里
  「复用 `buildFromCatalog` 却要丢弃它算出的 image 再从 `cf.Stations` 另取一份」的绕路;
  需要撤销一部分的复用不是好复用。
- 阶段3 服务端从 DB 读出来后本来就要套这层前缀,现在提前统一到同一个 `decorate`。
- `image` 列存原始 URL、`play_url_low` 留空,DB 里不存任何运行时配置——
  这条不变量阶段2/3 沿用。

**行为不变**:同样的输出,只是拼接位置后移。代价是本阶段确实动了服务端文件
(`source_tunein.go` / `tunein_catalog.go`),但对外行为零变化,验收仍按「线上无感知」走。

**性能**:`decorate` 每次调用新建一个切片(不能就地改 `tiTree` 里的共享切片)。
`Channels()` 在 `scopeCache` 之后([gateway.go](../../../radio-proxy/gateway.go)`:194`),
每范围 30 分钟才穿透一次,这层拷贝可忽略。

## 决策 3:排序与分类顺序不进表,服务端读时处理

- **国名拼音序**:不加 `sort_order` 列。约 200 行,服务端读出后用现有
  `collate.New(language.SimplifiedChinese)` 排,依赖已在。加列反而要求换排序规则就得重导。
- **分类固定顺序**:`tiTopCategories` 的 music/talk/sports 顺序继续写死在 Go,
  服务端按 DB 里实际存在的分类过滤这个固定清单。表里不加 order 列。

## 决策 4:建表沿用 db.go 风格

DDL 加 `IF NOT EXISTS`,并入 [db.go](../../../radio-proxy/db.go) 的 `schema` 常量,
随 `openActivationDB` 一起执行。不引入迁移框架——与该文件现有注释的判断一致。

同时 `db.go`:23 那段「TuneIn 目录仍走文件方案,不入库」的注释在本阶段变成错的,要改。

## 数据映射

| 表列 | 取值 |
|---|---|
| `source` | 恒为 `'tunein'` |
| `content_id` | `Channel.ContentID`(= guide_id,如 `s12345`) |
| `province_code` | `tiGuideToCode(国家 guide_id)` |
| `category_id` | `tree.genreOf[contentID]`(music/talk/sports);缺失写 `'0'` |
| `title` | `tiCleanTitle` 后的台名 |
| `subtitle` | `current_track`,缺失退 `subtext`(TuneIn 本就不实时,刻意入库) |
| `image` | **原始上游 URL**(见决策 2,`toChannel` 改造后直接就是它) |
| `play_url_low` | 空串 |
| `updated_at` | 本轮导入开始时刻,unix 秒 |

`radio_provinces` ← `tree.provinces`(name + code)。
`radio_categories` ← `tree.categories`,含 `id='0'` 的「全部」行。

## 决策 5:主键必须含 `province_code`(实测推翻来源文档)

来源文档写的是 `PRIMARY KEY (source, content_id)`。对着生产那份目录实测:
**147 个 guide_id 挂在两个以上国家下**(`tiDedupe` 只在同国内去重,跨国不合并;
去重后仍有 125 个)。例:`s6346` 同时在 `r100400` 与 `r101240`。

窄主键下这些台的第二条会被 UPSERT 覆盖,从其中一国的列表里**静默消失**。
而现有内存目录 `byProvince` 本来就是按地区各存一份,两国都能看到。

→ 主键改为 `(source, content_id, province_code)`。这才是行为不变的那个。
`idx_radio_channels_scope` 本就以 `(source, province_code, ...)` 打头,查询侧无影响。

**留给阶段3**:`/v1/channels/by-ids` 按 `content_id` 查会命中多行,需要去重
(今天的行为:`Channels()` 在 `allProvinceCode` 下合并各国切片也不去重,同样重复出现——
所以「按 content_id 取一条」这件事本来就要在读取侧定口径)。

## 写入语义:整份替换,不用 UPSERT

单事务内:

1. `DELETE FROM <三张表> WHERE source = 'tunein'`
2. 全量 `INSERT`

**为什么不是 UPSERT + 删旧行**:那条路要靠「`updated_at < 本轮开始时间`」判定下架,
而 `updated_at` 是 unix 秒 —— **同一秒内跑两次装载就一行也删不掉**(实测:
`TestImportIsIdempotentAndDropsStaleRows` 第一版就是这么挂的)。拿墙上时钟当轮次标记
本身就是错的。而目标语义本来就是「整份替换」(对齐现有 JSON 方案换文件的效果),
删光再插直接就是它,还少一半代码。

单事务保证读者要么看到上一轮的完整数据、要么看到这一轮的,不会看到空表或半张表
(Postgres MVCC,提交前旧快照一直可读)。

导入前校验:若 `tree.stations == 0` 直接报错退出,不执行任何写入——
否则一次坏 JSON 就是把整张表清空**并提交**。

## 本阶段不做

- 服务端读取路径不动,`tunein_catalog.go` 仍读 JSON,线上零变化
- 不建 `radio_channel_categories`(`genreOf` 本来就是单值,无证据要联结表)
- 不做定时调度,导入命令手动跑
