# 第2步:云听复制同一条链路 — 技术设计

> 需求见 [prd.md](./prd.md);实测事实见 [radio-catalog-db.md](../../../radio-proxy/docs/radio-catalog-db.md) 第四、五节。
> 链路形状见 [第1步 design.md](../archive/2026-08/08-12-radio-db-s1-qingting/design.md) —— **本文只写云听与它的差异**,相同的部分不重复。
> 起点:`radio-proxy` 的 `feat/radio-db-s1-qingting`(s1 五个提交都在这条分支上,尚未并回 main)。

## 0. 动手前必须先量的四件事

四件事全部量到(2026-08-13,北京时间下午),结果进了
[交接文档第四节](../../../radio-proxy/docs/radio-catalog-db.md)。
探测脚本量完即弃、未进仓库 —— 交接文档第七节:「一个实测发现值一行文档,不值一个机制」。

| 量的 | 结果 | 对设计的影响 |
|---|---|---|
| **① 分类筛选是不是省内全量的子序列** | ✅ 成立。国家 19 台 / 安徽 47 台 / 北京 7 台,三省的全部真分类逐条单调、无缺项、无逆序 | 决策 2 走**方案 A**:一列 `sort_order` 够,不需要第二列 → **不动表结构** |
| **② `appProvince/list/all` 的形状** | `provinceCode=0`「国家」排第 0 位,其余 31 省按拼音序;`provinceCode` 是 6 位行政区划码(北京 `110000`) | 决策 1 确认成立:云听**没有**「全部地区」这一档 |
| **③ `programName` 带不带前缀** | 不带,列表 `subtitle` 才带「正在直播:」。**但 `programName` 带尾随空格**(`'新闻进行时 '`) | 换源后不再需要 `ytStripLivePrefix`,**但必须补 `TrimSpace`** —— 今天那个函数尾部做了,不做就与今天不逐字节一致 |
| **④ 凌晨缺口** | 有节目单的台里约 1/3 当天首档在 05:00–06:30(18 台里 6 台),与蜻蜓 24/40 同量级 | 不改设计,接受(同 s1)。s1 遗留的「凌晨 live 显示什么」**仍未量** —— 量的时候是下午 |

**顺带量到、决定决策 4 成不成立的一条**(不在原计划里):

> `subtitle` 为空的台,上游会往这个字段里塞占位文案 `暂无节目单`。
> 抽样 191 台(8 个省):85 台的 `subtitle` 带「正在直播:」前缀且节目单有档,
> **另外 106 台的 `subtitle` 恰好全等于 `暂无节目单`、节目单恰好全是 0 档** —— 两边逐台对齐,
> 说明那句占位就是上游拿节目单的空结果生成的。
>
> → **subtitle 换源零回归**:有节目名的台推算与列表值 85/85 一致,没有的台今天显示的也不是节目名。
> 唯一的用户可见差异是那 106 台从「暂无节目单」四个字变成空白(见第 8 节,待拍板)。
>
> ⚠️ 第一次是按「`subtitle` 非空 = 有节目名」量的,量出个「56% 的台换源会掉字」的假警报。
> 与 s1 那个「跨切档比 subtitle」是同一类测法陷阱,已记进交接文档。

---

## 1. 决策 1:`provinceCode=0` 在读取侧是过滤条件,不是「不过滤」

**这是云听与另外两个来源最硬的一处差异,也是最容易静默丢台的地方。**

`model.go:63` 的 `allProvinceCode = 0` 对蜻蜓 / TuneIn 是「全部地区」哨兵,
`source_qingting.go:208` 因此写成「等于 0 就不加 `province_code` 条件」。
**云听的 0 是上游真实的「全国」桶**(实测 19 个台,id 639–734,一个都不出现在任何真地区下)。
云听没有「全部地区」这个视图 —— 今天 `Channels(0, cat)` 打上游拿到的就是那 19 个台。

所以照抄蜻蜓的写法 = 云听选「全国」时不加地区条件 = 把全部 943 台一次倒给用户,
既不是今天的行为,也不是任何人要的。

写法:共用一个查询函数,**地区过滤是参数而不是来源分支**。

```go
// 三来源共用。province 为 nil 表示不按地区过滤;非 nil 就按它过滤(含 0)。
func queryChannels(ctx context.Context, source string, province *int64, categoryID string) ([]radioChannelRow, error)
```

- 蜻蜓 / TuneIn:`provinceCode == allProvinceCode` 时传 `nil`。
- 云听:**永远**传 `&provinceCode`。

这不违反契约 8(「代码里不留分支」):契约管的是读取路径不按来源 `switch`,
而这里差异被收进了一个参数 —— 每个 Source 在自己的方法里决定传什么,共用函数里没有 `source ==` 的判断。
把这个差异做成表里的一列或一个配置,都是为一个二值事实建一套机制。

`categoryID` 那一侧**没有**这个问题:云听上游的分类列表自带 `id="0"`「全部」,
装载时不写进联结表(备份分支的 `ytRows` 已经这么做),读取时 `"0"` / `""` 一律不加条件,与蜻蜓一致。

> 顺带确认过的等价性:`gateway.go:69` / `gateway.go:115` / `search.go:193` 都用
> `parseInt64(q.Get("provinceCode"), allProvinceCode)`,客户端不传时默认 0。
> 云听今天走到上游就是 `provinceCode=0` → 19 台;切库后按 `province_code = 0` 过滤 → 同样 19 台。**行为不变。**

---

## 2. 决策 2:`sort_order` = 该省 `categoryId=0` 列表里的下标,漏网的 6 台排到该省末尾

蜻蜓的 `sort_order` 是全量翻页的全局下标,一个数搞定。云听没有「全量列表」这种东西 ——
它的目录是 320 个范围取并集来的,产出顺序是「按范围」,不是任何用户看得见的顺序
(`catalog_write.go:41` 那段注释就是为此写的:`radioChannelRow.sortOrder` 是显式字段而不是切片下标)。

云听的用户可见顺序单位是**省**:每个省的「全部分类」列表就是那个省的展示序。

```
sort_order := 该台在 (province=P, categoryId=0) 返回列表里的下标      # 937 台
              该省 categoryId=0 的长度 + 它在分类轮里第一次出现的位次   # 剩下的 6 台
```

- 前提 ①「分类筛选是省内全量的子序列」**已实测成立**(第 0 节),按这一列排,
  省内任何分类下的次序都与今天一致。备选的「再存一列分类内次序」不需要了,表结构不动。
- 那 6 台(5 台只在某个分类下、1 台只在「全部」里)在「地区+全部分类」视图里今天根本看不见,
  排在末尾不会改变任何现有列表的顺序;它们进库是为了不丢台和第 4 步的全局搜索。

`sort_order` 只在**同省内**有意义,与蜻蜓的全局序口径不同 —— 这没关系:
云听不存在跨省列表(决策 1),读取侧永远是「先按省过滤,再按 `(sort_order, content_id)` 排」。

地区行 / 分类行的 `sort_order` 照旧取上游列表切片下标(`writeCatalog` 已经这么做)。

---

## 3. 决策 3:抓取与读取拆开,同 s1

契约 10,交接文档第五节点名「云听会和蜻蜓一样撞到」。今天 `yunting_import.go`(备份分支)
直接调 `s.Provinces()` / `s.Categories()` / `s.Channels()` —— 这三个方法在阶段 4 会改成查库,
到那时装载就是自己读自己,目录永远刷不出新数据。

```
ytFetchProvinces / ytFetchCategories / ytFetchChannels(ctx, province, category)   ← 打上游
(*yunTingSource).Provinces / Categories / Channels                                ← 查库
```

`ytStripLivePrefix` 跟着**抓取侧**走(它清洗的是 subtitle,而 subtitle 换源后由节目单给,
见决策 4 —— 这个函数在读取侧不再有调用者,但抓取侧的起播解析仍会用到整份响应,先留着)。

---

## 4. 决策 4:节目单快照提成按来源的通用实现,不复制第二份

云听的 subtitle 与蜻蜓同构:每天拉一次当天节目单进内存,读取时挑覆盖此刻的那一档。
`(*yunTingSource).Programs` 已经在 `source_yunting.go:131` 就位(`web/appProgram/listByDate`,
日期格式 `yyyy/MM/dd`,与蜻蜓的入参格式相同),**不用新写解析**。

`qingting_playbill.go` 整个文件里与来源相关的只有两处:`&qingTingSource{}` 和日志前缀。
第二份就该合 —— 把它改成 `playbill.go`:

```go
var playbills struct {
    sync.RWMutex
    byID map[string]map[string][]Program   // source -> contentID -> 当天档次
}

func nowPlaying(source, contentID string) string
func refreshPlaybills(ctx context.Context, s Source) error   // s 只用到 Key() 和 Programs()
```

`qtNowPlaying(id)` 的调用点改成 `nowPlaying("qingting", id)`,行为逐字节不变。
理由是契约 8 的正例:两个来源做同一件事,共用一个实现比两份各自演化安全 ——
而且第 3 步 TuneIn 明确**没有**节目单,不会来凑第三份,这个抽象到此为止。

失败语义原样继承 s1(与目录相反):单台失败只丢它自己那行字,不整轮放弃;
整轮全空则保留上一轮快照并报错。

**两处云听独有的取值细节**(第 0 节量出来的,不做就与今天不逐字节一致):

- `programName` 要 `TrimSpace`(实测带尾随空格),对齐今天 `ytStripLivePrefix` 尾部做的那一下。
- 「整轮全空」的阈值判定要当心:云听有 106/191 台上游本来就没排节目单,
  蜻蜓那个「一台都没拉到才算全空」的判据照抄即可 —— 但**别**改成按比例判,
  否则云听正常的一轮会被判成失败。

成本:943 台 ≈ 943 次/天,并发 10,与蜻蜓 1098 台的 56s 同量级。

**已知降级,与 s1 相同、接受**:进程刚起的一分钟内 subtitle 为空;部分台在凌晨首档之前为空(见 ④)。

---

## 5. 决策 5:播放地址起播时解析 —— 独立的上游列表缓存,不是 `scopeCache`

`/v1/stream?source=yunting&contentId=X` 的做法:

1. 从库里查该台的 `province_code`(一行 SQL,`radio_channels` 主键前两列就能定位)。
2. 调 `ytFetchChannels(ctx, province, "0")` 拿该省的上游全量列表,按 `contentId` 取 `playUrlLow`。
3. 下发。拿到的是**刚签发的**地址,比日更库里躺一天的新鲜 —— 这正是它不进库的理由。

**第 2 步必须用一个新的缓存,不能用 `scopeCache`。** 交接文档第六节写的「scopeCache 缓存 30 分钟、
全网共享」是在**切库之前**的语境下说的;阶段 4 之后 `scopeCache` 装的是**库里查出来的行**
(`gateway.go:194` 的 `scopedChannels` 调的是 `s.Channels`),里面根本没有 `playUrlLow`。

新加一层,形状抄 `scopeCache`(懒过期、`sync.Map`、key 有限):

```go
var ytPlayURLCache sync.Map   // provinceCode -> {byID map[string]string, exp time.Time}
```

TTL 取 30 分钟(与 `channelsTTL` 同量级即可,不必对齐 :00/:30 —— 播放地址不随切档变)。
key 只有 32 个省,不做 GC,与既有两层同策略。

### ⚠️ 这一跳变成了云听起播的单点,顺序因此不能反

今天 `/v1/stream?source=yunting` 回空串,APP 走
[GatewaySource.kt:150](../../../app/src/main/java/cn/radio/tv/data/source/GatewaySource.kt) 的
`dto.url.ifEmpty { channel.playUrlLow }` —— 手里那个列表带来的地址是有效兜底。
**切库之后列表里的 `playUrlLow` 是空的**(签名地址不进库),兜底随之消失:
这一跳失败 = 播不出声,而不是「退回旧地址」。

三条应对,都要做:

- **阶段 3(起播解析)必须先于阶段 4(读取切库)上线**,中间隔一段观察。那期间两条路都有地址,
  真出问题也只是回到今天的行为。这是本步的执行顺序约束,写进 implement.md 的阶段划分。
- 解析失败要**重试**(复用 `fetchRetry`)并在缓存里保留上一份:云听实测撞到过间歇性空响应。
- 查不到就诚实回空串 + 记日志,不编一个地址 —— 路径能由 contentId 推出、`key` 推不出(实测篡改/去掉均 403)。

### 不顺手改 `streamType`

`gateway.go:379` 今天给云听下发 `progressive`,APP 用同一个地址、同一个工厂在播。
这一步只把 `url` 从空串换成真地址,**`hls` 仍传 false**。
那个地址虽是 `.m3u8` 结尾,但「今天能播」是既成事实;改流类型是另一个变更,
要改就单独改、单独验,不搭这一步的车。

---

## 6. 决策 6:调度器扩成按来源循环,缓存失效照清

`catalog_refresh.go` 今天硬编码蜻蜓(`importQingTing` + `refreshQTPlaybills` + `qtCatalogEmpty`)。
改成对一个来源列表循环,每个来源「装载 → 节目单」串行,来源之间也串行。

- 时刻仍是北京时间 00:05,不给云听单挑一个时刻:蜻蜓 10s + 56s,云听 56s + 分钟级,
  加起来仍是几分钟,一天一次,不值得为它拆两个 goroutine。
- **空库同步装载**(`qtCatalogEmpty` 那段)对云听同样要做,理由完全一致 ——
  表空 = 该来源返回空列表,而**空结果一样会被缓存**,一次部署换来几分钟的空目录。
- **规则 11(刷新后清元数据缓存)对云听原样适用**,而且更要紧:云听的 subtitle 同样来自快照,
  s1 线上就是在这里踩的(网络台 45 个台 subtitle 全空,数据没问题,是 `respCache` 冻住了一份旧响应)。
  `runCatalogRefresh` 末尾的 `clearMetaCaches()` 位置不动 —— 所有来源都跑完再清一次。

`-import-yunting` 一次性入口照 `-import-qingting` 加一个,手动重跑 / 排查要用。

## 7. 决策 7:`/admin/catalog` 的快照段改成按来源

`admin_catalog.go` 的 `qingtingPlaybills` 字段是单来源形状。跟着决策 4 改成
`playbills: { qingting: {...}, yunting: {...} }`。这个端点没有外部消费者(只给人看),改形状无成本。

云听在这里比蜻蜓多一个值得看的东西:**装载耗时**。320 次枚举 56s 是正常,变成 5 分钟就是上游在退化,
而目录本身仍然是对的 —— 表的行数看不出这件事。日志里已经有(`logLoaded`),这里不重复建状态。

---

## 8. 等价性验证与已知差异

做法同 s1:切库前在当前提交开 worktree 导出基线,切库后逐字节 diff。

**必须逐字节一致**:`Provinces`、`Categories`、若干个具体省的 `Channels`、省 × 分类的 `Channels`
(subtitle 字段除外 —— 它换了来源,单独按 ③ 的口径核)。

**「暂无节目单」那 106 台:已确认不是差异。**(2026-08-13 拍板并核对代码)
服务端算不出节目名就下发空串,占位文案归 UI 层 —— 而 APP 侧**已经在这么做**:
[ChannelCard.kt:158](../../../app/src/main/java/cn/radio/tv/ui/components/ChannelCard.kt) 与
[PlayerPanel.kt:725](../../../app/src/main/java/cn/radio/tv/ui/components/PlayerPanel.kt) 都是
`channel.subtitle.ifBlank { "暂无节目单" }`,注释写明「无(含 TuneIn 无 EPG)则『暂无节目单』,与各源一致」。
所以用户看到的字不变,**逐字节一致,APP 零改动**。
(顺带:s1 蜻蜓凌晨那段缺口在 APP 上也是这四个字,不是空白。)

**预期会出现、需要判定的差异,共三处:**


1. **`playUrlLow` 在列表里从「有值」变成「空串」。** 这是设计,不是回归 —— 地址改由 `/v1/stream` 给。
   验收要盯的是「APP 上云听电台点开能播」,不是这个字段。
2. **目录从 937 / 942 变成 943 台。** 今天的列表页每个范围只展示上游那一轮的结果;
   取并集后那 6 台会出现在它们所属的分类 / 全部列表里。这是修复(否则本来就在丢台),不是回归。
3. **`/v1/channels/by-ids` 命中率上升**,与第 2 条同源。

**不接受、必须回头查的差异:** 任何一个省的电台次序变了(→ 查 `sort_order` / 量 ①),
或者「正在播放」整片空白(→ 查快照建没建起来 / 缓存有没有清)。

## 9. 影响面

| 文件 | 动作 |
|---|---|
| `yunting_import.go` | **新增**,从备份分支取:去 `play_url_low`、加 `sortOrder`(决策 2)、改调 `ytFetch*` |
| `source_yunting.go` | 抓取/读取拆分;三个方法改查库;subtitle 取自快照;新增 `ytFetchChannels` |
| `qingting_playbill.go` → `playbill.go` | **改名 + 提成按来源**(决策 4);`qtNowPlaying` 的调用点跟着改 |
| `catalog_read.go` | **新增**,`queryChannels` 等共用查询(决策 1);`source_qingting.go` 的查库体搬过来 |
| `gateway.go` | `/v1/stream` 的 `yunting` 分支改成解析真地址;新增 `ytPlayURLCache` |
| `catalog_refresh.go` | 按来源循环;空库同步装载对云听同样生效 |
| `admin_catalog.go` | 快照段按来源(决策 7) |
| `main.go` | `-import-yunting` flag |
| `db.go` | **不动** —— 四张表 s1 已建成最终形状,云听不需要任何 DDL |
| `README.md` / `docs/radio-catalog-db.md` | 记新来源、新端点行为、① ③ ④ 量到的结果 |

APP 侧、`docker-compose.yml`、Caddy:**均无改动**(PRD 需求 4)。
蜻蜓的读取路径只在决策 4 的改名和决策 1 的函数提取上被碰到,**行为必须逐字节不变** ——
这两处都要有回归测试兜住。

## 10. 没做的事

- 不改表结构(s1 已建成最终形状),不加任何 DDL。
- 不给云听的播放地址做「提前预热 / 定时刷新」:起播时现取就是最新鲜的,预热是为一个不存在的问题建机制。
- 不测云听签名的 TTL。带时效的值不进库,TTL 是多少不需要知道(交接文档第四、七节点名过两次)。
- 不改 `streamType`(决策 5),不动 `/healthz`,不做推送告警(同 s1 决策 5)。
- 不碰 TuneIn 的任何代码路径。
