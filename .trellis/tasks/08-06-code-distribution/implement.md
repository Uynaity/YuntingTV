# 执行计划：激活码分发（管理页导出 + App 购买入口）

两块**互不依赖**，可按任意顺序做、分别上线。下面先做管理页（体量小、当场可验），再做 App。

## 第一块：管理页导出

### 1. 前端实现

- [ ] `admin_ui/index.html`：激活码页的筛选工具条里加 `<button id="export-unused">`。
      放在筛选/搜索之后、条数统计之前。
- [ ] `admin_ui/app.js`：
  - `exportUnusedCodes()`：`codes.filter(status === 'unused')` → 一行一个码（**末尾补一个换行**）
    → `Blob` → `<a download>` → **`URL.revokeObjectURL`**（不释放会一直占内存到页面关闭）。
  - 文件名 `activation-codes-unused-YYYYMMDD.txt`，**ASCII 安全**，不要用中文名。
  - 在 `renderCodes()` 里同步按钮文案（带条数）与 `disabled`。列表变化的入口只有它一个
    （生成/吊销/解绑都会走 `loadCodes()` → `renderCodes()`），挂这里不会漏。
  - **条数要从 `codes` 全量里数，不是 `rows`** —— `rows` 是过滤后的列表，顺手用了就会算错。
- [ ] `admin_ui/style.css`：确认 `button:disabled` 现有样式够用（已有 `opacity: 0.5`），不够再补。

### 2. 本地验证（起 Postgres + 服务，照前几个任务的做法）

- [ ] 生成几个码、兑换一个、吊销一个，制造出四种状态并存的局面。
- [ ] 点导出 → 下载的 txt 行数 == 按钮上的条数 == 未使用码的个数，内容只有码、一行一个。
- [ ] **把筛选切到「已吊销」再点导出** → 导出的仍是未使用的那批（这是最容易写错的一条）。
- [ ] 把所有未使用的码都吊销掉 → 按钮变禁用态。
- [ ] `git diff` 确认没新增后端接口、没引第三方 JS。

### 3. 提交与部署

- [ ] `radio-proxy/` 内提交并 push；部署到 `radio.hku.wtf`
      （`git pull && docker compose up -d --build`）。

---

## 第二块：App 购买入口

### 4. 依赖

- [ ] `gradle/libs.versions.toml`：加 `zxing = "3.5.3"` 与
      `zxing-core = { group = "com.google.zxing", name = "core", version.ref = "zxing" }`。
- [ ] `app/build.gradle.kts`：`implementation(libs.zxing.core)`。
- [ ] **只加 `core`**，不要 `android-integration` / `zxing-android-embedded`（那些是扫码用的，
      要相机权限）。
- [ ] `./gradlew assembleDebug` 确认能解析到该版本。

### 5. 二维码与弹窗

- [ ] 新建 `ui/components/PurchaseDialog.kt`：
  - `const val PURCHASE_URL = "https://pay.ldxp.cn/shop/hkuai"`（单一常量，别在 UI 里散落字面量）。
  - `encodeQr(content, sizePx)`：`QRCodeWriter().encode(...)` → `BitMatrix` → `Bitmap`。
    - **黑码白底**，与 App 深色主题相反是故意的：深色底上画深色模块扫不出来。
    - `EncodeHintType.MARGIN` 显式给静区，外层再加 padding。**静区不足是扫不出来的头号原因。**
    - `remember(content, sizePx)` 缓存，不必丢后台线程（30 字符的编码是毫秒级）。
  - 弹窗结构照 `ExitConfirmDialog` / `ActivationManageDialog`：`Dialog` + tv-material `Surface`
    + `DialogButton`。只有一个「关闭」按钮，默认焦点落在它上面。
  - 二维码画在**白色 Surface** 上，不要直接铺在弹窗深色背景上。
  - 链接文本用 `SelectionContainer` 包住，手机上可长按复制。

### 6. 设置页接入

- [ ] `SettingsScreen.kt` TuneIn 区块内、「管理激活码 / 输入激活码」条目**之下**加
      `ActionSettingRow(title = "购买激活码")`，**未激活与已激活都显示**。
- [ ] 双端分流复用现有的 `hasTouchScreen()`（`SettingsScreen.kt:339`），不另造判据：
  - 触摸设备 → `openUrl(PURCHASE_URL)`；**失败回落到二维码弹窗**（TV 盒子/精简 ROM 常常没浏览器，
    静默失败最糟：点了没反应，用户不知道是不是坏了）。
  - 非触摸设备 → 直接弹二维码弹窗。
- [ ] `openUrl` 要带 `Intent.FLAG_ACTIVITY_NEW_TASK` —— `LocalContext` 未必是 Activity，
      不带会抛异常。整体 `runCatching` 返回成功与否。
- [ ] `BackHandler` 的 `enabled` 条件要带上新弹窗的 state（照现有 `showAbout` /
      `showActivationDialog` / `showManageDialog` 的写法），否则弹窗开着时按返回键会直接关掉设置页。

### 7. 验证

- [ ] `./gradlew testDebugUnitTest assembleDebug` 全绿。
- [ ] **真机实测二维码：用手机相机扫一次，确认跳转到 `https://pay.ldxp.cn/shop/hkuai`。**
      「图渲染出来了」和「能扫出来」是两回事 —— 静区、对比度、尺寸任一不对都会让它看着正常却扫不动。
      这条不能用截图代替。
- [ ] 手机上点击 → 系统浏览器打开该链接。
- [ ] 飞行模式下打开弹窗 → 二维码照常显示（本地生成，不联网）。
- [ ] 已激活状态下条目仍然可见。
- [ ] 弹窗开着时按返回键 → 只关弹窗，不关设置页。

### 8. Quality check

- [ ] 对照 `prd.md` 的 Acceptance Criteria 逐条核对。
- [ ] `git diff` 确认没触碰激活码的生成/兑换/解绑逻辑。
- [ ] 确认只新增了 `zxing:core` 一个依赖。

## 验证命令汇总

```bash
cd radio-proxy && go build ./... && go vet ./... && gofmt -l . && go test ./...
```

```bash
./gradlew testDebugUnitTest assembleDebug
```

## 验证记录（2026-08-06）

**已验证**

- 管理页：本地无 Docker/Postgres，用真实 `app.js` + 桩数据在浏览器里跑通 ——
  筛选切到「已吊销」后导出的仍是 3 个未使用码（内容 `AAAA-1111-BBBB\nEEEE-3333-FFFF\nKKKK-6666-LLLL\n`）、
  文件名 `activation-codes-unused-20260806.txt`、`revokeObjectURL` 有调用、
  未使用数为 0 时按钮禁用且绕过禁用直调也不产生文件。`go build/vet/gofmt/test` 全绿。
- App：真机（PLJ110，Android 手机）安装 debug 包 —— 未激活状态下「购买激活码」条目
  出现在 TuneIn 区块「输入激活码」之下，副标题正确识别为触摸设备；点击后 Chrome
  打开 `pay.ldxp.cn/shop/hkuai`。
- 二维码内容：`PurchaseQrCodeTest` 5 个用例全过 —— 把 App 实际会画的像素方阵喂给 zxing
  解码器回环，断言解出原链接（220/330/440/660/770px 五档密度）、黑码白底未反相、
  最外缘一圈全白（静区）、空内容返回 null 不崩。
- 依赖只新增 `zxing:core`；`data/` 下激活码生成/兑换/解绑逻辑零改动。

**未验证（由 owner 发版后在电视上确认）**

- TV 上二维码弹窗的实际渲染，以及用手机相机扫**弹窗里那张图**跳转。
  本机无 Android 模拟器（SDK 无 system image、无 AVD），手机是触摸设备走浏览器分支，
  弹窗不出现。已把相同参数出的图单独导出供离线扫码，但那不等于扫弹窗本身。
- 弹窗开着时按返回键只关弹窗、不关设置页（`BackHandler` 的 `enabled` 已带上
  `showPurchaseDialog`，但没在真机上按过）。
- 已激活状态下条目仍可见（结构上该条目在 `active` 判断之外，未实际以已激活状态观察）。

## 回滚点

- 管理页：删按钮与导出函数即可，纯前端新增。
- App：整体 revert，连 `libs.versions.toml` 的依赖一起。不触碰任何既有逻辑，回滚无残留。
- 两块互不依赖，一块出问题不影响另一块上线。
