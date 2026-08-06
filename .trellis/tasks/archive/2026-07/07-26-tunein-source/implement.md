# 执行计划：TuneIn 来源接入

阶段间可独立验证。A/B/C/D 是网关（C/D 纯增量），E 触及共享起播代码，F 是 APP，G 收尾。

## A. 骨架与模型映射

- [x] `radio-proxy/source_tunein.go`：`tuneInSource{publicBase}` + `Key() == "tunein"`
- [x] OPML 响应结构体（`element` / `type` / `text` / `guide_id` / `item` / `image` / `subtext` / `current_track` / `genre_id` / `children`）
- [x] `tiGetJSON` 带 `User-Agent`，沿用 `apiClient`，校验 `head.status`
- [x] `Programs` 返回空列表（radiotime 无节目单）
- [x] `provinceCode` ↔ `guide_id` 互转（`r101215` ↔ `101215`）
- [x] `stationsOf` 返回 outline 而非 guide_id：调用方要 `Text` 作地区名，Body 里 station/link 混排按索引回查会错位
- [x] `initSources` 注册 `"tunein"`

验证：`go build ./...` ✅

## B. 离线爬虫（tools/tunein_crawl.py）

服务端定时全量爬取的方案已废弃：上游按 IP 计配额，全球 3000+ 次 Browse 在一台机器上
爬不到一半就连根节点 `r0` 都 403，且封禁以小时/天计。改为离线断点续爬 + 换 IP。

- [x] `browse` 遇 403/429 抛 `Blocked` 立即停，其余错误重试后跳过
- [x] `drillable` 白名单：只下钻 r/c/a，挡掉 g（分类轴）与 p/m（播客），否则重复抓且拖长十几倍
- [x] `split_nodes` 认三种形态；children 里的 link 同样下钻（BBC 台网就是这么丢的）
- [x] 每爬完一国就 `.tmp` + rename 落盘；`done` 清单驱动断点续爬
- [x] 国家清单未枚举完不落盘，避免固化残缺待办
- [x] 装成浏览器 UA + 可选 `TUNEIN_PARTNER_ID`/`TUNEIN_SERIAL`
- [x] `--status` 只看进度不发请求；`--selftest` 覆盖解析与白名单

验证：`python3 tools/tunein_crawl.py --selftest` ✅

## C. 服务端只读目录

- [x] `tunein_catalog.go`：`loadTuneInCatalog` 读产物 → `buildFromCatalog` 装配
- [x] 清洗/去重/分类本地化都在网关侧，规则变更重启即可，不必重爬
- [x] `genreOf` 只登记去重后留下的台
- [x] 空桶（被限流打断的国家）不产出空国家项
- [x] 缺文件/损坏只让 tunein 不可用，其余三来源正常
- [x] 删除 `tunein_snapshot.go`、`refreshLoop`、`buildTree`、`tiBrowse`、`stationsOf`、双缓冲阈值
- [x] `docker-compose.yml` 挂 `./data:/data:ro` + `TUNEIN_CATALOG`
- [x] README 补离线爬取流程与环境变量

验证：`go test ./...` ✅

## D. 分类

- [x] 分类名由爬虫抓好写进目录文件，服务端只做 `tiGenreZh` 本地化与排序
- [x] 只列真正出现过且有名字的 genre
- [x] 首项固定「全部」哨兵（`id == "0"`）

验证：假目录文件端到端，`/v1/categories` 返回「全部/当代基督教/流行」 ✅

## E. 起播分派（触及共享代码，谨慎）

- [x] `isTuneInID(id)`：`^s\d+$`
- [x] 现有 `resolveStreamURL` 主体抽为 `rbResolve`，行为逐字不变
- [x] `tuneInResolve`：`Tune.ashx?id=` → `body[0].url`；空 body 报错
- [x] `resolveStreamURL` 改为「缓存 → 按 id 形态分派」，`isSignedHLS` 缓存门提到分派层，两来源共用
- [x] **回归门**：本地网关 radio-browser 三台起播 200/206，UUID 路径行为零变化
- [x] `playlist_type` 跟一跳：抽样 50 台有 28% 是 pls/m3u，不解析则近三成台起播失败
- [x] Tune 布尔字段是字符串（`"false"`），不接 `is_hls_advanced` 免整体解码失败

验证：本地起 gateway，`curl -I` 一个 radio-browser 台 + 一个 radiotime 台，两者都要 200

## F. APP 侧

- [x] `RadioSourceType` 加 `TUNEIN`（key `tunein`）
- [x] `GatewaySource` 默认地区：美国（`r100436` → `100436L`）
- [x] `fetchPlaybill` 对 TUNEIN 短路返回空（无节目单）
- [x] 收藏合并流覆盖新来源
- [x] 设置页 `SourceDropdown` 多一项，D-pad 焦点正常（`entries` 自动带上）
- [x] 确认播放器无需改动（类型判定已是 mimeType 优先）

验证：`./gradlew assembleDebug`

## G. 实机走查与收尾

网关侧已在本地验证（下列打勾项为 curl 验证，实机走查仍需用户确认 UI）：

- [x] 完整目录：169/169 国 · 16037 台 · 323 分类，装配后 14832 台（同国去重）
- [x] 假目录端到端：去重、标题清洗、分类中文化、四接口均正确
- [x] mp3 直链、pls、m3u 三类各起播成功；抽样 50 台无 HLS 台
- [x] 分类收敛为三档：`categories` = 全部/音乐/谈话节目/体育
- [x] 分类筛选实数据复核：全部 14832 = 音乐 11822 + 谈话 2582 + 体育 179 + 无 genre 249；
      美国单国 3718 = 2849 + 722 + 110 + 37
- [x] 分类名中文化：`--refresh-genres` 后 263/323 为中文（余下是赛事等英文专名）
- [x] 启动读目录文件：日志「目录已加载」，瞬时可服务
- [x] 标题清洗：上游 `呼号 | 台名 (分类)` 格式已归一
- [x] 实机：四来源逐个切换，列表/起播正常，既有三来源行为无回归
- [x] 实机：卡片副标题显示 `current_track`；无该字段时不崩
- [x] spec 更新（2026-07-26 补）：
      - `backend/directory-structure.md` 加「TuneIn 是唯一的离线目录来源」
        （产物路径、启动读入、更新=换文件重启、缺文件只影响本源）
      - `backend/quality-guidelines.md` 加禁用模式「不要让服务端定时全量爬 radiotime」
        （上游按 IP 计配额、封禁以小时/天计；附爬虫侧下钻白名单与 link 下钻两个坑）
- [x] 提交：`b6ba1f7 新增 TuneIn 作为第四来源`

> 实机走查由用户在日常使用中完成（本来源上线后一直在用，并由此发现了列表加载慢的
> 问题 —— 已由 `07-26-channel-pagination` 任务解决）。

## 验证命令

```bash
cd radio-proxy && go vet ./... && go test ./...
```

```bash
./gradlew assembleDebug
```

## 回滚点

- E 阶段后若起播回归：`resolveStreamURL` 恢复单一 `byuuid` 实现即可，A–D 无副作用。
- 目录方案回滚点：服务端已无爬取代码，换目录文件即可；文件缺失时 tunein 自动降级为不可用。
- 全量回滚：`initSources` 摘掉 `tunein` 一行 + APP 枚举去掉一项 + `main.go` 去掉预热两行。无持久化结构变更（快照文件删掉即可）。

## 评审门

- B 完成后先确认爬取成本可接受，再进 E。✅（欧洲一洲 635 次 / 44s，全球外推 3–5 分钟，故选定时预热）
- E 触及既有起播路径，改完必须回归 radio-browser 起播，通过后才进 G 实机。
