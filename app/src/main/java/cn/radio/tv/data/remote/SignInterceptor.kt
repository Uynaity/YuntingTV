package cn.radio.tv.data.remote

import okhttp3.Interceptor
import okhttp3.Response
import java.security.MessageDigest

/**
 * 为每个请求注入 radio.cn 所需的签名头。
 *
 * signText = "<query 参数按 key 字母序拼接的 a=b&c=d>&timestamp=<ts>&key=<KEY>"
 * 无参数时 signText = "timestamp=<ts>&key=<KEY>"
 * sign = MD5(signText).toUpperCase()
 */
class SignInterceptor : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        val url = original.url
        val timestamp = System.currentTimeMillis()

        // 按 key 字母序拼接 query 参数（使用解码后的原始值）
        val names = url.queryParameterNames.sorted()
        val sortedParams = names.joinToString("&") { name ->
            "$name=${url.queryParameter(name)}"
        }

        val signText = if (sortedParams.isEmpty()) {
            "timestamp=$timestamp&key=$SIGN_KEY"
        } else {
            "$sortedParams&timestamp=$timestamp&key=$SIGN_KEY"
        }
        val sign = md5Upper(signText)

        val request = original.newBuilder()
            .header("Content-Type", "application/json")
            .header("platformCode", "WEB")
            .header("equipmentId", "0000")
            .header("timestamp", timestamp.toString())
            .header("sign", sign)
            .header("Origin", "https://www.radio.cn")
            .header("Referer", "https://www.radio.cn/")
            .header(
                "User-Agent",
                "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/149.0.0.0 Safari/537.36",
            )
            .build()

        return chain.proceed(request)
    }

    private fun md5Upper(text: String): String {
        val digest = MessageDigest.getInstance("MD5").digest(text.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }.uppercase()
    }

    companion object {
        private const val SIGN_KEY = "f0fc4c668392f9f9a447e48584c214ee"
    }
}
