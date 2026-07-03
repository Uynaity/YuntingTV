package cn.radio.tv.data.source

import cn.radio.tv.data.model.Category
import cn.radio.tv.data.model.Channel
import cn.radio.tv.data.model.Program
import cn.radio.tv.data.model.Province
import cn.radio.tv.data.remote.NetworkModule
import cn.radio.tv.data.remote.QingTingApi
import cn.radio.tv.data.remote.QtResponse
import cn.radio.tv.data.prefs.UserPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 蜻蜓FM（qtfm.cn）数据源。把蜻蜓接口映射到共享模型：
 * region→[Province]、category→[Category]、channel→[Channel]，直播地址由电台 id 构造。
 * 「全部地区 / 全部分类」对应省略 region_id / category_id（传 null）。
 */
class QingTingSource(
    private val api: QingTingApi = NetworkModule.qingTingApi,
) : BaseRadioSource() {

    override val type = RadioSourceType.QINGTING

    // 蜻蜓不设「全部地区」，默认城市为接口首项「网络台」(id=407)。
    override val defaultProvinceCode = NETWORK_REGION_ID

    override suspend fun fetchProvinces(): List<Province> = withContext(Dispatchers.IO) {
        api.getRegions().dataOrThrow("地区").items
            .map { Province(provinceName = it.title, provinceCode = it.id) }
    }

    override suspend fun fetchCategories(): List<Category> = withContext(Dispatchers.IO) {
        val categories = api.getCategories().dataOrThrow("分类").items
        buildList(categories.size + 1) {
            add(Category(id = ALL_CATEGORY_ID, categoryName = "全部"))
            // 蜻蜓类型名多带「台」后缀（音乐台/新闻台…），去掉以求简洁。
            categories.forEach { add(Category(id = it.id.toString(), categoryName = it.title.removeSuffix("台"))) }
        }
    }

    override suspend fun fetchChannels(categoryId: String, provinceCode: Long): List<Channel> =
        withContext(Dispatchers.IO) {
            // 0 / "0" 表示「全部」：传 null 以省略该 query 参数。
            val regionId = provinceCode.takeIf { it != UserPreferences.DEFAULT_PROVINCE_CODE }
            val catId = categoryId.toLongOrNull()?.takeIf { it != 0L }
            // ponytail: 单页 pagesize=300，覆盖常规「地区+分类」组合（实测 ≤ ~70 台）；
            // 仅「全部地区+全部分类」会被截断，若需完整列表再加翻页循环。
            api.getChannels(regionId = regionId, categoryId = catId)
                .dataOrThrow("电台列表")
                .items
                .map { c ->
                    Channel(
                        contentId = c.id.toString(),
                        title = c.title,
                        subtitle = c.currentProgram?.title.orEmpty(),
                        image = c.cover,
                        playUrlLow = LIVE_URL_PREFIX + c.id + LIVE_URL_SUFFIX,
                    )
                }
        }

    /**
     * 某天节目单：窗口 = [dayStart, dayStart+1 天)。蜻蜓返回扁平列表，回放判据 =
     * 已播出（endTime <= now）；回放地址点击时才二次解析，此处留空。
     */
    override suspend fun fetchPlaybill(channel: Channel, dayStartMillis: Long): List<Program> =
        withContext(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            api.getPlaybills(
                cid = channel.contentId,
                start = dayStartMillis,
                end = dayStartMillis + DAY_MILLIS,
            ).dataOrThrow("节目单")
                .map { b ->
                    Program(
                        id = b.id.toString(),
                        title = b.title,
                        startTime = b.startTime,
                        endTime = b.endTime,
                        canReplay = b.endTime in 1..now,
                        replayUrl = "",
                    )
                }
        }

    /** 蜻蜓回放地址需二次请求 replay_program，取最高码率的 aac 地址；无资源则返回空。 */
    override suspend fun resolveReplayUrl(channel: Channel, program: Program): String =
        withContext(Dispatchers.IO) {
            runCatching {
                api.getReplay(cid = channel.contentId, pid = program.id).data?.resources
                    ?.maxByOrNull { it.bitrate }?.aacPlayUrl.orEmpty()
            }.getOrDefault("")
        }

    private fun <T> QtResponse<T>.dataOrThrow(what: String): T {
        if (errcode != 0 || data == null) {
            throw IllegalStateException("获取${what}失败：${errmsg ?: "errcode=$errcode"}")
        }
        return data
    }

    private companion object {
        const val NETWORK_REGION_ID = 407L      // 蜻蜓「网络台」地区 id（regions 接口首项）
        const val LIVE_URL_PREFIX = "https://ls.qingting.fm/live/"
        const val LIVE_URL_SUFFIX = "/64k.m3u8"
        const val DAY_MILLIS = 86_400_000L
    }
}
