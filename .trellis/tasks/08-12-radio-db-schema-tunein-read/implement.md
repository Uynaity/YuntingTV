# A 执行计划

阅读顺序:[prd.md](prd.md) → [design.md](design.md)。
执行中若发现设计站不住,回 design.md 改,别在代码里绕过去(阶段1 的教训)。

工作仓库:`radio-proxy/`(**独立 git 仓库**,父仓库 `.gitignore:33` 排除),
分支 `feat/radio-db-s1-tunein`,基于已合入的 `main`。

## 检查清单

### 1. 表结构

- [ ] [db.go](../../../radio-proxy/db.go) 的 `schema`:`CREATE TABLE radio_channels` 去掉 `category_id` 列
- [ ] 追加 `CREATE TABLE IF NOT EXISTS radio_channel_categories` + `idx_rcc_scope`(DDL 见 design.md 决策 3)
- [ ] 追加 `ALTER TABLE radio_channels DROP COLUMN IF EXISTS category_id;`(老库收敛)
- [ ] 索引换名:`DROP INDEX IF EXISTS idx_radio_channels_scope;` +
      `CREATE INDEX IF NOT EXISTS idx_radio_channels_prov ON radio_channels (source, province_code);`
- [ ] 更新 `radio_channels` 上那段解释 `category_id` 的注释(该列已移走)

**验证**:`go build ./... && go test ./...`;连库 `\d radio_channels` 确认无 `category_id`、
`\d radio_channel_categories` 确认主键与索引。**新库与老库各验一次**(老库靠 ALTER 收敛)。

### 2. 装载侧写联结表

- [ ] [tunein_import.go](../../../radio-proxy/tunein_import.go) 的 `radioChannelRow` 去掉 `categoryID` 字段
- [ ] `tiChannelRows` 相应简化(不再填 `allCategoryID` 兜底)
- [ ] 新增分类行的产出:从 `tree.genreOf` 取 `content_id → 顶级分类`,**去重**
      (同一 content_id 跨多国有多行 channel,但分类只有一组)
- [ ] `writeTuneInRows` 的整份替换扩展到第四张表:`DELETE FROM radio_channel_categories WHERE source=?`
      也放进同一事务
- [ ] `radio_categories` 表照旧写入(它是分类**清单**,联结表是**归属**,两者都要)

**注意**:TuneIn 的 `genreOf` 当前全空 → 联结表这轮写入 0 行。**这是正确的**,
不要为了填满而伪造(design.md 决策 3)。

### 3. 读取切 DB

- [ ] `tuneInSource.Provinces`:`SELECT province_code, province_name FROM radio_provinces WHERE source='tunein'`
      → 取回后用现有 `collate` 排拼音序(design.md 决策 2)
- [ ] `tuneInSource.Categories`:`SELECT category_id FROM radio_categories WHERE source='tunein'`
      → 仍由 `tiCategoriesFrom` 按 `tiTopCategories` 的固定顺序过滤输出(含「全部」哨兵)
- [ ] `tuneInSource.Channels(province, category)`:
      - `province == allProvinceCode` → 不加地区条件
      - `category == allCategoryID || ""` → 不 JOIN 联结表
      - 否则 `JOIN radio_channel_categories USING (source, content_id) WHERE category_id = ?`
      - **取回后在 Go 里按 Title 排序**,不要 `ORDER BY title`(design.md 决策 2)
- [ ] `image` 与 `play_url_low` 仍走现有 `decorate` 补 `publicBase`(阶段1 已建立,不要改)

**验证**:见下方「等价性验收」。

### 4. 删除旧路径

- [ ] 删除 [tunein_tree.go](../../../radio-proxy/tunein_tree.go) 整个文件
- [ ] 删除 `loadTuneInCatalog`、`tiCatalogFile`/`tiCatalogStation`/`tiCatalogGenre`、`tuneInCatalogPath`
      —— ⚠️ 这几个结构体 `tunein_import.go` 还要用来解析 JSON,**移过去而不是删掉**
- [ ] 删除 `errTuneInWarming` 及三处判断(design.md 决策 4)
- [ ] [main.go](../../../radio-proxy/main.go) 去掉 `loadTuneInCatalog()` 调用
- [ ] `docker-compose.yml` 去掉 `TUNEIN_CATALOG` 环境变量与 `./data:/data:ro` 卷
- [ ] `tiBuildFromCatalog` 及清洗/去重逻辑**保持不动**(属于装载侧)

### 5. 自检

- [ ] 真库用例:联结表的整份替换与幂等(照 `setupRadioTables` 的现有写法)
- [ ] 真库用例:分类筛选走 JOIN —— 手工插入一个属于两个分类的台,断言两个分类下都查得到
      (TuneIn 数据本身覆盖不到这个场景,必须造数据,否则这条路径到 B 才第一次被验证)
- [ ] 真库用例:`province=0` 与指定 province 的结果集与排序
- [ ] 调整因删除而失效的既有测试(`TestBuildFromCatalog` 等仍应保留,它们测的是装载侧)

## 等价性验收(本任务的核心)

切换前后 `/v1/*` 出参必须逐字段一致。做法:**切换前先把三个接口的响应存下来**,
切换后逐字节 diff。

```bash
# 切换前(仍读 JSON),存基线
for u in "provinces" "categories" "channels?provinceCode=0" "channels?provinceCode=100436"; do
  curl -s "localhost:8080/v1/${u/\?/?source=tunein&}" > /tmp/base_${u//[\/?=&]/_}.json
done
```

- [ ] `/v1/provinces?source=tunein` 逐字节一致(**含拼音序**)
- [ ] `/v1/categories?source=tunein` 一致(固定顺序 + 只有「全部」)
- [ ] `/v1/channels?source=tunein&provinceCode=0` 一致 —— 23215 条,含 **125 个跨国台各出现两次**
- [ ] 抽 3 个具体国家的 `provinceCode` 各比一次
- [ ] `image` 仍是 `/img?url=...` 包装形态、`playUrlLow` 仍是 `/proxy/{id}`(`decorate` 未被绕过)
- [ ] 容器不挂载 `./data` 也能正常服务
- [ ] 跑 `-import-tunein` 后,≤30 分钟内新数据自然生效(无需重启)

## 必须记录的实测数字(回填 prd.md)

- [ ] **`Channels(0, "0")` 的查询耗时** —— design.md 决策 1 的唯一风险点。
      明显偏慢就退回「只为 province=0 加内存缓存」,不要恢复整棵树
- [ ] 删除的净行数(`tunein_tree.go` 44 + JSON 路径 ~60 + 环境变量/卷)

## 回滚点

- 第 1 步后:新表空置无人读,老列已删但读取仍走 JSON —— ⚠️ 此时 `tunein_import.go` 会因
  少了列而写失败,**第 1、2 步要一起提交**
- 第 3 步后:读取已切 DB 但旧代码还在,`git revert` 即可
- 第 4 步是纯删除,单独一个提交,便于单独回滚
