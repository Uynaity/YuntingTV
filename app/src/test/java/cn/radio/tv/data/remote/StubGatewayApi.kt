package cn.radio.tv.data.remote

import cn.radio.tv.data.model.ApiResponse
import cn.radio.tv.data.model.Category
import cn.radio.tv.data.model.Channel
import cn.radio.tv.data.model.Program
import cn.radio.tv.data.model.Province

/**
 * 全端点都成功返回空的假网关。用例只覆写自己关心的那一两个端点。
 *
 * 有这个基类是因为 [GatewayApi] 有十来个端点，而每个用例真正在意的只有一个：不集中一处
 * 的话每个测试文件都要抄一遍同样的空实现，接口一加端点就得改五个地方。
 */
open class StubGatewayApi : GatewayApi {

    override suspend fun getProvinces(source: String) = ApiResponse<List<Province>>(0, null, null)

    override suspend fun getCategories(source: String) = ApiResponse<List<Category>>(0, null, null)

    override suspend fun getChannels(
        source: String,
        provinceCode: Long,
        categoryId: String,
        offset: Int,
        limit: Int,
    ) = ApiResponse<List<Channel>>(0, null, null)

    override suspend fun getChannelsByIds(
        source: String,
        provinceCode: Long,
        contentIds: String,
        scope: String?,
    ) = ApiResponse<List<Channel>>(0, null, null)

    override suspend fun searchChannels(
        source: String,
        provinceCode: Long,
        categoryId: String,
        q: String,
        offset: Int,
        limit: Int,
        scope: String?,
    ) = ApiResponse<List<Channel>>(0, null, null)

    override suspend fun getPrograms(source: String, contentId: String, date: String) =
        ApiResponse<List<Program>>(0, null, null)

    override suspend fun getReplay(source: String, contentId: String, programId: String) =
        ApiResponse(0, null, ReplayDto())

    override suspend fun getStream(source: String, contentId: String) =
        ApiResponse(0, null, StreamDto())

    override suspend fun redeemActivation(body: RedeemRequest) =
        ApiResponse(0, null, ActivationStatusDto())

    override suspend fun getActivationStatus(deviceHash: String) =
        ApiResponse(0, null, ActivationStatusDto())

    override suspend fun unbindActivation(body: UnbindRequest) =
        ApiResponse(0, null, ActivationStatusDto())
}
