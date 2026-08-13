# 第1步:蜻蜓打通全链路

> 父任务:[08-12-radio-catalog-db](../08-12-radio-catalog-db/prd.md)(全局规则和边界在那里)
> 事实来源:[radio-proxy/docs/radio-catalog-db.md](../../../radio-proxy/docs/radio-catalog-db.md) 第四、六节

## Goal

用蜻蜓验证整条链路的形状:**建表 → 装载 → subtitle 改由节目单推算 → 定时 + 告警 → 读取切库**。
做完这一步,两个原始目标(入库 + subtitle 换来源)在这一个来源上都兑现,且随时能只把它切回 live。

选蜻蜓打头是因为它最省事:4 次请求、无签名、播放地址由 id 完全推得。

## Requirements

1. **建表**:一次建成文档第三节的四张表(含 `province_code` 进主键、分类独立联结表、**无 `play_url_low`**)。
2. **装载**:`v4/channels` 翻 4 页(每页 300)取全量 1098 台,写入五项:台名、图标、分类、地区、来源。
3. **subtitle 改由节目单推算**:`v3/channels/{id}/playbills` 取覆盖 `now()` 的那档的 `title`,
   替换现在的 `current_program.title`([radio-proxy/source_qingting.go:125](../../../radio-proxy/source_qingting.go))。
   ≈1098 次/天。
4. **定时 + 告警**:目录日更;抓取失败 / 返空要能被发现,不能只是静默不更新。
5. **读取切库**:蜻蜓的 `Provinces` / `Categories` / `Channels` 从库里读。
   播放地址读取时按 content_id 算(`https://ls.qingting.fm/live/{id}/64k.m3u8`),不查库。

⚠️ **3 和 5 必须在本任务内一起做完。** 先切读取、subtitle 还没换源,APP 上「正在播放」就空了。

## Constraints

- 父任务 Constraints 全部适用(整份替换、空目录拒写、任一子请求失败即整轮放弃、排序留在 Go……)。
- 蜻蜓实测事实(**不用重测**):
  - `category_ids` 原生是数组;13 台(1.2%)属于多个分类 → 必须走联结表。
  - 真实地区字段是 `province_id`(`region_id` 全 null),查询参数 `region_id` 与之同一套 ID。
  - **1 台(`province_id=471`「50首经典古典音乐」)没有内嵌 `province` 对象**,`v4/regions` 也不含 471。
    装载必须容忍这个缺字段——上一轮的探测脚本就是在这里崩的。
  - 「全部」哨兵(地区 `0`、分类 `0`)是网关合成的,上游没有。
  - 上游会间歇性返回空,必须重试。
  - `pagesize=300` 的截断在当前产品形态下够不着(最大地区 89 台)。
- 播放地址**不存**:能算出来的不存,这是既定规则。

## Acceptance Criteria

- [ ] 四张表建成,结构与文档第三节逐字一致,无 `play_url_low`
- [ ] 装载跑通真上游:1098 台入库,分类联结表覆盖那 13 台多分类电台
- [ ] `province_id=471` 那台能正常入库,装载不崩
- [ ] 单事务整份替换;抓取失败或结果为空时**不写库**(有测试覆盖失败语义)
- [ ] subtitle 来自 playbills 推算,与 `current_program` / `nowplaying` 结果一致
- [ ] 读取切库后 `Provinces` / `Categories` / 具体地区(及地区×分类)的 `Channels`
      与切库前**逐字节一致**——包括**顺序**(上游是策展序,见 [design.md](./design.md) 第 0 节)
- [ ] 唯一允许的差异是「全部地区+全部分类」范围由 300 台变 1098 台(今天被 `pagesize=300` 截断,
      该入口 APP 已隐藏),及其连带的 `/v1/channels/by-ids` 命中率上升
- [ ] APP 上蜻蜓电台的「正在播放」不为空,播放正常
- [ ] 定时任务在位,失败可被发现

## Notes

参考实现(分支 `backup/radio-db-2026-08-12`,按需取用,别整条合):

- `catalog_fetch.go` —— 重试 + 「任一子请求失败即整轮放弃」,与表结构无关,**拿来即用**
- `catalog_write.go` —— 值得,**去掉 `play_url_low` 那一列**
- `qingting_import.go` —— 逻辑简单,值得;同样去掉 `play_url_low`
- `catalog_import_test.go` —— 值得,尤其 `fetchAll` 的失败语义用例
- 提交 `4250a5a` 里的 SQL 查询形状(联结表 JOIN、哨兵语义、排序留在 Go)本步可参考

等价性验证做法(上一轮验证过):在切库前的提交上开 worktree 导出基线,切库后逐字节 diff。
