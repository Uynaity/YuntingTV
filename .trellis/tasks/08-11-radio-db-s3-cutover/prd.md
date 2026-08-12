# D:列表读取切 DB

父任务:[08-11-radio-db-migration](../08-11-radio-db-migration/prd.md)
前置:[B 装载入库](../08-11-radio-db-s2-crawlers/prd.md) + [C subtitle 改推算](../08-12-radio-db-subtitle-playbill/prd.md)

## Goal

把 `/v1/provinces`、`/v1/categories`、`/v1/channels`、`/v1/channels/by-ids`、`/v1/search`
五个接口的数据来源从「现场调上游」切到「查库」,三来源统一走一套读取代码。

**C 是硬前置。** 没有 C,查完 DB 还要现场调 `Source.Channels()` 取 subtitle,
而那正是要省掉的那次调用——DB 一次上游调用都省不掉,这一步就白做。

## Requirements

1. 开关 `CHANNEL_SOURCE_MODE`,支持**按来源**配置 `live|db`(默认全 `live`),
   同一份代码两条路径并存,切换不需重新部署
2. `db` 模式下 `Provinces` / `Categories` / `Channels` 改为 `SELECT ... WHERE source=?`,
   结果直接填充现有结构体,不加映射层
3. **分类筛选走联结表 JOIN**,不是 `radio_channels` 上的列(契约 3)。
   验收时重点看多分类的台(云听 125 个)在每个所属分类下都在
4. **`by-ids` 去重口径**:主键含 `province_code`,按 `content_id` 查会命中多行
   (TuneIn 125 台跨国)。需显式定义选哪一行,并写进代码注释
5. **`image` 读出后现场套 `publicBase` 前缀**(TuneIn 走 `/img?url=`),DB 里存的是原值(契约 2)
6. **省份排序、分类固定顺序留在 Go**:拼音序用现有 `collate`,分类用 `tiTopCategories` 的固定顺序
7. `respCache` 保留;`scopeCache` 的角色由 C 接管,本任务确认它不再承担列表数据
8. `Source` 接口、四个模型、`/v1/*` 出参格式一律不改
9. **顺带清理**:切完之后 `source_yunting.go` / `source_qingting.go` 的三个列表方法
   在请求路径上已无调用者,可移交装载侧。
   ⚠️ `ytSign`、两个 `get()`、Program / replay / stream 相关代码**一律不动**——节目单和回放还在用

## 非目标

- 不动 `/v1/programs`、`/v1/replay`、`/v1/stream`、`/proxy`、`/seg`
- 不做跨来源搜索(E)
- 不删签名与 `get()`(原阶段4 已因此取消)

## Acceptance Criteria

- [ ] `CHANNEL_SOURCE_MODE` 可按来源独立设置,默认全 `live`(部署后行为不变)
- [ ] 每个来源:同一请求在 `live` 与 `db` 下五个接口出参逐字段等价
- [ ] **分类浏览不丢台**:云听那 125 个多分类的台,在其全部所属分类下都能查到
      (这是 live 模式今天的行为,不能退化)
- [ ] `db` 模式下五个接口在请求路径上**零上游调用**(含 subtitle)
- [ ] `by-ids` 对跨地区多行的台返回单条,口径符合文档描述
- [ ] 单来源切 `db` 后可立即回切 `live`,无需重启依赖
- [ ] 三来源逐个灰度、各自稳定观察后全部切至 `db`
- [ ] 上游全部不可达时,五个接口仍能正常返回(subtitle 可为旧值/空)
- [ ] `/v1/programs`、`/v1/replay`、`/v1/stream`、`/proxy`、`/seg` 回归无变化
- [ ] APP 端无改动即可正常工作
