package cn.radio.tv.data.browse

import androidx.paging.PagingSource
import androidx.paging.PagingState
import cn.radio.tv.data.model.Channel
import cn.radio.tv.data.source.GatewaySource
import kotlinx.coroutines.CancellationException

/**
 * 频道列表的 [PagingSource]：把网关的 offset/limit 契约直接映射为 Paging 的页。
 *
 * key = offset。服务端响应里**没有总数**（`ApiResponse` 只有 code/message/data），
 * 因此判断"还有下一页"的唯一依据是返回条数是否等于请求条数。
 * 已知代价：列表长度恰为页大小整数倍时，会多发一次返回空数组的请求。除非网关补 total，
 * 否则无法消除，这里如实保留而不是假装解决。
 */
class ChannelPagingSource(
    private val gateway: GatewaySource,
    private val query: BrowseQuery,
) : PagingSource<Int, Channel>() {

    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, Channel> {
        val offset = params.key ?: 0
        return try {
            val page = if (query.isSearch) {
                // 搜索是整份目录范围，不受地区/分类影响 —— 故这里不传它们。
                gateway.searchChannels(
                    source = query.source,
                    q = query.query,
                    offset = offset,
                    limit = params.loadSize,
                )
            } else {
                gateway.fetchChannels(
                    source = query.source,
                    categoryId = query.categoryId,
                    provinceCode = query.provinceCode,
                    offset = offset,
                    limit = params.loadSize,
                )
            }
            LoadResult.Page(
                data = page,
                prevKey = null,  // 只向后翻，不支持反向分页
                nextKey = if (page.size < params.loadSize) null else offset + page.size,
            )
        } catch (e: CancellationException) {
            throw e  // 取消语义必须原样上抛，不能被当成加载失败
        } catch (e: Exception) {
            LoadResult.Error(e)
        }
    }

    /**
     * 刷新时回到首页。
     *
     * 返回 null 即从 offset 0 重新加载。对电台列表这是正确的：服务端排序会变
     * （节目单更新、上下线），锚定旧 offset 反而会漏台或重复。
     */
    override fun getRefreshKey(state: PagingState<Int, Channel>): Int? = null
}
