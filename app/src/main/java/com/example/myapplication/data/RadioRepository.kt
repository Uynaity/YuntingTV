package com.example.myapplication.data

import com.example.myapplication.data.model.Category
import com.example.myapplication.data.model.Channel
import com.example.myapplication.data.model.FavoriteChannel
import com.example.myapplication.data.model.Province
import com.example.myapplication.data.prefs.UserPreferences
import com.example.myapplication.data.remote.NetworkModule
import com.example.myapplication.data.remote.RadioApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 统一封装电台数据获取；接口失败抛异常由上层处理。 */
class RadioRepository(
    private val api: RadioApi = NetworkModule.radioApi,
) {

    suspend fun fetchProvinces(): List<Province> = withContext(Dispatchers.IO) {
        api.getProvinces().dataOrThrow("省份")
    }

    suspend fun fetchCategories(): List<Category> = withContext(Dispatchers.IO) {
        api.getCategories().dataOrThrow("分类")
    }

    suspend fun fetchChannels(categoryId: String, provinceCode: Long): List<Channel> =
        withContext(Dispatchers.IO) {
            api.getChannels(categoryId = categoryId, provinceCode = provinceCode)
                .dataOrThrow("电台列表")
        }

    /**
     * 刷新收藏电台的节目单（subtitle）。
     *
     * 云听无「按 id 查电台」的接口，因此按收藏时记录的城市 [FavoriteChannel.provinceCode]
     * 分组，每个城市拉取一次「全部分类」的电台列表，再按 contentId 匹配，用最新的
     * subtitle / image / playUrlLow 覆盖快照。某城市拉取失败或电台已下架时，保留原快照。
     * 收藏的原有顺序保持不变。
     */
    suspend fun refreshFavoritePrograms(favorites: List<FavoriteChannel>): List<FavoriteChannel> =
        withContext(Dispatchers.IO) {
            if (favorites.isEmpty()) return@withContext favorites

            val latestByProvince: Map<Long, Map<String, Channel>> = favorites
                .map { it.provinceCode }
                .distinct()
                .associateWith { code ->
                    runCatching {
                        api.getChannels(categoryId = UserPreferences.DEFAULT_CATEGORY_ID, provinceCode = code)
                            .dataOrThrow("收藏电台列表")
                    }.getOrDefault(emptyList())
                        .associateBy { it.contentId }
                }

            favorites.map { fav ->
                val latest = latestByProvince[fav.provinceCode]?.get(fav.channel.contentId)
                if (latest != null) fav.copy(channel = latest) else fav
            }
        }

    private fun <T> com.example.myapplication.data.model.ApiResponse<T>.dataOrThrow(what: String): T {
        if (code != 0 || data == null) {
            throw IllegalStateException("获取${what}失败：${message ?: "code=$code"}")
        }
        return data
    }
}
