package cn.radio.tv.ui

import cn.radio.tv.data.model.Channel
import cn.radio.tv.data.model.FavoriteChannel
import cn.radio.tv.data.source.RadioSourceType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 跨来源收藏的复合键。
 *
 * 三个来源的 contentId 空间彼此独立、不保证全局唯一。此前收藏视图把 `FavoriteChannel`
 * 拍平成裸 `Channel`（丢掉 source），LazyGrid 又拿 contentId 当唯一 key：
 * 相同 ID 的两个台一旦同时被收藏，Compose 因重复 key 直接崩溃，
 * 且播放/取消收藏会按裸 ID 反查来源、路由到错的源。
 */
class RadioUiStateFavoritesKeyTest {

    private fun channel(id: String, title: String = "台$id") =
        Channel(contentId = id, title = title)

    /** 两个来源恰好用了相同的 contentId —— 正是导致崩溃的那种数据。 */
    private val collidingFavorites = listOf(
        FavoriteChannel(channel("1001", "云听台"), provinceCode = 11, source = RadioSourceType.YUNTING),
        FavoriteChannel(channel("1001", "蜻蜓台"), provinceCode = 0, source = RadioSourceType.QINGTING),
    )

    @Test
    fun `跨来源同 contentId 的收藏产生不同的 grid key`() {
        val state = RadioUiState(favorites = collidingFavorites, showFavorites = true)

        val keys = state.displayedChannels.mapIndexed { i, ch -> state.gridKeyAt(i, ch) }

        assertEquals(2, keys.size)
        assertNotEquals("重复 key 会让 LazyGrid 抛异常崩溃", keys[0], keys[1])
        assertEquals(keys.size, keys.toSet().size)
    }

    @Test
    fun `收藏视图逐项来源与展示列表同序对齐`() {
        val state = RadioUiState(favorites = collidingFavorites, showFavorites = true)

        assertEquals(RadioSourceType.YUNTING, state.sourceAt(0))
        assertEquals(RadioSourceType.QINGTING, state.sourceAt(1))
        assertEquals(
            listOf("云听台", "蜻蜓台"),
            state.displayedChannels.map { it.title },
        )
    }

    @Test
    fun `非收藏视图每项来源即当前浏览来源`() {
        val state = RadioUiState(
            selectedSource = RadioSourceType.TUNEIN,
            channels = listOf(channel("1"), channel("2")),
        )

        assertNull(state.displayedSources)
        assertEquals(RadioSourceType.TUNEIN, state.sourceAt(0))
        assertEquals(RadioSourceType.TUNEIN, state.sourceAt(1))
        assertEquals("tunein:1", state.gridKeyAt(0, state.displayedChannels[0]))
    }

    @Test
    fun `搜索结果优先于收藏，来源取当前浏览来源`() {
        val state = RadioUiState(
            selectedSource = RadioSourceType.QINGTING,
            favorites = collidingFavorites,
            showFavorites = true,
            searchActive = true,
            searchQuery = "新闻",
            searchResults = listOf(channel("77")),
        )

        // displayedChannels 的分支优先级是 搜索 > 收藏 > 列表，displayedSources 必须同构，
        // 否则索引会与展示列表错位，逐项来源就全错了。
        assertEquals(listOf("台77"), state.displayedChannels.map { it.title })
        assertNull(state.displayedSources)
        assertEquals(RadioSourceType.QINGTING, state.sourceAt(0))
    }

    @Test
    fun `越界索引回退到当前浏览来源而不是抛异常`() {
        val state = RadioUiState(
            selectedSource = RadioSourceType.YUNTING,
            favorites = collidingFavorites,
            showFavorites = true,
        )

        assertEquals(RadioSourceType.YUNTING, state.sourceAt(99))
    }
}
