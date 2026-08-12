# 阶段1 执行计划

前置阅读:[prd.md](prd.md) → [design.md](design.md)。设计决策以 design.md 为准,
执行中若发现决策站不住,回 design.md 改,别在代码里绕过去。

## 检查清单

### 1. 建表

- [ ] 在 [db.go](../../../radio-proxy/db.go) 的 `schema` 常量追加三张表 + 索引 DDL,全部带 `IF NOT EXISTS`
      (字段以 [design.md](design.md)「数据映射」和来源文档为准)
- [ ] 改掉 `db.go`:23 那段「TuneIn 目录仍走文件方案,不入库」的过期注释
- [ ] 启动服务确认建表成功,现有激活功能不受影响

**验证**:`go build ./... && go test ./...`;连库 `\d radio_channels` 确认列与索引。

### 2. publicBase 包装后移(design.md 决策 2)

行为不变的重构,先做这步,后面导入命令才能干净复用转换代码。

- [ ] [source_tunein.go](../../../radio-proxy/source_tunein.go)`:159` `toChannel`:
      `Image` 改为直接取 `st.Image`(原始 URL),`PlayUrlLow` 改为空串;去掉对 `s.publicBase` 的依赖
- [ ] 新增 `(s *tuneInSource) decorate(chans []Channel) []Channel`:
      返回**新切片**(不可就地改 `tiTree` 的共享切片),对每条套
      `publicBase + "/img?url=" + url.QueryEscape(原始)`(原始为空则留空)
      和 `publicBase + "/proxy/" + ContentID`
- [ ] `Channels()`([source_tunein.go](../../../radio-proxy/source_tunein.go)`:365`)两个返回点
      都改为 `return s.decorate(...), nil`
- [ ] `toChannel` 不再用 `s` → 降成自由函数;`buildFromCatalog` 随之降成
      `tiBuildFromCatalog(cf *tiCatalogFile) (*tiTree, error)`,同步改
      `loadTuneInCatalog` 的调用点
- [ ] [tunein_test.go](../../../radio-proxy/tunein_test.go)`:133,:180` 去掉
      `s := &tuneInSource{...}`,改调自由函数

**验证**:`go test ./...` 全绿。手工起服务比对 `/v1/channels?source=tunein` 的
`image` / `playUrlLow` 与改动前逐字节一致(这是行为不变的唯一硬证据)。

### 3. 导入命令骨架

- [ ] 新增 `tunein_import.go`:`importTuneInCatalog(db *sql.DB, path string) error`
- [ ] 复用现有解析路径:读 JSON → `tiCatalogFile` → `tiBuildFromCatalog(&cf)` 得 `*tiTree`。
      **不要复制这段逻辑**;第 2 步做完后不需要构造 `tuneInSource`
- [ ] 此时 `tree.byProvince` 里的 `Image` 已经是原始 URL,直接入库,无需另取
- [ ] 在 [main.go](../../../radio-proxy/main.go) 挂一个 flag(如 `-import-tunein <path>`),
      跑完即退出,不启 HTTP 服务

**验证**:导入后随机抽 5 行,`image` 列必须是 `http(s)://...` 形态,
不含 `/img?url=`,不含本服务域名;`play_url_low` 为空串。

### 4. 写入(单事务,整份替换)

- [ ] 前置校验:`tree.stations == 0` → 返回错误,**不做任何写入**
- [ ] 事务内先 `DELETE FROM <三张表> WHERE source='tunein'`,再全量 INSERT
      (不用 UPSERT + 删旧行,理由见 design.md「写入语义」)
- [ ] 提交;任一步失败整体回滚
- [ ] 打印一行汇总日志:省数 / 台数 / 分类数,对齐 `loadTuneInCatalog` 的日志风格

**验证**:见下方「验收执行」。

### 5. 自检

- [ ] 加 `tunein_import_test.go`:用一份小的 `tiCatalogFile` fixture 跑映射逻辑,
      断言 image 是原始 URL、`play_url_low` 为空、`category_id` 缺失时为 `'0'`、
      `province_code` 是 `tiGuideToCode` 的结果
- [ ] 不需要真实 DB 的测试:把映射抽成纯函数 `tiChannelRows(tree) → rows`,只测它
- [ ] 加一条 `decorate` 的断言:原始 URL 进、包装后的地址出,空 image 仍为空
- [ ] **写库部分要用真库测**。原计划写的是「建 DB 桩成本高于收益」,这条判断是错的:
      [main_test.go](../../../radio-proxy/main_test.go) 的 `TestMain` 已经用
      embedded-postgres 起了真库(不需要 docker),照 `setupTestDB`
      ([activation_test.go](../../../radio-proxy/activation_test.go)`:33`)加一个
      `setupRadioTables` 即可,成本接近零。而这里要验的恰恰是 SQL 层语义
      ——事实上「同一秒内跑两次删不掉旧行」这个 bug 就是真库用例抓出来的
- [ ] 三条真库用例:一台多国各留一行 / 重复装载幂等且下架行被删 / 空目录拒绝写入且旧数据完好

## 验收执行

按 [prd.md](prd.md) 的 Acceptance Criteria 逐条跑:

```bash
# 1. 首轮导入
./radio-proxy -import-tunein data/tunein_catalog_v2.json

# 2. 条目数比对(应与 JSON 产物一致)
psql "$DSN" -c "SELECT count(*) FROM radio_channels WHERE source='tunein'"
psql "$DSN" -c "SELECT count(*) FROM radio_provinces WHERE source='tunein'"

# 3. 重复导入:行数不变,updated_at 全刷新
./radio-proxy -import-tunein data/tunein_catalog_v2.json
psql "$DSN" -c "SELECT count(*), count(DISTINCT updated_at) FROM radio_channels WHERE source='tunein'"

# 4. 下架检测:手工删掉 JSON 里某国的若干台,重导,确认库里对应行消失
```

- [ ] 抽样 ≥20 个 `content_id`,与服务端 `/v1/channels?source=tunein` 当前返回逐字段比对
      (image 比对时需自行套上 `/img?url=` 前缀再比)
- [ ] 确认线上行为零变化:`go test ./...` 全绿,服务端仍读 JSON

## 回滚点

- 第 1 步(建表)之后:新表空置无人读,无需回滚
- 第 4 步之后:`DELETE FROM radio_* WHERE source='tunein'` 即可清空重来
- 整体回滚:revert 提交即可——本阶段不改任何读取路径,服务端行为始终未变

## 结论记录(阶段完成时必须回填 prd.md)

- [ ] 表结构是否满足 TuneIn 全部字段?不满足的列出调整项,回父任务确认后再开阶段2
- [ ] 是否发现一台多分类的实证?(预期:否,`genreOf` 单值)
