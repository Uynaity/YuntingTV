# 阶段2:云听/蜻蜓爬虫灌数据(不切流量)

父任务:[08-11-radio-db-migration](../08-11-radio-db-migration/prd.md)
前置:[阶段1](../08-11-radio-db-s1-tunein/prd.md) 完成且表结构已验证

## Goal

给云听、蜻蜓各写一版离线爬虫,把 Province / Category / Channel 身份字段灌进阶段1 建好的三张表。
服务端**不切流量**,本阶段唯一目的是攒出可比对的数据,证明「DB 里的值 == 现在 live 接口返回的值」。

## Requirements

1. 每个来源一个爬虫(一个脚本带 `--source` 参数也可),调上游列表接口拿
   Provinces / Categories / Channels,归一化后 UPSERT。
2. **签名、Header、字段清洗规则从服务端平移**,不是重新设计:
   - 云听:MD5 签名等,见 [source_yunting.go](../../../radio-proxy/source_yunting.go)(含 `ytStripLivePrefix`)
   - 蜻蜓:直播地址拼接规则,见 [source_qingting.go](../../../radio-proxy/source_qingting.go)
3. `subtitle` 列这两个来源**留空**——它每 30 分钟一变,由服务端现场合并(阶段3 处理)。
4. `play_url_low` 写入稳定直链(这两个来源有,与 TuneIn 不同)。
5. 无归属地区写 `province_code=0`,未分类写 `category_id='0'`,对齐现有「全部」语义。
6. 下架检测同阶段1:删除 `updated_at < 本轮开始时间` 的行。
7. 这两个来源上游无 IP 配额限制,**不做断点续爬**,保持简单。
8. 更新频率定为每天一次;Province / Category 合并在同一轮跑,不单独调度。

## 非目标

- 不改服务端任何代码(服务端仍走 live)
- 不做 subtitle 爬取
- 不做 Program(节目单)爬取

## Acceptance Criteria

- [ ] 两个爬虫各跑通一轮,`radio_*` 表中 `source='yunting'` / `source='qingting'` 数据完整
- [ ] Province / Category 行数与对应 live 接口返回条目数一致
- [ ] Channel 抽样 ≥30 个 `content_id`:title / image / province_code / category_id / play_url_low
      与 live 接口返回逐字段一致(subtitle 不比)
- [ ] 连续跑 ≥3 轮(可跨天)人工比对无漂移,差异项有记录与解释
- [ ] 记录一台多分类的实测结论:若某来源同一电台出现在多分类下,在此提出联结表方案并回父任务确认
- [ ] 线上服务端行为零变化,现有测试全绿
