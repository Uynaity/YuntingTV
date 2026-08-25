package cn.radio.tv.data.activation

import cn.radio.tv.data.activation.ActivationRepository.status
import cn.radio.tv.data.remote.ActivationStatusDto
import cn.radio.tv.data.remote.GatewayApi
import cn.radio.tv.data.remote.NetworkModule
import cn.radio.tv.data.remote.RedeemRequest
import cn.radio.tv.data.remote.UnbindRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import retrofit2.HttpException

/**
 * 「TuneIn 代理」激活状态。
 *
 * 不做成 [cn.radio.tv.data.source.RadioSource] 的一部分：激活是**整套网关部署**的属性，
 * 跟具体哪个电台来源无关，塞进按来源分实例的接口里只会让三个来源各持一份同样的状态。
 *
 * 本地不缓存激活状态，也不缓存激活码：服务端返回的是唯一权威源，客户端存一份只会带来
 * 「本地说激活了、服务端说没有」的分歧。起播 TuneIn 本来就要联网，离线宽限没有意义。
 */
object ActivationRepository {

    private val api: GatewayApi get() = NetworkModule.gatewayApi

    /** 解析服务端错误体用。与 [NetworkModule] 的配置同口径，容忍字段增减。 */
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

    /** 本机设备哈希；为空表示取不到设备标识，此时任何激活操作都注定失败。 */
    val deviceHash: String get() = NetworkModule.deviceHash

    /**
     * 查当前设备的激活状态。失败静默降级为「未激活」——
     * 这只驱动设置页的展示与开关可用性，真正的拦截在服务端，猜错不会放开任何权限
     * （见 error-handling.md 的 B 类：非关键路径失败不打扰用户）。
     */
    suspend fun status(): ActivationState = withContext(Dispatchers.IO) {
        if (deviceHash.isEmpty()) return@withContext ActivationState.Inactive
        runCatching { api.getActivationStatus(deviceHash).data }
            .getOrNull()
            .toState()
    }

    /**
     * 兑换 / 换绑激活码。与 [status] 不同，这里是用户主动发起的操作，失败**必须**如实告知
     * （error-handling.md 的 A 类）：码打错了要重输、码被吊销了得找管理员，用户的下一步
     * 动作完全不同，笼统报一句「失败」等于没说。
     */
    suspend fun redeem(code: String): RedeemResult = withContext(Dispatchers.IO) {
        if (deviceHash.isEmpty()) {
            return@withContext RedeemResult.Failed("无法获取设备标识，该设备暂不支持激活")
        }
        try {
            val dto = api.redeemActivation(RedeemRequest(code = code, deviceHash = deviceHash)).data
            val state = dto.toState()
            if (state is ActivationState.Active) {
                RedeemResult.Success(state)
            } else {
                // 2xx 但没激活成:服务端契约不该出现,当作失败而不是静默当成功。
                RedeemResult.Failed("激活失败，请稍后再试")
            }
        } catch (e: HttpException) {
            parseFailure(e)
        } catch (e: Exception) {
            RedeemResult.Failed(e.message ?: "激活失败，请检查网络")
        }
    }

    /**
     * 自助解绑本设备。本设备立即失去代理权限，激活码回到未使用状态、**剩余有效期不变**，
     * 可在任意设备重新兑换。
     *
     * 与 [status] 的静默降级相反：这是用户主动点的破坏性操作，按 error-handling.md 的
     * A 类必须如实告知失败 —— 悄悄失败会让用户以为已经解绑了，转头把码发给别人。
     *
     * 服务端幂等：本设备没绑任何码时也回成功。
     */
    suspend fun unbind(): UnbindResult = withContext(Dispatchers.IO) {
        if (deviceHash.isEmpty()) {
            return@withContext UnbindResult.Failed("无法获取设备标识，该设备暂不支持解绑")
        }
        try {
            api.unbindActivation(UnbindRequest(deviceHash = deviceHash))
            UnbindResult.Success
        } catch (e: HttpException) {
            val body = runCatching { e.response()?.errorBody()?.string() }.getOrNull()
            val parsed = body?.let {
                runCatching { json.decodeFromString<ErrorEnvelope>(it) }.getOrNull()
            }
            UnbindResult.Failed(
                parsed?.message?.takeIf { it.isNotBlank() } ?: "解绑失败（${e.code()}）",
            )
        } catch (e: Exception) {
            UnbindResult.Failed(e.message ?: "解绑失败，请检查网络")
        }
    }

    /**
     * 从 HTTP 错误里取业务码与文案。
     *
     * 服务端在非 2xx 响应里照常放 [cn.radio.tv.data.model.ApiResponse] 包装，故这里解 body；
     * 解不出（网关自身返回的 5xx HTML 之类）时按状态码给个通用文案，不把原始 body 抛给用户。
     */
    private fun parseFailure(e: HttpException): RedeemResult {
        val body = runCatching { e.response()?.errorBody()?.string() }.getOrNull()
        val parsed = body?.let {
            runCatching { json.decodeFromString<ErrorEnvelope>(it) }.getOrNull()
        }
        val message = parsed?.message?.takeIf { it.isNotBlank() } ?: "激活失败（${e.code()}）"
        return RedeemResult.Failed(message)
    }

    // activated 是唯一判定依据，expiresAt 只是展示用的装饰：服务端在门禁被
    // 应急关闭（ENFORCE_ACTIVATION=false）时会回「已激活 + 到期时间 0」，
    // 若这里要求 expiresAt > 0 才算激活，那个回滚开关就等于失效。
    private fun ActivationStatusDto?.toState(): ActivationState =
        if (this != null && activated) {
            ActivationState.Active(expiresAtSeconds = expiresAt, code = code)
        } else {
            ActivationState.Inactive
        }

    @kotlinx.serialization.Serializable
    private data class ErrorEnvelope(
        val code: Int = 0,
        val message: String = "",
        val data: ActivationStatusDto? = null,
    )
}

/**
 * 设备的激活状态。
 *
 * [Active.expiresAtSeconds] 为 **epoch 秒**（服务端口径，注意本项目其余时间字段是毫秒），
 * **0 表示到期时间未知**而非「已过期」—— 服务端整体关闭门禁时就会这样下发。展示层遇到 0
 * 只是不显示日期，不能据此判定未激活。
 */
sealed interface ActivationState {
    data object Inactive : ActivationState
    data class Active(val expiresAtSeconds: Long, val code: String) : ActivationState
}

sealed interface RedeemResult {
    data class Success(val state: ActivationState.Active) : RedeemResult
    data class Failed(val message: String) : RedeemResult
}

/**
 * 解绑结果。与 [RedeemResult] 分开而不复用：解绑成功后没有 [ActivationState.Active]
 * 可回（解绑的结果必然是未激活），硬塞进同一个类型只会逼调用方处理不可能的分支。
 */
sealed interface UnbindResult {
    data object Success : UnbindResult
    data class Failed(val message: String) : UnbindResult
}
