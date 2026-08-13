# 第4步:跨来源搜索

> 父任务:[08-12-radio-catalog-db](../08-12-radio-catalog-db/prd.md)
> 前置:第 1–3 步做完(三个来源的完整目录都在库里之后才谈得上「搜遍」)
> 事实来源:[radio-proxy/docs/radio-catalog-db.md](../../../radio-proxy/docs/radio-catalog-db.md) 第六节第 4 步

## Goal

一次搜遍三个来源的完整目录,不再受当前地区 / 当前来源限制。

## Requirements

- 跨区域 + 跨来源搜索:一次查询覆盖云听 943 + 蜻蜓 1098 + TuneIn 23215 台。

## Constraints

- 父任务 Constraints 适用(尤其:服务端只读,不写这四张表)。
- **现有拼音搜索(`syllablesOf` / `initialsIndex` / `fullIndex`)在 Go 里,不能下推到 SQL。**
  这是本步的主要设计约束。
- 这是**新功能**,放在最后。前三步不依赖它。

## Acceptance Criteria

- [ ] 一次搜索可命中三个来源的电台
- [ ] 拼音搜索(全拼 / 首字母)行为与现状一致
- [ ] 搜索响应耗时可接受(参考:全量 23215 行取回 36ms)

## Notes

具体设计等前三步落地后再定——那时才知道库里的真实形状和读取路径长什么样。
本 PRD 只锁定范围,**不预先设计**。
