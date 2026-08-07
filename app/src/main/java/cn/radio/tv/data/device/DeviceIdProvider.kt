package cn.radio.tv.data.device

import android.annotation.SuppressLint
import android.content.Context
import android.media.MediaDrm
import android.provider.Settings
import java.security.MessageDigest
import java.util.UUID

/**
 * 设备标识。用于「TuneIn 代理」的激活码绑定：一个激活码同一时间只绑一台设备，
 * 服务端按这个哈希认设备（见 radio-proxy `activation.go`）。
 *
 * 取值优先级：
 *
 * 1. **Widevine Device ID**（`MediaDrm.PROPERTY_DEVICE_UNIQUE_ID`）。烧录在设备安全芯片里，
 *    恢复出厂设置、重装 App 都不变，普通用户改不了，API 26+ 可用且不要权限 —— 正是绑定授权
 *    想要的性质。
 * 2. **[Settings.Secure.ANDROID_ID]** 兜底。少数低端盒子不带 Widevine。它按「应用签名 + 用户」
 *    区分，重装/升级不变，恢复出厂会重置 —— 弱一些，但比没有强。
 *
 * 弃用的方案：IMEI/序列号（TV 盒子多无蜂窝模块，且 Android 10+ 普通应用禁读）、
 * MAC 地址（Android 6+ 固定返回假值）、广告 ID（用户可随时重置，绑定会莫名失效）。
 *
 * **只对外暴露 SHA-256**，原始标识不出本类、也不上报 —— 服务端存的同样只是哈希。
 */
object DeviceIdProvider {

    @Volatile
    private var cached: String? = null

    /**
     * 设备哈希（64 位小写十六进制）。首次调用会起一次 [MediaDrm] 会话（毫秒级），
     * 之后走内存缓存。取不到任何标识时返回空串 —— 调用方据此跳过携带请求头，
     * 表现为「未激活」，而不是崩溃（见 error-handling.md 的失败降级约定）。
     *
     * 从 [cn.radio.tv.RadioApp.onCreate] 预热一次，避免首次播放时在网络线程上现算。
     */
    fun hash(context: Context): String {
        cached?.let { return it }
        return synchronized(this) {
            cached ?: compute(context).also { cached = it }
        }
    }

    private fun compute(context: Context): String {
        val raw = widevineId() ?: androidId(context)
        return if (raw.isNullOrEmpty()) "" else sha256Hex(raw)
    }

    /**
     * Widevine device unique id。不支持的设备会抛 `UnsupportedSchemeException`，
     * 个别机型还会在 `getPropertyByteArray` 上抛 `IllegalStateException`；
     * 一律吞掉走兜底 —— 单台设备缺 DRM 不该让激活功能整个不可用。
     */
    private fun widevineId(): String? = runCatching {
        val drm = MediaDrm(WIDEVINE_UUID)
        try {
            drm.getPropertyByteArray(MediaDrm.PROPERTY_DEVICE_UNIQUE_ID)
                ?.takeIf { it.isNotEmpty() }
                ?.joinToString("") { "%02x".format(it) }
        } finally {
            // close() 需要 API 28；低版本用已废弃的 release()，否则会漏 MediaDrm 会话。
            @Suppress("DEPRECATION")
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                drm.close()
            } else {
                drm.release()
            }
        }
    }.getOrNull()

    /** ANDROID_ID 兜底。模拟器/极少数固件可能返回 null 或空串。 */
    @SuppressLint("HardwareIds")
    private fun androidId(context: Context): String? = runCatching {
        Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
    }.getOrNull()

    private fun sha256Hex(input: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(input.toByteArray())
            .joinToString("") { "%02x".format(it) }

    /** Widevine 的固定 UUID，见 Android 文档。 */
    private val WIDEVINE_UUID = UUID(-0x121074568629b532L, -0x5c37d8232ae2de13L)
}
