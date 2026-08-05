# 技术设计：节目单与回放

## 一、接口实测结论（探针已验证）

### 蜻蜓FM（`rapi.qtfm.cn`，无签名，现有 `qingTingClient`）
1. 节目单：`GET v3/channels/{cid}/playbills?start={ms}&end={ms}`
   → `{errcode, data:[{id, start_time, end_time, duration, title, program_id, ...}]}`；`data` 是**扁平**列表，按 `start_time` 自行分天。
2. 回放地址：`GET v3/channels/{cid}/playbills/{节目id}/replay_program`
   → `{errcode, data:{resources:[{bitrate, aac_play_url, m4a_play_url}]}}`；取 `bitrate` 最大项的 `aac_play_url`。**仅已播出节目有 resources**（未播出返回空/错误）。

### 云听（`ytmsout.radio.cn`，复用现有 `SignInterceptor`，现有 `yunTingClient`）
- 节目单：`GET web/appProgram/listByDate?broadcastId={cid}&date=yyyy/MM/dd`
  → `{code, message, data:[{programName, startTime, endTime, playUrlHigh, playUrlLow, playFlag, enableStatus, columnEnableStatus, ...}]}`。
- ⚠️ 关键更正：需求描述里的 `contentBiz/v1/appProgram/listByDate` 用现有 key 验签**失败**；真正 Web 路径是 `web/appProgram/listByDate`，签名方案与现有 `web/*` 完全一致（来源：云听 PC 端 `radio.cn/pc-portal/js/api.js`，同 key `f0fc4c66…`）。
- 签名细节已由 `SignInterceptor` 覆盖：两参数按 key 字母序（`broadcastId` < `date`），`date` 用**解码后**的 `yyyy/MM/dd`（斜杠）。实测 `code=0` 成功。
- 回放判据：`playFlag==1` 且 `playUrlHigh` 非空；`playUrlHigh` 即回放地址（无需二次请求）。

## 二、共享模型（`data/model/Models.kt`）

新增业务模型 `Program`：

```kotlin
@Serializable
data class Program(
    val id: String,            // 源内节目 id（蜻蜓按需解析回放用；云听可为空）
    val title: String,
    val startTime: Long,       // epoch ms
    val endTime: Long,
    val canReplay: Boolean,    // 是否展示回放图标
    val replayUrl: String = "",// 已知回放地址（云听直接给）；空且 canReplay 时需 resolve（蜻蜓）
)
```

- 云听映射：`canReplay = playFlag == 1 && playUrlHigh.isNotBlank()`，`replayUrl = playUrlHigh`。
- 蜻蜓映射：`canReplay = endTime <= now`（已播出），`replayUrl = ""`（点击时解析）。
- **判据均可本地计算，无需为「是否显示图标」发起 per-node 请求**（避免 192 次额外请求）。

## 三、数据源契约（`data/source/RadioSource.kt`）

在 `RadioSource` 接口新增两方法：

```kotlin
/** 取某电台某一天（[dayStartMillis]=当地 00:00 的 epoch ms）的节目单。 */
suspend fun fetchPlaybill(channel: Channel, dayStartMillis: Long): List<Program>

/** 解析回放地址：已带地址(云听)直接返回；蜻蜓按需二次请求 replay_program。默认返回 program.replayUrl。 */
suspend fun resolveReplayUrl(channel: Channel, program: Program): String
```

`BaseRadioSource` 给 `resolveReplayUrl` 默认实现：`return program.replayUrl`（云听走默认；蜻蜓覆盖）。

- **YunTingSource**：`fetchPlaybill` → `api.getPrograms(broadcastId=channel.contentId, date=fmt(dayStartMillis))` 映射为 `Program`。`resolveReplayUrl` 用默认。
- **QingTingSource**：`fetchPlaybill` → `api.getPlaybills(cid, start=dayStartMillis, end=dayStartMillis+86_400_000)`，映射为 `Program`（`canReplay = endTime <= now`）。覆盖 `resolveReplayUrl` → `api.getReplay(cid, program.id)` 取最高码率 `aac_play_url`。

日期格式化 `fmt(ms)`：`SimpleDateFormat("yyyy/MM/dd")`（设备本地时区），供云听 date 参数。

## 四、网络层（API + DTO）

### `QingTingApi.kt` 新增
```kotlin
@GET("v3/channels/{cid}/playbills")
suspend fun getPlaybills(@Path("cid") cid: String, @Query("start") start: Long, @Query("end") end: Long): QtResponse<List<QtPlaybill>>

@GET("v3/channels/{cid}/playbills/{pid}/replay_program")
suspend fun getReplay(@Path("cid") cid: String, @Path("pid") pid: String): QtResponse<QtReplay>
```
DTO：
```kotlin
@Serializable data class QtPlaybill(val id: Long, @SerialName("start_time") val startTime: Long = 0, @SerialName("end_time") val endTime: Long = 0, val title: String = "")
@Serializable data class QtReplay(val resources: List<QtReplayRes> = emptyList())
@Serializable data class QtReplayRes(val bitrate: Int = 0, @SerialName("aac_play_url") val aacPlayUrl: String = "")
```

### `RadioApi.kt`（云听）新增
```kotlin
@GET("web/appProgram/listByDate")
suspend fun getPrograms(@Query("broadcastId") broadcastId: String, @Query("date") date: String): ApiResponse<List<YtProgram>>
```
DTO：
```kotlin
@Serializable data class YtProgram(val programName: String = "", val startTime: Long = 0, val endTime: Long = 0, val playUrlHigh: String = "", val playFlag: Int = 0)
```
（`SignInterceptor` 已对该 client 生效，两 query 参数会被自动排序签名——无需改动签名逻辑。）

## 五、ViewModel（`RadioViewModel` / `RadioUiState`）

`RadioUiState` 新增：
```kotlin
val showPlaybill: Boolean = false,
val playbillDates: List<PlaybillDate> = emptyList(),   // 9 天，含 dayStartMillis + label（进入时计算一次）
val selectedPlaybillDate: Long = 0L,                   // 选中日的 dayStartMillis
val playbillPrograms: List<Program> = emptyList(),
val isLoadingPlaybill: Boolean = false,
val playbillError: String? = null,
```
`PlaybillDate(val dayStartMillis: Long, val label: String)`：label = 今天 / 明天 / 昨天 / 否则 `M-d 周X`。9 天 = 今天偏移 −7..+1。

VM 方法：
- `togglePlaybill()`：无 `currentChannel` 时忽略。开：计算 `playbillDates`，`selectedPlaybillDate=今天`，`showPlaybill=true`，`loadPlaybill(今天)`；关：`showPlaybill=false`。
- `selectPlaybillDate(dayStart)`：更新选中并 `loadPlaybill(dayStart)`。
- `loadPlaybill(dayStart)`（private）：`isLoadingPlaybill=true`；`sources[playingSource].fetchPlaybill(currentChannel, dayStart)`；写入 `playbillPrograms`；失败写 `playbillError`。用自增 token 防日期快切竞态（旧结果丢弃）。
- `playReplay(program)`：`viewModelScope.launch { val url = sources[playingSource].resolveReplayUrl(currentChannel, program); if (url.isBlank()) return; playUrl(url, title=program.title, artist=currentChannel.title, art=currentChannel.image); loadedUrl = url }`。

### 回放播放接管（复用 MediaController 通路）
现有 `playNow(channel)` 固定用 `channel.playUrlLow` 建 `MediaItem`。抽出通用 `playUrl(url, title, artist, artUri)`：
```kotlin
private suspend fun playUrl(url: String, title: String, artist: String, art: String) {
    val c = controller()
    c.setMediaItem(MediaItem.Builder().setUri(url).setMediaMetadata(
        MediaMetadata.Builder().setTitle(title).setArtist(artist)
            .setArtworkUri(art.takeIf { it.isNotBlank() }?.let(Uri::parse)).build()).build())
    c.prepare(); c.play()
}
```
`playNow(channel)` 改为调用它。回放时 `title=节目名, artist=电台名`。

**`togglePlayPause` 竞态**：现逻辑 `if (loadedUrl != channel.playUrlLow) 重新播直播`。回放后 `loadedUrl=回放url`，若直接沿用会导致按暂停后再播跳回直播。改法（懒）：暂停/继续只看控制器状态——`if (loadedUrl == null) 首次加载直播 else { if (c.playWhenReady) c.pause() else c.play() }`。即 `loadedUrl` 仅表示「是否已加载过任意媒体」，播直播和播回放都会置位；直播的「记忆但不自动播放」首次加载仍由 `loadedUrl == null` 触发。切台/切回放各自 `setMediaItem` 重载，不受影响。

> 说明：回放不改 `currentChannel`（仍指向电台，星标/来源不乱），面板「正在播放节目」通过媒体元数据 + 可选的一个 `nowPlayingProgramTitle` 状态展示；最简做法是回放时把面板副标题临时显示节目名（记一个 `playingProgramTitle: String?`，非空则 PlayerPanel 副标题用它）。

## 六、UI

### PlayerPanel（`ui/components/PlayerPanel.kt`）
- 新增 `PlaybillButton`（仿 `SleepTimerButton`：44dp 圆钮、`focusableChrome`、聚焦放大、图标「📅」或列表图标）。放在播放/暂停键**右侧**：
  - 竖排布局：`Box` 内以 `offset(x = +(36+16+22).dp)` 贴在 `PlayPauseButton` 右侧（睡眠键在左，节目单键在右，对称）。
  - 横排布局：`PlayPauseButton` 之后再加一个（`Spacer` + `PlaybillButton`）。
- 新增入参：`showPlaybill`、`onTogglePlaybill`、`playbill*` 相关（dates/programs/selectedDate/isLoading/error 回调 `onSelectDate`、`onPlayReplay`）、`playingProgramTitle`。
- 竖排：封面 `Box` 内容按 `showPlaybill` 切换——false 显示封面/📻，true 显示 `PlaybillContent`（两列）。用 `AnimatedContent`/`Crossfade` 过渡（可选）。
- 横排（竖屏）：无大封面区，`showPlaybill` 时以全屏 `Dialog` 覆盖显示 `PlaybillContent`（复用 `SleepTimerOverlay` 的 Dialog + 自绘遮罩 + 渐隐模式，见 `spec/frontend/compose-ui-guidelines.md`）。

### PlaybillContent（新 composable，两列）
- 左列（约 84–96dp 宽）：`LazyColumn` 列出 `playbillDates`，选中项高亮（金色 `Accent`），点击/遥控 OK → `onSelectDate(dayStartMillis)`。遥控上下键在此列移动。
- 右列（`weight(1f)`）：
  - `isLoadingPlaybill` → `LoadingIndicator`（复用现有）。
  - `playbillError != null && programs 空` → 占位文案。
  - programs 空 → 「暂无节目单」。
  - 否则 `LazyColumn` 列出节目：一行 = 起止时间（`HH:mm-HH:mm`）+ 节目名（`weight(1f)`，省略号）+ 若 `canReplay` 右侧一个可聚焦「▶」回放图标（点击 `onPlayReplay(program)`）。整行或图标可聚焦，遵循现有 `focusableChrome` 风格。

### RadioScreen（`ui/RadioScreen.kt`）
- 两处 `PlayerPanel(...)` 调用补齐新入参，透传 `state.showPlaybill / playbillDates / ...` 与 `viewModel::togglePlaybill / selectPlaybillDate / playReplay`。
- 返回键：节目单打开时优先关闭它（在现有 `BackHandler` 链最前面加 `if (state.showPlaybill) { viewModel.togglePlaybill(); return@BackHandler }`）。

## 七、时间/时区

- `dayStartMillis`：用 `Calendar`（本地时区）把某天归零到 00:00:00。
- 9 天集合：以「今天 00:00」为基准，偏移 `-7..+1` 天（`Calendar.add(DAY_OF_YEAR, offset)`）。
- 蜻蜓 end = start + 86_400_000（跨夏令时可忽略，国内无 DST）。
- 云听 date 字符串 = `SimpleDateFormat("yyyy/MM/dd", Locale.getDefault())` 对 dayStartMillis 格式化。

## 八、跨层/复用检查（对照 guides）

- 新增业务字段集中在 `Program` 一个模型，DTO→模型映射各留在各自 `*Source`（与现有 `Channel` 映射一致），UI 只吃 `Program`，不直接碰原始 payload 字段。
- 复用：`LoadingIndicator`、`focusableChrome`、`SleepTimerOverlay` 弹层模式、`Accent` 高亮色、`controller()`/MediaController 播放通路。
- 唯一新网络 client 需求？无——两来源各自复用既有 client（含云听签名拦截器）。

## 九、风险与取舍

- 蜻蜓「已播出=可回放」为启发式：极个别节目可能无 resources；`resolveReplayUrl` 返回空时静默不播（可加一次 toast，非必须）。
- 云听 date 依赖设备时区正确；TV 盒子时区异常会取错日期——超出本次范围。
- 回放不做进度条/拖动，仅播放/暂停（YAGNI，用户未要求）。
