# 第1步:蜻蜓打通全链路 — 执行计划

> 依据 [prd.md](./prd.md) + [design.md](./design.md)。工作目录一律 `radio-proxy/`(独立 git 仓库)。
> 起点:`main`(干净)。参考实现:`backup/radio-db-2026-08-12`,**按文件取用,不整条合**。

## 分阶段与可回滚点

五个阶段,**每个阶段结束时线上都是可部署、可停下的状态**。阶段 1–3 对用户零可见变化。

| 阶段 | 结束时的线上状态 | 回滚方式 |
|---|---|---|
| 1 建表 | 四张空表,没有任何代码读它 | 部署上一版镜像;表留着无害 |
| 2 装载 | 表里有 1098 台,读取仍走 live | 同上 |
| 3 节目单快照 + 调度 | 快照在内存里但没人读,目录每日自动更新 | 同上 |
| 4 读取切库 + subtitle 换源 | **用户可见的切换点** | 部署上一版镜像即回 live(设计决策 6) |
| 5 告警端点 + 文档 | — | — |

---

## 阶段 1:建表

- [x] `db.go` 的 `schema` 常量末尾追加四张表 DDL(design.md 决策 1 的形状)
  - [x] `radio_provinces` / `radio_categories` / `radio_channels` 各含 `sort_order BIGINT NOT NULL DEFAULT 0`
  - [x] `radio_channels` 主键 `(source, content_id, province_code)`;**无 `play_url_low` 列**
  - [x] `radio_channel_categories` 主键 `(source, content_id, category_id)`,不含 `province_code`
  - [x] 索引:`idx_radio_channels_prov (source, province_code)`、`idx_rcc_scope (source, category_id)`
  - [x] 一次建成,**不写任何 `ALTER TABLE ... DROP COLUMN`**
- [x] 更新 `db.go` 顶部注释:目录表也在这个库里,写入方是装载命令,服务端只读

验证:

```bash
cd radio-proxy && go build ./... && go test ./... 2>&1 | tail -20
```

> ⛔ **评审点 1**:表结构一旦部署过就不好改。逐列对一遍 design.md 决策 1 的形状再往下走。
> (`sort_order` 偏离已于 2026-08-13 签字。)

---

## 阶段 2:装载

- [x] 取备份分支的抓取骨架,**原样**:
      `git checkout backup/radio-db-2026-08-12 -- catalog_fetch.go`
- [x] 取 `catalog_write.go`,改三处:
  - [x] `radioChannelRow` 去掉 `PlayUrlLow` 的写入,INSERT 列表去 `play_url_low`
  - [x] `catalogRows` 的三类行各带 `sortOrder`,INSERT 写进去
  - [x] 保留:空目录拒写、单事务 `DELETE WHERE source=?` + 全量 INSERT、逐行 INSERT
- [x] 取 `qingting_import.go`,改三处:
  - [x] 去掉 `qtPlayURL` 的入库(函数本身留给读取侧用,或直接用 source_qingting 里那行)
  - [x] `qtRows` 按全量翻页的下标填 `sortOrder`
  - [x] 改调 `qtFetchProvinces` / `qtFetchCategories`(下一条)而不是 `s.Provinces` / `s.Categories`
- [x] `source_qingting.go` 拆分(design.md 决策 2):把现有 `Provinces` / `Categories` 的
      上游抓取体抽成 `qtFetchProvinces(ctx)` / `qtFetchCategories(ctx)`,
      现有方法先原样转调它们(**本阶段行为完全不变**)
  - [x] 哨兵行(地区 `0` / 分类 `0`)与「分类名去『台』后缀」留在抓取侧,随目录入库
  - [x] 哨兵行的 `sort_order` 取 0,排在最前(与今天一致)
- [x] `main.go` 加 `-import-qingting` flag(备份分支的写法),跑完即退,不起 HTTP
- [x] 测试:
  - [x] 取 `catalog_import_test.go`,保留 `fetchAll` 的失败语义用例
  - [x] 新增:`qtRows` 的 `sort_order` 单调、多分类台产出多条 link 行、
        `province_id=471` 那台不崩且照写
  - [x] 取 `catalog_live_test.go`(`LIVE_IMPORT=1` 才跑)

验证:

```bash
cd radio-proxy && go build ./... && go vet ./... && go test ./... 2>&1 | tail -20
```

打真上游(基线:1098 台 / 13 台多分类 / 0 台无分类 / 32 个地区行 / 13+1 个分类行):

```bash
cd radio-proxy && LIVE_IMPORT=1 go test -run TestLiveImport -v -timeout 20m .
```

> ⛔ **评审点 2**:对不上基线**先别改代码**——上游自己会漂,先确认是漂移还是回归。

---

## 阶段 3:节目单快照 + 每日调度

- [x] 新建 `qingting_playbill.go`:
  - [x] `map[contentID][]Program` + `sync.RWMutex`(整份替换,不做增量)
  - [x] `refreshQTPlaybills(ctx)`:从库里取本来源全部 `content_id`,用 `fetchAll`
        (并发 10)逐台调 `(*qingTingSource).Programs(ctx, id, 今天)`
  - [x] `qtNowPlaying(contentID) string`:取覆盖 `now()` 的那档标题,取不到返回空串
  - [x] 失败语义与目录**不同**:节目单是逐台独立的,单台失败只丢它自己的 subtitle,
        不整轮放弃(契约 5 管的是「半张目录」,subtitle 缺一条不会静默丢台)
- [x] 新建 `catalog_refresh.go`:goroutine,北京时间(`cnZone`)每日 00:05
      串行跑「目录装载 → 节目单刷新」;启动时各跑一次
  - [x] 失败只记日志并等下一轮,不退出进程
- [x] `main.go` 起调度器(仅服务模式,`-import-*` 一次性模式不起)
- [x] 测试:`qtNowPlaying` 的边界(覆盖档 / 无覆盖档 / 空快照)

验证:同上 `go test ./...`。本阶段读取路径未变,线上无可见变化。

---

## 阶段 4:读取切库 + subtitle 换源(**用户可见的切换点**)

⚠️ 这两件事必须在同一次提交里完成。先切读取、subtitle 还没换源,「正在播放」就空了。

- [x] 导基线(**改代码前做**):在当前提交开 worktree,导出蜻蜓的
      `Provinces` / `Categories` / 若干具体地区 `Channels` / 地区×分类组合 `Channels`
- [x] `source_qingting.go` 三个方法改查库(design.md 第 7 节的 SQL 形状):
  - [x] `Provinces` / `Categories`:查库 + 按 `sort_order` 排
  - [x] `Channels`:按地区 / 分类过滤,分类走 `EXISTS` 子查询
  - [x] 排序在 Go 里按 `(sort_order, content_id)`,**不用 SQL `ORDER BY`**
  - [x] `playUrlLow` 读取时按 `content_id` 拼,不查库
  - [x] `subtitle` 取自 `qtNowPlaying`
  - [x] 空表返回空列表,不报错
- [x] 测试:新建 `qingting_read_test.go`,仿备份分支的 `tunein_read_test.go`
      (`setupRadioTables` + 手写种子行,打真库):
  - [x] 一台多分类 → 每个所属分类下都查得到
  - [x] `categoryId` 为 `""` / `"0"` 等同「全部」
  - [x] 顺序按 `sort_order`,同序时按 `content_id` 兜底
  - [x] 空表返回空列表

验证:

```bash
cd radio-proxy && go build ./... && go vet ./... && go test ./... 2>&1 | tail -20
```

逐字节 diff 基线:

- [x] `Provinces` / `Categories` —— 必须完全一致
- [x] 具体地区、地区×分类的 `Channels` —— 必须完全一致(subtitle 外的字段)
- [x] 「全部地区+全部分类」—— 预期 300 → 1098,**这是已知差异**(design.md 第 8 节),不是回归
- [ ] 人工:APP 上翻蜻蜓的地区/分类列表,顺序与线上一致;「正在播放」有内容;点开能播
      **(只能由人做,待验收)**

> ⛔ **评审点 3**:顺序或「正在播放」任一不对,**不要往下走**,回来查 `sort_order` / 快照。

---

## 阶段 5:告警端点 + 文档

- [x] `admin.go` 加 `GET /admin/catalog`(走 `requireAdmin`):
      每来源 `MAX(updated_at)`、四张表行数、上轮结果
- [x] **不动 `/healthz`**(design.md 决策 5)
- [x] `README.md`:新表、`-import-qingting`、每日调度、`/admin/catalog`
- [x] 复核:服务端代码里对 `radio_*` 四张表**没有** INSERT / UPDATE / DELETE(契约 1)

```bash
cd radio-proxy && grep -rn "INSERT INTO radio_\|UPDATE radio_\|DELETE FROM radio_" --include='*.go' . | grep -v _test.go
```

期望:只出现在 `catalog_write.go`。

---

## 收尾检查(对齐 prd.md 的验收)

```bash
cd radio-proxy && go build ./... && go vet ./... && go test ./... 2>&1 | tail -20
```

- [x] 四张表无 `play_url_low`;库里没有任何蜻蜓播放地址
- [x] 装载真上游:1098 台 / 13 台多分类 / 471 那台在
- [x] 抓取失败或结果为空时不写库(有测试覆盖)
- [x] subtitle 来自 playbills 推算,APP「正在播放」不为空
- [x] 等价性:除第 8 节那两处已知差异外逐字节一致
- [x] 每日调度在位,失败可从日志 + `/admin/catalog` 发现

## 提交切分

一个阶段一个提交,信息写清「为什么」而不是「做了什么」。
**注释里不要写没测过的「实测」口吻断言**(文档第七节点名过两次)。
