package cn.radio.tv.data.update

import android.app.Application
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File

/** APK 下载与安装。下载走独立 OkHttp（无需 yecao 的 secret 头，下载地址是静态文件）。 */
object UpdateInstaller {

    private val client = OkHttpClient()

    /**
     * 下载 APK 到 cacheDir/update.apk（覆盖旧文件），按内容长度回调进度 [0f,1f]。
     * [sizeHint] 为接口给的字节数，仅当响应无 Content-Length 时作总量参考。
     */
    suspend fun download(
        app: Application,
        url: String,
        sizeHint: Long,
        onProgress: (Float) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        val file = File(app.cacheDir, "update.apk")
        client.newCall(Request.Builder().url(url).build()).execute().use { resp ->
            if (!resp.isSuccessful) error("下载失败 HTTP ${resp.code}")
            val body = resp.body
            val total = body.contentLength().takeIf { it > 0 } ?: sizeHint
            body.byteStream().use { input ->
                file.outputStream().use { output ->
                    val buf = ByteArray(8 * 1024)
                    var downloaded = 0L
                    while (true) {
                        val read = input.read(buf)
                        if (read == -1) break
                        output.write(buf, 0, read)
                        downloaded += read
                        if (total > 0) onProgress((downloaded.toFloat() / total).coerceIn(0f, 1f))
                    }
                }
            }
        }
        file
    }

    /**
     * 调起系统安装器。Android 8.0+ 未授予「未知来源」安装权限时先跳系统授权页并返回 false
     * （用户授权后需再次触发更新）；成功发起安装返回 true。
     */
    fun install(app: Application, file: File): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !app.packageManager.canRequestPackageInstalls()
        ) {
            val intent = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                "package:${app.packageName}".toUri(),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { app.startActivity(intent) }
            return false
        }
        val uri = FileProvider.getUriForFile(app, "${app.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        // TV 设备可能无系统安装器 → ActivityNotFound，兜底返回 false 由上层提示，不崩溃。
        return runCatching { app.startActivity(intent); true }.getOrDefault(false)
    }
}
