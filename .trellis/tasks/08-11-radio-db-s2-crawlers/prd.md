# 阶段2:云听/蜻蜓抓取 + 装载灌数据(不切流量)

父任务:[08-11-radio-db-migration](../08-11-radio-db-migration/prd.md)
前置:[阶段1](../08-11-radio-db-s1-tunein/prd.md) 已完成,表结构经实测验证并修正

## Goal

给云听、蜻蜓各做一套离线抓取 + 装载,把 Province / Category / Channel 身份字段灌进三张表。
服务端**不切流量**,本阶段唯一目的是攒出可比对的数据,证明「DB 里的值 == 现在 live 接口返回的值」。

## 开工前必须先回答的三个问题

这三个问题的答案决定本阶段怎么做,**答不出来就不要开始写抓取脚本**。它们都源自
「云听/蜻蜓的电台列表是按 scope 查询出来的,不像 TuneIn 那样是一棵有固有归属的树」。

### Q1:能不能一次拉全量?

两个来源的 `Channels` 都是 **scope 查询**,不是全量导出:

- 云听 [source_yunting.go](../../../radio-proxy/source_yunting.go)`:107`:
  `web/appBroadcast/list?categoryId=&provinceCode=`
- 蜻蜓 [source_qingting.go](../../../radio-proxy/source_qingting.go)`:100`:
  `v4/channels?region_id=&category_id=`

若 `(provinceCode=0, categoryId=0)` 就返回全部电台,抓取是一次调用;若不返回,就得
**枚举 地区 × 分类** 的笛卡尔积(云听约 34 省 × N 分类),量级和失败面完全不同。

→ 先各发一次请求确认,把结论写进本文件再继续。

### Q2:一个电台会不会出现在多个分类下?

**这是本阶段最可能踩的坑,必须在灌数据之前查清楚。**

`radio_channels` 的主键是 `(source, content_id, province_code)` —— 阶段1 为「一台多地区」
放宽过一次,但 **`category_id` 不在主键里**。所以如果云听/蜻蜓的同一个电台挂在两个分类下,
装载时后写的会覆盖先写的,**那些台从其中一个分类的列表里静默消失** —— 和阶段1 修掉的
是同一类 bug,只是换了一个维度。

阶段1 只证明了 TuneIn 没有这个情况(`genreOf` 是单值映射,且当前全空),
**不能外推到这两个来源**。

→ 枚举若干分类各拉一次,看 `contentId` 有没有跨分类重复。有的话,在此提出方案
(放宽主键含 `category_id`,或建 `radio_channel_categories` 联结表)并**回父任务确认**后
再动手 —— 这会改父任务契约 3 和 4。

### Q3:蜻蜓分页要抓到第几页?

蜻蜓今天硬编码 `page=1&pagesize=300`([source_qingting.go](../../../radio-proxy/source_qingting.go)`:102`),
即**服务端每个 scope 只返回前 300 个台**。抓取如果老老实实翻完所有页,库里会**比 live 接口多**,
下面的「逐字段一致」验收就永远对不上。

→ 定一个口径并写进本文件:
- **建议**:抓取翻全量(库是数据源,不该继承一个分页边界的偶然限制),比对时按
  「live 返回的那 300 条是否都能在 DB 里找到且逐字段一致」验收,而不是比总数。
- 若选择只抓第一页对齐现状,要说明理由 —— 但那等于把临时限制固化进数据。

## Requirements

1. **抓取与装载分成两段**(阶段1 已确立,见其 [design.md](../08-11-radio-db-s1-tunein/design.md) 决策1):
   抓取只产出原始响应,不做清洗;装载单独一步复用 `source_*.go` 里现成的解析与清洗代码。
   装载建议用 Go,理由同阶段1 —— 复用已有代码而非在 Python 里重写一份没测试的副本。
2. **签名/Header 属于抓取**:云听的 `ytSign`(MD5 签名,[source_yunting.go](../../../radio-proxy/source_yunting.go)`:31`)
   要平移到抓取侧。这两个来源上游无 IP 配额限制,**不做断点续爬**,保持简单。
3. **注意:云听几乎没有转换逻辑可搬**。上游 JSON 直接反序列化成
   `[]Province` / `[]Category` / `[]Channel`,唯一的清洗是 `ytStripLivePrefix`,
   而它只作用于 **subtitle** —— 本阶段不入库的那个字段(它属于阶段3 的现场合并路径)。
   所以云听这边不要为了"平移清洗规则"去搬一个本阶段用不到的函数。
4. **蜻蜓有真正的构造逻辑要处理**,装载时复用而非重写:
   - 「全部」哨兵行是**网关合成的**,上游没有:Provinces 补 `{全部, 0}`、Categories 补 `{0, 全部}`
     ([source_qingting.go](../../../radio-proxy/source_qingting.go)`:71,:90`)。装载时要一并写入,
     否则 APP 的「全部」入口会消失。
   - 分类名 `TrimSuffix(title, "台")`(同上 `:94`)。
   - `play_url_low` 是**按 contentId 拼出来的**:`https://ls.qingting.fm/live/{id}/64k.m3u8`
     (同上 `:135`)。是稳定直链、不含运行时配置,可以入库。
5. **`province_code` / `category_id` 记的是「这条电台是从哪个 scope 抓到的」**,
   不是电台的固有属性 —— 这两个来源与 TuneIn 的树状归属不同,别按 TuneIn 的直觉建模。
   `(0, "0")` scope 抓到的台不等于「无归属」,若 Q1 结论是「一次拉全量」,
   需要另行确定这批台的地区/分类怎么落列。
6. `subtitle` 列这两个来源**一律留空**——它每 30 分钟一变,由服务端现场合并(阶段3 处理)。
   即便上游响应里带了(蜻蜓的 `current_program`、云听的 `subtitle`)也不写入。
7. **整份替换**(父任务契约 5):单事务内 `DELETE FROM <表> WHERE source=?` 再全量 INSERT。
   **不用** UPSERT + 按 `updated_at` 删旧行 —— 那条路在阶段1 被证明是错的(unix 秒当轮次标记,
   同一秒内跑两次一行也删不掉)。装载前校验条目数为 0 即报错退出,否则一次坏抓取就是清空全表。
8. **`image` 存上游原值**,不做任何包装 —— DB 里不存运行时配置(父任务不变量,阶段1 确立)。
   这两个来源的 image 本来就是上游直链,不像 TuneIn 要过 `/img` 透传。
9. 更新频率定为每天一次;Province / Category 合并在同一轮跑,不单独调度。

## 非目标

- 不改服务端任何代码(服务端仍走 live,读取路径留给阶段3)
- 不做 subtitle 抓取
- 不做 Program(节目单)抓取
- 不动 TuneIn 的任何东西

## Acceptance Criteria

- [ ] **Q1 / Q2 / Q3 三个问题都有书面结论并回填本文件**;Q2 若发现一台多分类,
      已提出方案并经父任务确认(这会改父任务契约 3、4)
- [ ] 两个来源各跑通一轮抓取 + 装载,`radio_*` 表中 `source='yunting'` / `source='qingting'` 数据完整
- [ ] Province / Category 行数与对应 live 接口返回条目数一致,**含合成的「全部」哨兵行**
- [ ] Channel 抽样 ≥30 个 `content_id`:title / image / province_code / category_id / play_url_low
      与 live 接口返回逐字段一致(subtitle 不比,它按设计留空)
- [ ] 比对按 `(content_id, province_code)` 对齐,不是按 `content_id` —— 否则正常的多地区行
      会被误判成重复(TuneIn 实测有 125 个这样的台;这两个来源是否也有,见 Q2 的同类排查)
- [ ] 蜻蜓的分页口径已定并落实,验收方式与该口径自洽(见 Q3)
- [ ] 重复装载幂等:同一份抓取产物装两次,行数不变
- [ ] 空抓取结果被拒绝写入,且库里原数据完好
- [ ] 连续跑 ≥3 轮(可跨天)人工比对无漂移,差异项有记录与解释
- [ ] 线上服务端行为零变化,现有测试全绿

## Notes

阶段1 的教训:来源文档对 TuneIn 管线的描述与代码实际不符,三处初稿结论在实测后被推翻
(主键、写入语义、转换逻辑归属)。**本阶段的 PRD 同样是基于读代码写的推断,不是实测**——
Q1/Q2/Q3 就是为了先把推断变成事实再动手。发现与本文不符时,改本文,别在代码里绕过去。
