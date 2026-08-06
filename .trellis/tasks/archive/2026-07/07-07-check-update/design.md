# Design — APP 检查更新功能

## 总体
更新逻辑收敛到 `RadioViewModel`（复用 `RadioScreen` 已持有的 viewModel 实例，启动检查与设置页检查共用），弹窗托管在 `RadioScreen`。网络沿用现有 Retrofit + kotlinx.serialization + OkHttp 单例风格，新增一套 yecao 专用 client/api。下载安装因需 Context，落在一个独立的 `UpdateInstaller`（接收 Application）。

**无新增依赖**：okhttp 下载、`androidx.core.content.FileProvider`（core-ktx 已有）均已在库内。

## 数据流
```
启动(RadioViewModel.init) ─┐
设置页「检查更新」──────────┼─► checkForUpdate(manual)
                            │      └─ UpdateApi.resolve() → 比较 versionCode
                            │            ├─ 有更新 → _updateState = Available(app)
                            │            ├─ 无更新 & manual → _updateEvents.emit(UpToDate)
                            │            └─ 失败   & manual → _updateEvents.emit(Failed)
RadioScreen 观察:
  updateState==Available → UpdateDialog(app, progress)
  updateEvents           → Toast
UpdateDialog「立即更新」→ viewModel.downloadAndInstall()
                            └─ UpdateInstaller.download(url, onProgress) → 写 apk 文件
                                 → 更新 _updateState.progress
                                 → 完成 UpdateInstaller.install(file)（权限检查/跳授权）
UpdateDialog「取消」→ viewModel.dismissUpdate() → _updateState = None
```

## 契约

### 网络层 — `data/remote/YeCaoApi.kt`
命名沿用现有按平台惯例（`RadioApi`=云听、`QingTingApi`=蜻蜓）。interface 与响应 model 同文件内联（照 `RadioApi.kt` 内联 `YtProgram` 的惯例，不单独建 model 文件）。
```kotlin
interface YeCaoApi {
    @GET("api/v1/client/resolve/R2B1")
    suspend fun resolve(
        @Query("protocol_version") protocolVersion: Int = 1,
        @Query("client_time") clientTime: Long,   // 秒级
    ): UpdateResolveResponse
}

// yecao 返回 snake_case，@SerialName 映射；沿用现有 Json{ignoreUnknownKeys=true}，只取需要字段
@Serializable data class UpdateResolveResponse(val code: Int = -1, val data: UpdateData? = null)
@Serializable data class UpdateData(val apps: List<UpdateApp> = emptyList())
@Serializable data class UpdateApp(
    @SerialName("version_code") val versionCode: Int = 0,
    @SerialName("version_name") val versionName: String = "",
    @SerialName("download_url") val downloadUrl: String = "",
    val description: String = "",
    val size: Long = 0,
)
```
说明：token `R2B1` 硬编码进 path；secret 由拦截器统一注入，不作参数。

- `NetworkModule` 新增：
  - `YECAO_BASE_URL = "https://yecao.app/"`
  - `yecaoClient`：OkHttpClient，加 header 拦截器注入 `x-wgdc-client-secret: udp-demo-secret`（固定值），复用 debug 日志与超时。
  - `yecaoApi: YeCaoApi`（复用现有 `json`/`converter`）。

### ViewModel（`RadioViewModel`）
- 状态（更新信息低频 → 独立小 flow，不塞进大 `RadioUiState`；下载进度虽高频但已在独立 flow 内，避免污染 uiState 触发 Grid 重组）：
  ```kotlin
  sealed interface UpdateState {
      data object None : UpdateState
      data class Available(val app: UpdateApp, val downloading: Boolean = false, val progress: Float = 0f) : UpdateState
  }
  val updateState: StateFlow<UpdateState>
  // 一次性提示（仅手动检查）：无更新 / 失败
  val updateEvents: SharedFlow<UpdateEvent>  // UpToDate | Failed
  ```
- 方法：
  - `fun checkForUpdate(manual: Boolean)`：`viewModelScope` 内调 `yecaoApi.resolve(clientTime = currentTimeMillis()/1000)`，取 `data.apps[0]`。`app.versionCode > BuildConfig.VERSION_CODE` → `_updateState = Available(app)`；否则 manual 时 emit `UpToDate`。异常 catch：manual 时 emit `Failed`，自动检查静默。
  - `fun downloadAndInstall(app)`：置 `downloading=true`，调 `UpdateInstaller.download` 带进度回调更新 `_updateState`，完成调 `UpdateInstaller.install`。失败 emit `Failed` 并复位。
  - `fun dismissUpdate()`：`_updateState = None`（进行中的下载 job 取消）。
- `init` 末尾追加：`checkForUpdate(manual = false)`。

### UpdateInstaller（`data/update/UpdateInstaller.kt`）
接收 `Application`（VM 用 `getApplication()`）。
- `suspend fun download(url: String, onProgress: (Float) -> Unit): File`
  - `Dispatchers.IO`，okhttp（新建轻量 client 或复用）GET 完整 url（`YECAO_BASE_URL + downloadUrl`）。
  - 写入 `context.cacheDir/update.apk`（覆盖），按 `contentLength`（回退 PRD 的 `size`）累计字节回调进度。
- `fun install(file: File)`
  - Android O+：`packageManager.canRequestPackageInstalls()` 为 false → 跳 `Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES`（`package:` uri），return（用户授权后需再点一次「立即更新」，可接受）。
  - `FileProvider.getUriForFile(context, "cn.radio.tv.fileprovider", file)`。
  - `Intent(ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")` + `FLAG_ACTIVITY_NEW_TASK` + `FLAG_GRANT_READ_URI_PERMISSION`，`startActivity`。

### UI
- `ui/components/UpdateDialog.kt`：复刻 `ExitConfirmDialog`（`Dialog` + `focusableChrome` 按钮）。
  - 展示标题「发现新版本 vX.X」、大小（`size` 格式化 MB）。不展示更新说明（`description` 字段仍解析，仅不渲染）。
  - 未下载：两按钮「取消」(默认焦点) /「立即更新」。
  - 下载中：按钮区替换为进度条 + 百分比文案（禁二次点击）。
- `RadioScreen.kt`：
  - 观察 `viewModel.updateState`，`Available` 时叠加 `UpdateDialog`（在最外层 Box，盖过设置页/列表；与 `showExitDialog` 同层）。
  - `LaunchedEffect` collect `updateEvents` → `Toast`（LocalContext）。
  - 传 `onCheckUpdate = { viewModel.checkForUpdate(manual = true) }` 给 `SettingsScreen`。
- `SettingsScreen.kt`：新增参数 `onCheckUpdate: () -> Unit`，在「清除图片缓存」附近加一条 `ActionSettingRow`「检查更新 / 获取并安装最新版本」。

### Manifest / 资源
- 新增权限：`<uses-permission android:name="android.permission.REQUEST_INSTALL_PACKAGES" />`
- `<application>` 内新增 FileProvider：
  ```xml
  <provider
      android:name="androidx.core.content.FileProvider"
      android:authorities="cn.radio.tv.fileprovider"
      android:exported="false"
      android:grantUriPermissions="true">
      <meta-data android:name="android.support.FILE_PROVIDER_PATHS"
          android:resource="@xml/file_paths" />
  </provider>
  ```
- 新建 `res/xml/file_paths.xml`：暴露 `cache-path`（对应 `cacheDir`）。

## 兼容性 / 权衡
- minSdk 24：`FileProvider` 全程可用；`canRequestPackageInstalls` 为 API 26+，26 以下直接装。
- TV 无未知来源开关或系统安装器时，`install` 的 `startActivity` 可能抛 `ActivityNotFoundException` → try/catch，失败 toast 提示，不崩溃。
- 下载文件固定名覆盖写，不累积垃圾。

## 回滚
纯新增功能，涉及改动集中且独立。回滚 = 还原 Manifest 两处、删新增文件、还原 `RadioScreen`/`SettingsScreen`/`RadioViewModel`/`NetworkModule` 的增量。不影响播放主流程。
