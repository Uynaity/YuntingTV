# 技术设计：流类型显式下发

## 一、契约

### 端点

```
GET /v1/stream?source=<key>&contentId=<id>
```

响应沿用统一 `ApiResponse`（`model.go:14`，code==0 成功）：

```json
{"code":0,"data":{"url":"https://<gw>/proxy/s20277","streamType":"hls"}}
```

失败走 `writeErr`（code=-1 + message），与 `/v1/replay` 一致。

### streamType 枚举

| 值 | 含义 | 客户端映射 |
|----|------|-----------|
| `hls` | HLS 播放列表 | `MimeTypes.APPLICATION_M3U8` |
| `progressive` | 普通音频流/文件 | 不设 mimeType（留给后缀兜底与 sniff） |

字符串而非 bool：为 DASH 等未来类型留扩展位，且日志/抓包可读。
客户端遇到未知值按 `progressive` 处理（向前兼容：新服务端 + 旧客户端不至于崩）。

**为什么 progressive 不映射成具体 mime**：TuneIn 直链可能是 mp3/aac/ogg 任一，服务端的
`media_type` 不足以精确到容器（且实测有台字段与实际不符）。填错 mime 会让
`ProgressiveMediaSource` 用错 Extractor，比不填更糟——不填时 `DefaultExtractorsFactory`
会按内容 sniff，这是它擅长的。`RadioPlayer.kt:115-119` 的判据在 mime==null 时自动落到
后缀分支再落到渐进式，行为正确。

## 二、服务端

### 类型来源（按来源分）

| source | 类型判定 | 是否需上游调用 |
|--------|---------|---------------|
| `tunein` | `mediaType == "hls" \|\| isM3U8(url)` | 是（Tune.ashx，命中 urlCache 时免） |
| `qingting` | 固定 `hls`（`playUrlLow` 是 `.m3u8`，`source_qingting.go:135`） | 否 |
| `yunting` | 固定 `progressive`（上游给普通直链） | 否 |

云听/蜻蜓不查上游：它们的地址在列表阶段就已下发，`/v1/stream` 只是把「客户端已有的
url + 服务端已知的类型」组合返回。这让客户端能对三源走同一条代码路径，代价是两次冗余
往返——但省掉客户端的来源分支，值得。

**云听的坑**：`Channel.playUrlLow` 由上游 JSON 直接反序列化（`source_yunting.go:111`
整体解码），`/v1/stream` 拿不到它——客户端才有。故云听分支的 `url` 字段需要客户端把已有
地址传上来，或者服务端返回空 url 表示「沿用你手上的」。

**决定**：`url` 为空时客户端沿用 `channel.playUrlLow`。理由是避免为云听单独加一个
「按 contentId 反查地址」的上游调用（云听无此接口，只能拉整页列表再匹配，代价过大）。
这一点写进契约注释，是本设计里唯一的隐式约定，必须在两侧注释中明确。

### 改动点

1. **`source_tunein.go`**
   - `tuneInResolve` 返回值增加 `hls bool`：`(url string, hls bool, err error)`。
     判据 `first.MediaType == "hls" || isM3U8("", first.URL)`。
   - 注意 playlist 跳转分支（`:289-294`）：解出的真地址可能是 m3u8 而 `media_type` 报别的，
     故 hls 判定要在**跳转之后**对最终地址再算一次并集。
   - `cachedURL.hls` 字段赋值（激活死字段），`urlCache.set` 签名加 `hls` 参数。
   - `stationURLCache.get` 返回 `(url string, hls bool, ok bool)`。
   - `resolveStreamURL` 返回 `(url string, hls bool, err error)`。
     `handleProxy`（`handlers.go:84`）只用 url，忽略 hls。
2. **`gateway.go`**：新增 `handleV1Stream`，仿 `handleV1Replay`（`gateway.go:175`）的形状。
3. **`main.go`**：注册 `/v1/stream`，**不套 cachedHandler**（签名时效，同 `/v1/replay`）。
4. **`isM3U8` 复用**：现签名是 `isM3U8(contentType, rawURL)`（`handlers.go:184`），
   传空 contentType 即可只按后缀判，不必新增函数。

### 兼容性

- `/proxy/{id}` 行为完全不变，仍能独立工作（内部按响应 content-type 决定是否改写播放列表，
  `handlers.go:119`）。旧版 APP 不受影响。
- `Channel.playUrlLow` 不变，仍是 `/proxy/{id}`。

## 三、客户端

### 新值类型

`Models.kt` 增加：

```kotlin
@Serializable
data class StreamInfo(val url: String = "", val streamType: String = "progressive")
```

`streamType` 保持 String 而非 Kotlin enum：未知值不会让 kotlinx.serialization 抛异常
（enum 遇未知值默认抛），向前兼容更稳。映射到 mime 的地方做 when 分支，else → null。

### 分层职责

| 层 | 职责 |
|----|------|
| `GatewayApi` | 新增 `getStream(source, contentId): ApiResponse<StreamInfo>` |
| `RadioSource` | 新增 `suspend fun resolveStream(channel): ResolvedStream` |
| `BaseRadioSource` | 默认实现：不查网关，返回 `(channel.playUrlLow, progressive)` |
| `GatewaySource` | 覆盖：调 `/v1/stream`，url 为空则回退 `channel.playUrlLow`；整体失败回退默认 |
| `RadioViewModel` | 起播前调 `resolveStream`，把结果整体交给 `mediaItemOf` |
| `RadioPlayer` | 不变（mime 判据已就位） |

`ResolvedStream` 是客户端内部模型（`data class ResolvedStream(val url: String, val isHls: Boolean)`），
与网络 DTO `StreamInfo` 分开：DTO 用 String 容错，内部模型收敛成 Boolean 供播放器用。
字符串→布尔的映射只发生在 `GatewaySource` 一处。

### 失败降级

`/v1/stream` 请求失败（超时、网关挂）时**不阻断播放**：回退到
`ResolvedStream(channel.playUrlLow, isHls = false)`，行为等同当前版本。理由是「类型不确定」
不该比「完全放不出声」更严重；HLS 台此时仍会卡，但那是修复前的既有状态，不是回归。

### 6 个起播入口的收口

不在 6 处各调一次 `resolveStream`，而是让 `playNow(channel)` 内部调用，
其余入口只要都经由 `playNow` 即可。审查现状：

- `:268` `playNow` ← 本体
- `:440`、`:750`、`:852` 已调 `playNow`
- `:762` `togglePlayPause` 已调 `playNow`
- `:553` **例外**：它走 `controller.replaceMediaItem` 直接换 item（为了不打断播放只更新
  副标题），不经 `playNow`

`:553` 的处理：它重建 MediaItem 只为刷新 metadata，**url 和类型都没变**。正确做法是从
`controller.currentMediaItem` 取出现有的 `localConfiguration`（含 uri 与 mimeType）
原样带过去，只换 MediaMetadata。这比重新解析一次更对——避免为刷个副标题多打一次网关，
也天然不会丢类型。

`loadedUrl` 那 6 处赋值保持不变（它只用于判断「是否已起播过」，不参与派发）。

## 四、验证策略

- **Go 单测**（`tunein_test.go` 已存在）：加 `TestStreamTypeDecision` 覆盖 R2 四组合。
  纯函数判据抽成 `isHLSStream(mediaType, url string) bool` 便于直接测。
- **端点手测**：curl 四条（tunein hls / tunein 直链 / qingting / yunting）。
- **APP 实机**：验收标准 5-7。

## 五、回滚

服务端与客户端改动相互独立、都向后兼容：
- 只回滚客户端：网关多一个没人调的端点，无副作用。
- 只回滚服务端：客户端 `/v1/stream` 请求 404 → 走失败降级 → 退回当前行为。

故可分两次部署，先服务端后客户端。
