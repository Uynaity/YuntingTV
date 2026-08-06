# TuneIn HLS 类型识别：网关显式下发流类型

## 背景

TuneIn 部分电台（BBC 系全部）的直播源是 HLS，APP 起播后永远停在「缓冲中…Ns」。

根因是**流类型这个知识产生的时刻晚于客户端需要它的时刻**：

- `RadioPlayer.kt:111-122` 按「显式 mimeType 优先，其次 URI 后缀」选 MediaSource 工厂。
- `RadioViewModel.kt:254` 的 `mediaItemOf` 从不调用 `setMimeType`。
- TuneIn 的 `playUrlLow` 是 `<网关>/proxy/{guideId}`（`source_tunein.go:175`），无后缀，
  `Util.inferContentType` 一律返回 `CONTENT_TYPE_OTHER`。
- 于是**所有 TuneIn 台都走 progressiveFactory**。HLS 台拿到的是 m3u8 文本，渐进式
  解析器 sniff 全部失败 → 抛错 → 触发重试恢复机制（`RadioPlayer.kt:33-35`），
  UI 表现为「缓冲中…Ns」倒计时。

服务端其实一直知道类型：`tuneInResolve`（`source_tunein.go:272-277`）已解出 `media_type`
却丢弃；`cachedURL.hls`（`source_tunein.go:197`）是个从未被赋值的死字段，其注释写明
「供 /v1/stream 告诉客户端用哪种解析器」——原设计留了这个通路但没实现。

### 实测依据

`Tune.ashx?id=s20277`（BBC Radio 1Xtra）返回：

```
media_type=hls  bitrate=96  reliability=100  url=https://open.live.bbc.co.uk/mediaselector/...
```

关键事实：该 URL **路径不含 `.m3u8` 后缀**（mediaselector 重定向端点），所以纯靠 URL
后缀判据在服务端和客户端都判不出来，只有 `media_type` 字段知道。反向也存在
（`media_type` 报 aac 而地址是 m3u8），故判据必须是两者的并集。

## 目标

流类型作为**显式结构化字段**在网关契约中传递，由客户端映射成 `mimeType` 交给播放器，
使 HLS 台正确走 `hlsFactory`。

## 非目标

- **多候选流降级**不在本次范围。`Tune.ashx` 返回多条候选（不同码率 + `reliability` 打分），
  现在固定取首条（`source_tunein.go:285-286`），流断了不会切备份源。独立缺陷，单独开子任务。
- 不修 HLS 签名过期 403、上游地域封锁等其他「缓冲中」成因。类型判对只解决被误派到
  错误工厂这一类。

## 需求

### R1 网关显式下发流类型

新增 `GET /v1/stream?source=<key>&contentId=<id>`，返回：

```json
{"code":0,"data":{"url":"<网关>/proxy/s20277","streamType":"hls"}}
```

- `streamType` 是**字符串枚举**（`hls` / `progressive`），不是 bool。bool 只能表达
  「是/不是 HLS」，枚举为未来的 DASH 留位置且不必改字段名。
- 三个来源**都要**实现此端点，不做 TuneIn 特例：云听是普通直链（`progressive`）、
  蜻蜓是 `.m3u8`（`hls`），服务端本来就确定。统一走同一条路，客户端才不会长出
  `if (source == TUNEIN)` 分支——消除来源特例是本次该顺手做掉的事。
- TuneIn 分支复用现有 `resolveStreamURL`，把 `media_type` 一并存入 `cachedURL.hls`
  死字段，激活它。

### R2 服务端 HLS 判据取并集

判为 HLS 的条件：`mediaType == "hls" || isM3U8(url)`。两个条件都要，单靠任一个都会漏
（见实测依据）。

### R3 客户端按类型显式派发

- 起播路径统一先解析出「地址 + 类型」的值对，`mediaItemOf` 显式 `setMimeType`。
- 地址与类型作为**不可分的一个值**传递，避免出现只带了一个的状态。
- `RadioPlayer.kt:115-119` 的后缀兜底分支保留，作为「服务端漏填时不至于全挂」的防御，
  但正常路径永远走 mimeType，不依赖后缀。

### R4 覆盖所有起播入口

`RadioViewModel.kt` 现有 6 处用 `channel.playUrlLow` 起播，全部要走新路径：

| 位置 | 场景 |
|------|------|
| `playNow` / `:268` | 常规选台起播 |
| `:440` | 恢复上次播放（从持久化状态重建） |
| `:553` | 节目单刷新后 `replaceMediaItem` 重建 |
| `:750` | 收藏/列表点击起播 |
| `:762` | `togglePlayPause` 首次起播 |
| `:852` | `playLive` 从回放切回直播 |

漏任一处的表现是「首播正常、切个节目又卡住」，这是本需求最容易出错的地方。
`:440` 和 `:553` 尤其注意：它们从持久化状态/后台刷新重建 MediaItem，类型必须一起带上。

## 验收标准

1. `GET /v1/stream?source=tunein&contentId=s20277` 返回 `streamType: "hls"`。
2. `GET /v1/stream?source=tunein&contentId=s25231`（aac 直链台）返回 `streamType: "progressive"`。
3. `source=qingting` 返回 `hls`；`source=yunting` 返回 `progressive`。
4. Go 单测覆盖 R2 判据四种组合：`media_type=hls` + 无后缀、`media_type=aac` + `.m3u8`、
   两者皆是、两者皆非。
5. APP 实机播放 BBC Radio 1Xtra 出声，不再进入「缓冲中…Ns」倒计时。
6. APP 实机播放一个 TuneIn 非 HLS 台、一个蜻蜓台、一个云听台，均正常出声（无回归）。
7. 表 R4 的 6 个入口逐一实机验证：切台、杀进程后恢复、跨半点节目刷新、回放切回直播。

## 风险

- **起播多一次 HTTP 往返**。TuneIn 首次解析约 1.15s（`source_tunein.go:185-186`），但这
  一跳本来就发生在 `/proxy` 内部，提前到 `/v1/stream` 后 `/proxy` 命中 6h 缓存，净增约
  几十 ms。云听/蜻蜓分支无上游调用，纯本地拼串。
- **`/v1/stream` 不能缓存响应**：带签名的 HLS 地址有时效（`isSignedHLS`，
  `source_tunein.go:243`），缓存会固化过期签名 → 403。参照 `/v1/replay`，不进 cachedHandler。
- **客户端改动面比后缀方案大**（6 个入口 + 一个新值类型）。这是显式契约的代价，换来的
  是类型不会再从任何一条路径上丢失。
