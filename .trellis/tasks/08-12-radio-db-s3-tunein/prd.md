# 第3步:TuneIn 统一形态

> 父任务:[08-12-radio-catalog-db](../08-12-radio-catalog-db/prd.md)
> 事实来源:[radio-proxy/docs/radio-catalog-db.md](../../../radio-proxy/docs/radio-catalog-db.md) 第四、六节

## Goal

TuneIn 已经有本地目录,是三个里最不急的。收益是**统一形态**(与另外两个源同表同读取路径)
+ **每周自动爬**,不再手工维护 JSON 文件。

## Requirements

1. **爬虫产物入库**:23215 台,写入五项。带 `TUNEIN_PARTNER_ID` 可在单个 IP 下一次爬完
   → 不需要 IP 池、不需要增量爬。
2. **读取切库**,退休内存目录树。
3. **每周自动爬** + 失败告警。
4. **抓取 / 转换分开**:爬虫只负责抓,转换是纯函数。把转换焊进爬虫 =
   让「改一条清洗规则」去付「重爬一遍全球」的钱。

TuneIn 没有节目单,subtitle 一直来自爬虫产物里的 `current_track`,**不受 subtitle 前置约束**。

## Constraints

- 父任务 Constraints 全部适用。
- TuneIn 实测事实(**不用重测**):
  - 目录是人工维护的地点树,层级不定深,遍历要 3000+ 次 Browse,上游按 IP 计配额。
  - **147 个 guide_id 挂在两个以上国家下(去重后 125 个)** → 这正是 `province_code` 进主键的理由;
    只用 `(source, content_id)` 会让后写的国家覆盖先写的。
  - **两份爬虫产物的 genre 字段都是空的 → 联结表对 TuneIn 写 0 行。这是正确结果,不要伪造分类。**
  - 切库后 `Channels(全部地区)` 取 23215 行耗时 36ms,单地区(8326 台)16ms,
    每范围每 30 分钟才查一次 → **不需要为它加内存缓存**。
- 播放地址仍是起播时调 `Tune.ashx`,现状不变,不进库。

## Acceptance Criteria

- [ ] 23215 台入库,那 125 个跨国台在**每一个**所属国家的列表里都在
- [ ] 分类联结表对 TuneIn 是 0 行(不伪造)
- [ ] 读取切库:`Provinces` / `Categories` / 具体地区 `Channels` 与切库前逐字节一致;
      全部地区的 23215 台集合完全相同(**同名台内部次序不同不是回归**——
      旧实现用不稳定的 `sort.Slice`,连跑两次基线都不同;按 `(Title, ContentID)` 定序后才可复现)
- [ ] 内存目录树删除,不再手工维护 JSON
- [ ] 每周自动爬在位,失败可被发现
- [ ] 起播路径不变,`Tune.ashx` 正常

## Notes

参考实现:`backup/radio-db-2026-08-12` 的提交 **`4250a5a` 就是这一步**,可直接参考
(它捆了「删掉内存目录树」,本步正好需要)。
