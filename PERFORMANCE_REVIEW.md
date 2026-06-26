# 性能审查与优化记录

> 面向 Android TV(弱性能设备)的网络广播 App。审查日期:2026-06-26。
> 状态图例:⬜ 待优化 / 🔧 进行中 / ✅ 已完成 / ⏸️ 暂不优化

---

## 🔴 高影响（明显拖慢弱性能 TV）

### #1 release 构建关闭了 R8/资源压缩 ✅
- **位置**:`app/build.gradle.kts` `optimization { enable = false }`
- **问题**:完全关闭代码优化、压缩、混淆与资源裁剪。DEX 体积大、方法数多、无内联,导致冷启动慢、内存占用高。
- **优化**:release 开启 minify + resource shrinking + R8,配套 ProGuard 规则。

### #2 UiState 每次 copy() 重算 favoriteIds / displayedChannels ✅
- **位置**:`RadioViewModel.kt` `RadioUiState.favoriteIds` / `displayedChannels`
- **问题**:写成属性初始化器,播放器 `isPlaying`/`isBuffering` 两条 Flow 频繁触发 `copy()`,每次都重建 HashSet + list,造成持续 GC 压力(缓冲时尤甚)。
- **优化**:改为 `by lazy` 惰性计算,仅首次访问时构建。

---

## 🟠 中影响（特定场景明显）

### #3 启动时三次串行网络请求 ✅
- **位置**:`RadioViewModel.bootstrap()`
- **问题**:`fetchProvinces()` → `fetchCategories()` → `loadChannels()` 串行,首屏内容出现前需 3 个串行往返,弱网下首屏慢。
- **优化**:provinces / categories 用 `async` 并行拉取。

### #4 收藏自动刷新按城市数 N 次请求 + 重复请求 ✅
- **位置**:`RadioRepository.refreshFavoritePrograms()` / `RadioViewModel.refreshPrograms()`
- **问题**:跨 N 城收藏 → 每次刷新发 N+ 个整列表请求;`refreshPrograms` 还可能为在播电台再发第 2 次整列表请求。
- **优化**:限制并发;复用已拉取数据避免重复请求。

### #5 自动刷新不分前后台、无条件每 30 分钟唤醒 ✅
- **位置**:`RadioViewModel.startProgramAutoRefresh()` (`while(true)` + `viewModelScope`)
- **问题**:TV 常驻开机,后台仍定时网络 + 全量状态更新 + 重组。
- **优化**:绑定生命周期(仅前台刷新)/ 无在播且非收藏视图时跳过本轮。

### #6 单一大 UiState 导致全树重组 ✅
- **位置**:`RadioScreen.kt` 读取整个 `state`
- **问题**:`isBuffering`/`isPlaying` 抖动触发整个 `RadioScreen` 重组(含 Grid 区 `when` 块重跑),叠加 #2 放大开销。
- **优化**:配合 #2 惰性化降低重组成本;必要时 `derivedStateOf` 隔离订阅。

### #7 HttpLoggingInterceptor 在 release 也常驻 ✅
- **位置**:`NetworkModule.okHttpClient`
- **问题**:每请求多一层字符串拼接 + 日志 I/O。
- **优化**:仅 `BuildConfig.DEBUG` 时添加该拦截器。

---

## 🟡 低影响（建议优化，长期收益）

### #8 Coil 图片无显式缓存/降采样策略 ⬜
- **位置**:`ChannelCard.kt` / `PlayerPanel.kt` 的 `AsyncImage`
- **优化**:显式 `size()`、关 crossfade、配 memory/disk cache policy。

### #9 ExoPlayer 用默认 LoadControl 且无音频属性/唤醒锁 ⬜
- **位置**:`RadioPlayer.kt`
- **优化**:直播 HLS 调小缓冲窗口加快起播、降内存;设 `setAudioAttributes` + `setWakeMode`。

### #10 展开筛选时重复构建 FilterItem 列表 / 折叠时线性扫描 ⬜
- **位置**:`RadioScreen.kt` `provinces.map{}` / `categories.map{}` / `currentProvinceName` 等
- **优化**:`remember(state.provinces)` 缓存映射结果。

### #11 MD5 逐字节 String.format ⬜
- **位置**:`SignInterceptor.md5Upper()`
- **优化**:十六进制查表替代 `"%02x".format`。

### #12 SDK 版本不一致 ⬜
- **位置**:`build.gradle.kts`(compileSdk 37 / targetSdk 36 / minSdk 28)
- **优化**:对齐 SDK 版本。

### #13 每次 DataStore 发射都解析整份收藏 JSON ⬜
- **位置**:`UserPreferences.favorites`
- **优化**:列表小可接受;增长后缓存上次解析结果。

---

## 优化批次

- **本批次(高+中影响)**:#1 #2 #3 #4 #5 #6 #7
- **后续(低影响)**:#8 #9 #10 #11 #12 #13
