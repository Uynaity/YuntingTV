package com.example.myapplication.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "radio_prefs")

/** 用户上次选择的地区与分类（记住选择）。 */
class UserPreferences(private val context: Context) {

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

    companion object {
        const val DEFAULT_PROVINCE_CODE = 0L      // 国家（全国台）
        const val DEFAULT_CATEGORY_ID = "0"        // 全部

        private val KEY_PROVINCE = longPreferencesKey("province_code")
        private val KEY_CATEGORY = stringPreferencesKey("category_id")
    }
}
