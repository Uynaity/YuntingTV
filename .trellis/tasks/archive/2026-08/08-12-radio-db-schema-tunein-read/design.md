# A 技术设计:联结表 + TuneIn 读取切 DB

前置阅读:[prd.md](prd.md);父任务契约见 [父 PRD](../08-11-radio-db-migration/prd.md)。

## 决策 1:废掉 `tiTree`,读取直接查库

原计划是「从 DB 装配 `tiTree`,替换 JSON 装配」——保留内存树,只换数据源。
读完代码后认为不该这样:**内存树的存在理由是「JSON 文件只能整份读入」,这个理由随 DB 一起消失了。**

DB 本身就支持按范围查询,而查询前面还压着两层缓存:

```
请求 → respCache(完整 URI) → scopeCache(来源+地区+分类, 30 分钟) → SELECT
```

`Channels()` 每个范围每 30 分钟才被调用一次([gateway.go:194](../../../radio-proxy/gateway.go)),
一次 SELECT 的成本完全可以忽略。为这个频率维护一棵常驻内存树、一把读写锁、
一套装载与失效逻辑,是拿复杂度换一个不存在的性能问题。

**删掉的东西**:

| | 行数 | 为什么能删 |
|---|---|---|
| [tunein_tree.go](../../../radio-proxy/tunein_tree.go) 整个文件 | 44 | `tiTree` / `tiStore` / RWMutex 全部不再需要 |
| `loadTuneInCatalog` + `tiCatalogFile`/`tiCatalogStation`/`tiCatalogGenre` | ~60 | JSON 读取路径 |
| `tuneInCatalogPath`、`TUNEIN_CATALOG` 环境变量 | ~6 | 不再有目录文件 |
| `errTuneInWarming` | ~4 | 见决策 4 |
| `docker-compose.yml` 的 `./data:/data:ro` | 1 行 | 容器不再依赖目录文件 |

**顺带解决了「更新目录要重启」**:没有常驻状态,就没有需要重新加载的东西。
跑完 `-import-tunein`,下一次 scopeCache 过期(≤30 分钟)自然读到新数据。
不需要 SIGHUP、不需要管理端点、不需要轮询 —— **靠删代码解决,不是靠加机制。**

> 若要求「立即生效」而非「≤30 分钟」,再加一个清空 scopeCache 的管理端点即可,
> 但那是独立需求,本任务不做(YAGNI)。

**保留的东西**:`tiBuildFromCatalog` 及其全部清洗/去重逻辑**原样不动** ——
它属于装载侧(`-import-tunein`),不属于读取侧。阶段1 定的「抓取/转换/服务」三段式不变。

### 唯一的风险点

`Channels(allProvinceCode, "0")` 要取 TuneIn 全部 23215 行。今天是内存 map 合并,
改成 SELECT 后是一次两万行的扫描 + 行扫描解码。**这是本任务唯一需要实测的东西。**

- 预期:走 `(source, province_code)` 索引,几十毫秒量级,每范围每 30 分钟一次,可接受
- 若实测明显偏慢:退回「只为 `provinceCode=0` 这一个范围加内存缓存」,而不是恢复整棵树
- 实测数字必须记录进 prd.md,B/D 会依赖这个结论(云听 940 + 蜻蜓 1098 比这小一个量级)

## 决策 2:排序留在 Go,不交给 SQL

- **省份拼音序**:必须留在 Go(`collate.New(language.SimplifiedChinese)`)。
  Postgres 的中文排序依赖服务器 locale,不可控且与现有行为可能不一致。
- **电台按 Title 排序**:今天是 Go 的 `sort.Slice(... Title < Title)`,即**字节序**。
  改用 SQL `ORDER BY title` 会走数据库 collation,对非 ASCII 台名结果不同。
  → **取回后在 Go 里排**,保证与今天逐字节一致。这是「出参逐字段等价」验收的前提。

## 决策 3:分类联结表

```sql
CREATE TABLE IF NOT EXISTS radio_channel_categories (
    source      TEXT NOT NULL,
    content_id  TEXT NOT NULL,
    category_id TEXT NOT NULL,
    PRIMARY KEY (source, content_id, category_id)
);
CREATE INDEX IF NOT EXISTS idx_rcc_scope ON radio_channel_categories (source, category_id);
```

- **不含 `province_code`**:分类是电台的属性,与它挂在哪个地区无关。
  TuneIn 一个台跨两国时,`radio_channels` 有 2 行、联结表只有 1 组分类行。
- **无分类的台不写行**,而不是写 `'0'`。「全部」是查询语义(不加 JOIN 条件),不是一个分类值。
  → `Categories()` 输出的「全部」哨兵仍由 Go 侧拼(`tiCategoriesFrom` 的现有逻辑)。
- 查询形状:
  - 分类=全部 → 不 JOIN
  - 指定分类 → `JOIN radio_channel_categories USING (source, content_id) WHERE category_id = ?`

**TuneIn 的联结表本轮为空**:两份爬虫产物的 genre 字段都是空的(阶段1 已记录),
`genreOf` 全空。这是**正确结果,不要为了填满而伪造分类**。
所以 A 的联结表读取路径在 TuneIn 上验证不到「多分类」场景 —— 真正的验证在 B。

## 决策 4:`errTuneInWarming` 退休,空目录返回空列表

今天目录文件缺失时返回错误,注释理由是「否则 APP 会把『服务器没装目录』显示成
『这个国家没有电台』」。这个理由随文件一起消失:

- 文件可能缺失(部署没挂卷),**表不会缺失**(建表在启动路径上,`openActivationDB` 里执行)
- 表为空只有一种原因:没跑过装载。那是运维状态,不是请求错误
- 而且切到 DB 后三个来源的「空」语义应当一致 —— 云听/蜻蜓查不到也是返回空

→ 直接返回空列表。**代价**:装载没跑过时 APP 会显示「没有电台」而不是报错。
用**陈旧告警**(父任务契约 6)覆盖这个场景 —— 那本来就是它该管的事,
比在每个读取方法里塞一个哨兵判断更对。

## 决策 5:表结构变更沿用 db.go 的无迁移框架做法

[db.go](../../../radio-proxy/db.go) 的既有约定:「无迁移框架,`IF NOT EXISTS` 足够,
后续加字段用 `ALTER TABLE` 单独处理」。照办:

```sql
-- CREATE TABLE radio_channels 里去掉 category_id 列(新库直接就没有)
-- 老库靠这一句收敛,幂等:
ALTER TABLE radio_channels DROP COLUMN IF EXISTS category_id;
```

**索引要换名字,不能原名重建**:老索引 `idx_radio_channels_scope` 含 `category_id`,
`CREATE INDEX IF NOT EXISTS` 遇到同名索引不会改定义。用新名 + 删旧名,两句都幂等,
且启动时不会反复 DROP/CREATE:

```sql
DROP INDEX IF EXISTS idx_radio_channels_scope;
CREATE INDEX IF NOT EXISTS idx_radio_channels_prov ON radio_channels (source, province_code);
```

不写数据迁移脚本:目录是可重建的,改完重跑一次 `-import-tunein` 即可。

## 对 B / D 的产出

本任务落地后,`tuneInSource` 的三个读取方法就是**三来源统一 SQL 读取路径的雏形**。
B 灌完数据、D 切读取时,云听/蜻蜓复用同一组查询(只换 `source=?`),
差异只剩「装载频率」这一项,且它活在调度层,代码里不留分支(父任务契约 8)。
