# 技术设计：激活码分发（管理页导出 + App 购买入口）

两块改动**互不依赖**，可分别实现、分别上线。

| 文件 | 动作 |
|---|---|
| `radio-proxy/admin_ui/index.html` | 工具条加「导出未使用」按钮 |
| `radio-proxy/admin_ui/app.js` | 导出逻辑 + 按钮条数/禁用态随列表更新 |
| `radio-proxy/admin_ui/style.css` | 按钮禁用态样式（若现有不够） |
| `gradle/libs.versions.toml`、`app/build.gradle.kts` | 新增 `com.google.zxing:core` |
| `app/.../ui/components/PurchaseDialog.kt` | 新增：二维码 + 链接文本 |
| `app/.../ui/SettingsScreen.kt` | 新增「购买激活码」条目与双端分流 |

---

## 一、管理页导出

### 范围固定，不跟随筛选

导出的是 `codes.filter(c => c.status === 'unused')`，**与状态筛选/搜索框无关**。

这一点必须在 UI 上说清楚 —— 按钮文案带条数（「导出未使用 (12)」），管理员点之前就知道会拿到几个。
否则「我明明筛到了已吊销，导出来却是别的」会是个很难自己想明白的困惑。

### 实现

```js
function exportUnusedCodes() {
  const unused = codes.filter((c) => c.status === 'unused');
  if (unused.length === 0) return;

  // 一行一个码,末尾留一个换行 —— 没有末尾换行的文本文件在某些工具里会少读一行。
  const text = unused.map((c) => c.code).join('\n') + '\n';
  const blob = new Blob([text], { type: 'text/plain;charset=utf-8' });
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  // 文件名必须 ASCII 安全:中文名在部分系统/工具链上会变成乱码或被截断。
  a.download = `activation-codes-unused-${todayStamp()}.txt`;
  a.click();
  // 不释放的话这个 Blob 会一直占着内存直到页面关闭。
  URL.revokeObjectURL(url);
}
```

`todayStamp()` 产出 `YYYYMMDD`（本地时区即可，这是给人看的文件名，不是数据）。

### 按钮状态

`renderCodes()` 每次都重算未使用条数，同步按钮文案与 `disabled`。放在 `renderCodes()` 里而不是
单独监听：列表数据变化的入口只有它一个（生成、吊销、解绑后都会走 `loadCodes()` → `renderCodes()`），
挂在这里就不会漏。

**注意**：`renderCodes()` 现有实现里的 `rows` 是**过滤后**的列表，导出条数要从 `codes` 全量里数，
别顺手用了 `rows.length`。

---

## 二、App 购买入口

### 依赖

```toml
zxing = "3.5.3"
zxing-core = { group = "com.google.zxing", name = "core", version.ref = "zxing" }
```

只要 `core`，**不要 `android-integration` / `zxing-android-embedded`** —— 那些是给「扫码」用的
（要相机权限、要 Activity），我们只是「生成」，纯 Java 的 `core` 就够。

### 二维码渲染：深色主题下的陷阱

App 是深色主题。二维码**必须黑码白底**并且带白色静区（quiet zone），否则扫不出来：

- 深色底上画深色模块 → 对比度不足，识别失败。
- 没有静区（四周留白）→ 大多数扫码器无法定位。

所以二维码要画在一块**白色 Surface** 上，而不是直接铺在弹窗的深色背景上：

```kotlin
// 二维码模块画成黑色、底色画成白色 —— 与 App 的深色主题相反是**故意的**:
// 扫码器要的是高对比 + 四周静区,跟着主题走深色会直接扫不出来。
private fun encodeQr(content: String, sizePx: Int): Bitmap {
    val hints = mapOf(
        EncodeHintType.CHARACTER_SET to "UTF-8",
        // MARGIN 就是静区宽度(单位:模块数)。默认值随版本变化,显式给 1 个模块的余量,
        // 外层再用 padding 补足视觉留白 —— 静区不足是扫不出来的头号原因。
        EncodeHintType.MARGIN to 1,
    )
    val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
    ...
}
```

生成用 `remember(content, sizePx)` 缓存：内容固定、尺寸固定，只算一次。链接只有 30 字符，
编码是毫秒级，不必丢到后台线程（丢过去反而要处理加载态）。

### 双端分流

复用现有的 `Context.hasTouchScreen()`（`SettingsScreen.kt:339`，读
`PackageManager.FEATURE_TOUCHSCREEN`）：

```kotlin
ActionSettingRow(
    title = "购买激活码",
    subtitle = if (hasTouch) "在浏览器中打开购买页" else "用手机扫码打开购买页",
    onClick = {
        if (hasTouch) {
            // 打不开浏览器就回落到二维码弹窗 —— 让用户拿另一台设备扫,
            // 或者照着弹窗里的链接文本手输。静默失败最糟:点了没反应,用户不知道是不是坏了。
            val opened = context.openUrl(PURCHASE_URL)
            if (!opened) showPurchaseDialog = true
        } else {
            showPurchaseDialog = true
        }
    },
)
```

```kotlin
/** 用系统浏览器打开链接。返回是否成功 —— TV 盒子上常常压根没有浏览器。 */
private fun Context.openUrl(url: String): Boolean = runCatching {
    startActivity(
        Intent(Intent.ACTION_VIEW, url.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )
    true
}.getOrDefault(false)
```

`FLAG_ACTIVITY_NEW_TASK` 不能省：从非 Activity context 启动会抛异常，而这里的 context 来自
`LocalContext`，在某些宿主下未必是 Activity。

### 弹窗

`PurchaseDialog` 同时给二维码与链接文本，一个组件服务两端（TV 主用扫码、手机作兜底）。
结构照 `ExitConfirmDialog` / `ActivationManageDialog`：`Dialog` + tv-material `Surface` +
`DialogButton`（`focusableChrome`，触屏与遥控都能触发）。只有一个「关闭」按钮，默认焦点落在它上面。

链接文本用 `SelectionContainer` 包一下，手机上可以长按复制；TV 上无害。

### 常量

```kotlin
/** 激活码购买页。改这里一处即可 —— 别在 UI 里散落字面量。 */
const val PURCHASE_URL = "https://pay.ldxp.cn/shop/hkuai"
```

放在 `PurchaseDialog.kt` 同文件（唯一使用它的两处都在设置页这条路径上），不新造配置层。
**不做服务端下发**：那要新增接口 + 缓存 + 兜底默认值，为一个几乎不变的链接不值当；真要改，
发一版就是了。

---

## 兼容性与回滚

- 管理页：纯前端新增，回滚即删按钮。旧浏览器（不支持 `Blob`/`download`）不在考虑范围内——
  管理页本来就只有 owner 一个人用。
- App：新增依赖 + 一个条目 + 一个弹窗，不触碰任何既有逻辑。回滚即整体 revert，
  连 `libs.versions.toml` 一起。
- 两块互不依赖，可分别上线。

## 风险 / 实现阶段关注

- **二维码必须真机扫过才算数**。「图渲染出来了」和「能扫出来」是两回事 —— 静区不足、对比度不够、
  尺寸太小都会让它看起来正常却扫不动。验收要用手机相机实际扫一次并确认跳转到了正确的 URL。
- **zxing 版本**：`3.5.3` 是写文档时的稳定版，实现时确认一下仓库里能解析到；`core` 是纯 Java，
  不需要额外的 ProGuard 规则（本项目 debug 构建也不混淆）。
- **APK 体积**：`zxing:core` 约 530KB。对 TV 应用可忽略，但这是本任务唯一的体积代价，心里有数。
