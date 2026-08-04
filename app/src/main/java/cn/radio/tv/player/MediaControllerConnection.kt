package cn.radio.tv.player

import android.content.ComponentName
import android.content.Context
import androidx.core.content.ContextCompat
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * 生产环境的 [PlaybackConnection] 装配：连到 [PlaybackService] 的 MediaSession。
 *
 * [onFutureCreated] 把 future 交回调用方保存，用于销毁时 `MediaController.releaseFuture(...)`
 * —— 这是 Media3 要求的释放方式，即使连接尚未完成也要释放。
 */
@androidx.annotation.OptIn(UnstableApi::class)
fun mediaControllerConnection(
    context: Context,
    scope: CoroutineScope,
    onFutureCreated: (ListenableFuture<MediaController>) -> Unit,
): PlaybackConnection<MediaController> = PlaybackConnection(scope) {
    val app = context.applicationContext
    val future = MediaController.Builder(
        app,
        SessionToken(app, ComponentName(app, PlaybackService::class.java)),
    ).buildAsync()
    onFutureCreated(future)
    future.await(ContextCompat.getMainExecutor(app))
}

/**
 * `ListenableFuture` → 挂起函数的桥。
 *
 * P0 修复点就在这几行：`get()` 包在 try/catch 里，失败经 [resumeWithException] 回到协程，
 * 由 [PlaybackConnection] 转成 [ConnectionState.Failed]。旧代码在主线程 Runnable 里裸调
 * `get()`，服务创建/绑定/ExoPlayer 初始化失败时异常无人接管，直接杀进程。
 *
 * `ExecutionException` 会剥掉一层拿真实 cause，否则错误状态里全是无信息量的包装异常。
 */
private suspend fun <T> ListenableFuture<T>.await(executor: Executor): T =
    suspendCancellableCoroutine { cont ->
        addListener({
            try {
                cont.resume(get())
            } catch (e: ExecutionException) {
                cont.resumeWithException(e.cause ?: e)
            } catch (e: Throwable) {
                cont.resumeWithException(e)
            }
        }, executor)
        cont.invokeOnCancellation { cancel(false) }
    }
