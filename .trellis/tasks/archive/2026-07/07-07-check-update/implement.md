# Implement — APP 检查更新功能

## 执行顺序

1. **网络 + Model** — `data/remote/YeCaoApi.kt` + `NetworkModule` 增量
   - `YeCaoApi.resolve(protocolVersion, clientTime)`，响应 model（`UpdateResolveResponse`/`UpdateData`/`UpdateApp`，`@SerialName` 映射 snake_case）内联同文件。
   - `NetworkModule`：`YECAO_BASE_URL`、header 拦截器（`x-wgdc-client-secret: udp-demo-secret`）、`yecaoClient`、`yecaoApi`。

3. **下载安装** — `data/update/UpdateInstaller.kt`
   - `download(url, onProgress): File` 写 `cacheDir/update.apk`，按 contentLength 回调进度。
   - `install(file)`：O+ 权限检查 → 跳授权；FileProvider + ACTION_VIEW；try/catch `ActivityNotFoundException`。

4. **Manifest / 资源**
   - 加 `REQUEST_INSTALL_PACKAGES` 权限、FileProvider provider。
   - 新建 `res/xml/file_paths.xml`（cache-path）。

5. **ViewModel** — `RadioViewModel` 增量
   - `UpdateState` sealed interface、`UpdateEvent`、`_updateState`/`updateState`、`_updateEvents`/`updateEvents`。
   - `checkForUpdate(manual)` / `downloadAndInstall(app)` / `dismissUpdate()`。
   - `init` 末尾 `checkForUpdate(manual = false)`。

6. **弹窗** — `ui/components/UpdateDialog.kt`
   - 复刻 `ExitConfirmDialog`；下载中显示进度条替换按钮区。

7. **接线** — `RadioScreen.kt` + `SettingsScreen.kt`
   - `RadioScreen`：观察 `updateState` 叠加 `UpdateDialog`；collect `updateEvents` toast；传 `onCheckUpdate` 给设置页。
   - `SettingsScreen`：加 `onCheckUpdate` 参数 + 「检查更新」`ActionSettingRow`。

## 验证命令
```bash
./gradlew :app:assembleDebug          # 编译通过
./gradlew :app:lintDebug              # 可选，lint
```
- 手动验证：临时将 `app/build.gradle.kts` 的 `versionCode` 改为 `4`，装 debug 包冷启动 → 应弹更新弹窗；设置页「检查更新」→ 弹窗；改回 `versionCode=6` → 无弹窗、设置页检查 toast「已是最新」。
- 验证完**务必把 versionCode 改回 6**。

## Review gates
- 编译通过 + 无新增 lint error。
- 遥控焦点：弹窗两按钮可 D-pad 切换、OK 触发，默认焦点「取消」。
- 无更新时零打扰。

## Rollback
`git checkout` 还原 4 个改动文件（NetworkModule / RadioViewModel / RadioScreen / SettingsScreen / AndroidManifest / build.gradle 若改过 versionCode），删除 3 个新增文件与 file_paths.xml。
