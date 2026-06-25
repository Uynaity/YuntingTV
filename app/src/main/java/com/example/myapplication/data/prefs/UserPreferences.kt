package com.example.myapplication.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.myapplication.data.model.Channel
import com.example.myapplication.data.model.FavoriteChannel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "radio_prefs")

/** 用户上次选择的地区与分类（记住选择），以及收藏的电台。 */
class UserPreferences(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true }

    /** 用户上次选择的筛选项；首次为默认值（国家 + 全部）。 */
    data class Selection(val provinceCode: Long, val categoryId: String)

    val selection: Flow<Selection> = context.dataStore.data.map { prefs ->
        Selection(
            provinceCode = prefs[KEY_PROVINCE] ?: DEFAULT_PROVINCE_CODE,
            categoryId = prefs[KEY_CATEGORY] ?: DEFAULT_CATEGORY_ID,
        )
    }

    suspend fun saveSelection(provinceCode: Long, categoryId: String) {
        context.dataStore.edit { prefs ->
            prefs[KEY_PROVINCE] = provinceCode
            prefs[KEY_CATEGORY] = categoryId
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

        private val KEY_PROVINCE = longPreferencesKey("province_code")
        private val KEY_CATEGORY = stringPreferencesKey("category_id")
        private val KEY_FAVORITES = stringPreferencesKey("favorites")
    }
}
