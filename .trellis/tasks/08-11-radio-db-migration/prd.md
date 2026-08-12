# 电台数据库化:爬虫写库 + 服务端只读

来源文档:[radio-proxy/docs/crawler-db-interface.md](../../../radio-proxy/docs/crawler-db-interface.md)(本 PRD 是它的任务化拆解,契约以文档为准)

## Goal

把云听 / 蜻蜓 / TuneIn 三个来源的**身份字段**(Province / Category / Channel 的
title、image、provinceCode、categoryId、playUrlLow)从「每次请求现场调上游」改成
「离线爬虫写 Postgres,服务端只 SELECT」。服务端里三套各自的签名/Header/JSON 清洗
逻辑随之退休。

`/v1/*` 出参格式、`Source` 接口、四个模型结构体**全部不变**,APP 端零改动。

## 范围

### 迁移
- `radio_provinces` / `radio_categories` / `radio_channels` 三张表(SQL 见来源文档「数据表结构」)
- 三个来源各自的离线爬虫(唯一写者)
- `/v1/provinces`、`/v1/categories`、`/v1/channels`、`/v1/channels/by-ids`、`/v1/search`
  五个接口的数据来源

### 不迁移(明确排除)
- **节目单 Program**:按需调用量小,预抓「全部电台 × 每天」性价比低。YAGNI。
- **TuneIn `Tune.ashx` 起播解析**:地址带时效签名,存不成静态值,继续走 `/v1/stream` / `/proxy`。
- **`subtitle`(当前节目)**:每 30 分钟一变,不进爬虫。云听/蜻蜓由服务端查完 DB 后
  现场调一次 `Source.Channels()` 按 `contentId` 合并(复用现有 `scopeCache`);
  TuneIn 的 `current_track` 例外,本就不实时,跟身份字段一起进 DB。
- `gateway.go` 的 `scopeCache` / `respCache` 机制不动,只是含义收窄成「subtitle 合并中间结果」。
- 不引入新存储依赖,沿用 [db.go](../../../radio-proxy/db.go) 已有 Postgres。

## 跨子任务约束(契约)

1. **爬虫是唯一写者,服务端只读**。服务端代码里不得出现对这三张表的 INSERT/UPDATE/DELETE。
2. **表结构以来源文档为准**,字段与 [model.go](../../../radio-proxy/model.go) 逐字段对齐,
   SELECT 出来直接塞进现有结构体,不加映射层。
3. **一台多分类**:`category_id` 先做单值列。只有当爬虫拿到证据(某来源同一电台出现在
   多个分类下)才拆 `radio_channel_categories` 联结表,现在不建。
4. **一台多地区**(阶段1 实测后新增,推翻了来源文档的主键设计):
   `radio_channels` 主键是 `(source, content_id, province_code)`,**不是** `(source, content_id)`。
   TuneIn 目录里实测有 147 个 guide_id 挂在两个以上国家下(去重后 125 个),
   窄主键会让后写的国家覆盖先写的,那些台从其中一国的列表里静默消失。
   现有内存目录本来就是按地区各存一份,宽主键才是行为不变的那个。
   → 阶段3 注意:`/v1/channels/by-ids` 按 `content_id` 查会命中多行,需要去重。
5. **整份替换**(阶段1 实测后修正):每轮在单事务里 `DELETE FROM ... WHERE source=?`
   再全量 INSERT,**不用** UPSERT + 删 `updated_at < 本轮开始时间` 的旧行。
   后者拿墙上时钟当轮次标记:`updated_at` 是 unix 秒,同一秒内跑两次装载就一行也删不掉。
   单事务保证读者要么看到上一轮完整数据、要么看到这一轮,不会看到空表(Postgres MVCC)。
6. **爬取频率**:身份字段每天一次即可,不对齐 `channelsTTL` 的 30 分钟。
7. **增量切流**:先灌数据比对,再用 `CHANNEL_SOURCE_MODE=live|db` 按来源灰度,不一把梭。

## 任务树

| 子任务 | 交付物 | 独立验收方式 |
|---|---|---|
| [阶段1](../08-11-radio-db-s1-tunein/prd.md) 建表 + TuneIn 装载入库 | 三张表 DDL;`-import-tunein` 命令把爬虫产物整份替换进库(爬虫本身不动,理由见其 design.md) | 装载一轮后,库里行数/抽样字段与现有内存目录一致 |
| [阶段2](../08-11-radio-db-s2-crawlers/prd.md) 云听/蜻蜓爬虫 | 两个来源各一版爬虫,签名/清洗规则从 `source_*.go` **平移**过来 | 服务端不切流量;DB 数据与现有 live 接口返回人工比对若干轮一致 |
| [阶段3](../08-11-radio-db-s3-cutover/prd.md) 灰度切库 | `CHANNEL_SOURCE_MODE` 开关 + DB 读取路径 + subtitle 合并 | 切单一来源到 db,五个接口出参与 live 模式逐字段等价 |
| [阶段4](../08-11-radio-db-s4-cleanup/prd.md) 删旧路径 | 删 `source_*.go` 现场调用与签名逻辑、删 `tunein_catalog.go` | 全量测试通过,三来源统一走一套 DB 读取代码 |

顺序强依赖:1 → 2 → 3 → 4。阶段 1 的表设计若被推翻,后续三个子任务的 PRD 需回炉。

## Acceptance Criteria(父任务,四个子任务全部完成后验收)

- [ ] 三个来源的 Province / Category / Channel 身份字段全部来自 DB,服务端无现场上游列表调用
- [ ] `/v1/provinces`、`/v1/categories`、`/v1/channels`、`/v1/channels/by-ids`、`/v1/search`
      出参与迁移前逐字段等价(subtitle 除外,行为见下条)
- [ ] 云听/蜻蜓 subtitle 仍为实时值(经 `scopeCache` 合并);TuneIn subtitle 取自 DB
- [ ] `/v1/programs`、`/v1/replay`、`/v1/stream`、`/proxy`、`/seg` 行为零变化
- [ ] APP 端无任何改动即可正常工作
- [ ] `tunein_catalog.go` 的 JSON 文件加载路径已删除
- [ ] 未引入新的存储/运行时依赖

## Notes

- 本任务为**父任务**,不直接承载实现工作;实现在四个子任务里推进。
- 每个子任务需各自完成 Phase 1 规划(复杂子任务补 `design.md` / `implement.md`)后才能 `task.py start`。
