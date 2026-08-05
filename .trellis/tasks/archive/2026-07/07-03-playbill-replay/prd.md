# 电台节目单与回放收听

## Goal

为播放器增加「查看节目单 + 收听回放」能力：用户可查看某电台**过去 7 天 + 今天 + 明天**（共 9 天）的节目单，并对支持回放的节目一键回听。云听、蜻蜓FM 两来源统一体验。

## 背景（接口已探明，结论见 design.md）

- **蜻蜓FM**（无签名）：`GET /v3/channels/{cid}/playbills?start=&end=` 取窗口内节目（扁平列表，含 `id/start_time/end_time/title`）；回放地址需再请求 `GET /v3/channels/{cid}/playbills/{节目id}/replay_program`，从 `data.resources[].aac_play_url` 取（有 24k/64k 两档，取高码率）。仅**已播出**节目有回放资源。
- **云听**（复用现有 `SignInterceptor`）：正确 Web 路径为 `GET /web/appProgram/listByDate?broadcastId={cid}&date=yyyy/MM/dd`（**不是** `contentBiz/v1/...`，后者验签失败）。返回 `data[]`，每项含 `programName/startTime/endTime/playUrlHigh/playFlag`。**`playUrlHigh` 即回放地址**；`playFlag==1` 且 `playUrlHigh` 非空 → 可回放（未播出节目 `playFlag==0`、地址为空）。

## Requirements

- R1 播放器面板在「播放/暂停」按钮**右侧**新增「节目单」按钮（TV 遥控可聚焦）。
- R2 点击后，电台名称**上方的图标（封面）区域**就地切换为节目单视图；再次点击 / 返回键退出，恢复封面。
- R3 节目单分两列：左侧一小列列出 9 个日期（过去 7 天、今天、明天），可上下选择；右侧展示所选日期的节目（节目名 + 起止时间）。
- R4 默认选中「今天」。切换日期即加载该日节目。
- R5 支持回放的节目，在其右侧显示一个「播放」图标；不支持则不显示。
  - 蜻蜓FM：默认所有电台支持回听，判据 = 节目**已播出**（`endTime <= 现在`）。
  - 云听：判据 = `playFlag==1` 且回放地址非空。
- R6 点击某节目的回放图标 → 加载并播放其回放音频（蜻蜓需按需二次请求解析回放地址）。回放接管当前播放，面板节目名反映正在回放的节目。
- R7 节目单针对**当前播放器展示的电台**（`currentChannel`），按其自身来源（`playingSource`）路由请求。
- R8 加载中 / 失败 / 空节目单各有占位提示；失败不崩溃、不影响直播播放。

## 约束

- 复用现有分层：`RadioSource` 契约 + 两 `*Source` 实现 + `NetworkModule`/API 接口 + `RadioViewModel` 状态 + Compose 组件。
- 懒加载：按所选日期**按需**请求当天节目，不预取 9 天；蜻蜓回放地址点击时才解析，避免对每条节目额外请求。
- 双布局：横屏（TV，`PlayerPanel` 竖排在左 1/3）就地替换封面区；竖屏（手机，`PlayerPanel` 横排在底部）无大封面区，节目单以全屏 `Dialog` 覆盖呈现（复用 `SleepTimerOverlay` 的弹层模式）。
- 不新增第三方依赖。

## Acceptance Criteria

- [ ] AC1 播放某电台后，播放/暂停键右侧出现可聚焦的「节目单」按钮。
- [ ] AC2 点击「节目单」→ 封面区（横屏）或全屏弹层（竖屏）显示两列节目单，默认「今天」。
- [ ] AC3 左列 9 个日期可选，切换后右列加载对应日节目并展示节目名 + 起止时间。
- [ ] AC4 蜻蜓FM：已播出节目显示回放图标，未播出（今天稍后 / 明天）不显示；点击可正常回听。
- [ ] AC5 云听：`playFlag==1` 的节目显示回放图标并可回听，未播出节目不显示。
- [ ] AC6 回放播放时面板反映当前回放节目名；暂停/继续正常；返回键 / 再次点击按钮可关闭节目单。
- [ ] AC7 节目单加载失败时给出提示且不影响正在进行的直播播放；`./gradlew assembleDebug` 通过。
