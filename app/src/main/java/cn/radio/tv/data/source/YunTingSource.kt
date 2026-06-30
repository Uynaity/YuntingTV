package cn.radio.tv.data.source

import cn.radio.tv.data.model.ApiResponse
import cn.radio.tv.data.model.Category
import cn.radio.tv.data.model.Channel
import cn.radio.tv.data.model.Province
import cn.radio.tv.data.remote.NetworkModule
import cn.radio.tv.data.remote.RadioApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
        }

    private fun <T> ApiResponse<T>.dataOrThrow(what: String): T {
        if (code != 0 || data == null) {
            throw IllegalStateException("获取${what}失败：${message ?: "code=$code"}")
        }
        return data
    }
}
