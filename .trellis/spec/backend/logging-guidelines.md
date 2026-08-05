# Logging Guidelines

> 本项目的日志约定。现状：**几乎不打日志**——这是刻意的，不是缺失。

---

## 现状与原则

App 代码里**没有任何 `android.util.Log` / `Timber` / `println` 调用**。唯一的日志是
`NetworkModule` 里挂给 OkHttp 的 `HttpLoggingInterceptor`，且仅在 **debug 构建**下启用：

```kotlin
private fun OkHttpClient.Builder.withDebugLogging() = apply {
    if (BuildConfig.DEBUG) {
        addInterceptor(HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BASIC })
    }
}
```

原则：**不为「以防万一」加日志。** 失败已通过 UiState 的 error 字段反馈给用户（见 error-handling.md），
无需再 log。加日志前先问：这条日志谁会看、在哪看、值不值得每次运行都执行。

---

## Log Levels

如确需临时排查，用标准 `android.util.Log`：`Log.e`（会导致功能不可用的异常）、`Log.w`（可降级的异常）、
`Log.d`（仅调试）。**release 不留 `Log.d`**——用 `if (BuildConfig.DEBUG)` 包住，或排查完删掉。

---

## What to Log

- 网络请求：交给 OkHttp 拦截器，别在业务层重复打。
- 播放异常：如需排查 ExoPlayer，临时挂 `Player.Listener` 打 `Log.e`，排查后移除。

---

## What NOT to Log

- ❌ 任何鉴权凭证。上游签名已收归服务端网关，APP 侧不再持密钥——也别把网关请求头打全。
- ❌ `HttpLoggingInterceptor` 保持 `BASIC`，不要升到 `BODY`——会打出完整响应体（含地址、可能的凭证）。
- ❌ release 构建打任何日志（拦截器已用 `BuildConfig.DEBUG` 挡住，新增日志同样要挡）。
