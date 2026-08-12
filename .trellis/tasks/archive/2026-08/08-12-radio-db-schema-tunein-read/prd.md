# A:表结构调整(联结表) + TuneIn 读取切 DB

父任务:[08-11-radio-db-migration](../08-11-radio-db-migration/prd.md)
前置:无。阶段1 已把 TuneIn 数据装载入库,本任务改表结构并让服务端读它。

## Goal

两件事,一起做因为都要动 `radio_channels`:

1. **加 `radio_channel_categories` 联结表**,`radio_channels` 去掉 `category_id` 列。
   实测云听 125 台(13.3%)、蜻蜓 13 台(1.2%)属于多个分类,单值列会让它们从其余分类的
   浏览列表里静默消失——与阶段1 修掉的「跨地区丢台」是同一类 bug,换了个维度。
2. **服务端从 DB 读 TuneIn 目录**,退休 JSON 文件加载路径。

第 2 件同时兑现「TuneIn 是唯一有本地目录、且是个 7MB 单 JSON」这个架构不对称,
并产出三来源统一读取路径的雏形,供 B/D 复用。

## Requirements

### 表结构

```sql
CREATE TABLE IF NOT EXISTS radio_channel_categories (
    source      TEXT NOT NULL,
    content_id  TEXT NOT NULL,
    category_id TEXT NOT NULL,
    PRIMARY KEY (source, content_id, category_id)
);
CREATE INDEX IF NOT EXISTS idx_rcc_scope ON radio_channel_categories (source, category_id);
```

- `radio_channels` 删除 `category_id` 列;主键保持 `(source, content_id, province_code)`
  (TuneIn 125 台跨国挂载,契约 4)
- 联结表**不含 `province_code`**:分类归属是电台的属性,与它挂在哪个地区无关
- `idx_radio_channels_scope` 相应改为 `(source, province_code)`
- 无分类的台在联结表里**不写行**(而不是写 `'0'`)。「全部」是查询语义,不是一个分类值

### 装载侧

- `tunein_import.go` 同步改:整份替换时一并清空并重写本来源的联结表行
- TuneIn 当前 `genreOf` 全空(两份产物的 genre 字段都是空的,阶段1 已记录),
  所以联结表对 TuneIn 暂时是空的——**这是正确的**,不要为了填满而伪造分类

### 读取侧

- ~~新增从 DB 装配 `tiTree` 的路径~~ → **改为不留常驻内存树,三个读取方法直接查库**
  (design.md 决策 1:内存树的存在理由是「JSON 只能整份读入」,这个理由随 DB 一起消失)
- 省份按拼音排序的逻辑**保留在 Go**(现有 `collate`),不加 `sort_order` 列
- 分类清单仍由 `tiTopCategories` 固定顺序 + 库里实际出现的分类过滤得出
- 退休:`tiTree`/`tiStore` 常驻状态、`loadTuneInCatalog`、`tuneInCatalogPath`、
  `TUNEIN_CATALOG` 环境变量、`errTuneInWarming`、`docker-compose.yml` 的 `./data:/data:ro` 卷
  (JSON 解析结构体留在 `tunein_catalog.go`,但只服务装载命令——它是爬取管线的中间产物,
  不再是运行时依赖)
- ~~提供一个重新加载的入口~~ → **不需要**:没有常驻状态就没有要重载的东西。
  跑完装载,下一次 scopeCache 过期(≤30 分钟)自然读到新数据,零新增机制

## 非目标

- 不动云听/蜻蜓的读取路径(留给 D)
- 不动 subtitle(留给 C)
- 不做数据迁移脚本:目录是可重建的,改完表结构重跑一次 `-import-tunein` 即可

## Acceptance Criteria

- [x] 联结表建成;`radio_channels` 无 `category_id` 列;索引已调整
      (老库靠 `ALTER ... DROP COLUMN IF EXISTS` 收敛,由 `TestSchemaConvergesFromOldLayout` 覆盖 ——
      嵌入式测试库每次全新,不造老库就永远走不到这条路)
- [x] 重跑 `-import-tunein` 后行数与阶段1 一致(179 国 / 23215 台 / 1 分类),联结表 0 行且符合预期
- [x] 服务端**不读任何 JSON 文件**即可提供 TuneIn 的 provinces / categories / channels
- [x] 切换前后出参逐字段等价 —— 见下方实测
- [x] `TUNEIN_CATALOG` 与 `./data` 卷已移除
- [x] 更新目录后无需重启即可生效(靠删除常驻状态达成,不是靠加重载入口)
- [x] `gofmt` / `go vet` / 全量测试通过;真库用例覆盖联结表的整份替换、幂等与多分类筛选

## 实测(2026-08-12)

等价性比对方法:在切库前的提交上开 worktree,导出三个读取方法对真实 7MB 目录的出参作基线,
切库后逐字节 diff。

| 范围 | 结果 | 查询耗时 |
|---|---|---|
| `Provinces` | **逐字节一致**(9397 字节,含拼音序) | — |
| `Categories` | **逐字节一致**(36 字节) | — |
| `Channels(0,"0")` 全部地区 | 23215 台,**集合完全相同**,仅同名台内部次序不同 | **36ms** |
| `Channels(100436)` 美国 | **逐字节一致**(8326 台 / 1.9MB) | 16ms |
| `Channels(101309)` 英国 | **逐字节一致**(457 台) | 0.6ms |
| `Channels(100400)` | **逐字节一致**(13 台) | 0.3ms |

- **`Channels(0,"0")` 36ms** —— design.md 决策 1 的唯一风险点,结论是**不需要退路**。
  每范围每 30 分钟一次,完全可接受。B/D 的数据量小一个量级(云听 940 + 蜻蜓 1098),
  不必再单独评估。
- **全部地区的次序差异不是回归,是修掉了一个既有的不确定性**。旧实现从 Go map 合并
  (遍历顺序随机)后用不稳定的 `sort.Slice` 排,实测**连跑两次基线导出结果就不同**。
  新实现按 `(Title, ContentID)` 定序,可复现。逐位置 Title 完全一致、集合完全相同,
  差异只在 421 组 / 946 条同名台之间。这条对翻页有实际意义:次序不稳会让翻页漏台或重台。
- **净删除**:生产代码 159 删 / 188 增,其中 `tunein_tree.go` 整个文件(44 行)、
  JSON 加载路径、`errTuneInWarming`、一个环境变量、一个 compose 卷。增量主要是三个
  SQL 读取方法与它们的注释。
- **装载耗时 3.1s**(23215 行逐行 INSERT),仍在阶段1 记的量级内,不动。

### 遗留

- 联结表的**多分类**场景 TuneIn 数据覆盖不到(`genreOf` 全空 → 写 0 行,这是正确结果)。
  已用手工造的「一台属两个分类」真库用例覆盖(`TestChannelsFilterByCategoryUsesLinkTable`),
  不把这条路径的首次验证推到 B。
- `errTuneInWarming` 退休的代价:装载没跑过时 APP 显示「没有电台」而不是报错。
  由父任务契约 6 的陈旧告警覆盖,不在每个读取方法里塞哨兵。
