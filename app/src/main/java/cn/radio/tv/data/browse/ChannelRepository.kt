package cn.radio.tv.data.browse

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import cn.radio.tv.data.model.Channel
import cn.radio.tv.data.source.RadioSource
import cn.radio.tv.data.source.RadioSourceType
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest

/**
 * 频道浏览的分页数据源。
 *
 * [pagingFlow] 用 `flatMapLatest` 绑定查询流：查询一变即取消旧分页的在途请求
 * （取消沿协程边界传到 Retrofit），并整体换成新的一套分页。
 */
class ChannelRepository(
    private val sourceOf: (RadioSourceType) -> RadioSource,
) {

    @OptIn(ExperimentalCoroutinesApi::class)
    fun pagingFlow(queries: Flow<BrowseQuery>): Flow<PagingData<Channel>> =
        queries.flatMapLatest { query ->
            Pager(
                config = PagingConfig(
                    pageSize = RadioSource.PAGE_SIZE,
                    // Paging 默认首次加载 3 倍页大小，会把首屏的 JSON 解析与位图解码
                    // 一次性放大三倍，弱设备上很伤，故收紧到与常规翻页一致的单页体量。
                    initialLoadSize = RadioSource.PAGE_SIZE,
                    prefetchDistance = PREFETCH_DISTANCE,
                    enablePlaceholders = false,
                ),
                pagingSourceFactory = { ChannelPagingSource(sourceOf(query.source), query) },
            ).flow
        }

    companion object {
        /** 距列表底部多少项时预取下一页。 */
        const val PREFETCH_DISTANCE = 12
    }
}
