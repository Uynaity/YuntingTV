# 第3步:TuneIn 统一形态 — 执行计划

> 设计:[design.md](./design.md) · 需求:[prd.md](./prd.md)
> 工作目录:`radio-proxy/`(**独立 git 仓库**,父仓库 `.gitignore:33` 排除)
> 分支:从 `feat/radio-db-s2-yunting` 切出 `feat/radio-db-s3-tunein`(s1/s2 的提交在它历史里)

## 分阶段与可回滚点

沿用 s1/s2 的纵切法:**每个阶段自己能站住,单独可部署、可回滚**。

| 阶段 | 产出 | 用户可见? | 回滚方式 |
|---|---|---|---|
| 0 | 两个实测数字 | 否(不改代码) | — |
| 1 | 爬虫 + `-import-tunein`,读取仍走内存树 | 否 | 不部署即可 |
| 2 | 读取切库 + 内存树退休 | **是**(整个 TuneIn 列表换了数据源) | 回滚到阶段 1 的二进制,`./data` 卷还在 |
| 3 | 每周自动爬 + 清理 Python | 否 | 回滚二进制;手动 `-import-tunein` 兜底 |

⚠️ **阶段 2 之前必须先跑一次装载把库填上**,否则切库当场就是空目录。
⚠️ **`./data` 卷和 JSON 文件在阶段 3 之前不要删** —— 阶段 2 的回滚路径依赖它。

---

## 阶段 0:先量两件事(不改代码)✅ 已完成(2026-08-13)

1. **Browse 电台节点的字段分布**:挑 3 个国家节点(大/中/小各一)现拉,统计
   `subtext` / `image` / `current_track` / `genre_id` 各有几条非空。
   期望与现有产物一致:前两个全有、后两个全空。
   **对不上就停下来改设计** —— 决策 1(不移植 genre)和决策 4③(subtitle 取 `subtext`)
   都建在这个分布上。
2. **一轮全量爬的成本**:8 并发跑完整一轮,记总请求数、耗时、国家数、台数。
   参照值(2026-07-27 那份产物):179 国 / 30081 原始行 / 去重后 23215 台。

产出写进 `docs/radio-catalog-db.md` 第四节 TuneIn 段(**实测事实,不是推断**)。

> **实测结果(2026-08-13)**:
> - 字段分布 —— `subtext` / `image` 全有;`current_track` 稀疏(波兰 9/232,柬埔寨与
>   卢森堡 0);**`genre_id` 照常有**(波兰 231/232、柬埔寨 1/1、卢森堡 13/13)。
>   → **推翻了「不移植分类」那一半设计**,见 design 决策 1 的更正。
>   空的是 2026-07 那两份产物本身,不是上游的形状。
> - 一轮全量爬 —— 用旧 Python 爬虫跑到 154/179 国 / 25846 台**全程无 403**,
>   单 IP 一次爬完这条前提成立;精确耗时以 Go 爬虫那一轮为准(`TestLiveImportTuneIn`)。

---

## 阶段 1:爬虫 + 装载(读取不变)✅ 已完成(2026-08-13,提交 aeb0cf8)

**新增 `tunein_crawl.go`**

- `tiCrawl(ctx) (*tiCatalogFile, error)`:枚举洲 → 国家清单 → 逐国 BFS(`MAX_DEPTH=6`)。
- 复刻 `drillable`(只下钻 `r`/`c`/`a`)、`split_nodes`(三种形态:`children` 挂台 /
  直挂台 / `children` 挂 link)、`station_row`(只留 id/text/subtext/image)。
- 请求头复用 `tiSetHeaders` + 加 `Accept-Language: zh-CN`(国名/分类名直接是中文)。
  凭据复用 `tiAuth()`。
- 失败分层(设计决策 6):洲/国家清单失败、某国 403、某国 0 台 → 返回 error(整轮放弃);
  单个叶子节点非 403 失败 → 记日志跳过。
- 并发用 `errgroup` 或简单 worker + channel,并发度做成常量(参照 Python 的 8)。

**新增 `tunein_import.go`**(参考备份 `git show backup/radio-db-2026-08-12:tunein_import.go`)

- `importTuneIn(db *sql.DB) error` = `tiCrawl` → `buildFromCatalog` → `tiCatalogRows` → `writeCatalog`。
- `tiCatalogRows(t *tiTree) *catalogRows`:摊平 `byProvince`,**并按决策 3 计算 `sort_order`**
  —— 全部行按 `(Title, ContentID)` 排一次,下标即位次。备份分支那版没有这一列,要补。
- `links` 对 TuneIn 产出 0 行(genre 恒空),**这是正确结果,不要伪造分类**。

**改 `tunein_catalog.go`**:国名的 collate 拼音序留在这里(装载侧),排完的下标进
`provinces` 的顺序;`tiCatalogFile` / `buildFromCatalog` / `tiDedupe` 其余不动。

**改 `main.go`**:加 `-import-tunein` flag,位置与 `-import-qingting` 并列。

**测试**

- `tunein_crawl_test.go`:`httptest` 假上游,覆盖三种节点形态、`drillable` 过滤、
  深度上限、403 整轮放弃、单节点失败跳过、某国 0 台整轮放弃。
- `tunein_import_test.go`:`tiCatalogRows` 纯函数 —— `sort_order` 是全局 `(Title,ContentID)` 序、
  跨国台在**每个**所属国家各有一行、`links` 为 0 行。
- `catalog_live_test.go`:加 `TestLiveImportTuneIn`(默认跳过,`LIVE_IMPORT=1` 开),
  基线数字来自阶段 0。

**验证**

```bash
cd radio-proxy && go vet ./... && go test ./...
```

```bash
cd radio-proxy && go run . -import-tunein
```

跑完查 `/admin/catalog`:tunein 那行应是 23215 台 / 179 地区 / 1 分类 / 0 联结行。

**提交**:`feat(tunein): 目录爬取搬进服务并装载进库,读取不变`

---

## 阶段 2:读取切库 + 内存树退休(用户可见)✅ 已完成(2026-08-13,提交 57e1c26)

**先导基线**(在改动前的 worktree 里,走文件加载路径):

```bash
cd radio-proxy && BASELINE_OUT=/tmp/ti-before.json go test -run TestExportBaseline -v -timeout 20m .
```

⚠️ 基线与切库后必须**比同一份数据**(设计第 7 节):切库后那次导出别现爬,
临时让装载从现有 `data/tunein_catalog.json` 读入,比完再撤。否则量到的是上游的漂移。

**改 `source_tunein.go`**

- `Provinces` / `Categories` / `Channels` 改调 `queryProvinces` / `queryCategories` /
  `queryChannels`,**不写自己的 SQL**(契约 8)。
- `Channels`:`provinceCode == allProvinceCode` → 传 `nil`(同蜻蜓;云听相反,见规则 12)。
- `decorate` 在读取时拼 `publicBase`:`image` → `/img?url=`,`playUrlLow` → `/proxy/{id}`。
- 删 `errTuneInWarming`(表不会缺失,空表是运维状态,由 `/admin/catalog` 管)。

**改 `catalog_read.go`**:`queryChannels` 的 SELECT 多取一列 `subtitle`,填进
`catalogChannel.Channel.Subtitle`。蜻蜓/云听随后照旧覆盖成 `nowPlaying(...)`,行为不变。

**删除**:`tunein_tree.go` 整个文件、`loadTuneInCatalog`、`tuneInCatalogPath`、
`main.go` 里的加载段、`tunein_test.go` 里依赖 `tiCatalog` 的用例。
**`docker-compose.yml` 的 `./data` 卷与 `TUNEIN_CATALOG` 留到阶段 3 再删**(回滚路径)。

**验证**

```bash
cd radio-proxy && go vet ./... && go test ./...
```

```bash
cd radio-proxy && BASELINE_OUT=/tmp/ti-after.json go test -run TestExportBaseline -v -timeout 20m . && diff <(jq -S . /tmp/ti-before.json) <(jq -S . /tmp/ti-after.json)
```

逐字节一致的预期范围与已知差异见设计第 7 节。**「全部地区」同名台次序不同不是回归**。

> **实测结果(2026-08-13,两次都喂 2026-07 那份旧产物)**:
> provinces(179,含拼音序)/ categories / 美国 8326 / 波兰 222 / 卢森堡 13
> **全部逐字节一致**;全部地区 23215 台集合完全相同,403 行换了位次,
> 且这 403 行**台名两两相同** —— 正是旧实现那个不稳定 `sort.Slice` 的表现。

**提交**:`feat(tunein): 读取路径切库,内存目录树退休`

---

## 阶段 3:每周自动爬 + 清理 ✅ 已完成(2026-08-13,提交 57066ba)

**改 `catalog_refresh.go`**

- `catalogJob` 加 `weekly` / `playbills` 两个字段;TuneIn 入列:
  `{src: &tuneInSource{}, load: importTuneIn, weekly: true}`
  (装载侧不碰 `publicBase`,`image` 存上游原值 —— 这里的空 `publicBase` 是个不变量检查,
  写一行注释说明)。
- `runCatalogRefresh`:`weekly` 作业只在周一那一轮跑;`playbills == false` 跳过
  `refreshPlaybills`(否则 23215 台空节目单会被判成整轮失败,天天刷假告警)。
- `startCatalogRefresh` 的「空库先同步装一轮」跳过 `weekly` 作业(别把冷启动堵几分钟)。

**清理**:`tools/tunein_crawl.py`、`tunein_crawl_v2.py`、`run_tunein_crawl.sh`、
`verify_updates.sh`、`test_catalog.json`、`__pycache__`、5 份 markdown;
`docker-compose.yml` 的 `./data:/data:ro` 与 `TUNEIN_CATALOG`;`README.md` 相关段落。

**文档**:`docs/radio-catalog-db.md` —— 第二节补本步提交清单、第四节补阶段 0 的实测、
第六节第 3 步标完成。`/admin/catalog` 的陈旧度说明要写清 **TuneIn 是周更,
`staleSeconds` 正常可到 7 天多**,别拿蜻蜓的阈值套(同规则 13)。
顺手改掉 `playbill.go` 顶部那句「TuneIn 的 subtitle 来自 `current_track`」(实际是 `subtext`)。

**验证**

```bash
cd radio-proxy && go vet ./... && go test ./... && go build ./...
```

调度判断用单测覆盖(周一 / 非周一、`playbills=false` 不调节目单),不靠等一周。

> **真上游实测(2026-08-13,`TestLiveImportTuneIn`)**:179 国 / 24077 行 /
> **整轮 4m40s** / 0 次 403 —— 周更这个频率绰绰有余,单 IP 一次爬完成立。
> 计划外修了两处(提交 `55df9c9`):
> ① 菲律宾那一轮爬出 0 台,是上游间歇性返空(200 + 合法 JSON,逐节点重试认不出),
>    重试补在国家这一层;
> ② `TestMain` 的临时库目录从来没删过,154 份把盘撑满,伪装成一行 `FAIL`。

**提交**:`feat(tunein): 目录每周自动爬,离线爬虫退休` + `docs: 第 3 步的实测与交接文档`

---

## 收尾检查(对齐 prd.md 的验收)

- [x] 入库 **24077 台 / 179 国**(基线 23215/179,17 天的自然漂移),**143 个跨国台**在每一个所属国家的列表里都在
- [x] ~~分类联结表对 TuneIn 是 0 行~~ **改为**:分类真入库 —— 361 个原始 genre 收敛成三档,音乐 19173 / 谈话 4077 / 体育 457 行,每一档点进去都非空
- [x] `Provinces` / `Categories` / 具体国家 `Channels` 与切库前逐字节一致;
      全部地区集合完全相同(403 行同名台换位,不是回归)
- [x] 内存目录树、`tools/` 的 Python 爬虫、`./data` 卷都已删除
- [x] 每周自动爬在位(单测覆盖调度判据),失败时库里留上一轮数据且 `/admin/catalog` 看得出来
- [ ] 起播路径不变,`Tune.ashx` 正常(真机放一个 TuneIn 台)—— 待真机验收
- [x] 服务端代码对 `radio_*` 四张表无 INSERT/UPDATE/DELETE(契约 1)
- [x] 读取路径里没有 `if source == "tunein"` 这类分支(契约 8)
