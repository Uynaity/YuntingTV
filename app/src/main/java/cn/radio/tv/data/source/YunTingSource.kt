package cn.radio.tv.data.source

import cn.radio.tv.data.model.ApiResponse
import cn.radio.tv.data.model.Category
import cn.radio.tv.data.model.Channel
import cn.radio.tv.data.model.Program
import cn.radio.tv.data.model.Province
import cn.radio.tv.data.remote.NetworkModule
import cn.radio.tv.data.remote.RadioApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Locale

/** 云听（radio.cn）数据源。接口本就贴合共享模型，直接透传。 */
class YunTingSource(
    private val api: RadioApi = NetworkModule.yunTingApi,
) : BaseRadioSource() {

    override val type = RadioSourceType.YUNTING

    override suspend fun fetchProvinces(): List<Province> = withContext(Dispatchers.IO) {
        api.getProvinces().dataOrThrow("省份")
    }

    override suspend fun fetchCategories(): List<Category> = withContext(Dispatchers.IO) {
        api.getCategories().dataOrThrow("分类")
    }

    override suspend fun fetchChannels(categoryId: String, provinceCode: Long): List<Channel> =
        withContext(Dispatchers.IO) {
            api.getChannels(categoryId = categoryId, provinceCode = provinceCode)
                .dataOrThrow("电台列表")
                .map { it.copy(subtitle = it.subtitle.stripLivePrefix()) }
        }

    /**
     * 某天节目单：date 用设备本地时区的 yyyy/MM/dd。回放地址即 playUrlHigh，
     * canReplay = playFlag==1 且地址非空（未播出节目 playFlag==0、地址为空）。
     */
    override suspend fun fetchPlaybill(channel: Channel, dayStartMillis: Long): List<Program> =
        withContext(Dispatchers.IO) {
            // 日期快切会并发触发本方法；SimpleDateFormat 非线程安全，故每次新建实例。
            val date = SimpleDateFormat("yyyy/MM/dd", Locale.getDefault()).format(dayStartMillis)
            val resp = api.getPrograms(broadcastId = channel.contentId, date = date)
            // 无节目单时云听返回 code=0/message=SUCCESS/data=null —— 是「成功但无数据」，
            // 按空列表处理（UI 显示「暂无节目单」），不能当失败抛错。
            if (resp.code != 0) {
                throw IllegalStateException("获取节目单失败：${resp.message ?: "code=${resp.code}"}")
            }
            (resp.data ?: emptyList())
                .map { p ->
                    Program(
                        id = "",
                        title = p.programName,
                        startTime = p.startTime,
                        endTime = p.endTime,
                        canReplay = p.playFlag == 1 && p.playUrlHigh.isNotBlank(),
                        replayUrl = p.playUrlHigh,
                    )
                }
        }

    /** 云听直播节目名远端会带「正在直播」前缀；去掉它及其后的分隔符/空白。 */
    private fun String.stripLivePrefix(): String =
        removePrefix("正在直播").trimStart('：')

    private fun <T> ApiResponse<T>.dataOrThrow(what: String): T {
        if (code != 0 || data == null) {
            throw IllegalStateException("获取${what}失败：${message ?: "code=$code"}")
        }
        return data
    }
}
