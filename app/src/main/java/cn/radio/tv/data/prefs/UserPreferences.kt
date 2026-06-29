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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "radio_prefs")

/** 用户设置（所在城市、启动自动播放）、上次播放的电台，以及收藏的电台。 */
class UserPreferences(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * 用户设置项。
     * [homeCityCode] 所在城市；默认 [DEFAULT_PROVINCE_CODE]（国家）。设定具体城市后，
     * 城市筛选栏将其置顶，且每次启动按此城市展示（不再记忆上次退出时的筛选）。
     * [autoPlayLast] 启动时是否自动播放上次退出前正在播放的电台；默认开启。
     */
    data class Settings(
        val homeCityCode: Long = DEFAULT_PROVINCE_CODE,
        val autoPlayLast: Boolean = DEFAULT_AUTO_PLAY,
    )

    val settings: Flow<Settings> = context.dataStore.data.map { prefs ->
        Settings(
            homeCityCode = prefs[KEY_HOME_CITY] ?: DEFAULT_PROVINCE_CODE,
            autoPlayLast = prefs[KEY_AUTO_PLAY] ?: DEFAULT_AUTO_PLAY,
        )
    }

    suspend fun saveHomeCity(provinceCode: Long) {
        context.dataStore.edit { prefs -> prefs[KEY_HOME_CITY] = provinceCode }
    }

    suspend fun saveAutoPlayLast(enabled: Boolean) {
        context.dataStore.edit { prefs -> prefs[KEY_AUTO_PLAY] = enabled }
    }

    /**
     * 上次播放的电台快照（含其所属城市，用于自动播放后刷新节目单）。
     * 从未播放过或解析失败时为 null。
     */
    val lastPlayed: Flow<FavoriteChannel?> = context.dataStore.data.map { prefs ->
        prefs[KEY_LAST_PLAYED]?.let { raw ->
            runCatching { json.decodeFromString<FavoriteChannel>(raw) }.getOrNull()
        }
    }

    /** 记录刚播放的电台及其所属城市，供下次启动自动续播。 */
    suspend fun saveLastPlayed(channel: Channel, provinceCode: Long) {
        context.dataStore.edit { prefs ->
            prefs[KEY_LAST_PLAYED] = json.encodeToString(FavoriteChannel(channel, provinceCode))
        }
    }

    /** 收藏列表（最近收藏在前）。解析失败时回退为空列表。 */
    val favorites: Flow<List<FavoriteChannel>> = context.dataStore.data.map { prefs ->
        prefs[KEY_FAVORITES]?.let { raw ->
            runCatching { json.decodeFromString<List<FavoriteChannel>>(raw) }.getOrDefault(emptyList())
        } ?: emptyList()
    }

    /**
     * 切换收藏状态：已收藏则移除，未收藏则加入（置于最前）。
     * [provinceCode] 为收藏时电台所在的城市，用于日后按城市刷新节目单。
     */
    suspend fun toggleFavorite(channel: Channel, provinceCode: Long) {
        context.dataStore.edit { prefs ->
            val current = prefs[KEY_FAVORITES]
                ?.let { runCatching { json.decodeFromString<List<FavoriteChannel>>(it) }.getOrNull() }
                ?: emptyList()
            val updated = if (current.any { it.channel.contentId == channel.contentId }) {
                current.filterNot { it.channel.contentId == channel.contentId }
            } else {
                listOf(FavoriteChannel(channel, provinceCode)) + current
            }
            prefs[KEY_FAVORITES] = json.encodeToString(updated)
        }
    }

    /** 用最新快照覆盖整份收藏列表（刷新节目单后写回）。 */
    suspend fun saveFavorites(favorites: List<FavoriteChannel>) {
        context.dataStore.edit { prefs ->
            prefs[KEY_FAVORITES] = json.encodeToString(favorites)
        }
    }

    companion object {
        const val DEFAULT_PROVINCE_CODE = 0L      // 国家（全国台）
        const val DEFAULT_CATEGORY_ID = "0"        // 全部
        const val DEFAULT_AUTO_PLAY = true         // 默认启动自动播放上次电台

        private val KEY_HOME_CITY = longPreferencesKey("home_city_code")
        private val KEY_AUTO_PLAY = booleanPreferencesKey("auto_play_last")
        private val KEY_LAST_PLAYED = stringPreferencesKey("last_played")
        private val KEY_FAVORITES = stringPreferencesKey("favorites")
    }
}
