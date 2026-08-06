# 技术设计：TuneIn 代理开关

## 边界与契约

只新增字段、新增设置项，不改变任何现有契约的语义：

- `/v1/stream` 响应新增 `directUrl`（字符串，可空）。只有 `source=tunein` 且解析成功时非空；
  其余来源固定为空字符串。旧客户端不认识该字段会被 JSON 解码器忽略，行为不变。
- 客户端 `RadioSource.resolveStream` 接口签名变化仅加一个**带默认值**的参数，
  不破坏未来任何潜在实现方（目前只有 `GatewaySource` 一个实现）。

## 服务端改动（`radio-proxy`，未纳入版本控制，本机 + 部署单独同步）

### `gateway.go: handleV1Stream`

现状（`gateway.go:352-361`）：

```go
case "tunein":
    _, hls, err := resolveStreamURL(contentID)
    if err != nil {
        writeErr(w, err.Error())
        return
    }
    writeStream(w, proxyPublicBase+"/proxy/"+contentID, hls)
```

`resolveStreamURL` 已经把真实上游地址解出来了，只是被 `_` 丢弃。改为接住它，
一并下发：

```go
case "tunein":
    stream, hls, err := resolveStreamURL(contentID)
    if err != nil {
        writeErr(w, err.Error())
        return
    }
    writeStreamDirect(w, proxyPublicBase+"/proxy/"+contentID, stream, hls)
```

`qingting` / `yunting` 分支继续走原 `writeStream`（`directUrl` 留空，两者播放地址本就非代理）。

### `writeStream` 扩展

```go
func writeStream(w http.ResponseWriter, url string, hls bool) {
    writeStreamDirect(w, url, "", hls)
}

func writeStreamDirect(w http.ResponseWriter, url, directURL string, hls bool) {
    st := streamTypeProgressive
    if hls {
        st = streamTypeHLS
    }
    writeOK(w, map[string]string{"url": url, "directUrl": directURL, "streamType": st})
}
```

保留 `writeStream` 签名不变，`qingting`/`yunting` 调用点不用改，降低本轮 diff 面积。

### 零额外成本确认

`resolveStreamURL` 内部有 6h 缓存（`urlCache`），本来就会在 `/v1/stream` 阶段调一次
（注释：`gateway.go:354` "这一跳(约 1.15s)本来就发生在 /proxy 内部，提前到这里后 /proxy
直接命中 urlCache"）。这里只是多传一个已经在手的字符串，不产生新请求。

签名 HLS 地址（`isSignedHLS`）不缓存、每次 `/v1/stream` 现取——与现状一致，
`directUrl` 天然也是"当次有效"的最新地址，不存在额外的过期问题。

## 客户端改动

### `GatewayApi.kt`：`StreamDto` 加字段

```kotlin
data class StreamDto(
    val url: String = "",
    val directUrl: String = "",
    val streamType: String = "progressive",
)
```

默认值 `""` 保证旧网关部署（无 `directUrl` 字段）反序列化不炸，等价于"未下发"。

### `data/prefs/UserPreferences.kt`：新增全局开关

照抄 `autoFullscreen` 的既有模式（同文件 41-57 行），新增一组同构的
`tuneInProxy: Flow<Boolean>` / `saveTuneInProxy` / `KEY_TUNEIN_PROXY`
/ `DEFAULT_TUNEIN_PROXY = false`（关 = 直连）。存储上是全局项，不按来源隔离（其余来源
用不上，但没必要为它单独设计"仅一个来源用"的存储形状）；是否展示由 UI 层按来源决定。

### `data/source/RadioSource.kt` / `GatewaySource.kt`：`resolveStream` 加参数

```kotlin
// RadioSource.kt
suspend fun resolveStream(channel: Channel, useProxy: Boolean = false): ResolvedStream
```

默认值 `false` = 直连，与设置项默认值一致。

`GatewaySource.resolveStream`（`GatewaySource.kt:139-152`）改动点：

```kotlin
override suspend fun resolveStream(channel: Channel, useProxy: Boolean): ResolvedStream =
    withContext(Dispatchers.IO) {
        val fallback = ResolvedStream(channel.playUrlLow, isHls = false)
        runCatching {
            val dto = api.getStream(type.key, channel.contentId).data
                ?: return@runCatching fallback
            val direct = dto.directUrl.takeIf { !useProxy && it.isNotEmpty() }
            ResolvedStream(
                url = direct ?: dto.url.ifEmpty { channel.playUrlLow },
                isHls = dto.streamType == STREAM_TYPE_HLS,
            )
        }.getOrDefault(fallback)
    }
```

`useProxy` 对非 tunein 来源天然是 no-op：`qingting`/`yunting` 的 `dto.directUrl`
服务端本就固定回空串，`direct` 恒为 null，走原有 `dto.url` 分支——不需要在客户端
按 `type == TUNEIN` 做条件判断，服务端的空值约定已经把这层判断收掉了。

### `ui/RadioViewModel.kt`：接线

- 构造期读 `private val prefs = UserPreferences(app)`（已存在）。
- 仿照 `autoFullscreen` 的订阅（`RadioViewModel.kt:667-668`），新增
  `prefs.tuneInProxy.distinctUntilChanged().collect { ... _uiState.update { it.copy(tuneInProxy = it) } }`，
  `RadioUiState` 加一个字段（仿 `autoFullscreen: Boolean`，`RadioViewModel.kt:78-79`）。
- 新增 `setTuneInProxy(enabled: Boolean)`，调用 `prefs.saveTuneInProxy`
  （仿 `setAutoFullscreen`/`saveAutoFullscreen` 现有写法）。
- `playLiveStream`（`RadioViewModel.kt:442-454`）起播调用处传入开关当前值：
  `sources.getValue(source).resolveStream(channel, useProxy = uiState.value.tuneInProxy)`
  ——直接读 `_uiState`/`uiState` 当前值即可，不需要额外传参链路。

### `ui/SettingsScreen.kt` / `ui/RadioScreen.kt`：UI

- `SettingsScreen` 加入参数 `tuneInProxy: Boolean` + `onToggleTuneInProxy: (Boolean) -> Unit`，
  紧跟在 `SourceDropdown`（电台来源）之后，且**仅在 `selectedSource == RadioSourceType.TUNEIN`
  时展示**：

  ```kotlin
  if (selectedSource == RadioSourceType.TUNEIN) {
      Spacer(modifier = Modifier.height(16.dp))
      ToggleSettingRow(
          title = "TuneIn 代理",
          subtitle = "默认直连电台源；开启后经服务器中转，适合直连不通的网络环境",
          checked = tuneInProxy,
          onToggle = { onToggleTuneInProxy(!tuneInProxy) },
      )
  }
  ```

  与自动播放/自动全屏这类全局项不同，本项只对一个来源有意义，藏起来比在副标题里解释
  生效范围更省认知；值仍存在全局 key 上，切来源来回横跳时状态不丢。

- `RadioScreen.kt:358` 的 `SettingsScreen(...)` 调用点透传新增的两个参数，
  与 `autoFullscreen`/`onToggleAutoFullscreen` 传法一致。

## 数据流（直连模式 = 开关关闭，默认态）

```
APP 起播 TuneIn 电台
  → GatewaySource.resolveStream(channel, useProxy=false)
  → GET /v1/stream?source=tunein&contentId=s355090
  → 服务端 resolveStreamURL 命中/回填 urlCache，同时下发 { url: 代理地址, directUrl: 真实上游地址, streamType }
  → 客户端取 directUrl（非空）→ ResolvedStream(url=directUrl, isHls=...)
  → ExoPlayer 直接连上游地址播放，不再经过 radio.hku.wtf/proxy
```

失败路径：`directUrl` 上游不可达 → ExoPlayer 报播放错误 → 走现有错误提示 UI，
**不**在此处捕获后改用代理地址重试（PRD 已明确本轮不做自动降级）。

## 兼容性与回滚

- 纯增量：不改 `/proxy/`、`/seg`、HLS 改写逻辑；`resolveStream` 新参数带默认值。
- 旧网关（未部署本次改动）→ `directUrl` 字段不存在 → `StreamDto` 默认值 `""` →
  `direct` 恒为 null → 开关形同虚设、自动回退现状代理链路。**必须**作为验收项验证
  （PRD 已列）。
- 回滚 = 服务端摘掉 `directUrl` 下发（或直接不部署）+ 客户端把设置项拿掉 /
  `resolveStream` 参数恢复默认，两侧任一单独回滚都不影响另一侧正常运行。

## 自检

- 服务端：`gateway_test.go` 已覆盖 `/v1/stream` 类型下发（`gateway_test.go:50` 起），
  加一个断言 `source=tunein` 响应体含非空 `directUrl` 的用例。
- 客户端：`GatewaySource` 相关单测补 `useProxy=false` 时优先取 `directUrl`、
  `directUrl` 为空时回退 `dto.url` 两个分支；`useProxy=true` 时无视 `directUrl`
  （回到透传链路）。
