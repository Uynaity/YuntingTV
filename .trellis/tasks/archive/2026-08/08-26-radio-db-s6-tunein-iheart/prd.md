# TuneIn 爬取改用 categories 新接口,补回 iHeart 系电台

> 父任务:[08-12-radio-catalog-db](../08-12-radio-catalog-db/prd.md)
> 事实来源:[radio-proxy/docs/radio-catalog-db.md](../../../radio-proxy/docs/radio-catalog-db.md)「TuneIn」一节
> (2026-08-13 首次发现、2026-08-26 查清原因与替代路径)

## Goal

现在的爬虫(`tunein_crawl.go` 走 `opml.radiotime.com/Browse.ashx`)拿到的浏览树,
iHeartRadio 系电台已被上游摘掉(美国 2026-08-13 一次消失 1031 台,抽样 18/20 台官网是
`iheart.com`)。已证实这些台没有真的下线,只是不挂在 Browse.ashx 这棵树上 ——
官方 App 走的 `api.radiotime.com/categories/{guide_id}` 新接口还能查到(以 WILD 94.9
`s26656` 为例)。本任务评估并按需落地:把爬取路径切到新接口,补回这批台。

## 已查清的事实(2026-08-26 实测,详见事实来源文档)

- **guide_id 命名空间与树结构和 Browse.ashx 完全一致**:洲→国→州→都会区→台每一层都验证过
  id 一一对应(北美 `r101218` → 美国 `r100436` → 加州 `r100455` → 旧金山 `r100004`,
  都会区数量、州直挂台数量级都对得上)。唯独 `r0`(树根)本身 400 —— 继续用
  `Browse.ashx?id=r0` 拿 7 大洲 id 起步,不影响下面的遍历。
- **子节点类型对应现有 `tiDrillable` 的 r/c/a 判法**:可下钻的是 `UrlCell`
  (`Action.Browse.GuideId`),电台叶子是 `Cell`(`Action.Play.GuideId`)—— 不是
  `type=="link"` / `item=="station"` 那套 OPML outline 字段,响应结构要另写解析
  (`Header/Items/List/Cell`,不是 `head/body`)。
- **能不能查到 iHeart 系台,是认证参数决定的,不是随便加个参数就行**:逐项拆参数试出来,
  必须 `partnerId` + `version=42.0` + `latlon` **三个一起**才会出现,单独加任何一个
  (包括 `formats`)都不出现。而且是**每一层都要带**,不是只在电台叶子那层 ——
  加州州直挂列表不带这三个参数是 84 台,带了才涨。
- **`partnerId=xwhZkVKi` 是官方 Android App 自己的凭据**,不是本项目申请的
  `TUNEIN_PARTNER_ID`。硬编码它等于冒充官方客户端,比现在「申请来的凭据缺失时功能不变、
  只是额度低」的降级路径脆得多。
- 命中率**不是 100%**:反复打同一个全参数 URL 做过重复测试,但用于估算命中率的早期测法
  (直接 grep 计数、未存原始响应)有漏洞,「约 90%」这个数字**没有干净证据支撑,不可信**,
  需要重新用规范方法(存全量响应 + 记条数 + 记 HTTP 状态码)测出可信的命中率再定重试策略。
  已确认存在的、有实锤的间歇性问题是另一件事:菲律宾 `r100396` 用 Browse.ashx 爬出 0 台、
  原地重爬变 62 台(HTTP 200 + 合法 JSON + 空列表),`tiCrawlCountryRetry` 已经在处理这类。

## Requirements

- 重新、干净地测出新接口的命中率:每次请求存完整响应,同时记 HTTP 状态码与条目数,
  不能只 grep 一个 guide_id 就下结论(上一轮的教训)。命中率决定要不要重试、重试几次。
- 若决定切换爬取路径:
  - 新写一套 `categories/{id}` 的响应解析(`tiOutline`/`tiResponse` 那一套 OPML 字段不能直接套用)。
  - `partnerId`/`version`/`latlon` 三个参数要在每一层请求里都带上,选型上要么申请一个
    能拿到同等目录的凭据,要么明确接受「硬编官方 App 凭据」这个风险并让用户签字确认。
  - 保留现有失败分层语义(403/429 整轮放弃、单点失败跳过、0 台整体重来,见
    `tunein_crawl.go` 注释里的契约 5/13)—— 这是已经用血换来的规则,不因为换接口就丢。
- 若决定不切换:至少把「已知有损、且知道怎么补」这件事从「查清楚了」推进到「有没有必要现在做」
  的明确结论,别让待办再烂在文档里。

## Constraints

- 父任务 Constraints 适用。
- 不能引入「换个接口就重写一遍风险控制」的回归 —— 契约 5(403/429 整轮放弃)、
  契约 13(0 台判定不按比例)这些规则是跨接口不变的。
- 新旧接口混用要考虑清楚边界:是整棵树切过去,还是只在 Browse.ashx 漏了台的节点上
  补一次 categories 查询(后者请求量小得多,但需要先知道「哪些节点漏了台」,
  而漏台恰恰是查不出来的 —— 这条路径大概率不可行,倾向整棵树切换或完全不切)。

## Acceptance Criteria

- [x] 命中率用规范方法(存响应 + 记状态码 + 记条数)重新测过,给出可信数字,不是猜的
      → 同一 URL 打 20 次:16 次 200,**16/16 命中,台数恒定 138**;4 次 SSL EOF 传输错误,
      `fetchRetry` 已覆盖。**不需要新的重试层。**
- [x] 明确决定:切换爬取路径 / 维持现状且记录为已知限制 → **两个都不是,是第三条路**:
      `Browse.ashx` 加 `&version=42.0` 就够,接口不用换。已写进 `docs/radio-catalog-db.md`。
- [x] 全量重爬一轮成功,美国台数回升到 10848 量级 → **10929 台**(改前 10299);
      全球 180 国 / 24784 台 / 4m48s / 0 次 403。
- [x] 失败分层行为在新接口下同样有测试覆盖 → **接口没换**,契约 5/13 的现有用例原样适用;
      另在 `tiFakeUpstream` 里加了一条:爬取侧每一跳都必须带 `version`(漏一层静默丢 iHeart)。
- [x] `partnerId` 硬编码风险已和用户明确 → **风险不存在**:`docker-compose.yml:11` 的
      `TUNEIN_PARTNER_ID` 本来就是 `xwhZkVKi`,爬虫一直在带,不是新增的冒充行为。

## 结论(2026-08-26)

PRD 立项时的两个前提都被实测推翻,方向因此便宜了一个数量级:

1. ~~「必须 partnerId + version + latlon 三个一起」~~ → **只有 `version` 起作用**。
   上一轮是一个个往上加参数试的,`version` 全程混在里面,从没单独拆出来过。
2. ~~「要换 `categories/{id}` 接口 + 另写一套 `Header/Items/List/Cell` 解析」~~ →
   **不用换**。老接口带上 `version` 拿到的台数和新接口一样(138)。

落地就是 `tunein_crawl.go` 一个常量 `tiVersion` + 一处拼串,加两条守门检查。
新接口那套解析、latlon、重试层、申请新凭据 —— 全部不需要,没写。

**留下的已知风险**:`version=42.0` 是官方 App 的版本号,过期时的表现是**静默少台**
(200 + 合法 JSON,只是没有 iHeart 系)。守门数字是 `TestLiveImportTuneIn` 里的美国
10929 台 —— 掉回 10300 量级就是该往上调版本号的信号。

**待人工执行**

- [ ] 部署服务端(`radio.hku.wtf`)。爬虫是每周跑的后台作业,不部署就还是旧目录 ——
      下一轮爬完后美国台数应到 10900 量级。
- 提交:`radio-proxy` `92edff8`(在 `refactor/ponytail-audit` 分支上,和本任务无关的分支名,
  当时手头就在那条上)。
