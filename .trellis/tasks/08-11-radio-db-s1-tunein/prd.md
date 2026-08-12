# 阶段1:建表 + TuneIn 爬虫改写为写 DB

父任务:[08-11-radio-db-migration](../08-11-radio-db-migration/prd.md)
来源文档:[radio-proxy/docs/crawler-db-interface.md](../../../radio-proxy/docs/crawler-db-interface.md)

## Goal

先落地三张表,再把已有的 TuneIn 离线爬虫从「产出 JSON 文件」改成「UPSERT 进 Postgres」。
选它打头阵是因为它本来就是「离线爬 → 落地 → 服务端只读」模式,风险最低,
真正目的是**验证表结构站不站得住**——表设计若在这一步被推翻,后三个阶段的规划要回炉。

## Requirements

1. 建表:`radio_provinces` / `radio_categories` / `radio_channels` + `idx_radio_channels_scope`,
   DDL 逐字段照抄来源文档「数据表结构」。建表方式沿用 [db.go](../../../radio-proxy/db.go) 现有做法。
2. 改造 [tools/tunein_crawl_v2.py](../../../radio-proxy/tools/tunein_crawl_v2.py):
   - 归一化后写库,`ON CONFLICT (source, content_id) DO UPDATE`,`source='tunein'`。
   - `current_track` 写入 `subtitle` 列(TuneIn 的 subtitle 本就不实时,这是刻意为之)。
   - `play_url_low` **留空**——起播仍走 `/v1/stream` 现场解析。
   - 分类映射沿用 `genreOf` 的单值语义([tunein_catalog.go](../../../radio-proxy/tunein_catalog.go)`:140`)。
   - 保留现有断点续爬 / 限流退避逻辑(上游按 IP 限配额)。
3. **下架检测**:每轮记录开始时间,爬完删除该 source 下 `updated_at < 本轮开始时间` 的行。
4. 本阶段**服务端不改**:`tunein_catalog.go` 仍读 JSON,线上行为零变化。
   若为便于比对需要同时产出 JSON,允许保留 JSON 输出。

## 非目标

- 不动服务端任何读取路径(留给阶段3)
- 不写云听/蜻蜓爬虫(留给阶段2)
- 不建 `radio_channel_categories` 联结表,除非本阶段爬取时**实测**发现一台多分类

## 实测结论(阶段完成时回填,供后续阶段用)

对 `data/tunein_catalog.json`(7MB,生产在用的那份)跑完整装载后:

- **179 国 / 23215 台 / 1 分类**,与内存目录逐项一致;含运行时配置的行 0;重复装载行数不变
- ✅ **表结构基本可用,但主键必须放宽**:实测 147 个 guide_id 挂在 2 个以上国家下
  (去重后 125 个)。来源文档的 `PRIMARY KEY (source, content_id)` 会让这些台从其中一国
  静默消失 → 已改为 `(source, content_id, province_code)`,见父任务契约 4
- ⚠️ **TuneIn 的分类维度当前是空的**:两份产物(v1/v2)的 `genres` 映射都为空、
  每条 station 的 `genre` 字段都是 `""`,所以 `genreOf` 全空、分类只有「全部」一档。
  这是**现有生产数据的既有状态,不是本次改动引入的** —— 服务端今天的
  `/v1/categories?source=tunein` 本来就只返回「全部」。
  影响:`idx_radio_channels_scope` 的 category 维度暂时无用;若要恢复分类筛选,
  需回头修爬虫的 genre 抓取,那是独立问题,不在本阶段范围
- ❌ **一台多分类**:无证据(`genreOf` 是单值映射,且当前全空),不建联结表
- ⏱ 全量装载耗时约 6 秒(逐行 INSERT,23215 行),日更一次完全够用

## Acceptance Criteria

- [ ] 三张表 + 索引在目标库中创建成功,字段名/类型与来源文档一致
- [ ] 跑通一轮完整 TuneIn 爬取,`radio_channels` 中 `source='tunein'` 行数与现有 JSON 产物条目数一致
- [ ] 抽样 ≥20 个 `content_id`,title / image / province_code / category_id / subtitle 与 JSON 产物逐字段一致
- [ ] 重复跑第二轮:行数稳定(UPSERT 不产生重复),`updated_at` 全部刷新
- [ ] 人为从上游结果中剔除一个电台后重跑,该行被下架检测删除
- [ ] 服务端行为无任何变化(现有测试全绿)
- [ ] 记录结论:当前表结构是否满足 TuneIn 全部字段;若不满足,列出需调整项并回父任务确认
