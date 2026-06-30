package cn.radio.tv.data.source

import cn.radio.tv.data.model.Category
import cn.radio.tv.data.model.Channel
import cn.radio.tv.data.model.FavoriteChannel
import cn.radio.tv.data.model.Province
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/**
 * 单个电台来源的数据契约。各来源（云听 / 蜻蜓FM）各自实现，把自家接口映射到
 * 共享的 [Province] / [Category] / [Channel] 业务模型；上层只面向本接口，
 * 切换来源即切换实现，列表与收藏天然互不混合。
 *
 * 约定：[fetchChannels] 的 categoryId / provinceCode 为 "0" / 0L 时表示「全部」。
 */
interface RadioSource {

    val type: RadioSourceType

    suspend fun fetchProvinces(): List<Province>

    suspend fun fetchCategories(): List<Category>

    suspend fun fetchChannels(categoryId: String, provinceCode: Long): List<Channel>

    /** 刷新收藏电台的节目单（subtitle 等）；见 [BaseRadioSource] 的通用实现。 */
    suspend fun refreshFavoritePrograms(favorites: List<FavoriteChannel>): List<FavoriteChannel>
}

/**
 * 提供收藏节目单刷新的通用实现：两来源均无「按 id 查电台」接口，故按收藏时记录的
 * 所在地区 [FavoriteChannel.provinceCode] 分组，每个地区拉一次「全部分类」列表，
 * 按 contentId 匹配并用最新快照覆盖。地区拉取失败或电台下架则保留原快照，原顺序不变。
 */
abstract class BaseRadioSource : RadioSource {

    override suspend fun refreshFavoritePrograms(
        favorites: List<FavoriteChannel>,
    ): List<FavoriteChannel> = withContext(Dispatchers.IO) {
        if (favorites.isEmpty()) return@withContext favorites

        val distinctProvinces = favorites.map { it.provinceCode }.distinct()

        // 各地区相互独立，并行拉取以缩短整体刷新等待；信号量限制并发，避免收藏跨多地区时
        // 一次性发起过多请求拖垮弱性能 TV / 触发限流。
        val gate = Semaphore(MAX_CONCURRENT_REFRESH)
        val latestByProvince: Map<Long, Map<String, Channel>> = coroutineScope {
            distinctProvinces.map { code ->
                async {
                    code to gate.withPermit {
                        runCatching { fetchChannels(ALL_CATEGORY_ID, code) }
                            .getOrDefault(emptyList())
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

    protected companion object {
        /** 「全部分类」的约定 categoryId。 */
        const val ALL_CATEGORY_ID = "0"

        /** 收藏跨地区刷新时的最大并发请求数，平衡刷新速度与弱 TV 负载。 */
        const val MAX_CONCURRENT_REFRESH = 4
    }
}
