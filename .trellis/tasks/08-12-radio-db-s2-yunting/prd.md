# 第2步:云听复制同一条链路

> 父任务:[08-12-radio-catalog-db](../08-12-radio-catalog-db/prd.md)
> 前置:[第1步 蜻蜓](../08-12-radio-db-s1-qingting/prd.md) 做完(链路形状在那里验证)
> 事实来源:[radio-proxy/docs/radio-catalog-db.md](../../../radio-proxy/docs/radio-catalog-db.md) 第四、六节

## Goal

把第 1 步验证过的链路复制到云听。形状不变,云听多两件事:**320 次枚举**、**播放地址起播时解析**。

## Requirements

1. **装载(320 次枚举)**:云听电台对象里**没有分类字段**,分类归属只能靠「地区 × 分类」枚举反推——
   32 地区 × 9 个真分类 = 288 次,加 32 次 `categoryId=0` 取全量清单,共 320 次,≈56s。
   **这是硬约束,不是实现选择。**
2. **目录取并集**:`categoryId=0` 枚举出 937 台、分类枚举出 942 台,以任何一边为准都丢台(共 943)。
3. **subtitle 改由节目单推算**:源是 `web/appProgram/listByDate`,≈940 次/天。
4. **播放地址起播时解析,不进库**:`/v1/stream?source=yunting&contentId=X` ——
   从库里查该台 `province_code`,调一次那个地区的上游列表(`scopeCache` 缓存 30 分钟、全网共享),
   取 `playUrlLow` 下发。拿到的是刚签发的地址,比日更库里躺一天的更新鲜。
   **APP 侧零改动**:[GatewaySource.kt:150](../../../app/src/main/java/cn/radio/tv/data/source/GatewaySource.kt) 已是
   `url = direct ?: dto.url.ifEmpty { channel.playUrlLow }`,服务端从回空串改成下发真地址即自动走前一分支。
5. **定时 + 告警**、**读取切库**:同第 1 步。

⚠️ 3 和 5 必须在本任务内一起做完(理由同第 1 步)。

## Constraints

- 父任务 Constraints 全部适用。
- 云听实测事实(**不用重测**):
  - **签名是硬门槛**:无签名一律 `code 1001 参数不合法`。
  - **`provinceCode=0` 是上游真实的「全国」桶,不是「全部」哨兵。** 它返回 19 个台(id 639–734),
    这 19 个**一个都不出现在任何真地区下**。跳过它 = 静默丢掉全部国家级电台。
  - 125 台(13.3%)属于多个分类 → 必须走联结表。
  - **没有按 id 查单台的接口**,取单台信息只能拉整个地区的列表(这就是需求 4 那样做的原因)。
  - `playUrlLow` 是签名 URL(`?type=1&key=<md5>&time=<hex unix>`),篡改 / 去掉 `key` 均 403;
    路径能由 contentId 推出,`key` 推不出。
- **带时效的值不进库。** 它的 TTL 是多少**不需要知道**——上一轮花力气测 TTL 是走错方向。
- 别把播放地址塞进入库清单(五项之外一个字不加)。

## Acceptance Criteria

- [ ] 装载跑通真上游:943 台入库(并集正确,两边各自独有的 5 台 + 1 台都在)
- [ ] `provinceCode=0` 的 19 个全国台在库里且能被浏览到
- [ ] 分类联结表覆盖那 125 台多分类电台
- [ ] 库里**没有**任何云听播放地址
- [ ] `/v1/stream?source=yunting&contentId=X` 返回可播地址,`scopeCache` 30 分钟生效
- [ ] APP 侧无改动,云听电台起播正常
- [ ] subtitle 来自 `listByDate` 推算,APP 上「正在播放」不为空
- [ ] 读取切库前后逐字节等价性验证通过
- [ ] 定时任务在位,失败可被发现

## Notes

参考实现(`backup/radio-db-2026-08-12`):

- `yunting_import.go` —— **值得**,320 次枚举 + 取并集,两个坑已踩平;去掉填 `play_url_low` 的那几行
- `catalog_fetch.go` / `catalog_write.go` —— 第 1 步应已取用
- `catalog_import_test.go` 里 `ytRows` 的并集用例 —— 值得

测法陷阱:探测脚本 288 次要 132s,Go 装载 320 次只要 56s——差别是探测每次 fork 一个 curl。
**别拿探测耗时当性能基线。**
