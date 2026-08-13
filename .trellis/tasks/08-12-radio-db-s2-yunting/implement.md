# 第2步:云听复制同一条链路 — 执行计划

> 依据 [prd.md](./prd.md) + [design.md](./design.md)。工作目录一律 `radio-proxy/`(独立 git 仓库)。
> 起点:`feat/radio-db-s1-qingting`(s1 的五个提交在这条分支上,尚未并回 main)。
> 新分支 `feat/radio-db-s2-yunting` 从它切出。
> 参考实现:`backup/radio-db-2026-08-12` 的 `yunting_import.go`,**按文件取用,不整条合**。

## 分阶段与可回滚点

六个阶段,每个阶段结束时线上都是可部署、可停下的状态。

| 阶段 | 结束时的线上状态 | 回滚方式 |
|---|---|---|
| 0 先量四件事 | 无代码改动 | — |
| 1 装载 | 库里有 943 台云听数据,读取仍走 live | 部署上一版镜像;数据留着无害 |
| 2 节目单快照 + 调度接入 | 快照在内存里但没人读,云听目录每日自动更新 | 同上 |
| 3 起播解析播放地址 | **第一个用户可见变化**:`/v1/stream` 下发真地址,列表里的旧地址仍在当兜底 | 部署上一版镜像即回空串下发 |
| 4 读取切库 + subtitle 换源 | **第二个用户可见变化,不可与阶段 3 合并** | 部署上一版镜像即回 live |
| 5 状态端点 + 文档 | — | — |

> ⚠️ **阶段 3 必须先于阶段 4 上线,中间隔一段观察**(design.md 决策 5):
> 切库后列表里的 `playUrlLow` 是空的,`/v1/stream` 这一跳就成了云听起播的单点。
> 先让它在有兜底的情况下跑一阵,确认稳了再撤兜底。**合并成一次上线 = 把两个风险叠在同一个夜里。**

---

## 阶段 0:先量四件事(不改代码)✅ 已完成(2026-08-13)

- [x] ① 云听列表顺序 → **子序列成立**(国家 / 安徽 / 北京 三省全部真分类,无缺项无逆序)
- [x] ② `web/appProvince/list/all` → 32 档,`provinceCode=0`「国家」排第 0 位,其余按拼音序
- [x] ③ `programName` **不带**「正在直播:」前缀,**但带尾随空格** → 换源要补 `TrimSpace`
- [ ] ④ 凌晨时段蜻蜓 live 的 subtitle 显示什么 —— **仍未量**(量的时候是北京时间下午),
      云听侧的等价问题已量到:有节目单的台约 1/3 首档在 05:00–06:30
- [x] 计划外量到:上游用 `subtitle` 字段塞占位文案 `暂无节目单`,与 0 档节目单逐台对齐
      → subtitle 换源零回归(85/85 一致);占位文案归 APP(`ifBlank { "暂无节目单" }` 已在线上),
      服务端下发空串即可,**用户看到的字不变、APP 零改动**(design.md 第 8 节)
- [x] 结果写进 `docs/radio-catalog-db.md` 第四节

> ⛔ **评审点 0**:① 成立 → `sort_order` 走一列的方案 A,**表结构不动**。可以进阶段 1。

---

## 阶段 1:装载 ✅ 已完成(2026-08-13,提交 f2b2913)

- [x] 取备份分支的 `yunting_import.go`:`git checkout backup/radio-db-2026-08-12 -- yunting_import.go`
- [x] 改四处:
  - [x] 去掉 `PlayUrlLow` 的入库,以及那段「TTL 还在实测中」的注释(带时效的值不进库,不是注意事项)
  - [x] `ytRows` 按 design.md 决策 2 填 `sortOrder`:省内 `categoryId=0` 的下标;
        只在分类轮出现的 6 台排到该省末尾
  - [x] 改调 `ytFetchProvinces` / `ytFetchCategories` / `ytFetchChannels`,不调同名方法(契约 10)
  - [x] 保留:`ytScopes` 的 320 个范围(`provinceCode=0` 与 `categoryId=0` **都不能跳**)、
        `ytRows` 的取并集、`fetchRetry` / `fetchAll` 的失败语义
- [x] `source_yunting.go` 拆分:把 `Provinces` / `Categories` / `Channels` 的上游抓取体抽成
      `ytFetchProvinces` / `ytFetchCategories` / `ytFetchChannels`,现有方法先原样转调
      (**本阶段行为完全不变**)
- [x] `main.go` 加 `-import-yunting` flag,跑完即退,不起 HTTP
- [x] 测试(仿 `catalog_import_test.go` 里的 `qtRows` 用例):
  - [x] `ytRows` 取并集:只在分类轮出现的台、只在「全部」轮出现的台,都要在结果里
  - [x] `ytRows` 的 `sortOrder`:省内单调;漏网台排在该省末尾
  - [x] 一台多分类 → 产出多条 link 行;`categoryId=0` 那一轮**不**写 link
  - [x] `provinceCode=0` 的台正常入库

验证:

```bash
cd radio-proxy && go build ./... && go vet ./... && go test ./... 2>&1 | tail -20
```

打真上游(基线:943 台 / 125 台多分类 / 19 个全国台 / 320 次请求 ≈56s):

```bash
cd radio-proxy && LIVE_IMPORT=1 go test -run TestLiveImport -v -timeout 20m .
```

> ⛔ **评审点 1**:对不上基线**先别改代码** —— 上游自己会漂,先确认是漂移还是回归。
> 尤其盯两个数:19 个全国台在不在、并集是不是 943(937 与 942 任何一边都不对)。

- [x] 复核:库里**没有**任何云听播放地址

```bash
cd radio-proxy && grep -rn "PlayUrlLow\|play_url_low" --include='*.go' . | grep -v _test.go
```

期望:只出现在读取侧的拼接(蜻蜓)与 `/v1/stream` 路径上,`catalog_write.go` 里一个都没有。

---

## 阶段 2:节目单快照 + 调度接入 ✅ 已完成(2026-08-13,提交 f6893c3)

- [x] `qingting_playbill.go` → `playbill.go`,按 design.md 决策 4 提成按来源:
  - [x] `playbills.byID` 改成 `map[source]map[contentID][]Program`
  - [x] `nowPlaying(source, contentID)` / `refreshPlaybills(ctx, s Source)`
  - [x] `qtNowPlaying` 的调用点改掉;`qingting_playbill_test.go` 跟着改,**用例语义不动**
  - [x] 失败语义原样:单台失败只丢自己;整轮全空则保留上一轮快照并报错
- [x] `catalog_refresh.go` 改成按来源循环:
  - [x] 每来源「装载 → 节目单」串行,来源之间也串行,时刻仍是北京时间 00:05
  - [x] 空库同步装载(`qtCatalogEmpty` 那段)对云听同样生效 —— 提成按来源判断
  - [x] `clearMetaCaches()` 仍在**所有来源都跑完之后**清一次,位置不动(规则 11)
- [x] 测试:蜻蜓那三个 `nowPlaying` 用例改成按来源后仍全绿;补了「两来源互不串台」
      与「每日刷新列表覆盖全部已入库来源」两条;live 测试补云听节目单覆盖率与 TrimSpace 校验

验证:同上 `go test ./...`。本阶段读取路径未变,线上无可见变化。

> ⛔ **评审点 2**:蜻蜓的行为必须逐字节不变。跑一遍 s1 的读取测试(`qingting_read_test.go`)确认。

---

## 阶段 3:起播解析播放地址 ✅ 已完成(2026-08-13,提交 315b747)

此时列表里的 `playUrlLow` 还是上游给的真地址(读取尚未切库),兜底完好 —— 这正是先做这一步的理由。

- [x] `gateway.go` 新增 `ytPlayURLCache`(`sync.Map`,provinceCode → `{byID, exp}`,TTL 30 分钟,懒过期)
- [x] `/v1/stream` 的 `yunting` 分支:查库取 `province_code` → 取该省上游列表(走缓存)→ 按 `contentId` 取 `playUrlLow` → 下发
  - [x] 上游失败走 `fetchRetry`;失败时保留缓存里上一份
  - [x] 查不到就回空串 + 记日志,**不编地址**(路径能推、`key` 推不出,实测篡改/去掉均 403)
  - [x] **`hls` 仍传 false**,不顺手改流类型(design.md 决策 5)
- [x] 测试:命中缓存不打上游;上游失败时回落到缓存;查不到时回空串

验证:

```bash
cd radio-proxy && go build ./... && go vet ./... && go test ./... 2>&1 | tail -20
```

- [ ] 人工:APP 上点开云听电台能播,起播耗时与今天无肉眼差别 **(待真机验收)**
      服务端侧已验:live 测试解析国家桶与广东各一台,地址真拉都是 200

> ⛔ **评审点 3**:这一步上线后**停一停再做阶段 4**。云听起播成功率是阶段 4 撤掉兜底的前提。

---

## 阶段 4:读取切库 + subtitle 换源 ✅ 代码已完成(2026-08-13,提交 68c482e)

⚠️ 这两件事必须在同一次提交里完成。先切读取、subtitle 还没换源,「正在播放」就空了。

- [x] 导基线(**改代码前做**):在当前提交开 worktree,导出云听的
      `Provinces` / `Categories` / 若干具体省 `Channels` / 省×分类组合 `Channels`
- [x] 新建 `catalog_read.go`,把 `source_qingting.go` 的查库体提成共用函数:
  - [x] `queryChannels(ctx, source, province *int64, categoryID string)`(design.md 决策 1)
  - [x] 地区 / 分类列表的查库也一并提取
  - [x] `source_qingting.go` 改调它,**行为逐字节不变**(`qingting_read_test.go` 必须原样全绿)
- [x] `source_yunting.go` 三个方法改查库:
  - [x] `Channels` **永远**传 `&provinceCode`(0 是全国桶,不是「全部」哨兵)
  - [x] 排序在 Go 里按 `(sort_order, content_id)`,不用 SQL `ORDER BY`
  - [x] `subtitle` 取自 `nowPlaying("yunting", id)`;`playUrlLow` 留空(由 `/v1/stream` 给)
  - [x] 空表返回空列表,不报错
- [x] 测试:新建 `yunting_read_test.go`,仿 `qingting_read_test.go`(`setupRadioTables` + 手写种子行):
  - [x] `provinceCode=0` 只返回全国桶那几台,**不是**全部台 ← 本步最容易写错的一条
  - [x] 一台多分类 → 每个所属分类下都查得到
  - [x] `categoryId` 为 `""` / `"0"` 等同「全部分类」
  - [x] 顺序按 `sort_order`,同序时按 `content_id` 兜底
  - [x] 空表返回空列表

验证:

```bash
cd radio-proxy && go build ./... && go vet ./... && go test ./... 2>&1 | tail -20
```

逐字节 diff 基线:

- [x] `Provinces` / `Categories` —— 必须完全一致
- [x] 具体省、省×分类的 `Channels` —— 除 `subtitle`(换源)与 `playUrlLow`(转空串)外必须完全一致,**含位次**
- [x] 台数:基线的五个范围台数**没变**(各范围本就取自单轮枚举);
      并集带来的 6 台只影响全库总数 937/942 → 943,不影响这些范围
- [ ] 人工:APP 上翻云听的地区/分类列表,顺序与线上一致;「正在播放」有内容且**无「正在直播:」前缀**;点开能播 **(待真机验收)**
- [ ] 人工:蜻蜓侧回归一眼 —— 列表顺序、「正在播放」、起播都不变 **(待真机验收)**

> ⛔ **评审点 4**:顺序、「正在播放」、起播任一不对,**不要往下走**。
> 顺序 → 查 `sort_order` / 回看量 ①;空白 → 查快照与缓存清理;播不出 → 阶段 3 那一跳。

---

## 阶段 5:状态端点 + 文档 ✅ 已完成(2026-08-13,提交 329eba1)

- [x] `admin_catalog.go` 的快照段改成按来源(design.md 决策 7)——
      实做比设计更省:两个数并进每来源那一行,没有再套一层 map,顶层字段去掉了
- [x] `README.md`:`-import-yunting`、云听 `/v1/stream` 的新行为、按来源的快照字段
- [x] `docs/radio-catalog-db.md`:第四节补阶段 0 量到的四条;第六节把第 2 步标成已完成并写实际结果
- [x] 复核契约 1:

```bash
cd radio-proxy && grep -rn "INSERT INTO radio_\|UPDATE radio_\|DELETE FROM radio_" --include='*.go' . | grep -v _test.go
```

期望:只出现在 `catalog_write.go`。

---

## 收尾检查(对齐 prd.md 的验收)

```bash
cd radio-proxy && go build ./... && go vet ./... && go test ./... 2>&1 | tail -20
```

- [x] 装载真上游:943 台入库,并集正确(两边各自独有的 6 台都在)
- [x] `provinceCode=0` 的 19 个全国台在库里且能被浏览到
- [x] 分类联结表覆盖那 125 台多分类电台
- [x] 库里没有任何云听播放地址
- [x] `/v1/stream?source=yunting&contentId=X` 返回可播地址,缓存 30 分钟生效
- [ ] APP 侧无改动,云听电台起播正常 **(待真机验收;服务端侧已验:解析出的地址真拉 200)**
- [x] subtitle 来自 `listByDate` 推算,「正在播放」不为空、无前缀
- [x] 读取切库前后等价性验证通过(除第 8 节那三处已知差异)
- [x] 定时任务在位,失败可从日志 + `/admin/catalog` 发现
- [ ] 蜻蜓侧无回归 **(待真机验收;`qingting_read_test.go` 与基线测试原样全绿)**

## 提交切分

一个阶段一个提交,信息写清「为什么」而不是「做了什么」。
**注释里不要写没测过的「实测」口吻断言**(交接文档第七节点名过两次)。
