# 技术设计 — 收藏夹跨来源同步

## 核心思路

存储保持现状（仍按源分 key），不做数据迁移。改动集中在「读取合并 + 来源标记 + 播放路由」：

1. 给 `FavoriteChannel` 加 `source: RadioSourceType` 字段，使收藏项在内存中自带来源，供播放/刷新路由。
2. `UserPreferences.favorites(source)` 读取时把每条 `source` **戳成该 key 对应的来源**——既解决新字段，又自动让老数据带上正确来源（无迁移）。
3. ViewModel 用 `combine(favorites(YUNTING), favorites(QINGTING))` 订阅，`state.favorites` 变为**合并列表**；由它派生当前源星标集合与收藏视图展示。
4. 播放与节目单刷新按收藏项自身 `source` 路由到 `sources[source]`。

## 数据模型

`Models.kt`：
```kotlin
@Serializable
data class FavoriteChannel(
    val channel: Channel,
    val provinceCode: Long,
    val source: RadioSourceType = RadioSourceType.YUNTING, // 默认值仅为兼容老 JSON；读取时按 key 重新戳源
)
```
`RadioSourceType.kt`：枚举加 `@Serializable`（FavoriteChannel 序列化需要；默认按枚举名持久化）。

## 存储层（UserPreferences）

- `favorites(source)`：解码后 `.map { it.copy(source = source) }`，保证内存里每条都带正确来源（覆盖老数据默认值）。
- `toggleFavorite(source, channel, provinceCode)`：新增收藏时构造 `FavoriteChannel(channel, provinceCode, source)`。
- `saveLastPlayed(source, channel, provinceCode)`：构造时带上 `source`（lastPlayed 解码端不依赖该字段，但保持一致）。
- 不新增合并方法；合并在 ViewModel 完成（save 仍需按源写回，故保留 per-source API）。

## ViewModel（RadioViewModel）

### 订阅
把原「`flatMapLatest(selectedSource) -> favorites`」改为合并两源：
```kotlin
combine(prefs.favorites(YUNTING), prefs.favorites(QINGTING)) { y, q -> y + q }
    .collect { favs -> _uiState.update { it.copy(favorites = favs) } }
```
`state.favorites` 即合并列表，云听在前。toggle 写入任一源都会触发 combine 重新合并。

### 派生状态（RadioUiState）
- `favoriteIds`（星标，仅当前源）：`favorites.filter { it.source == selectedSource }.mapTo(HashSet) { it.channel.contentId }`。
- `displayedChannels` 收藏视图：`favorites.map { it.channel }`（全部，不变逻辑、源已合并）。
- 新增 `playingSource: RadioSourceType`（默认 = selectedSource）放入 state，供正在播放面板星标判断。
- 新增 `currentIsFavorite`（lazy）：`currentChannel != null && favorites.any { it.channel.contentId == currentChannel.contentId && it.source == playingSource }`。

### 播放路由 `playChannel(channel)`
确定该次播放的来源与城市：
- 收藏视图（`showFavorites`）：在合并 `favorites` 里按 contentId 找到该收藏项，取其 `source` 与 `provinceCode`。
  - ponytail: 两源 contentId 偶然相同时 `firstOrNull` 取云听项；概率极低，接受。
- 普通视图：source = `selectedSource`，城市沿用现逻辑（当前源收藏匹配 provinceCode，否则 `selectedProvinceCode`）。

随后：`playingSource = 该源`、`playingProvinceCode = 该城市`，写入 state.playingSource；`saveLastPlayed(playingSource, channel, playingProvinceCode)`。
> lastPlayed 写到该台自身来源：日后切到该来源会续播它；冷启动自动续播仍按 selectedSource 的 lastPlayed（沿用现状，跨源播放不改变当前源续播目标）。

### 节目单刷新
- `refreshFavorites()` 与 `refreshPrograms()` 收藏分支：收藏跨两源，需按 `source` 分组刷新。提取
  ```kotlin
  private suspend fun refreshFavoritesGrouped(favs) {
      favs.groupBy { it.source }.forEach { (src, list) ->
          val refreshed = sources.getValue(src).refreshFavoritePrograms(list)
          prefs.saveFavorites(src, refreshed) // 按源写回，combine 自动重新合并到 state
      }
  }
  ```
- `refreshPrograms()` 的「正在播放节目单」分支：改用 `sources.getValue(playingSource)` + `playingProvinceCode` 拉取（跨源播放的台不在当前源列表里，`refreshed` 复用仅在 `playingSource == selectedSource` 时有效，否则走该源单独拉取）。
- `loadSource(source)`：设置 currentChannel = 该源 lastPlayed 时，同步 `playingSource = source`、playingProvinceCode 不变逻辑。

## UI（RadioScreen）

- Grid 卡片星标：保持 `state.favoriteIds.contains(channel.contentId)`（当前源）。
- PlayerPanel 星标：由 `state.currentChannel?.let{ favoriteIds.contains }` 改为 `state.currentIsFavorite`（按 playingSource 判断）。
- 收藏视图渲染、点击/长按接线不变（`displayedChannels` 已是合并）。

## 兼容 / 回滚

- 兼容：老数据无迁移，读取时戳源；新增字段有默认值，旧 JSON 可反序列化。
- 回滚：纯代码改动，无 schema 破坏；revert 提交即可，DataStore 里多出的 `source` 字段对旧版本读取无影响（`ignoreUnknownKeys = true`）。

## 影响面

`Models.kt`、`RadioSourceType.kt`、`UserPreferences.kt`、`RadioViewModel.kt`、`RadioScreen.kt`（PlayerPanel 星标一行）。
