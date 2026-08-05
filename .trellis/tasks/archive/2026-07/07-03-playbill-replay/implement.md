# 执行计划：节目单与回放

按「数据 → 网络 → 数据源 → VM → UI → 联调」自底向上，每步可独立编译验证。

## 步骤

- [ ] S1 模型：`data/model/Models.kt` 新增 `Program`（id/title/startTime/endTime/canReplay/replayUrl）。
- [ ] S2 网络（蜻蜓）：`QingTingApi.kt` 加 `getPlaybills`、`getReplay` 及 DTO `QtPlaybill/QtReplay/QtReplayRes`（`@Path`/`@Query`/`@SerialName`）。
- [ ] S3 网络（云听）：`RadioApi.kt` 加 `getPrograms(broadcastId,date)` 及 DTO `YtProgram`。确认 `NetworkModule` 无需改（云听 client 已挂 `SignInterceptor`）。
- [ ] S4 契约：`RadioSource.kt` 接口加 `fetchPlaybill`、`resolveReplayUrl`；`BaseRadioSource` 给 `resolveReplayUrl` 默认实现（返回 `program.replayUrl`）。
- [ ] S5 云听源：`YunTingSource.fetchPlaybill` 实现（日期格式化 + 映射，`canReplay=playFlag==1&&playUrlHigh非空`）。
- [ ] S6 蜻蜓源：`QingTingSource.fetchPlaybill`（窗口=当天，映射 `canReplay=endTime<=now`）+ 覆盖 `resolveReplayUrl`（取最高码率 `aacPlayUrl`）。
- [ ] S7 VM 状态：`RadioUiState` 加 playbill 相关字段 + `PlaybillDate`；`playingProgramTitle` 承接回放节目名。
- [ ] S8 VM 逻辑：`togglePlaybill`/`selectPlaybillDate`/`loadPlaybill`(带竞态 token)/`playReplay`；抽 `playUrl(...)` 并让 `playNow` 复用；修正 `togglePlayPause` 的 loadedUrl 语义。日期工具函数（9 天 + label + dayStart 归零）。
- [ ] S9 UI 组件：新增 `PlaybillButton` + `PlaybillContent`（两列）；`PlayerPanel` 加入参与两布局接入（竖排就地换封面区 / 横排 Dialog 覆盖）。
- [ ] S10 屏幕接线：`RadioScreen` 两处 `PlayerPanel` 透传新入参；`BackHandler` 最前置处理关闭节目单。
- [ ] S11 联调 + 校验。

## 验证

- 编译：`./gradlew assembleDebug`（每完成 S1–S10 的网络/源/VM 后至少各跑一次）。
- 手动（真机/模拟器，两来源各一台）：
  - 云听某台 → 打开节目单 → 今天有回放图标、明天无 → 点回放能出声，暂停/继续正常。
  - 蜻蜓某台 → 今天已播出节目有回放图标、未播出无 → 点回放（触发 replay_program 解析）能出声。
  - 切日期加载正确；返回键关闭节目单；再次点按钮关闭；横竖屏各验证一次。
  - 节目单请求失败（可临时改错 URL 验证）→ 有提示且直播不中断。

## 回滚点

- 各步独立提交；S1–S6（数据/网络/源）为纯新增，UI 未接线前不影响现有行为。
- S8 修改 `togglePlayPause`/`playNow` 属改动既有播放逻辑，为主要风险点——单独提交并重点回归直播播放/暂停/切台/启动续播。

## 备注

- 探针脚本在 `/tmp/`（probe_qt*.py / yt_web.py 等），如需复核接口可重跑。
- 不新增依赖；文案/注释沿用项目中文风格。
