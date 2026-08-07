# Compose UI 约定

Jetpack Compose（含 TV / 手机双端）界面编写约定。踩过的坑与可复用模式记在这里。

---

## 全屏居中弹窗 + 遮罩覆盖系统栏

**What**：需要一个居中弹窗，且半透明遮罩要盖住状态栏和底部导航手势条时，用 `Dialog` 而非 `Popup`。

**Why**：`Popup` 会被系统栏 inset，窗口铺不满整屏，遮罩到不了状态栏/导航条区域。`Dialog` 配合下面两个 flag 才能让窗口铺满全屏；而窗口自带的 dim 若不关掉，会和自绘遮罩叠成两层。

**How**：

```kotlin
Dialog(
    onDismissRequest = onClose,
    properties = DialogProperties(
        usePlatformDefaultWidth = false,   // 窗口宽度改为 MATCH_PARENT
        decorFitsSystemWindows = false,    // 内容延伸到系统栏区域
    ),
) {
    // 关掉窗口自带 dim，只留下方可随动画渐变的自绘遮罩，否则两层遮罩叠加。
    val dialogWindow = (LocalView.current.parent as? DialogWindowProvider)?.window
    LaunchedEffect(dialogWindow) { dialogWindow?.setDimAmount(0f) }

    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)))  // 自绘遮罩
        // ...居中卡片
    }
}
```

**渐入渐隐**：用 `graphicsLayer { this.alpha = alpha }` + `animateFloatAsState` 驱动整体透明度。关闭时先把目标置 0 播放退出动画，在 `finishedListener` 里 `if (!visible) onDismiss()` 才真正把弹层移出组合树，以保留退出动画。

**遮罩点击穿透**：遮罩 `Box` 与卡片 `Box` 各挂一个 `clickable(indication = null)`——遮罩的关闭、卡片的空 lambda 吞掉点击，避免点卡片穿透到遮罩误关。

**参考实现**：`app/src/main/java/cn/radio/tv/ui/components/PlayerPanel.kt` 的 `SleepTimerOverlay`。

---

## TV 子页/全屏：用 AnimatedContent 整页替换做焦点隔离

**What**：进入全屏播放（或任何覆盖整屏的子页）时，要让 DPAD 焦点被困在子页内、退出后能回到原处。

**Why**：若用 `AnimatedVisibility` 把子页**叠**在原界面之上，原界面（如电台网格）仍在组合树且可聚焦，遥控器乱按会把焦点跳到背后的列表且回不来。

**How**：用 `AnimatedContent(targetState = showFullscreen)` 做**整页二选一替换**（同 `SettingsScreen` 的切换方式）——目标为子页时原列表不参与组合，焦点自然被困住；进入后 `LaunchedEffect` 请求子页内某控件焦点。渐入渐出用 `fadeIn(tween(300)) togetherWith fadeOut(tween(300))`。

**参考实现**：`RadioScreen.kt` 的 `AnimatedContent(showFullscreen)`；子页 `PlayerPanel.kt` 的 `FullScreenPlayer`。

---

## 可点击项「按两次才触发」：勿在 clickable 上再叠 focusable

**What**：TV 上焦点落到某卡片/按钮后，第一次确认键只是移动焦点、第二次才真正触发点击。

**Why**：`Modifier.clickable` 自身已是一个可聚焦节点；若再手动叠 `.focusable()`，modifier 链里就有两个焦点目标，第一次按键在两者间挪焦点而非触发。

**How**：只保留 `clickable`（需要焦点回写时在其上方加 `.onFocusChanged`），删掉多余的 `.focusable()`。仅当节点**没有** `clickable`（如只用 `onKeyEvent` 的进度条）时才需要独立 `.focusable()`。

---

## 动画位移用 offset 的 lambda 重载

**What**：用 `animateDpAsState` 等驱动位移时。

**Why**：`.offset(y = animatedDp)` 非 lambda 重载会在动画每帧触发重组（lint `UseOfNonLambdaOffsetOverload`）。

**How**：改 `.offset { IntOffset(0, animatedDp.roundToPx()) }`，位移在布局阶段生效、不重组。

---

## 从图片取色做背景/强调色（Palette）

**What**：按电台 LOGO 取色做全屏背景主调与控件强调色。

**How**：`ImageRequest.data(url).allowHardware(false).size(64)` → `context.imageLoader.execute` 取 `BitmapDrawable.bitmap` → `Palette.from(bmp).generate()`（放 `Dispatchers.Default`）。背景取深色系（`darkVibrant…dominant`，过亮向黑收敛保证白字可读）、强调色取鲜艳系（`vibrant…dominant`，过暗向白提亮）。整段放 `LaunchedEffect(url)` 内自动取消。依赖 `androidx.palette:palette-ktx`。

**参考实现**：`PlayerPanel.kt` 的 `rememberPlayerPalette`。

---

## 列表分页：切筛选必须 `scrollToItem(0)`，否则会连锁自动翻页

**What**：`LazyVerticalGrid` 做「滚到接近底部自动加载下一页」时，切来源/地区/分类/收藏视图后。

**Why**：`LazyGridState` 是 `remember` 的，跨筛选切换不重置。旧位置若很深（如 index 200），新筛选只有一页（60 条），grid 会把位置夹到列表末尾 → 立刻命中预取阈值 → 加载下一页 → 仍在末尾 → 一路自动翻到底。分页前这只是「显示位置略怪」，分页后是真 bug。

**How**：`LaunchedEffect(selectedSource, selectedProvinceCode, selectedCategoryId, showFavorites) { gridState.scrollToItem(0) }`。空列表上调用是 no-op，首次组合时安全。

触发判断用 `snapshotFlow { visibleItemsInfo.lastOrNull()?.index } .distinctUntilChanged()`，不要在 composition 里直接判断（每帧重组都跑一次）。阈值用 `layoutInfo.totalItemsCount` 现算，别把 `UiState` 塞进 `LaunchedEffect` 的 key（列表每次追加都会重启 effect）。D-pad 不需要单独逻辑：焦点移动同样滚动列表，`visibleItemsInfo` 一样变化。

幂等守卫放 ViewModel（滚动会连续触发十几次），UI 只负责报告「快到底了」。

**参考实现**：`RadioScreen.kt` 的 `gridState` 两个 `LaunchedEffect` + `RadioViewModel.loadMoreChannels`。

---

## TV 与手机共用 Activity：系统栏策略按设备分流

**What**：同一个 Compose Activity 同时跑 Android TV 与手机时，TV 保持沉浸式，手机按
edge-to-edge 展示透明系统栏并避让安全区。

**Why**：全局调用 `hide(statusBars())` 会把 TV 的全屏假设带到手机，导致手机没有正常状态栏；
仅设置 `decorFitsSystemWindows=false` 又不消费 insets，则顶栏会被状态栏、刘海或挖孔遮住。

**How**：Activity 用 `FEATURE_LEANBACK` 分流：TV 继续 `setDecorFitsSystemWindows(false)` +
隐藏状态栏；手机调用 `enableEdgeToEdge`，深色页面使用透明栏与浅色系统图标。Compose 根内容
对非 TV 应用 `Modifier.windowInsetsPadding(WindowInsets.safeDrawing)`；外层 Surface 背景色必须
覆盖整窗，才能做到“背景延伸、交互内容避让”。输入页在 Activity 上声明 `adjustResize`，让旧版
Android 也能在系统输入法出现时正确调整 Compose 可用区域。

若竖屏底部是常驻播放器，根节点整体避让 `safeDrawing` 后，导航手势区会露出页面主背景，
与播放器形成断层。保持控件避让不变，同时在根节点的 padding 之前用 `drawBehind` 按
`WindowInsets.navigationBars.getBottom(density)` 高度补绘播放器 `surface` 色；这样小白条背景
与播放器连成一体，又不会让按钮落进系统手势热区。设置页等非播放器页面不要补绘该颜色。

**参考实现**：`MainActivity.kt` 的 `configureSystemBars`、`RadioScreen.kt` 根 `Box`。

手机横屏进入真正的全屏播放时，要临时隐藏 `WindowInsetsCompat.Type.systemBars()`，并在 Android P+
把 `layoutInDisplayCutoutMode` 设为 `SHORT_EDGES`；同一状态下根内容不再消费 `safeDrawing`，否则
左侧挖孔虽已允许绘制，Compose 仍会保留 cutout inset，把整套播放器 UI 推向右边。用
`DisposableEffect` 保存并恢复原 cutout mode，退出全屏或旋转回竖屏时同时 `show(systemBars())`。

---

## 顶栏可变 chip：动画容器不要包住固定兄弟按钮

**What**：顶栏中一个 chip 在展开/收起时切换内容，旁边还有搜索等固定图标按钮。

**Why**：若把 `animateContentSize()` 挂在整个按钮组上，固定按钮也会参与宽高动画；新增一个
44dp 图标后还可能把原本约 36dp 的 chip 行撑高，导致右侧时钟/设置随父 Row 垂直居中而抖动。

**How**：只用一个 `Box(Modifier.padding(vertical = 4.dp).animateContentSize())` 包住两种互斥
chip，使其总高稳定为 44dp；搜索等固定按钮放在该 Box 外、同级 Row 内。这样 chip 保留尺寸
过渡，固定按钮不参与高度动画。

**参考实现**：`RadioScreen.kt` 顶栏的 `FavoriteFilterChip` / `CompactFilter` 切换容器。

---

## 混用 tv-material 与 compose material3：两套主题都要套

**What**：主体 UI 走 `androidx.tv.material3`，个别手机专用组件（如 `SearchBar`）只有
`androidx.compose.material3` 版本。

**Why**：两个库各有独立的 `LocalColorScheme` 等 CompositionLocal，互不读取。只套
tv-material 主题时，M3 组件会退回默认浅紫配色，在深色页面上非常突兀。

**How**：在 `RadioTvTheme` 里用 `androidx.compose.material3.MaterialTheme` 外层包住
tv-material 的 `MaterialTheme`，两边各喂一份同色板的 colorScheme。M3 组件的容器色常取自
`surfaceContainerHigh` 之类的扩展槽位，别只覆盖 `surface`，漏掉的槽位仍会用默认值。
依赖只在 `libs.versions.toml` 里加不带版本的 material3（版本由 Compose BOM 统一）。

**参考实现**：`ui/theme/Theme.kt` 的 `M3MaterialTheme` 嵌套、`ui/components/MobileSearchBar.kt`。

## 只对单一来源生效的设置项：按 `selectedSource` 条件展示，紧贴「电台来源」

**What**：设置页里只对某一个电台来源有意义的项（如「TuneIn 代理」），包在
`if (selectedSource == RadioSourceType.TUNEIN) { ... }` 里，位置紧跟 `SourceDropdown` 之下；
自动播放、自动全屏这类三来源通用项才常驻展示。

**Why**：常驻展示时只能靠副标题解释「仅对 X 生效」，用户在别的来源下读到一个永远不生效的
开关，比它偶尔消失更费解。紧贴来源项则让「切了来源、这项才出现」的因果一眼可见。

**How**：**值仍存全局 key**（`UserPreferences` 里不按来源加后缀），只有可见性受来源约束 ——
用户切走再切回来，开关状态不能丢；条件隐藏是展示层的事，别顺手把存储也按来源隔离。
条件块内自带前置 `Spacer`，避免隐藏时页面留下双倍间距。

**参考实现**：`ui/SettingsScreen.kt` 的 `tuneInProxy` 分支、`UserPreferences.tuneInProxy`。

---

## TV 遥控逐字输入：复用 `KeyboardGrid`，别再手搭一套定宽网格键盘

**What**：任何需要电视遥控器 D-pad 逐字输入的场景（搜索、激活码等短文本输入）。

**Why**：`SearchPanel.kt` 最初把「定宽网格键盘」私有实现在自己文件里；激活码输入（`ActivationCodeDialog.kt`）
需要同样的交互时，若照抄一份会形成两处几乎相同的 `Row`/`Column` 网格 + 焦点样式代码，后续任一处调整
（列数、焦点色、方键圆角）都要同步改两遍。已提取为公共组件，新增输入场景应复用它，不要再手搭。

**How**：`ui/components/KeyboardGrid.kt` 暴露 `KeyboardKey(label, wide, onClick)` 与
`KeyboardGrid(keys, columns, firstKey)`——调用方只负责把自己的按键语义（字符/退格/切换模式等）映射成
`KeyboardKey` 列表并在 `onClick` 闭包里处理，网格布局、方键宽高比、焦点样式、首键自动聚焦均由组件统一
负责。`columns` 按场景调（`SearchPanel` 用 5 列适配横屏搜索，`ActivationCodeDialog` 用 8 列适配
"31 字符+退格"更宽的弹窗）。

**参考实现**：`ui/components/KeyboardGrid.kt`；调用方 `SearchPanel.kt`（5 列，字母/数字双模式）、
`ActivationCodeDialog.kt`（8 列，激活码字符集）。

---

## 深色主题里画二维码：必须黑码白底 + 静区，且要有回环解码测试兜底

**What**：在本项目（全局深色主题）任何位置展示二维码时。

**Why**：二维码「画出来了」和「扫得出来」是两回事，而两者的差别**肉眼看不出来**：

- 跟着深色主题把模块画成浅色、底画成深色 → 对比度不足，多数手机相机直接读不出。
- 四周没有静区（quiet zone）→ 扫码器无法定位，同样读不出。
- `EncodeHintType.MARGIN` 的默认值随 zxing 版本变化，不显式给就是在赌。

这类错误最坑的地方在于：截图看着完全正常，只有真机扫的时候才发现不行；而人工扫一次只
能证明「当时那一版是好的」，之后谁改了配色、`MARGIN` 或尺寸都不会有人再扫一遍。

**How**：

- 二维码固定**黑码白底**（`0xFF000000` / `0xFFFFFFFF` 字面量，别跟主题色走），画在一块
  **白色 Surface** 上再加 `padding`，不要直接铺在弹窗的深色背景上。
- 显式给 `EncodeHintType.MARGIN`（模块数），外层 padding 只是视觉留白，不能替代静区。
- `Image(filterQuality = FilterQuality.None)`，并让位图按最终显示尺寸生成 —— 插值会把模块
  边缘磨糊。
- **把「生成像素方阵」与「转成 Android Bitmap」拆成两个函数**：前者不碰任何 Android 类型
  （所以不能用 `android.graphics.Color.BLACK`，那是 Android 类），单元测试就能把它的输出
  直接喂给 zxing 解码器做回环，断言解出来的确实是原文，并逐圈检查最外缘全白。这样
  「能不能扫」变成 CI 里跑得动的断言，而不是一次性目测。
- 依赖只加 `com.google.zxing:core`（纯 Java，约 530KB）。`android-integration` /
  `zxing-android-embedded` 是给**扫码**用的（要相机权限与 Activity），生成用不上。

**参考实现**：`ui/components/PurchaseDialog.kt` 的 `encodeQrPixels` / `encodeQr`；
测试 `app/src/test/java/cn/radio/tv/ui/PurchaseQrCodeTest.kt`。

---

## 外跳浏览器：`FLAG_ACTIVITY_NEW_TASK` + 打不开时要有回落

**What**：任何从 Compose 里 `startActivity(ACTION_VIEW, url)` 外跳浏览器的入口。

**Why**：两个坑叠在一起 ——

1. `LocalContext.current` 在某些宿主下不是 Activity，不带 `FLAG_ACTIVITY_NEW_TASK` 会直接抛异常。
2. TV 盒子与精简 ROM 上**常常压根没有浏览器**，`ActivityNotFoundException` 是常态而非异常情况。
   此时若静默失败，用户看到的是「点了没反应」，分不清是自己没点到还是应用坏了。

**How**：把外跳封成返回 `Boolean` 的扩展函数，调用方据此走回落路径（如改为展示二维码弹窗
或链接原文），而不是吞掉异常：

```kotlin
private fun Context.openUrl(url: String): Boolean = runCatching {
    startActivity(Intent(Intent.ACTION_VIEW, url.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    true
}.getOrDefault(false)
```

**参考实现**：`ui/SettingsScreen.kt` 的 `openUrl` 与「购买激活码」条目。
