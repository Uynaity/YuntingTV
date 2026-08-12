# B 执行计划

阅读顺序:[prd.md](prd.md) → [design.md](design.md)。
执行中若发现设计站不住,回 design.md 改,别在代码里绕过去(阶段1 与 A 的教训)。

工作仓库:`radio-proxy/`(**独立 git 仓库**),分支 `feat/radio-db-s1-tunein`(A 已合入)。

## 检查清单

### 1. 跨来源的写入函数(先做,两个来源都要用)

- [ ] 把 [tunein_import.go](../../../radio-proxy/tunein_import.go) 的 `writeTuneInRows`
      抽成 `writeCatalog(db, source string, cat *catalogRows) error`
- [ ] `catalogRows` 就是四张表各一个切片:provinces / categories / channels / channelCategories
- [ ] 语义不变:单事务、按 `source` 删光再插、四张表一起(契约 5)
- [ ] TuneIn 改走新函数,**现有测试必须原样通过**(这一步是纯重构,不改行为)

**验证**:`go test ./...` 全绿,`tunein_import_test.go` 一个断言都不用改。

### 2. 抓取:共用的重试与失败语义

- [ ] 一个小工具:`fetchWithRetry(fn func() error) error`,最多 4 次,退避 0.5/1/1.5/2s
- [ ] **任一子请求重试耗尽 → 整轮返回 error,一行都不写**(design 决策 4)
- [ ] 并发度 10(实测 288 次并发 10 耗时 132s、0 失败);两来源共用

⚠️ 不要写成「失败就跳过这个地区」。半张目录能写进库,比写不进去危险。

### 3. 蜻蜓装载

- [ ] `v4/regions` → provinces,**补「全部」哨兵行** `{全部, 0}`(design 决策 5)
- [ ] `v4/categories?type=channel` → categories,补哨兵 `{0, 全部}`,
      分类名 `TrimSuffix(title, "台")`(复用 `source_qingting.go` 的现有规则)
- [ ] `v4/channels?page=N&pagesize=300`,N 从 1 递增到返回空页(实测 4 页 / 1098 台)
- [ ] 每台:`province_id` → province_code;`category_ids` 全部写联结表(实测 13 台有 2 个)
- [ ] `play_url_low` 按 id 算:`https://ls.qingting.fm/live/{id}/64k.m3u8`
- [ ] `image` 取 `cover` **原值**,不包装(契约 2)
- [ ] `subtitle` 留空,**即便 `current_program` 就在响应里**(契约 7 / design 决策 7)
- [ ] ⚠️ **`province` 对象可能不存在**(实测 471 那台),取名字前判空;
      不为它造 `radio_provinces` 行(design 决策 6)

### 4. 云听装载

- [ ] 复用 `ytSign` 与 `yunTingSource.get`,**不重写签名**
- [ ] `web/appProvince/list/all` → provinces;`web/appCategory/list/all` → categories
      (云听自带「全部」,不用补哨兵 —— 实测 id `0` 就在返回里)
- [ ] 全量清单:32 个地区 × `categoryId=0`
- [ ] 分类归属:32 个地区 × 9 个真分类(排除 id=`0`)= 288 次
- [ ] **目录取两者并集**(design 决策 2):只在分类下出现的 5 台、只在「全部」里的 1 台都要在
- [ ] `play_url_low` / `image` 存抓到的原值;`subtitle` 留空
- [ ] 同一台在多个地区出现?实测 0 个,但按 `(content_id, province_code)` 去重写入,不假设

**注意**:云听几乎没有清洗逻辑可搬 —— 上游 JSON 直接反序列化成模型,
唯一的 `ytStripLivePrefix` 只作用于 subtitle,而 subtitle 本任务不入库。
**不要为了「对称」去搬一个用不到的函数。**

### 5. 命令行入口

- [ ] `-import-yunting` / `-import-qingting` 两个 flag,对齐现有 `-import-tunein` 的形状
- [ ] 跑完就退,不起 HTTP 服务
- [ ] 日志打出:各表行数、耗时、并集里各来源贡献了多少(便于核对 943 这个数)

### 6. 云听 `play_url_low` 签名 TTL 实测(交付物,不是可选项)

- [ ] 抓一条 `playUrlLow`,记下签发时刻,**每小时 curl 一次直到失效或满 24h**
- [ ] 结论回填 prd.md:观测到失效则记 TTL;满 24h 仍有效则记「≥24h」
- [ ] 这个数字决定 D 要不要加快刷新作业。**B 不加**(design 决策 3)

已知下界(2026-08-12 实测):签发后 **≥13 分钟仍 200**;篡改 key → 403、去掉 key → 403。

⚠️ **判据只认 403**。观测中撞到过 `HTTP 000 / Empty reply from server`(5 次里 1 次),
那是上游间歇性抽风,不是签名过期 —— 第一版观测脚本就是在这里误报了「707 秒失效」。
每轮重试 3 次,连续 3 次 403 才算失效。这个坑同时印证了第 2 步的重试是必需项。

### 7. 自检

- [ ] 真库用例:两来源的整份替换与幂等(照 `setupRadioTables` 的写法)
- [ ] 真库用例:云听多分类台在**每个**所属分类下都查得到(A 已建的 JOIN 路径,这次是真数据)
- [ ] 纯函数用例:蜻蜓 `province` 缺失时不 panic 且行照写
- [ ] 纯函数用例:并集逻辑 —— 只在分类里 / 只在全量里 / 两边都有,三种都覆盖
- [ ] 失败语义用例:注入一个必败的子请求,断言**库里一行没变**

## 验收比对(本任务的核心)

装载完成后,把 DB 里的行与 **live 接口**逐字段比。live 仍在服务,直接调本机即可。

- [ ] 抽 ≥30 个台比 `title / image / province_code / play_url_low`(subtitle 不比,按设计留空)
- [ ] 按 `(content_id, province_code)` 对齐
- [ ] 云听行数 ≈ **943**、蜻蜓 **1098**;偏差要能解释(上游会漂,不是一定是 bug)
- [ ] 联结表:云听 125 个多分类台(108 个 2 类 + 17 个 3 类)、蜻蜓 13 个
- [ ] Province / Category 行数与 live 一致,**含哨兵行**
- [ ] 连跑 ≥3 轮无漂移(可跨天),差异项记录进 prd.md
- [ ] **线上行为零变化**:全量测试通过,`/v1/*` 出参不受影响(读取路径本任务不动)

## 必须记录的实测数字(回填 prd.md)

- [ ] 两来源各自的装载耗时与请求数
- [ ] 云听 `play_url_low` 的签名 TTL(第 6 步)
- [ ] 并集的实际台数,以及「只在分类里 / 只在全量里」的台各是哪几个(记 contentId)

## 回滚点

- 第 1 步是纯重构,单独提交,行为零变化 —— 出问题直接 revert
- 第 3、4 步各自独立,一个来源坏了不影响另一个
- 装载命令不在请求路径上,**任何一步出问题都不影响线上服务**;
  最坏情况是库里多了两个来源的行而没人读(D 之前本就没人读)
