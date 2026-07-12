package cn.radio.tv.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import cn.radio.tv.ui.theme.GoldStar
import kotlin.math.roundToInt

/** 睡眠定时步长（分钟）与最大档位：0..8 -> 0..120 分钟，0 = 关闭。 */
private const val SLEEP_STEP_MINUTES = 15
private const val SLEEP_MAX_STEP = 8

/** 睡眠定时最大分钟数（0..120，步长 [SLEEP_STEP_MINUTES]）。 */
private const val SLEEP_MAX_MINUTES = SLEEP_MAX_STEP * SLEEP_STEP_MINUTES

/**
 * 睡眠定时按钮：44dp 圆形按钮。未设定显示 ⏰，计时中显示剩余分钟并在外沿绕一圈环形倒计时。
 * 手机（[phone]）：点击从屏幕底部弹出菜单（[SleepTimerBottomSheet]）拖动调节。
 * 电视：聚焦后按 上/下 键就地调节待定时长（步长 15，0..120），按确认（中键）生效并开始倒计时；
 * 焦点移开而未确认则丢弃待定值。左右键不拦截，保留 Row 内左右导航。
 */
@Composable
internal fun SleepTimerButton(
    remainingMinutes: Int,
    totalMinutes: Int,
    enabled: Boolean,
    onSelect: (Int) -> Unit,
    phone: Boolean,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    var focused by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf<Int?>(null) }
    val active = remainingMinutes > 0
    val container = when {
        !enabled -> MaterialTheme.colorScheme.surfaceVariant
        active -> GoldStar            // 计时中始终金色，聚焦靠白描边+放大体现，白色环形才有稳定对比
        focused -> Color.White
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    val contentColor = if ((focused || active) && enabled) Color.Black else Color.White

    val label = when {
        pending != null -> if (pending == 0) "关" else pending.toString()
        active -> remainingMinutes.toString()
        else -> "⏰"
    }

    var boxModifier = modifier
        .size(44.dp)
        .scale(if (focused) 1.1f else 1f)
    if (!phone) {
        boxModifier = boxModifier.onKeyEvent { e ->
            if (e.type != KeyEventType.KeyDown) return@onKeyEvent false
            when (e.key) {
                Key.DirectionUp -> {
                    val base =
                        pending ?: ((remainingMinutes / SLEEP_STEP_MINUTES) * SLEEP_STEP_MINUTES)
                    pending = (base + SLEEP_STEP_MINUTES).coerceAtMost(SLEEP_MAX_MINUTES); true
                }

                Key.DirectionDown -> {
                    val base = pending
                        ?: (((remainingMinutes + SLEEP_STEP_MINUTES - 1) / SLEEP_STEP_MINUTES) * SLEEP_STEP_MINUTES)
                    pending = (base - SLEEP_STEP_MINUTES).coerceAtLeast(0); true
                }

                else -> false
            }
        }
    }
    boxModifier = boxModifier.focusableChrome(
        shape = CircleShape,
        container = container,
        focused = focused,
        onFocusChanged = {
            focused = it
            if (!it) pending = null   // 失焦未确认：还原为已生效值
        },
        onClick = {
            if (phone) {
                expanded = true
            } else {
                pending?.let { onSelect(it); pending = null }
            }
        },
        enabled = enabled,
    )

    Box(modifier = boxModifier, contentAlignment = Alignment.Center) {
        if (active && totalMinutes > 0) {
            val ratio = (remainingMinutes.toFloat() / totalMinutes).coerceIn(0f, 1f)
            Canvas(modifier = Modifier.fillMaxSize()) {
                val stroke = 3.dp.toPx()
                val inset = stroke / 2f
                val arcSize = Size(size.width - stroke, size.height - stroke)
                drawArc(
                    color = Color.Black.copy(alpha = 0.25f),   // 深色底衬，让白色进度在金底上更清晰
                    startAngle = 0f, sweepAngle = 360f, useCenter = false,
                    topLeft = Offset(inset, inset), size = arcSize,
                    style = Stroke(width = stroke, cap = StrokeCap.Round),
                )
                drawArc(
                    color = Color.White,
                    startAngle = -90f, sweepAngle = 360f * ratio, useCenter = false,
                    topLeft = Offset(inset, inset), size = arcSize,
                    style = Stroke(width = stroke, cap = StrokeCap.Round),
                )
            }
        }
        Text(
            text = label,
            style = MaterialTheme.typography.titleSmall,
            color = contentColor,
        )
    }

    if (expanded) {
        SleepTimerBottomSheet(
            initialMinutes = remainingMinutes,
            onCommit = onSelect,
            onDismiss = { expanded = false },
        )
    }
}

/**
 * 睡眠定时底部菜单（手机）：从屏幕下方滑入，高度按内容自适应（仅容纳标题 + 滑块），见 [SlideUpSheet]。
 * 点遮罩 / 返回键 / 选定档位触发滑出动画，动画结束再回调 [onDismiss]。
 */
@Composable
private fun SleepTimerBottomSheet(
    initialMinutes: Int,
    onCommit: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { visible = true }
    val anim by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(220),
        finishedListener = { if (!visible) onDismiss() },
        label = "sleepSheetAnim",
    )
    val startClose = { visible = false }

    SlideUpSheet(
        anim = anim,
        onClose = startClose,
        panelModifier = Modifier.wrapContentHeight(),
        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 20.dp),
    ) {
        Text(
            text = "睡眠定时",
            style = MaterialTheme.typography.titleMedium,
            color = Color.White,
        )
        Spacer(modifier = Modifier.size(20.dp))
        SleepTimerSlider(
            initialMinutes = initialMinutes,
            onCommit = { minutes ->
                onCommit(minutes)
                startClose()
            },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * 睡眠定时滑块（手机触屏）：0..[SLEEP_MAX_STEP] 档，每档 [SLEEP_STEP_MINUTES] 分钟，0 = 关闭。
 * [detectHorizontalDragGestures] 拖动改档、松手 [onCommit] 提交。仅供 [SleepTimerBottomSheet] 使用。
 */
@Composable
private fun SleepTimerSlider(
    initialMinutes: Int,
    onCommit: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    var step by remember {
        mutableIntStateOf((initialMinutes / SLEEP_STEP_MINUTES).coerceIn(0, SLEEP_MAX_STEP))
    }

    Column(modifier = modifier) {
        Text(
            text = if (step == 0) "关闭定时" else "${step * SLEEP_STEP_MINUTES} 分钟后暂停",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.size(16.dp))
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(32.dp)
                .pointerInput(Unit) {
                    detectHorizontalDragGestures(
                        onDragEnd = { onCommit(step * SLEEP_STEP_MINUTES) },
                    ) { change, _ ->
                        val ratio = (change.position.x / size.width).coerceIn(0f, 1f)
                        step = (ratio * SLEEP_MAX_STEP).roundToInt().coerceIn(0, SLEEP_MAX_STEP)
                    }
                },
        ) {
            val cy = size.height / 2f
            val r = 10.dp.toPx()
            val trackH = 4.dp.toPx()
            val thumbX = r + (size.width - 2 * r) * (step.toFloat() / SLEEP_MAX_STEP)
            drawLine(
                color = Color(0x66FFFFFF),
                start = Offset(r, cy), end = Offset(size.width - r, cy),
                strokeWidth = trackH, cap = StrokeCap.Round,
            )
            drawLine(
                color = GoldStar,
                start = Offset(r, cy), end = Offset(thumbX, cy),
                strokeWidth = trackH, cap = StrokeCap.Round,
            )
            drawCircle(GoldStar, r, Offset(thumbX, cy))
        }
    }
}
