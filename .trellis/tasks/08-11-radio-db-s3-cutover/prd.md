# 阶段3:CHANNEL_SOURCE_MODE 开关按来源灰度切库

父任务:[08-11-radio-db-migration](../08-11-radio-db-migration/prd.md)
前置:[阶段2](../08-11-radio-db-s2-crawlers/prd.md) 比对通过

## Goal

服务端读取路径从「现场调上游」切到「查库」,用环境变量开关按来源灰度,随时可回退。
这是整个方案的收益兑现点,也是唯一有线上风险的阶段——所以旧代码这一阶段**一行不删**。

## Requirements

1. 新增开关 `CHANNEL_SOURCE_MODE`,支持按来源配置 `live|db`(默认全 `live`)。
   同一份代码里两条路径并存,切换不需要重新部署。
2. `db` 模式下,`Provinces` / `Categories` / `Channels` 三个方法改为
   `SELECT ... WHERE source=?`,SELECT 结果直接填充现有结构体,不加映射层。
3. **subtitle 合并**(本阶段最容易做错的地方):
   - `/v1/channels`、`/v1/search`、`/v1/channels/by-ids` 三个返回 `Channel` 的接口,
     云听/蜻蜓在查完 DB 拿到身份字段后,**仍要现场调一次 `Source.Channels()`** 取 subtitle,
     按 `contentId` 合并进结果。
   - 这一步复用现有 `scopeCache`([gateway.go](../../../radio-proxy/gateway.go)`:194`),不是新缓存;
     它的含义从「缓存整条 live 数据」收窄成「缓存 subtitle 合并用的中间结果」,机制不动。
   - TuneIn 不做这一步,直接用 DB 里的 `subtitle` 列。
4. `respCache` 不动。
5. `Source` 接口、四个模型、`/v1/*` 出参格式一律不改。

## 非目标

- 不删任何旧代码(留给阶段4)
- 不动 `/v1/programs`、`/v1/replay`、`/v1/stream`、`/proxy`、`/seg`
- `tunein_catalog.go` 本阶段可先切 DB 读取,但文件不删

## Acceptance Criteria

- [ ] `CHANNEL_SOURCE_MODE` 支持按来源独立设置,默认值保持全 `live`(部署后行为不变)
- [ ] 对每个来源:同一请求在 `live` 与 `db` 两种模式下,五个接口出参逐字段等价(subtitle 除外)
- [ ] 云听/蜻蜓在 `db` 模式下 subtitle 仍是实时值,且 `scopeCache` 命中率与切换前同量级
      (即没有退化成每请求穿透上游)
- [ ] TuneIn 在 `db` 模式下 subtitle 取自 DB 列,无额外上游调用
- [ ] 单来源切 `db` 后回切 `live`,行为立即恢复,无需重启依赖
- [ ] 三个来源逐个灰度、各自稳定观察后全部切至 `db`
- [ ] `/v1/programs`、`/v1/replay`、`/v1/stream`、`/proxy`、`/seg` 回归测试无变化
- [ ] APP 端无改动即可正常工作
