# 技术设计 — APP 接入统一网关

## 现状与目标

现状:APP 有两套官方接口(`RadioApi`/`QingTingApi`)、三套 OkHttpClient、云听签名拦截器,两个 `*Source` 各自映射到共享模型,ViewModel 用 `sources: Map<RadioSourceType, RadioSource>` 按源路由。

目标:所有来源经单一网关取数。网关**已在服务端把三来源归一成同一套 DTO 与响应格式**,因此客户端不再需要"每源一个适配器 + 每源一套 DTO"。

## 核心决策

### 决策 1:三个 Source 合并为一个 GatewaySource

网关对 `/v1/*` 的输出对所有来源都是同一套模型(`Channel`/`Program`/`Province`/`Category`)+ 同一响应包装。来源差异(签名、地址拼接、去"台"后缀、回放两段式)**已全部在服务端消化**。

所以客户端 `YunTingSource` / `QingTingSource` 合并为单个 `GatewaySource`,持有当前 `RadioSourceType`,每个请求带上 `source=<key>`。

- `RadioSource` 接口:**保留**(ViewModel 依赖它),但实现从 2 个变 1 个(按 source 参数化)。
- `BaseRadioSource`:并发收藏刷新、`currentProgramWindow` 等通用逻辑仍可复用,`GatewaySource` 继承它。
- 回放:云听回放地址已随节目单返回(`Program.replayUrl` 直给);蜻蜓走网关 `/v1/replay` 解析;全球电台无回放。`GatewaySource.resolveReplayUrl` 按 source 分派:非蜻蜓直接返回 `program.replayUrl`,蜻蜓调 `/v1/replay`。

ponytail: 合并三源为一,删掉的比加的多。若将来某源要客户端特判逻辑,再从 GatewaySource 里拆分。

### 决策 2:sources map 的处理

`ViewModel` 现有 `sources: Map<RadioSourceType, RadioSource>` + 按 `selectedSource`/`playingSource` 路由。合并后所有 key 指向同一个 `GatewaySource` 实例(它内部按传入的 `type` 参数化请求)。

两个方案:
- **A(改动最小)**:保留 map 结构,三个 key 都映射到同一 `GatewaySource`,但 `GatewaySource` 的方法需要知道"当前是哪个 source"。由于 `RadioSource` 接口方法不带 source 参数,故让 map 装 `RADIOBROWSER→GatewaySource(RADIOBROWSER)` 等**三个轻量实例**(仅存一个枚举字段,共享同一个 gatewayApi)。ViewModel 路由代码零改动。
- B:改 ViewModel 路由为"单 source + 传参"。改动面大,牵动 playingSource/收藏分组多处。

选 **A**:`sources = RadioSourceType.entries.associateWith { GatewaySource(it, gatewayApi) }`。ViewModel 的 `sources.getValue(type)` 全部照旧,收藏分组、playingSource 路由零改动。三个实例只各持一个枚举,无额外成本。

### 决策 3:网络层收敛

`NetworkModule` 改动:
- 新增 `GATEWAY_BASE_URL`(集中一处,如 `https://radio.hku.wtf/`),单一 `gatewayClient`(普通 OkHttp,带 debug 日志,不含签名)。
- 新增 `GatewayApi`(Retrofit interface),端点对齐网关 `/v1/*`。
- **删除**:`SignInterceptor`、`yunTingClient`、`qingTingClient`、`RadioApi`、`QingTingApi`、两套 DTO(蜻蜓 DTO 在 `QingTingApi.kt` 内)。
- **保留**:`yecaoApi` 及其 client(更新检查,独立)。

### 决策 4:全球电台无 EPG / 回放

- `GatewaySource.fetchPlaybill`:source 为 `RADIOBROWSER` 时直接返回空列表(不打网关,省一次请求)。网关对该源也返回空,双保险取其一即可——客户端短路更省。
- UI 层:节目单区域已有"空列表"路径(需确认 RadioScreen 对空节目单的呈现)。若当前空列表会显示尴尬空态,则在该来源下隐藏节目单入口。**实现阶段确认 RadioScreen 现有空态行为后再定**。
- 回放入口:全球电台 `Program` 不会产生(无节目单),回放路径自然不可达,无需额外拦截。

### 决策 5:全球电台无封面 —— 视觉方案

radio-browser 的 `favicon` 大面积为空或死链、且分辨率极低,决定**全球电台一律不使用 favicon**。改用"国家"作为视觉线索,但国旗只出现在被弱化的位置,不铺列表。

**数据支撑**:给共享模型 `Channel` 新增 `countryCode: String = ""`(ISO 国家码,如 `cn`/`jp`)。云听/蜻蜓留空;全球电台由网关下发。APP 端按国家码拼国旗 CDN URL(如 `https://flagcdn.com/w320/{cc}.png`),CDN 与尺寸由客户端掌控,网关只给干净的 ISO 码。

各位置呈现:

| 位置 | 云听/蜻蜓 | 全球电台 |
|---|---|---|
| 列表图标(`ChannelCard`) | 封面 | **不渲染图标位**(纯文字行) |
| 迷你/竖排播放条 | 封面 | 国旗小图,无国家码则 `📻` |
| 全屏前景方块 | 清晰封面 | 国旗小图,无国家码则 `📻` |
| 全屏背景 | 封面模糊 | **国旗模糊色块**,无国家码则纯深色 |
| 强调色(进度条/播放键) | 封面提色 | **国旗提色**,无国家码则主题色 |

**关键**:全屏背景与强调色的国旗**复用现有管线**——`PlayerPanel.kt:858-899` 的模糊层(高版本 `Modifier.blur`,低版本 RenderScript)与 `rememberPlayerPalette`(64px 缩略图提色)都接受任意图 URL,只需把输入从 `channel.image` 换成"封面优先,空则国旗 URL"。国旗被 60dp 高斯糊成认不出形状的国家色块,不会有"国旗贴脸"的廉价感,视觉语言与其他来源一致。

**列表塌缩**:`ChannelCard`(`ChannelCard.kt:80-88`)现直接把 `image` 交给 `AsyncImage`,空串仅剩 `surfaceVariant` 底色。全球电台下需让图标位不占布局(而非留空洞)——实现阶段确认卡片布局,图标缺席时文字区补齐宽度。

**边界**:极少数电台连 `countryCode` 都没有 → 背景退回纯深色、前景退回 `📻`(现有兜底),可接受。

**改动集中在**:`Models.kt`(加字段)、`ChannelCard.kt`(列表塌缩)、`PlayerPanel.kt`(背景/前景/提色的图源改为"封面 or 国旗 URL"的解析函数)。国旗 URL 拼接逻辑抽一个小 helper(输入 countryCode,输出 URL),供播放器与播放条复用。

## GatewayApi 契约

```
GET v1/provinces?source=                             -> ApiResponse<List<Province>>
GET v1/categories?source=                            -> ApiResponse<List<Category>>
GET v1/channels?source=&provinceCode=&categoryId=    -> ApiResponse<List<Channel>>
GET v1/programs?source=&contentId=&date=yyyy/MM/dd   -> ApiResponse<List<Program>>
GET v1/replay?source=qingting&contentId=&programId=  -> ApiResponse<ReplayDto{replayUrl}> (仅蜻蜓)
```

- `ApiResponse<T>` 复用现有 `Models.kt` 定义(`code==0` 成功)。
- `Province`/`Category`/`Channel`/`Program` 复用现有共享模型,网关输出即对齐,**无需新 DTO**。
- `/v1/replay` 返回 `{replayUrl}`,需一个小 DTO 或复用 map 解析。

## RadioSourceType 变更

```kotlin
enum class RadioSourceType(val key: String, val displayName: String) {
    YUNTING("yunting", "云听"),
    QINGTING("qingting", "蜻蜓FM"),
    RADIOBROWSER("radiobrowser", "全球电台");
}
```

- `key` 直接作为网关 `source` 参数,与服务端一致。
- 新增项的 DataStore 收藏 key 用 `_radiobrowser` 后缀(`scoped()` 现有逻辑自动处理,无需改)。
- `defaultProvinceCode`:蜻蜓现为 407(网络台)。网关侧蜻蜓已可返回地区列表;全球电台默认用 `0`(全部/按热门)。`GatewaySource` 按 type 返回对应默认值。

## 设置页来源下拉(UI)

现状:`SourceSelectRow`/`SourcePill`(`SettingsScreen.kt:477-541`)一排 pill。
目标:仿 `CityDropdown` 家族(`SettingsScreen.kt:233-471`)做来源下拉。

复用 `DropdownAnchor` + `Popup` + `CityMenuItem` 三件套:
- 数据源:`provinces: List<Province>` → `RadioSourceType.entries`
- 当前值:`homeCityCode: Long` → `selectedSource: RadioSourceType`
- 显示文案:`province.provinceName` → `sourceType.displayName`
- `key`:`provinceCode` → `sourceType.key`
- 选中判定、`selectedIndex`、锚点回焦逻辑按枚举适配。

`onSelectSource: (RadioSourceType) -> Unit` 回调签名不变,`SettingsScreen` 对外参数不变。可抽一个通用下拉泛化 City/Source,但两处字段差异不大;ponytail: 先各自实现,若第三个下拉出现再抽泛型。

## 影响面清单

| 文件 | 改动 |
|---|---|
| `RadioSourceType.kt` | 加 `RADIOBROWSER` 项 |
| `NetworkModule.kt` | 加 `GatewayApi`/`gatewayClient`/base url;删签名与两官方 client/api |
| `remote/GatewayApi.kt` | 新增(替代 RadioApi/QingTingApi) |
| `SignInterceptor.kt` | 删除 |
| `RadioApi.kt` / `QingTingApi.kt` | 删除(DTO 若被别处引用需先确认) |
| `data/source/GatewaySource.kt` | 新增(合并 YunTing/QingTing Source) |
| `YunTingSource.kt` / `QingTingSource.kt` | 删除 |
| `RadioSource.kt` | 接口保留;`BaseRadioSource` 基本不动 |
| `RadioViewModel.kt` | `sources` 装配改一行;路由逻辑零改动 |
| `SettingsScreen.kt` | 来源 pill → 下拉;节目单空态(全球)确认 |
| `RadioScreen.kt` | 确认空节目单呈现;必要时按来源隐藏节目单入口 |

## 风险与回滚

- **风险 1:蜻蜓直播地址**。原客户端拼 `ls.qingting.fm/.../64k.m3u8`,网关已改为服务端拼好下发 `playUrlLow`。需实测网关返回的蜻蜓直播地址 APP 能播。
- **风险 2:云听节目单回放地址**。网关 `/v1/programs` 是否已带 `replayUrl`(= playUrlHigh)需实测。
- **风险 3:全球电台空节目单的 UI 呈现**。实现阶段先看 RadioScreen 现有空态。
- **回滚**:网络层改动集中,git 分支开发;保留旧 `*Source`/`*Api` 到验证通过再删,可快速回退。

## 待实现阶段确认

1. RadioScreen 对空节目单列表的现有呈现(决定全球电台是隐藏还是空态)。
2. `ReplayDto` 用独立类还是复用现有类型。
3. 网关返回的蜻蜓直播地址、云听回放地址的实测可播性。
4. 蜻蜓/全球电台的 `defaultProvinceCode` 在网关语义下的正确取值。
