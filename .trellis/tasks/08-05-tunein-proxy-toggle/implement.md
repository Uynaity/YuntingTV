# 执行计划：TuneIn 代理开关

服务端（`radio-proxy`，未纳入版本控制）与客户端（本仓库）分两段做，服务端先行——
客户端的"回退代理"分支本就要靠"字段缺失"这条路径验证，顺序颠倒也不阻塞，
但服务端先上可以让客户端联调时就有真实 `directUrl` 可用。

## 阶段一：服务端 `/v1/stream` 下发 `directUrl`

- [x] `gateway.go`：`handleV1Stream` 的 `tunein` 分支改为接住 `resolveStreamURL` 的
      地址（不再用 `_` 丢弃），新增 `writeStreamDirect(w, proxyURL, directURL, hls)`；
      `writeStream` 保持原签名，内部转调 `writeStreamDirect(w, url, "", hls)`，
      `qingting`/`yunting` 调用点不变。
- [x] 响应体 JSON 加 `directUrl` 字段（空字符串表示未下发/不适用）。
- [x] `gateway_test.go`：在现有 `/v1/stream` 类型下发用例（`gateway_test.go:50` 附近）
      旁加一条断言：`source=tunein` 时响应含非空 `directUrl` 且不等于 `/proxy/` 地址；
      `source=qingting`/`yunting` 时 `directUrl` 为空。
- [x] `go test ./...` 全绿。
- [x] 部署到 `radio.hku.wtf`（该服务端改动不在 git 版本控制内，手动同步；
      2026-08-05 由项目所有者确认已上线）。

## 阶段二：客户端

- [x] `data/remote/GatewayApi.kt`：`StreamDto` 加 `val directUrl: String = ""`。
- [x] `data/prefs/UserPreferences.kt`：仿 `autoFullscreen` 模式新增
      `tuneInDirectPlay: Flow<Boolean>` / `saveTuneInDirectPlay` /
      `KEY_TUNEIN_DIRECT_PLAY` / `DEFAULT_TUNEIN_DIRECT_PLAY = false`。
- [x] `data/source/RadioSource.kt`：`resolveStream` 签名加
      `directPlay: Boolean = false`。
- [x] `data/source/GatewaySource.kt`：`resolveStream` 实现里，`directPlay` 为真且
      `dto.directUrl` 非空时优先用它，否则回退现有 `dto.url.ifEmpty { channel.playUrlLow }`
      链路。
- [x] `ui/RadioViewModel.kt`：
      - `RadioUiState` 加 `tuneInDirectPlay: Boolean = UserPreferences.DEFAULT_TUNEIN_DIRECT_PLAY`。
      - 仿 `autoFullscreen` 订阅（`RadioViewModel.kt:667` 附近）新增对
        `prefs.tuneInDirectPlay` 的收集，写回 `_uiState`。
      - 新增 `onToggleTuneInDirectPlay(enabled: Boolean)`，调用
        `prefs.saveTuneInDirectPlay`（仿 `onToggleAutoFullscreen`）。
      - `playLiveStream`（`RadioViewModel.kt:442` 附近）调用
        `resolveStream(channel, directPlay = uiState.value.tuneInDirectPlay)`。
- [x] `ui/SettingsScreen.kt`：加 `tuneInDirectPlay` / `onToggleTuneInDirectPlay` 参数，
      新增一行 `ToggleSettingRow`（文案见 design.md）。
- [x] `ui/RadioScreen.kt`：`SettingsScreen(...)` 调用点透传新增两个参数。
- [x] 单测：新增/扩展 `GatewaySource` 相关测试（可参照
      `app/src/test/java/cn/radio/tv/data/source/GatewayByIdsTest.kt` 的 `FakeGatewayApi`
      写法），覆盖：
      - `directPlay=true` + `directUrl` 非空 → 使用 `directUrl`。
      - `directPlay=true` + `directUrl` 为空（旧网关）→ 回退 `dto.url`/`playUrlLow`，
        与 `directPlay=false` 结果一致（回归验证）。
      - `directPlay=false` → 无视 `directUrl`，即便非空也不用（现状不变）。

## 阶段三：语义反转 + 按来源展示（2026-08-05 需求变更）

默认态改为直连，开关反转为「TuneIn 代理」（开 = 走透传），且只在选中 TuneIn 来源时展示、
位置移到「电台来源」下方。功能未对外上线，DataStore key 直接改名、不做迁移。

- [x] `UserPreferences`：`tuneInDirectPlay`/`saveTuneInDirectPlay`/`KEY_TUNEIN_DIRECT_PLAY`
      (`tunein_direct_play`)/`DEFAULT_TUNEIN_DIRECT_PLAY` → `tuneInProxy`/`saveTuneInProxy`/
      `KEY_TUNEIN_PROXY`（`tunein_proxy`）/`DEFAULT_TUNEIN_PROXY = false`。
- [x] `RadioSource`/`BaseRadioSource`/`GatewaySource`：参数 `directPlay` → `useProxy`
      （默认仍 `false`，但含义变为直连）；取直连地址的条件 `directPlay &&` → `!useProxy &&`。
- [x] `RadioViewModel`：状态字段 `tuneInProxy`、订阅 `prefs.tuneInProxy`、
      `setTuneInProxy`、起播传 `useProxy = _uiState.value.tuneInProxy`。
- [x] `RadioScreen`：调用点参数改名。
- [x] `SettingsScreen`：参数改名；开关移到 `SourceDropdown` 之后，包在
      `if (selectedSource == RadioSourceType.TUNEIN)` 内；标题「TuneIn 代理」、
      副标题「默认直连电台源；开启后经服务器中转，适合直连不通的网络环境」。
- [x] `GatewayApi.StreamDto.directUrl` 注释改为「默认（代理开关关闭）用它」。
- [x] 单测同步反转：`GatewayStreamTest` 六个用例按新语义重写（默认/显式 false = 直连，
      `useProxy = true` = 透传）；两个 Fake source 的 override 签名改名。

## 验证

- [x] `./gradlew testDebugUnitTest`（或项目既有单测命令）全绿。
- [x] lint 0 error。
- [x] `assembleDebug` 通过。
- [ ] 真机/模拟器验收：
      - 开关默认关闭，行为与当前线上版本一致。
      - 开关打开后起播一个 TuneIn HLS 台 + 一个 progressive 台，均可正常播放
        （抓包或 logcat 确认请求未经过 `radio.hku.wtf/proxy`）。
      - 模拟旧网关（本地临时把服务端 `directUrl` 改回空，或直接对照未部署阶段一的环境）：
        开关打开也不影响播放，静默回退代理地址。

## 执行记录（与计划的偏差）

- **`gateway_test.go` 的 tunein 断言换了形式**：原计划「断言 `source=tunein` 响应含非空
  `directUrl`」需要打上游（该文件现有用例正是为此刻意避开 tunein 分支）。改为两条本地可跑的：
  `qingting`/`yunting` 的 `directUrl` 必须为空（防止未来误给非 TuneIn 来源塞地址），
  加 `TestWriteStreamDirect` 直测下发形状（代理地址与直连地址是两个独立字段）。
- **tunein 非空下发改用本地起服务实测**（`LISTEN_ADDR=:18080` + curl），两类台各验一个：
  - progressive：`s355090` → `https://playerservices.streamtheworld.com/.../WYKYFM.mp3`
  - HLS：`s20277`（BBC）→ `https://open.live.bbc.co.uk/mediaselector/...`
  BBC 那条是 302 重定向端点且路径无 `.m3u8` 后缀 —— 客户端已按 `stream.isHls` 显式传
  `MimeTypes.APPLICATION_M3U8`，且 `RadioPlayer` 的 `DefaultHttpDataSource` 已开
  `setAllowCrossProtocolRedirects(true)`，两处都不需要为直连额外改。
- **lint 与改动前基线逐条比对一致**（IconDuplicates×5 / UnusedResources×4 /
  ModifierParameter×3 / OldTargetApi / ExportedService / ConstantLocale），0 新增。
- **服务端已于 2026-08-05 部署上线**（项目所有者确认）。
- **未完成**：真机验收（见下方未勾选项）。本机无 adb 设备，客户端网关地址硬编码
  `https://radio.hku.wtf/`（`NetworkModule.kt:17`），这几条留给项目所有者在真实设备上过一遍。
- **阶段三改了验收口径**：默认态即直连，所以「不经过 `/proxy/`」变成默认行为的回归项，
  而「走 `/proxy/`」变成开开关后的验证项，prd.md 验收清单已按此重写。

## 回滚点

- 客户端：`resolveStream` 参数带默认值、`StreamDto.directUrl` 默认空 —— 单独回退
  客户端改动（去掉设置项 UI）不影响服务端，也不影响老逻辑。
- 服务端：摘掉 `directUrl` 下发（或不部署）即可让所有客户端（无论开关状态）
  自动回到现状代理链路，无需协调发布顺序。
