package cn.radio.tv.perf

import android.util.Log
import cn.radio.tv.BuildConfig
import okhttp3.Interceptor
import okhttp3.Response
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * 确定性性能计数器（仅 debug）。
 *
 * 本项目没有低端 TV 真机，帧时长 / GPU 内存一类指标在模拟器上不具参考性；
 * 因此重构的前后对比以「与设备无关的确定性计数」为准：请求扇出、图片解码次数、
 * 网格项重组次数。这三项在任何设备上跑出来的数都一样，可复现、可断言。
 *
 * 能用单测覆盖的指标不放这里（如「快速切来源只保留最新请求」——那是 repository
 * 的单测）。这里只留单测覆盖不到的**全局**行为：一次冷启动整个 App 发了多少请求、
 * 进一次全屏解了几张图。
 *
 * 所有调用点都包在 `if (BuildConfig.DEBUG)` 里；`BuildConfig.DEBUG` 是编译期常量，
 * release 构建下整段被移除，不留运行时开销。
 */
object PerfCounters {

    private const val TAG = "PerfCounters"

    private val requests = ConcurrentHashMap<String, AtomicInteger>()
    private val decodes = ConcurrentHashMap<String, AtomicInteger>()
    private val recompositions = ConcurrentHashMap<String, AtomicInteger>()

    private fun ConcurrentHashMap<String, AtomicInteger>.bump(key: String) {
        computeIfAbsent(key) { AtomicInteger() }.incrementAndGet()
    }

    /** 记一次网络请求。[path] 用 URL 路径（不含 query），避免按参数散开成无数条。 */
    fun request(path: String) {
        if (BuildConfig.DEBUG) requests.bump(path)
    }

    /** 记一次图片解码。[key] 建议为 "宽x高" 或用途标签，用于发现同图多次解码。 */
    fun decode(key: String) {
        if (BuildConfig.DEBUG) decodes.bump(key)
    }

    /** 记一次重组。[key] 为组件标签。 */
    fun recomposition(key: String) {
        if (BuildConfig.DEBUG) recompositions.bump(key)
    }

    /** 清零。测量一个场景前调用，使读数只覆盖该场景。 */
    fun reset() {
        if (!BuildConfig.DEBUG) return
        requests.clear()
        decodes.clear()
        recompositions.clear()
    }

    /** 打到 logcat。用 `adb logcat -s PerfCounters` 读。 */
    fun dump(scenario: String) {
        if (!BuildConfig.DEBUG) return
        Log.i(TAG, "===== $scenario =====")
        dumpSection("请求", requests)
        dumpSection("解码", decodes)
        dumpSection("重组", recompositions)
    }

    private fun dumpSection(label: String, m: Map<String, AtomicInteger>) {
        if (m.isEmpty()) return
        val total = m.values.sumOf { it.get() }
        Log.i(TAG, "$label 合计 $total:")
        m.entries
            .sortedByDescending { it.value.get() }
            .forEach { (k, v) -> Log.i(TAG, "  ${v.get()}  $k") }
    }

    /** OkHttp 计数拦截器；仅在 debug 构建挂载。 */
    val interceptor: Interceptor = Interceptor { chain ->
        val req = chain.request()
        request(req.url.encodedPath)
        chain.proceed(req)
    }
}
