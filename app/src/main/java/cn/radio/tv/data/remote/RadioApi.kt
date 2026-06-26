package cn.radio.tv.data.remote

import cn.radio.tv.data.model.ApiResponse
import cn.radio.tv.data.model.Category
import cn.radio.tv.data.model.Channel
import cn.radio.tv.data.model.Province
import retrofit2.http.GET
import retrofit2.http.Query

/** radio.cn（云听）开放接口。 */
interface RadioApi {

    /** 全部省份（城市筛选）。 */
    @GET("web/appProvince/list/all")
    suspend fun getProvinces(): ApiResponse<List<Province>>

    /** 全部分类（类型筛选）。 */
    @GET("web/appCategory/list/all")
    suspend fun getCategories(): ApiResponse<List<Category>>

    /** 按地区 + 分类查询电台列表。 */
    @GET("web/appBroadcast/list")
    suspend fun getChannels(
        @Query("categoryId") categoryId: String,
        @Query("provinceCode") provinceCode: Long,
    ): ApiResponse<List<Channel>>
}
