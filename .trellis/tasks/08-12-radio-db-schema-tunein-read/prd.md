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

- 新增从 DB 装配 `tiTree` 的路径,替换 `loadTuneInCatalog` 的 JSON 读取
- 省份按拼音排序的逻辑**保留在 Go**(现有 `collate`),不加 `sort_order` 列
- 分类清单仍由 `tiTopCategories` 固定顺序 + 联结表里实际出现的分类过滤得出
- 退休:`tiCatalogFile` / `tiCatalogStation` / `tiCatalogGenre` 三个结构体、
  `tuneInCatalogPath`、`TUNEIN_CATALOG` 环境变量、`docker-compose.yml` 的 `./data:/data:ro` 卷
  (JSON 解析代码移交 `tunein_import.go` 独有——它是爬取管线的中间产物,不再是运行时依赖)
- 提供一个重新加载的入口,使「更新目录」不再需要重启

## 非目标

- 不动云听/蜻蜓的读取路径(留给 D)
- 不动 subtitle(留给 C)
- 不做数据迁移脚本:目录是可重建的,改完表结构重跑一次 `-import-tunein` 即可

## Acceptance Criteria

- [ ] 联结表建成;`radio_channels` 无 `category_id` 列;索引已调整
- [ ] 重跑 `-import-tunein` 后行数与阶段1 一致(179 国 / 23215 台),联结表为空且符合预期
- [ ] 服务端**不读任何 JSON 文件**即可提供 TuneIn 的 provinces / categories / channels
- [ ] 切换前后 `/v1/provinces`、`/v1/categories`、`/v1/channels?source=tunein` 出参逐字段等价
      (含省份拼音序、分类固定顺序、125 个跨国台各自出现在两个地区)
- [ ] `TUNEIN_CATALOG` 与 `./data` 卷已移除,容器不挂载目录文件也能正常服务
- [ ] 更新目录后无需重启即可生效
- [ ] `go vet` + 全量测试通过;真库用例覆盖联结表的整份替换与幂等
