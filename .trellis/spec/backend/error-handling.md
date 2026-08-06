# Error Handling

> 本项目的错误处理约定。核心原则：**失败降级、绝不崩溃**——这是个消费端播放 App，
> 网络 / 数据源随时可能抽风，任何一处失败都不该让用户看到崩溃或白屏。

---

## Error Types

不定义自定义异常类型。数据层（`source` / `remote`）直接抛标准 `Exception`（多为 IO / 序列化 / HTTP），
由调用方（ViewModel）捕获并转成 UI 可展示的字符串或降级值。

---

## Error Handling Patterns

分两种，按「失败是否需要告知用户」选择：

**A. 首屏 / 主动加载失败 → 写进 UiState 的 error 字段展示给用户**
用 `try/catch (e: Exception)`，把 `e.message ?: "…失败"` 写入对应 error / loading 字段：
```kotlin
try {
    val channels = src.fetchChannels(...)
    _uiState.update { it.copy(channels = channels, isLoadingChannels = false) }
} catch (e: Exception) {
    _uiState.update { it.copy(isLoadingChannels = false, error = e.message ?: "加载电台失败") }
}
```
节目单等异步项用 `runCatching { }.fold(onSuccess, onFailure)`，见 `RadioViewModel.fetchPlaybill`。

**B. 非关键 / 后台刷新失败 → 静默降级，保留旧值**
用 `runCatching { }.getOrDefault(...)/.getOrNull()`，失败不打扰用户：
```kotlin
val url = runCatching { source.resolveReplayUrl(channel, program) }.getOrDefault("")
if (url.isBlank()) return@launch            // 地址解析失败则静默不播
runCatching { refreshFavoritesGrouped(...) } // 收藏刷新失败保留原快照
```
`BaseRadioSource.refreshFavoritePrograms` 同理：某地区拉取失败保留原快照、原顺序不变。

**过期结果丢弃**：快切（切日期 / 切源）产生的迟到响应用自增 token 校验丢弃，
避免旧结果覆盖新状态——见 `fetchPlaybill` 的 `playbillToken`。

---

## API Error Responses

无自建后端，消费第三方 HTTP 接口。`Json { ignoreUnknownKeys = true; coerceInputValues = true }`
容忍字段缺失 / 类型不符，减少因对方接口微调导致的解析失败。

---

## Common Mistakes

- ❌ 让数据层异常直接冒泡到 UI 线程 —— 必须在 ViewModel 边界捕获。
- ❌ 该静默的后台刷新用 `try/catch` 弹错给用户，或该展示的首屏失败被 `getOrDefault` 吞掉。
- ❌ 异步结果不做 token 校验 —— 快切时旧响应会覆盖新状态。
- ❌ `catch (e: Exception)` 后什么都不做也不降级 —— 要么写 error 字段，要么给降级值。
