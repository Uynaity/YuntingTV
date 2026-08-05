# 执行计划

顺序：服务端先行（可独立部署验证），客户端后随。每阶段留可回滚点。

## 阶段 1：服务端类型判据 + 缓存激活

- [ ] 1.1 `source_tunein.go`：抽出纯函数
      `func isHLSStream(mediaType, rawURL string) bool { return mediaType == "hls" || isM3U8("", rawURL) }`
- [ ] 1.2 `tunein_test.go`：加 `TestIsHLSStream`，四组合断言
      （`hls`+无后缀 → true、`aac`+`.m3u8` → true、两者皆是 → true、`mp3`+`.aac` → false）
- [ ] 1.3 `tuneInResolve` 签名改 `(string, bool, error)`；hls 判定放在 playlist 跳转**之后**
      对最终地址计算（design 二.改动点 1）
- [ ] 1.4 `stationURLCache.get/set` 带上 hls；`cachedURL.hls` 死字段激活
- [ ] 1.5 `resolveStreamURL` 签名改 `(string, bool, error)`；`handlers.go:84` 处忽略 hls 返回值

验证：`cd radio-proxy && go build ./... && go test ./...`
回滚点：此阶段纯内部重构，`/proxy` 行为不变，可单独提交。

## 阶段 2：/v1/stream 端点

- [ ] 2.1 `gateway.go`：`handleV1Stream`，按 source 分派
      （tunein → `resolveStreamURL`；qingting → `{url:"", streamType:"hls"}`；
      yunting → `{url:"", streamType:"progressive"}`）
- [ ] 2.2 url 为空的约定写进函数注释（design 二「云听的坑」——本设计唯一隐式约定）
- [ ] 2.3 `main.go` 注册路由，**不套 cachedHandler**
- [ ] 2.4 `gateway_test.go`：加端点级测试，至少覆盖三个 source 的 streamType 取值

验证：
```bash
cd radio-proxy && go build ./... && go test ./...
```
手测四条 curl（tunein s20277 / tunein s25231 / qingting / yunting），对齐验收标准 1-3。

回滚点：新增端点，无人调用即无影响，可单独提交并部署。

## 阶段 3：客户端契约层

- [ ] 3.1 `Models.kt`：加 `StreamInfo`（streamType 为 String，默认 `progressive`）
- [ ] 3.2 `GatewayApi.kt`：加 `getStream(source, contentId): ApiResponse<StreamInfo>`
- [ ] 3.3 `RadioSource.kt`：接口加 `suspend fun resolveStream(channel: Channel): ResolvedStream`；
      定义 `ResolvedStream(url, isHls)`；`BaseRadioSource` 默认实现返回
      `(channel.playUrlLow, false)`
- [ ] 3.4 `GatewaySource.kt`：覆盖 `resolveStream`——调 `/v1/stream`，
      `url` 空则回退 `channel.playUrlLow`，`streamType == "hls"` → isHls，
      未知值 → false；整体 `runCatching` 失败回退默认（design 三.失败降级）

验证：`./gradlew :app:compileDebugKotlin`

## 阶段 4：客户端派发接线

- [ ] 4.1 `mediaItemOf` 增参 `isHls: Boolean`，真时 `.setMimeType(MimeTypes.APPLICATION_M3U8)`
- [ ] 4.2 `playUrl` 改为接 `ResolvedStream`
- [ ] 4.3 `playNow` 内部先 `resolveStream(channel)` 再 `playUrl`
- [ ] 4.4 `:553` 的 `replaceMediaItem`：**不调 resolveStream**，从
      `c.currentMediaItem?.localConfiguration` 取现有 uri + mimeType 原样带过去，只换
      MediaMetadata（design 三「6 个起播入口的收口」）
- [ ] 4.5 `playReplay`（`:838-841`）：回放地址是 `.aac`/普通文件，走 progressive；
      确认它不经 `resolveStream`（回放不是直播流，不该问 `/v1/stream`）
- [ ] 4.6 复核表 R4 六个入口全部经由 `playNow` 或 4.4 的路径，无遗漏

验证：`./gradlew :app:assembleDebug`

## 阶段 5：实机验收

- [ ] 5.1 BBC Radio 1Xtra（s20277）出声，无「缓冲中…Ns」（验收 5）
- [ ] 5.2 TuneIn 非 HLS 台 + 蜻蜓台 + 云听台无回归（验收 6）
- [ ] 5.3 六入口逐一：切台 / 杀进程恢复 / 跨半点节目刷新 / 回放切回直播（验收 7）
- [ ] 5.4 弱网或断开网关时确认走失败降级、不崩

## 审查门

- 阶段 2 完成后：curl 结果给用户确认契约符合预期，再动客户端。
- 阶段 4 完成后：`:553` 与 `playReplay` 两处最易错，需单独 review diff。

## 待办衍生

多候选流降级（`reliability` / 多码率备份源切换）另开子任务，本次不做。
