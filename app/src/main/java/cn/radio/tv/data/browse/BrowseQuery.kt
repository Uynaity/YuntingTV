package cn.radio.tv.data.browse

import cn.radio.tv.data.source.RadioSourceType
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map

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

/**
 * 一次查询提交。[typed] 只影响防抖时长，不参与「是否重复」判断：
 * 用户打字要等一拍，切来源/筛选/退出搜索要立即换列表。
 */
data class QueryRequest(val query: BrowseQuery, val typed: Boolean)

/**
 * 把查询提交流整形成分页要的查询流。
 *
 * null 表示「首个查询尚未确定」（要先读出该来源的所在城市），此时不该发请求 ——
 * 否则冷启动会先按默认地区拉一份、再按所在城市拉一份。
 *
 * 防抖只加在打字上：[typedDebounceMs] 内的连续输入合成一次请求，而切筛选、切来源、
 * 退出搜索都是 typed=false，立即生效。去重在防抖之后、按 [BrowseQuery] 本身比较，
 * 故「打字后立刻切筛选」不会被误当成重复吞掉（typed 标志不参与相等判断）。
 */
@OptIn(FlowPreview::class)
fun Flow<QueryRequest?>.toBrowseQueries(typedDebounceMs: Long): Flow<BrowseQuery> =
    filterNotNull()
        .debounce { if (it.typed) typedDebounceMs else 0L }
        .map { it.query }
        .distinctUntilChanged()

