package cn.radio.tv.data

import cn.radio.tv.data.model.Category
import cn.radio.tv.data.model.Channel
import cn.radio.tv.data.model.FavoriteChannel
import cn.radio.tv.data.model.Province
import cn.radio.tv.data.prefs.UserPreferences
import cn.radio.tv.data.remote.NetworkModule
import cn.radio.tv.data.remote.RadioApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
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

            val distinctProvinces = favorites.map { it.provinceCode }.distinct()

            // 各城市相互独立，并行拉取以缩短整体刷新等待；用信号量限制并发，
            // 避免收藏跨多城时一次性发起过多请求拖垮弱性能 TV / 触发限流。
            val gate = Semaphore(MAX_CONCURRENT_REFRESH)
            val latestByProvince: Map<Long, Map<String, Channel>> = coroutineScope {
                distinctProvinces.map { code ->
                    async {
                        code to gate.withPermit {
                            runCatching {
                                api.getChannels(
                                    categoryId = UserPreferences.DEFAULT_CATEGORY_ID,
                                    provinceCode = code,
                                ).dataOrThrow("收藏电台列表")
                            }.getOrDefault(emptyList())
                                .associateBy { it.contentId }
                        }
                    }
                }.awaitAll().toMap()
            }

            favorites.map { fav ->
                val latest = latestByProvince[fav.provinceCode]?.get(fav.channel.contentId)
                if (latest != null) fav.copy(channel = latest) else fav
            }
        }

    private fun <T> cn.radio.tv.data.model.ApiResponse<T>.dataOrThrow(what: String): T {
        if (code != 0 || data == null) {
            throw IllegalStateException("获取${what}失败：${message ?: "code=$code"}")
        }
        return data
    }

    private companion object {
        /** 收藏跨城刷新时的最大并发请求数，平衡刷新速度与弱 TV 负载。 */
        const val MAX_CONCURRENT_REFRESH = 4
    }
}
