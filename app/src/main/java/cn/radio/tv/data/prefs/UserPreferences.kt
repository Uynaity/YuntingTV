package cn.radio.tv.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import cn.radio.tv.data.model.Channel
import cn.radio.tv.data.model.FavoriteChannel
import cn.radio.tv.data.source.RadioSourceType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "radio_prefs")

/**
 * 用户偏好存储。
 *
 * 全局项：[selectedSource]（当前电台来源）、[autoPlayLast]（启动自动播放）、[lastPlayed]（上次播放，跨源单条）。
 * 按来源隔离项：所在城市、收藏列表 —— 各来源独立 key，互不混合 / 不同步。
 * 云听沿用历史无后缀 key 名，老用户升级后收藏与上次播放保持不变。
 */
class UserPreferences(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true }


    val selectedSource: Flow<RadioSourceType> = context.dataStore.data.map { prefs ->
        RadioSourceType.fromKey(prefs[KEY_SELECTED_SOURCE])
    }

    suspend fun saveSelectedSource(source: RadioSourceType) {
        context.dataStore.edit { it[KEY_SELECTED_SOURCE] = source.key }
    }

    /** 启动时是否自动播放上次电台；默认开启。来源切换不影响该全局开关。 */
    val autoPlayLast: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_AUTO_PLAY] ?: DEFAULT_AUTO_PLAY
    }

    suspend fun saveAutoPlayLast(enabled: Boolean) {
        context.dataStore.edit { it[KEY_AUTO_PLAY] = enabled }
    }

    /** 首页播放中 30s 无操作是否自动进入全屏；默认开启。 */
    val autoFullscreen: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_AUTO_FULLSCREEN] ?: DEFAULT_AUTO_FULLSCREEN
    }

    suspend fun saveAutoFullscreen(enabled: Boolean) {
        context.dataStore.edit { it[KEY_AUTO_FULLSCREEN] = enabled }
    }


    /** 某来源的所在城市；未设定时回退 [default]（由各来源决定，多数为「全部」）。 */
    fun homeCity(source: RadioSourceType, default: Long = DEFAULT_PROVINCE_CODE): Flow<Long> =
        context.dataStore.data.map { prefs ->
            prefs[homeCityKey(source)] ?: default
        }

    suspend fun saveHomeCity(source: RadioSourceType, provinceCode: Long) {
        context.dataStore.edit { it[homeCityKey(source)] = provinceCode }
    }


    /**
     * 全局上次播放的电台快照（含所属来源与城市，用于跨源续播后刷新节目单）；无则为 null。
     * 不按来源隔离：跨源收藏播放后，恢复的应是真正最后一次播放的电台，而非选中来源的旧记录。
     */
    fun lastPlayed(): Flow<FavoriteChannel?> =
        context.dataStore.data.map { prefs ->
            prefs[KEY_LAST_PLAYED]?.let { raw ->
                runCatching { json.decodeFromString<FavoriteChannel>(raw) }.getOrNull()
            }
        }

    suspend fun saveLastPlayed(source: RadioSourceType, channel: Channel, provinceCode: Long) {
        context.dataStore.edit {
            it[KEY_LAST_PLAYED] =
                json.encodeToString(FavoriteChannel(channel, provinceCode, source))
        }
    }


    /**
     * 某来源的收藏列表（最近收藏在前）。解析失败回退空列表。
     * 解码后按存储 key 重新戳 [FavoriteChannel.source]，使老数据（无 source 字段）
     * 自动带上正确来源，供上层跨源合并后路由播放/刷新。
     */
    fun favorites(source: RadioSourceType): Flow<List<FavoriteChannel>> =
        context.dataStore.data
            .map { prefs -> prefs[favoritesKey(source)] }
            .distinctUntilChanged()  // 无关键（自动播放/所在城市等）写入不再触发收藏 JSON 重解码
            .map { raw ->
                raw?.let {
                    runCatching { json.decodeFromString<List<FavoriteChannel>>(it) }
                        .getOrDefault(emptyList())
                        .map { fav -> fav.copy(source = source) }
                } ?: emptyList()
            }

    /**
     * 切换收藏状态：已收藏则移除，未收藏则加入（置于最前）。
     * [provinceCode] 为收藏时电台所在城市，用于日后按城市刷新节目单。
     */
    suspend fun toggleFavorite(source: RadioSourceType, channel: Channel, provinceCode: Long) {
        context.dataStore.edit { prefs ->
            val key = favoritesKey(source)
            val current = prefs[key]
                ?.let { runCatching { json.decodeFromString<List<FavoriteChannel>>(it) }.getOrNull() }
                ?: emptyList()
            val updated = if (current.any { it.channel.contentId == channel.contentId }) {
                current.filterNot { it.channel.contentId == channel.contentId }
            } else {
                listOf(FavoriteChannel(channel, provinceCode, source)) + current
            }
            prefs[key] = json.encodeToString(updated)
        }
    }

    /** 用最新快照覆盖某来源的整份收藏列表（刷新节目单后写回）。 */
    suspend fun saveFavorites(source: RadioSourceType, favorites: List<FavoriteChannel>) {
        context.dataStore.edit { it[favoritesKey(source)] = json.encodeToString(favorites) }
    }

    // 云听沿用历史无后缀 key 名以保留既有数据；其余来源加 _<key> 后缀隔离。
    private fun scoped(base: String, source: RadioSourceType): String =
        if (source == RadioSourceType.YUNTING) base else "${base}_${source.key}"

    private fun favoritesKey(source: RadioSourceType) =
        stringPreferencesKey(scoped("favorites", source))

    private fun homeCityKey(source: RadioSourceType) =
        longPreferencesKey(scoped("home_city_code", source))

    companion object {
        const val DEFAULT_PROVINCE_CODE = 0L      // 全部（全国台 / 全部地区）
        const val DEFAULT_CATEGORY_ID = "0"        // 全部
        const val DEFAULT_AUTO_PLAY = true         // 默认启动自动播放上次电台
        const val DEFAULT_AUTO_FULLSCREEN = true   // 默认首页 30s 无操作自动进全屏

        private val KEY_SELECTED_SOURCE = stringPreferencesKey("selected_source")
        private val KEY_AUTO_PLAY = booleanPreferencesKey("auto_play_last")
        private val KEY_AUTO_FULLSCREEN = booleanPreferencesKey("auto_fullscreen")

        // 沿用云听历史无后缀 key，老用户升级后上次播放续播不变。
        private val KEY_LAST_PLAYED = stringPreferencesKey("last_played")
    }
}
