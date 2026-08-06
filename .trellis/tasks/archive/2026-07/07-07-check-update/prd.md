# PRD — APP 检查更新功能

## 背景
应用当前无自更新能力，发新版只能手动重装。需接入 yecao.app 分发接口，实现启动自动检查 + 设置页手动检查，检查到新版即引导用户下载安装。

## 接口事实（已用脚本验证）
- 请求：`GET https://yecao.app/api/v1/client/resolve/R2B1?protocol_version=1&client_time=<秒级时间戳>`
- **必带请求头**：`x-wgdc-client-secret: udp-demo-secret`，缺失返回 `401 {"code":401,"message":"invalid client secret"}`
- `client_time` = 当前**秒级**时间戳（`System.currentTimeMillis()/1000`）；服务器回 `time_delta` 做偏差校验，勿传毫秒或写死
- 成功响应关键字段（取 `data.apps[0]`）：
  - `version_code`（Int）— 与本地 `BuildConfig.VERSION_CODE` 比较，本地 **<** 它即有更新
  - `version_name`（String）— 展示
  - `description`（String）— 更新说明，展示
  - `download_url`（String，相对路径）— **完整地址 = `https://yecao.app/` + download_url**（已验证 200 / `application/vnd.android.package-archive`）
  - `size`（Long，字节）— 进度条参考
  - `md5`（String）— 暂不做校验（见范围外）

## 需求
1. **启动自动检查**：冷启动后后台静默检查。有更新 → 弹更新弹窗；无更新/检查失败 → 不打扰（无弹窗无 toast）。
2. **更新弹窗**：展示版本名、包大小（**不展示更新说明**）；两个按钮「立即更新」「取消」，遥控可操作，默认焦点落「取消」（复刻 `ExitConfirmDialog` 风格）。
   - 「取消」：关闭弹窗，**不记忆**（下次启动若仍有新版会再弹）。
   - 「立即更新」：应用内下载 APK，弹窗内显示下载进度；下载完成调起系统安装器。
3. **设置页手动检查**：新增「检查更新」项（复用 `ActionSettingRow`）。点击触发检查：
   - 有更新 → 弹**相同**更新弹窗。
   - 无更新 → `Toast`「当前已是最新版本」。
   - 检查失败 → `Toast`「检查更新失败」。
4. **安装权限**：Android 8.0+ 未授予「未知来源」安装权限时，引导用户跳系统设置授权。

## 验收标准
- [ ] 本地 versionCode 低于线上时，冷启动弹出更新弹窗，信息正确，遥控可切换/确认两按钮。
- [ ] 「立即更新」下载过程弹窗内有进度，完成后拉起系统安装界面。
- [ ] 「取消」关闭弹窗；再次冷启动仍会弹（无跳过记忆）。
- [ ] 设置页「检查更新」：有更新弹窗一致；无更新 toast 最新版；失败 toast 失败。
- [ ] 无更新时启动无任何打扰。
- [ ] 启动检查网络失败时静默；手动检查网络失败时 toast。
- [ ] 8.0+ 无未知来源权限时跳转系统授权页。

## 范围外 / 明确不做（ponytail）
- **不做 MD5 校验**：下载完直接装，交由系统安装器校验签名。若日后出现坏包再加。
- **不做「跳过此版本」记忆**：用户选定，取消即下次再提醒，无需持久化。
- **不做断点续传 / 后台通知栏下载**：弹窗内一次性下载，取消弹窗即视为放弃本次。
- **不做灰度 / 强制更新**：一律可取消。

## 测试注意
线上现为 `version_code=5 / 1.5`，本地 `versionCode=6`，本地更新不会触发弹窗。验证时需临时把 `app/build.gradle.kts` 的 `versionCode` 调到 ≤4 再冷启动。
