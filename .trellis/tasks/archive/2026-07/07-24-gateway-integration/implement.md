# 执行计划 — APP 接入统一网关

按依赖顺序推进:先加不删(新层可编译)→ 切换装配 → 视觉 → 删旧。每步后可 `./gradlew assembleDebug` 验证,保证任意中断点都能编译。

> 状态(2026-07-25 核对):阶段 A–E 代码全部落地并已提交(`ddc5c0f`),`assembleDebug` 通过。仅剩 B2/C2/D 的实机走查待用户在设备+网关上确认。

## 阶段 A:新增网关层(纯加法,旧路径不动)

- [x] A1. `RadioSourceType.kt`:加 `RADIOBROWSER("radiobrowser", "全球电台")`。确认 `scoped()` 收藏 key 后缀逻辑对新枚举自动生效(无硬编码 when)。
- [x] A2. `Models.kt`:`Channel` 加 `countryCode: String = ""`(默认空,云听/蜻蜓不受影响)。
- [x] A3. 新增 `remote/GatewayApi.kt`:Retrofit interface,5 端点对齐 design 的契约。`/v1/replay` 的 `ReplayDto` 先在同文件内定义(决策待确认项 2)。
- [x] A4. `NetworkModule.kt`:加 `GATEWAY_BASE_URL`(`https://radio.hku.wtf/`)、`gatewayClient`(普通 OkHttp + debug 日志,无签名)、`gatewayApi`。**此步只加不删。**
- [x] A5. 新增 `data/source/GatewaySource.kt`:继承 `BaseRadioSource`,构造持 `type: RadioSourceType` + `gatewayApi`。实现 fetchProvinces/categories/channels/playbill,每请求带 `source=type.key`。
  - `fetchPlaybill`:`type == RADIOBROWSER` 直接返回空列表(短路)。
  - `resolveReplayUrl`:非蜻蜓返回 `program.replayUrl`;蜻蜓调 `/v1/replay`。
  - `defaultProvinceCode`:按 type 返回(蜻蜓 407,全球 US,云听沿用)。

**校验**:`./gradlew assembleDebug` —— 已通过。

## 阶段 B:切换装配(旧层仍保留,便于回滚)

- [x] B1. `RadioViewModel.kt`:`sources = RadioSourceType.entries.associateWith { GatewaySource(it, gatewayApi) }`。路由 `sources.getValue(type)` 零改动。
- [~] B2. 实机验证三来源(**待用户在设备+网关上跑**):筛选项/列表/直播流;云听/蜻蜓节目单+回放;切源刷新;全球电台空节目单不崩。
  - 对应验收标准 1/2/3。风险 1(蜻蜓直播地址)、风险 2(云听回放)在此实测。

**校验**:装 debug 包,遥控走查三来源 + 切换。

## 阶段 C:设置页来源下拉

- [x] C1. `SettingsScreen.kt`:仿 `CityDropdown` 家族做 `SourceDropdown`(`:135` 调用、`:263` 定义),替换旧 `SourceSelectRow`/`SourcePill`。`onSelectSource` 签名不变。
- [~] C2. TV D-pad 走查(**待实机**):展开/上下选/确认收起/焦点回锚,与城市下拉一致(验收标准 4)。

**校验**:遥控操作来源下拉。

## 阶段 D:全球电台空封面视觉(决策 5)

- [x] D1. 国旗 URL 由 `Models.kt` 扩展属性提供:`Channel.flagUrl`(有 countryCode 拼 `flagcdn.com/w320/{cc}.png`,否则空串)+ `Channel.displayImageUrl`(封面优先,空退国旗)。播放器/列表共用。
- [x] D2. `ChannelCard.kt:79-87`:图源改吃 `displayImageUrl`,全球电台有国家码即显国旗,都空则走既有兜底。
- [x] D3. `PlayerPanel.kt:1122`:`visualImageUrl()` 包装 `displayImageUrl`,前景方块/全屏模糊背景/提色统一走它;都空 → 现有 `📻` + 深色兜底不动。
- [~] D4. 视觉走查(**待实机**):全球电台进全屏播放器,确认国旗模糊背景与提色合理;无国家码电台退回深色 + 📻。

**校验**:见 D4。

## 阶段 E:删旧(验证通过后)

- [x] E1. 删 `SignInterceptor.kt`、`YunTingSource.kt`、`QingTingSource.kt`。
- [x] E2. 删 `RadioApi.kt`、`QingTingApi.kt`。
- [x] E3. `NetworkModule.kt`:删 `yunTingClient`/`qingTingClient`/`yunTingApi`/`qingTingApi` 及旧 base url,保留 `yecaoApi`。
- [x] E4. 全量搜残留引用(旧 base url、`SignInterceptor`、`SIGN_KEY`)—— 零命中,验收标准 5 通过。

**校验**:`assembleDebug` 通过;三来源冒烟 + 收藏读取(验收标准 6)随 B2 一并实机确认。

## 回滚点

- 阶段 A/C/D 独立可回退(git)。
- 阶段 B/E 已提交且旧 Source 已删,回滚需 `git revert ddc5c0f`。

## 待确认项(design 决策 4/5 遗留)

1. RadioScreen 空节目单现有呈现 → 决定全球电台隐藏入口还是空态(实机 B2 时看)。
2. ~~`ReplayDto` 独立类 vs 复用现有类型~~ → 已在 `GatewayApi.kt` 内定义。
3. 网关返回的蜻蜓直播、云听回放地址实测可播性(B2)。
4. ~~蜻蜓/全球 `defaultProvinceCode`~~ → 蜻蜓 407、全球 `ccToProvinceCode("US")`,实机复核。
