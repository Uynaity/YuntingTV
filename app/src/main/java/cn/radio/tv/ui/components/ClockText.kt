package cn.radio.tv.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import kotlinx.coroutines.delay
import java.util.Calendar
import kotlin.time.Duration.Companion.milliseconds

/**
 * 24 小时制时钟（HH:MM，白色粗体）。
 * 仅在前台(STARTED)走时，按分钟边界对齐刷新；退到后台随生命周期挂起。
 */
@Composable
fun ClockText(modifier: Modifier = Modifier) {
    val lifecycleOwner = LocalLifecycleOwner.current
    var time by remember { mutableStateOf(currentHhMm()) }

    LaunchedEffect(Unit) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                time = currentHhMm()
                delay(millisToNextMinute().milliseconds)
            }
        }
    }

    Text(
        text = time,
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.Bold,
        color = Color.White,
        modifier = modifier,
    )
}

/** 当前时间，格式 HH:MM（24 小时制，补零）。 */
private fun currentHhMm(): String {
    val cal = Calendar.getInstance()
    return "%02d:%02d".format(cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE))
}

/** 距下一分钟整点的毫秒数，用于把刷新对齐到分钟边界。 */
private fun millisToNextMinute(): Long {
    val cal = Calendar.getInstance()
    return 60_000L - (cal.get(Calendar.SECOND) * 1_000L + cal.get(Calendar.MILLISECOND))
}
