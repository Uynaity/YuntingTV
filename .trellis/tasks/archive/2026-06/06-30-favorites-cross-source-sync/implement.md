# 执行计划 — 收藏夹跨来源同步

## 步骤

1. **模型加来源字段**
   - `RadioSourceType.kt`：枚举上加 `@Serializable`。
   - `Models.kt`：`FavoriteChannel` 加 `val source: RadioSourceType = RadioSourceType.YUNTING`（默认仅兼容老 JSON）。
   - 校验：项目编译（kotlinx 序列化对枚举/默认值）。

2. **存储层戳源**
   - `UserPreferences.favorites(source)`：解码后 `.map { it.copy(source = source) }`。
   - `toggleFavorite`：新增项构造带 `source`。
   - `saveLastPlayed`：构造 `FavoriteChannel(channel, provinceCode, source)`。

3. **ViewModel 订阅合并**
   - 收藏订阅改 `combine(prefs.favorites(YUNTING), prefs.favorites(QINGTING)) { y, q -> y + q }`（去掉 flatMapLatest）。
   - `RadioUiState`：`favoriteIds` 改为按 `selectedSource` 过滤；加 `playingSource` 字段与 `currentIsFavorite` lazy 派生。

4. **播放路由**
   - `playChannel`：按 showFavorites 区分，取收藏项 source/provinceCode 或当前源；设置 `playingSource` 并写入 state；`saveLastPlayed` 用 playingSource。
   - `loadSource`：设 currentChannel 时同步 `playingSource = source`。

5. **节目单刷新分组**
   - 提取 `refreshFavoritesGrouped(favs)`：按 source 分组、各源 `refreshFavoritePrograms` 后 `saveFavorites(src, ...)`。
   - `showFavoritesView`/`refreshFavorites` 调用它。
   - `refreshPrograms` 收藏分支用它；正在播放分支改用 `sources.getValue(playingSource)` + playingProvinceCode。

6. **UI 星标**
   - `RadioScreen` PlayerPanel：`isFavorite = state.currentIsFavorite`。
   - Grid 卡片星标不动。

## 验证命令

```bash
./gradlew :app:compileDebugKotlin   # 编译通过
./gradlew :app:assembleDebug        # 打包（如需真机验证）
```

## 手动验收（对应 prd Acceptance Criteria）

- 云听下打开 ★收藏 → 看到两源台。
- 云听下点蜻蜓收藏台 → 出声、云听列表不清空。
- 跨源播放后 PlayerPanel 标题前有金色 ★。
- 普通浏览云听 → 星标只按云听。
- ★收藏 视图等到半点 → 两源节目单刷新不串源（或临时缩短间隔验证）。

## 回滚点

每步可独立编译；如序列化/路由出问题，revert 本任务提交即可，DataStore 多出的 `source` 字段被旧版 `ignoreUnknownKeys` 忽略，无破坏。
