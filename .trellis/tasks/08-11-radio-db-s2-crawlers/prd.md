# B:云听/蜻蜓装载入库(不切流量)

父任务:[08-11-radio-db-migration](../08-11-radio-db-migration/prd.md)
前置:[A 表结构调整](../08-12-radio-db-schema-tunein-read/prd.md)——需要联结表就位

## Goal

把云听、蜻蜓的身份字段灌进统一目录表,三来源目录齐备。
服务端**不切流量**(读取路径留给 D),本阶段目的是备料 + 验证数据正确。

> 上一版 PRD 里的三个「开工前必答问题」已全部实测回答,见下。不再有未决前提。

## 实测前提(2026-08-12 复测,数字以本节为准)

| | 台数 | 装载成本 | 多分类 | 多地区 |
|---|---|---|---|---|
| 云听 | **943**(见下方并集) | **320 次请求 / 并发 10 约 2.5 分钟** | 125 台(108 个 2 类、17 个 3 类) | 0 |
| 蜻蜓 | **1098** | **4 次请求**(page 1–4,pagesize=300) | 13 台(1.2%) | 0 |

- **云听的原始响应里没有分类字段**。单台字段实测只有
  `contentId / title / subtitle / image / playUrlLow / mp3PlayUrlLow / mp3PlayUrlHigh / playUrlMulti`。
  → 分类归属**只能靠「地区 × 分类」枚举**:32 × 9 = 288 次,再加 32 次 `categoryId=0` 取全量。
  实测 288 次并发 10 耗时 **132s,0 失败**。这是硬约束,不是实现选择。
- **云听的「全量」和「分类枚举」对不上**:`categoryId=0` 枚举出 **937** 台,
  分类枚举出 **942** 台 —— 有 **5 台只出现在某个分类下、不在「全部」里**,另有 **1 台**反过来
  只在「全部」里、不属于任何分类。**目录取并集(943)**,这正是「完整目录」这个目标的实例。
- **云听必须逐地区枚举**:`(0,0)` 只返回 19 条全国台,不是全量
- **云听签名是硬门槛**:无签名一律 `code 1001 参数不合法`
- **上游会间歇性返回空**:实测多次撞到,装载必须带重试退避(这本身就是「数据可控」的论据)
- ✅ **蜻蜓 `province_id` 与查询参数 `region_id` 是同一套 ID**(原「须确认」项已查实):
  `v4/regions` 返回 32 个 id,电台的 `province_id` 有 33 个取值,**多出来的只有 `471`**,
  且 `region_id=471` 过滤确实能查到它。
- **蜻蜓有 1 台(`province_id=471`「50首经典古典音乐」)没有 `province` 对象**,
  `v4/regions` 也不含 471 → **它在 APP 里没有任何地区入口可以到达**。装载必须容忍这个缺字段
  (探测脚本就是在这里 `KeyError` 崩的),并照写 `province_code=471`。
- **蜻蜓单台字段很全**,一次请求就够:`id / title / cover / province_id / city_id /
  category_ids / categories / current_program / description / audience_count / clout`。
- 蜻蜓最大地区 **89 台**,`pagesize=300` 的截断在当前产品形态下够不着(契约 7 已查实)。
- 蜻蜓另有 `city_id`(261 个取值),比地区细。本任务不用,但数据在,以后想做按城市浏览可用

### ⚠️ 云听的 `play_url_low` 是**签名 URL**,不是稳定直链

```
http://ytcast.radio.cn/77/radios/11364/index_11364.m3u8?type=1&key=<md5>&time=<hex unix>
```

实测:**篡改 `key` → 403,去掉 `key`/`time` → 403**,签名被强制校验;`time` 就是签发时刻。
路径部分由 contentId 推得(`1`+contentId / mp3 是 `1`、`2`+contentId),但 `key` 推不出来。

→ 这一列的值**带时效**,存进 DB 等于赌它的 TTL。父任务契约 5 原写「云听的推不出来必须存」,
是在不知道它带签名的前提下写的。**B 照存,但把 TTL 实测列为交付物**;
若实测 TTL 短于装载周期,由 D 决定加一个只刷这一列的快作业(32 次请求),B 不做(YAGNI)。

对照:**蜻蜓的 `https://ls.qingting.fm/live/{id}/64k.m3u8` 无签名**,实测直连 200,
由 id 完全推得 —— 这一列对蜻蜓零风险。

## Requirements

1. **抓取与装载分两段**(阶段1 已确立):抓取只产出原始响应,装载复用 `source_*.go`
   里现成的解析与清洗代码。装载建议 Go,理由同阶段1——复用已测代码,不在别处重写副本
2. **签名/Header 属于抓取**:云听沿用 `ytSign`。两来源上游无 IP 配额,不做断点续爬,但**要重试**
3. **注意:云听几乎没有转换逻辑可搬**。上游 JSON 直接反序列化成 `[]Province`/`[]Category`/`[]Channel`,
   唯一的清洗 `ytStripLivePrefix` 只作用于 **subtitle**——本任务不入库的那个字段。
   不要为了「平移清洗规则」去搬一个用不到的函数
4. **蜻蜓有真正的构造逻辑**,装载时复用而非重写:
   - 「全部」哨兵是**网关合成的**,上游没有:Provinces 补 `{全部, 0}`、Categories 补 `{0, 全部}`
     ([source_qingting.go:71,:90](../../../radio-proxy/source_qingting.go))。漏了 APP 的「全部」入口会消失
   - 分类名 `TrimSuffix(title, "台")`
   - `play_url_low` 按 id 拼:`https://ls.qingting.fm/live/{id}/64k.m3u8`
5. **`play_url_low` 必须填**。`/v1/stream` 对这两个来源都返回空 url,意思是「用你手上
   `Channel.playUrlLow`」;列表改从 DB 出之后,这一列为空 = **两个来源全部放不出声**。
   - 蜻蜓:装载时按 id 算出来填入(无签名,零风险)
   - 云听:照抓到的原样存,并**实测它的签名 TTL**(见上方 ⚠️)。
     `updated_at` 即签发时刻,D 依据实测 TTL 决定是否需要单独的快刷新
6. **分类写联结表**,一台多分类的全部分类都要写(契约 3)
7. `subtitle` 一律留空——即便上游响应里带了(蜻蜓 `current_program`、云听 `subtitle`)也不写入。
   它由 [C](../08-12-radio-db-subtitle-playbill/prd.md) 从节目单推算
8. **`image` 存上游原值**,不做包装(契约 2)
9. **整份替换 + 空结果拒绝写入**(契约 5)
10. 装载频率日级;Province / Category 同轮跑

## 非目标

- 不改服务端读取路径(仍走 live)
- 不做 subtitle、节目单抓取
- 不动 TuneIn

## Acceptance Criteria

- [ ] 两来源各跑通一轮,`source='yunting'` 约 **943 台**、`'qingting'` **1098 台**
      (与实测基线一致;偏差需解释)
- [ ] 云听目录取**并集**:只在分类下出现的 5 台、只在「全部」里的 1 台,都在库里
- [ ] Province / Category 行数与 live 接口一致,**含合成的「全部」哨兵行**
- [ ] 联结表中云听多分类台 125 个(108 个 2 类 + 17 个 3 类)、蜻蜓 13 个,
      且这些台在**每个**所属分类下都能查到
- [ ] Channel 抽样 ≥30 个:title / image / province_code / play_url_low 与 live 逐字段一致
      (subtitle 不比,按设计留空)
- [ ] 比对按 `(content_id, province_code)` 对齐
- [x] `province_id` 与查询参数 `region_id` 一致性已验证并记录(见实测前提)
- [ ] 蜻蜓那台缺 `province` 对象的(id 在 471 下)装载不崩,且行已写入
- [ ] 重复装载幂等;**任一子请求彻底失败即整轮放弃、不写库**,旧数据完好
- [ ] **云听 `play_url_low` 的签名 TTL 已实测并记录**(观测到失效,或记录「≥N 小时仍有效」)
- [ ] 连续跑 ≥3 轮(可跨天)无漂移,差异项有记录
- [ ] 线上服务端行为零变化,全量测试通过
