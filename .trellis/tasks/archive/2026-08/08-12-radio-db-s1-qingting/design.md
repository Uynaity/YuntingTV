# 第1步:蜻蜓打通全链路 — 技术设计

> 需求见 [prd.md](./prd.md);实测事实见 [radio-catalog-db.md](../../../radio-proxy/docs/radio-catalog-db.md) 第四节。
> 本文只写「怎么做」和「为什么这么做」,不重复已有事实。

## 0. 本次新测出来的三件事(交接文档里没有)

都是 2026-08-12 直接打 `rapi.qtfm.cn` 跑出来的,**决定了表结构**:

| 实测 | 结果 |
|---|---|
| `v4/regions` 的顺序 | **不是** id 序、也不是拼音序,是策展序:`网络台 / 北京 / 天津 / 河北 / 上海 / 山西 …` |
| `v4/categories?type=channel` 的顺序 | 同样是策展序:`资讯台 / 音乐台 / 交通台 / 经济台 / 文艺台 …`(13 个) |
| `v4/channels` 的顺序 | 全量翻页(1098 台)是**一个稳定的全局序**;`region_id=3`(10 台)、`category_id=429`(229 台)、`region_id=3&category_id=442`(4 台)三种筛选的返回顺序,**逐条都是该全局序的子序列**(单调,无缺项) |

顺带复核了文档第四节的三条,全部对上:1098 台 / 13 台多分类 / `province_id=471`「50首经典古典音乐」确实没有内嵌 `province` 对象(33 个 distinct `province_id` vs `v4/regions` 的 32 个)。另测出 **0 台无分类**。

**节目单推算的可行性也当场量了**(2026-08-13 10:07,随机 40 台,列表与节目单同一时刻取):

| 指标 | 结果 |
|---|---|
| 推算结果与 `current_program.title` 一致 | **40 / 40** |
| 节目单为空的台 | 0 |
| 有节目单但当前时刻无覆盖档 | 0 |
| 当天首档从 00:00 起(凌晨无缺口) | 24 / 40 —— 另 16 台首档在 06:00 之类 |

> 测法陷阱记一笔:第一次测出 30/42 不一致,是拿半小时前下载的列表比刚拉的节目单,
> 中间跨了 10:00 切档。**比 subtitle 必须两端同一时刻取**,否则量到的是切档而不是差异。

**推论:今天用户看到的每一个列表顺序,都来自上游的策展序,而这个顺序无法从 (id, name) 复原。**
切库后若按 Title 排,北京台的列表首位会从「北京新闻广播」变成「1079音乐有话说」——
每个地区、每个分类的列表都被打乱一次。这是本步最大的等价性风险,不是性能。

## 1. 决策 1:表结构在文档第三节基础上加 `sort_order`

**✅ 已签字(2026-08-13)。** 这是对文档第三节「已验证可用」表结构的唯一偏离。

三张表各加一列 `sort_order BIGINT NOT NULL DEFAULT 0`:

```sql
radio_provinces          (source, province_code) PK
                         province_name / sort_order / updated_at
radio_categories         (source, category_id)   PK
                         category_name / sort_order / updated_at
radio_channels           (source, content_id, province_code) PK
                         title / subtitle / image / sort_order / updated_at
radio_channel_categories (source, content_id, category_id)   PK
```

装载侧写入抓取时的下标,读取侧按它排序。

为什么是加列而不是别的办法:

- 文档第三节的表结构是**只对着 TuneIn 验证过的**,而 TuneIn 的旧实现本来就按拼音 / Title 排序,
  「顺序无法复原」这个问题在它身上不存在。蜻蜓是第一个撞上的来源,云听大概率同样。
- 顺序不是能推导出来的东西。不存它,就只能选一个新顺序,即用户可见的重排。
- 它**不是**给入库清单加第六项:清单说的是电台的哪些属性入库,`sort_order` 是那份清单里
  「地区 / 分类」两项的呈现次序,和 `updated_at` 一样属于表自身的记账列。
- 加列是幂等的 `ADD COLUMN IF NOT EXISTS`,与文档第三节警告的 `DROP COLUMN` 不同,**可逆**。

对后续两步的影响(不在本任务范围,但决定了这一列的形状):

- 第 2 步云听:同样写枚举顺序。
- 第 3 步 TuneIn:装载时算好拼音序 / Title 序写进 `sort_order`,**读取侧就不再需要
  `x/text/collate`**——排序统一成「按整数排」,三个来源零分支(契约 8),
  比备份分支里「TuneIn 读取时现算拼音序」更干净。

其余结构一律照抄文档第三节:`province_code` 进主键、分类走联结表、**无 `play_url_low`**,
一次建成,不留 `ALTER ... DROP COLUMN`。

## 2. 决策 2:上游抓取与读取必须拆成两组函数

备份分支的 `qingting_import.go` 直接调 `s.Provinces()` / `s.Categories()` 复用「补『全部』哨兵」
和「分类名去『台』后缀」这两段网关合成逻辑。**本步一旦把这两个方法改成查库,那就成了自己读自己。**
TuneIn 撞不到这个问题(它的装载读的是 JSON 文件,不是 Source 方法)。

拆法:

```
qtFetchProvinces(ctx) / qtFetchCategories(ctx) / qtFetchAllPages(ctx)   ← 打上游,装载侧用
(*qingTingSource).Provinces / Categories / Channels                     ← 查库,读取侧用
```

哨兵行与「去『台』后缀」留在**抓取侧**,随目录一起入库,读取侧原样输出。
理由同备份分支的注释:否则每个来源在读取路径上都要再带一段补哨兵的分支,统一读取就白统一了。

## 3. 决策 3:subtitle 用「当日节目单内存快照」,不入库

- 每天(北京时间 00:05)对 1098 台各调一次 `v3/channels/{id}/playbills` 取当天节目单,
  存进进程内 `map[contentID][]Program`。并发沿用 `fetchConcurrency=10`,**实测 56s**
  (2026-08-13 真上游:1098/1098 台都有节目单,其中 1094 台在当时有覆盖档)。
- 读取侧构造 `Channel` 时,取覆盖 `now()` 的那档的 `title` 作 subtitle;没有覆盖档则空串。
- 复用现成的 `(*qingTingSource).Programs(ctx, id, date)`,不新写解析。

为什么不入库:它每 30 分钟一变,契约 9(带时效的值不进库)。
为什么不按需拉:一个地区最多 89 台,但 scopeCache 每 30 分钟过期一次,按需拉会把
89 次上游请求压进用户的首屏路径;日更批量总量更小(1098 次/天)且全在后台。

**✅ 方案已定(2026-08-13):用节目单,不用列表。**

被否掉的更省的做法,记在这里免得后面有人重新提:蜻蜓全量列表只要 4 次请求,
`current_program` 就在那份响应里,每 30 分钟重拉一次即 192 次/天,且取值与今天构造上完全相同。
**不采用的理由是需求第 2 条的原话是「不再依赖列表接口现给」**,那条路与它字面冲突。
节目单方案额外买到的东西:精确的档位边界(今天只能按 :00/:30 硬对齐),第 4 步若要做
「还有 x 分钟结束」之类的东西,边界已经在手。

已知的两处降级,都接受:

- **进程刚起的 ~56s 内 subtitle 为空**(后台刷新未完成)。不做启动期阻塞刷新:
  为了一行「正在播放」把服务启动卡半分钟不划算。
- **部分台在凌晨为空**:实测 40 台里 16 台当天首档不是 00:00 起(而是 06:00 之类),
  在首档之前没有覆盖档 → 空串。今天那个时段 live 显示什么**没量到**(测的时候是白天),
  是本设计唯一未量的点。想量的话:凌晨 03:00 跑一次同一个对比脚本即可。
  影响面限于深夜的那行文字,不影响播放,不阻塞开工。

陈旧度与今天持平:今天 subtitle 随 scopeCache 缓存 30 分钟(`channelsTTL` 对齐 :00/:30),
切换后仍在同一层缓存里,口径不变。

## 4. 决策 4:进程内每日调度器,不引外部 cron

新增一个 goroutine,北京时间每日 00:05 依次跑:目录装载 → 节目单快照刷新。启动时也各跑一次
(装载失败不影响服务:库里还有上一轮的数据)。

- 不上 cron 容器 / 宿主 crontab:compose 里再加一个服务,只为一天跑一次。
- `-import-qingting` 一次性入口保留(备份分支已有):首次灌数据、排查、手动重跑都要用它。
- 两个作业串行:蜻蜓装载实测 10–13s + 快照 56s,都是分钟级,并行没有收益,串行少一份并发心智。
  (文档第四节那个 56s 是云听装载的耗时,别混。)

## 5. 决策 5:告警 = 失败日志 + `/admin/catalog`,不引推送

- 装载 / 刷新失败:`log.Printf` 明确到来源与轮次。
- 新增 `GET /admin/catalog`(走现成的 `requireAdmin`,见 [admin.go:39](../../../radio-proxy/admin.go)),
  输出每个来源的 `MAX(updated_at)`、四张表的行数、上一轮结果。

数据全部来自已有的 `updated_at` 列,**不新建状态表**。
不做 webhook / TG 推送:那是「发现 ≠ 待办」里被点名的那类东西,等真的漏过一次故障再说。

**不动 `/healthz`**:目录陈旧不该让 compose 判定容器不健康并重启——重启修不了上游返空,
只会把一个数据问题变成一个可用性问题。

## 6. 决策 6:回滚 = 回滚镜像,不做运行时开关

新表是纯增量的,老二进制完全不认识它们,读取仍走 live 上游。所以「切回 live」= 部署上一版镜像,
不需要 `ENFORCE_ACTIVATION` 那样的环境变量开关。

不加开关的理由:开关意味着两条读取路径长期共存,而它们的 subtitle 来源不同
(live 用 `current_program`,库用节目单快照),两条路都得一直维护。
契约 8 要的是「代码里不留分支」,这里就是第一处该守住的地方。

## 7. 读取路径的形状

沿用备份分支 `4250a5a` 验证过的 SQL 形状,只改三处(去 `play_url_low`、加 `sort_order`、
分类名不写死):

```sql
-- Channels(provinceCode, categoryID)
SELECT content_id, title, image, sort_order
  FROM radio_channels c
 WHERE c.source = $1
   [AND c.province_code = $2]                       -- 非「全部地区」时
   [AND EXISTS (SELECT 1 FROM radio_channel_categories l
                 WHERE l.source = c.source
                   AND l.content_id = c.content_id
                   AND l.category_id = $n)]          -- 非「全部分类」时
```

分类走 `EXISTS` 而不是 `JOIN`:联结表不含 `province_code`,JOIN 到多地区行上语义没错但形状更绕。

排序在 Go 里按 `(sort_order, content_id)` 做,不用 SQL `ORDER BY`(契约 7)。
`content_id` 是兜底定序:SQL 不保证行序,只按 `sort_order` 排会让同序行在两次查询间换位置,
翻页时表现为漏台或重复。

`playUrlLow` 读取时按 `content_id` 拼(`https://ls.qingting.fm/live/{id}/64k.m3u8`),
与今天 [source_qingting.go:135](../../../radio-proxy/source_qingting.go) 同一条规则,不查库。

`image` 存上游原值,不拼 `publicBase`(契约 2)。蜻蜓本来就不经 `/img` 包装,行为不变。

空表返回空列表而不是报错:表不会缺失(建表在启动路径上),「表空」是运维状态,
由 `/admin/catalog` 的陈旧度管,不该在每个读取方法里塞哨兵判断。

## 8. 等价性验证与已知差异

做法沿用上一轮(已验证有效):在切库前的提交上开 worktree 导出基线,切库后逐字节 diff。

**必须逐字节一致**:`Provinces`、`Categories`、若干个具体地区的 `Channels`、
地区 × 分类组合的 `Channels`。有了 `sort_order`,这几项都应当能做到。

**已知且接受的差异,共两处:**

1. **「全部地区 + 全部分类」范围:300 台 → 1098 台。**
   今天这个范围走的是 `page=1&pagesize=300` 一次请求,被截断在 300;切库后是全量。
   文档第四节已记:该入口在 APP 里已隐藏,够不着。这是修复,不是回归。
2. **`/v1/channels/by-ids` 的命中率上升。** 它固定用「全部地区」范围查(见
   [gateway.go:113](../../../radio-proxy/gateway.go)),今天收藏台若不在前 300 里就查不到、
   客户端只能沿用旧快照;切库后全部能查到。同样是上述截断的连带修复。

`province_id=471` 那台照写 `province_code=471`,库里没有对应的地区行,因此在 APP 里
没有地区入口能到达它——**与今天一致**,不是本步要解决的问题(第 4 步的全局搜索才是)。

## 9. 影响面

| 文件 | 动作 |
|---|---|
| `db.go` | 加四张表 DDL(含 `sort_order`) |
| `catalog_fetch.go` | **新增**,从备份分支取,原样 |
| `catalog_write.go` | **新增**,从备份分支取,去 `play_url_low`、加 `sort_order` |
| `qingting_import.go` | **新增**,从备份分支取,去 `play_url_low`、加 `sort_order`、改调 `qtFetch*` |
| `qingting_playbill.go` | **新增**,节目单快照 + 刷新 |
| `catalog_refresh.go` | **新增**,每日调度器 |
| `source_qingting.go` | 抓取/读取拆分;三个方法改查库;subtitle 取自快照 |
| `admin.go` | 加 `/admin/catalog` |
| `main.go` | `-import-qingting` flag;启动调度器 |
| `README.md` | 记新表、新端点、新调度 |

APP 侧、`docker-compose.yml`、Caddy:**均无改动**。
`/v1/stream?source=qingting` 仍返回空 url,客户端沿用 `channel.playUrlLow`(gateway.go:376),不变。

测试基础设施已就位:`TestMain` 里的 embedded-postgres(见
[main_test.go:25](../../../radio-proxy/main_test.go))、备份分支的 `setupRadioTables` 与
`catalog_live_test.go`(`LIVE_IMPORT=1` 才跑真上游)都可直接取用。

## 10. 没做的事

- 不给 `Channels` 加内存缓存:前面已压着 respCache + scopeCache 两层(TuneIn 两万行实测 36ms)。
- 不做推送告警、不做状态表、不做运行时读取开关(理由见决策 5、6)。
- 不碰云听、TuneIn 的任何代码路径。
