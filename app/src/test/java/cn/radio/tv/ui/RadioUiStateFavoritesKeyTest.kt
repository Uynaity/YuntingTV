package cn.radio.tv.ui

import cn.radio.tv.data.model.Channel
import cn.radio.tv.data.model.FavoriteChannel
import cn.radio.tv.data.source.RadioSourceType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * 跨来源收藏的复合键。
 *
 * 三个来源的 contentId 空间彼此独立、不保证全局唯一。此前收藏视图把 `FavoriteChannel`
 * 拍平成裸 `Channel`（丢掉 source），LazyGrid 又拿 contentId 当唯一 key：
 * 相同 ID 的两个台一旦同时被收藏，Compose 因重复 key 直接崩溃，
 * 且播放/取消收藏会按裸 ID 反查来源、路由到错的源。
 *
 * 接入 Paging 后网格分成两支（分页项全体同属浏览来源 / 收藏项各自携带来源），
 * 但两支的 key 都必须出自 [gridKeyOf] 这一个函数 —— 崩溃条件没有变。
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
        val keys = collidingFavorites.map { gridKeyOf(it.source, it.channel) }

        assertEquals(2, keys.size)
        assertNotEquals("重复 key 会让 LazyGrid 抛异常崩溃", keys[0], keys[1])
        assertEquals(keys.size, keys.toSet().size)
    }

    @Test
    fun `分页项与收藏项的 key 出自同一函数、格式一致`() {
        assertEquals("tunein:1", gridKeyOf(RadioSourceType.TUNEIN, channel("1")))
        assertEquals(
            "yunting:1001",
            gridKeyOf(collidingFavorites[0].source, collidingFavorites[0].channel),
        )
    }

    /**
     * 收藏视图的星标取自「该项自身来源」，而 [RadioUiState.favoriteIds] 只收当前浏览来源
     * 的 contentId —— 两源撞号时它不能把别源的收藏认成当前源的。
     */
    @Test
    fun `favoriteIds 只含当前浏览来源的收藏`() {
        val state = RadioUiState(
            selectedSource = RadioSourceType.YUNTING,
            favorites = collidingFavorites + FavoriteChannel(
                channel("2002"),
                provinceCode = 0,
                source = RadioSourceType.QINGTING,
            ),
        )

        assertEquals(setOf("1001"), state.favoriteIds)
    }
}
