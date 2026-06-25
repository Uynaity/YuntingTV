package com.example.myapplication.data

import com.example.myapplication.data.model.Category
import com.example.myapplication.data.model.Channel
import com.example.myapplication.data.model.Province
import com.example.myapplication.data.remote.NetworkModule
import com.example.myapplication.data.remote.RadioApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 统一封装电台数据获取；接口失败抛异常由上层处理。 */
class RadioRepository(
    private val api: RadioApi = NetworkModule.radioApi,
) {

    suspend fun fetchProvinces(): List<Province> = withContext(Dispatchers.IO) {
        api.getProvinces().dataOrThrow("省份")
    }

    suspend fun fetchCategories(): List<Category> = withContext(Dispatchers.IO) {
        api.getCategories().dataOrThrow("分类")
    }

    suspend fun fetchChannels(categoryId: String, provinceCode: Long): List<Channel> =
        withContext(Dispatchers.IO) {
            api.getChannels(categoryId = categoryId, provinceCode = provinceCode)
                .dataOrThrow("电台列表")
        }

    private fun <T> com.example.myapplication.data.model.ApiResponse<T>.dataOrThrow(what: String): T {
        if (code != 0 || data == null) {
            throw IllegalStateException("获取${what}失败：${message ?: "code=$code"}")
        }
        return data
    }
}
