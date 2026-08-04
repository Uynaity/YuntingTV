package cn.radio.tv.data.browse

import cn.radio.tv.data.source.RadioSourceType

/**
 * 一次浏览请求的完整输入。
 *
 * 把「来源 + 地区 + 分类 + 搜索词」收成一个不可变值，是分页重构的支点：
 * 查询一变就是一套全新的分页，旧分页整体作废。此前这套语义靠 `channelsGeneration`
 * 代次计数器、`loadChannelsJob`/`loadMoreJob` 两个 job 引用和 UI 侧的幂等守卫
 * 手工维持，散落在四处且各自有边界情况。
 *
 * [query] 非空即搜索模式。搜索与普通浏览的分页契约在服务端是同一套（offset/limit），
 * 故这里合成同一个查询类型，不再维护两套并行的分页状态。
 */
data class BrowseQuery(
    val source: RadioSourceType,
    val provinceCode: Long,
    val categoryId: String,
    val query: String = "",
) {
    val isSearch: Boolean get() = query.isNotBlank()
}
