package cn.radio.tv.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import cn.radio.tv.data.model.Channel
import cn.radio.tv.data.model.Program
import cn.radio.tv.ui.PlaybillDate
import cn.radio.tv.ui.theme.GoldStar
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.milliseconds

/** 播放器面板：封面 + 名称 + 当前节目 + 播放/暂停按钮。
 * 默认竖排（横屏时置于左侧 1/3）；[horizontal] = true 时改为横排，用于竖屏时置于屏幕底部。 */
@Composable
fun PlayerPanel(
    channel: Channel?,
    isPlaying: Boolean,
    isBuffering: Boolean,
    retrySeconds: Int = 0,
    isFavorite: Boolean,
    onTogglePlayPause: () -> Unit,
    modifier: Modifier = Modifier,
    playButtonFocusRequester: FocusRequester? = null,
    horizontal: Boolean = false,
    positionMs: Long = 0L,
    durationMs: Long = 0L,
    seekable: Boolean = false,
    onSeekTo: (Long) -> Unit = {},
    sleepTimerRemainingMinutes: Int = 0,
    sleepTimerTotalMinutes: Int = 0,
    onSetSleepTimer: (Int) -> Unit = {},
    showPlaybill: Boolean = false,
    onTogglePlaybill: () -> Unit = {},
    playbillDates: List<PlaybillDate> = emptyList(),
    playbillPrograms: List<Program> = emptyList(),
    selectedPlaybillDate: Long = 0L,
    isLoadingPlaybill: Boolean = false,
    playbillError: String? = null,
    onSelectPlaybillDate: (Long) -> Unit = {},
    onPlayReplay: (Program) -> Unit = {},
    onPlayLive: () -> Unit = {},
    playingProgramTitle: String? = null,
) {
    val titleText = channel?.title?.trim() ?: "未在播放"
    val titleAnnotated = if (channel != null && isFavorite) {
        buildAnnotatedString {
            withStyle(SpanStyle(color = GoldStar)) { append("★ ") }
            append(titleText)
        }
    } else {
        AnnotatedString(titleText)
    }
    val subtitleText = when {
        channel == null -> "请选择一个电台"
        isBuffering -> if (retrySeconds > 0) "缓冲中… ${retrySeconds}s" else "缓冲中…"
        !playingProgramTitle.isNullOrBlank() -> playingProgramTitle   // 回放中：显示回放节目名
        else -> channel.subtitle.ifBlank { "暂无节目单" }
    }

    if (horizontal) {
        Box(modifier = modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .clickable(enabled = channel != null, onClick = onTogglePlayPause),
                    contentAlignment = Alignment.Center,
                ) {
                    if (channel != null && channel.image.isNotBlank()) {
                        AsyncImage(
                            model = channel.image,
                            contentDescription = channel.title,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.size(64.dp),
                        )
                    } else {
                        Text(text = "📻", style = MaterialTheme.typography.headlineSmall)
                    }
                    if (channel != null) {
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .clip(CircleShape)
                                .background(Color.Black.copy(alpha = 0.35f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            PlayGlyph(
                                isPlaying = isPlaying,
                                isBuffering = isBuffering,
                                color = Color.White,
                                glyphSize = 16.dp,
                                strokeWidth = 2.dp,
                            )
                        }
                    }
                }

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 16.dp)
                ) {
                    Text(
                        text = titleAnnotated,
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = subtitleText,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }

                SleepTimerButton(
                    remainingMinutes = sleepTimerRemainingMinutes,
                    totalMinutes = sleepTimerTotalMinutes,
                    enabled = channel != null,
                    onSelect = onSetSleepTimer,
                    phone = true,
                )
                Spacer(modifier = Modifier.size(16.dp))
                PlaybillButton(
                    active = showPlaybill,
                    enabled = channel != null,
                    onClick = onTogglePlaybill,
                )
            }
            PlaybackProgressBar(
                positionMs = positionMs,
                durationMs = durationMs,
                seekable = seekable,
                capsuleThumb = true,
                onSeekTo = onSeekTo,
                onDragPreview = {},
                placeholder = playingProgramTitle != null,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .offset(y = -(PhoneProgressBarHeight / 2))
                    .fillMaxWidth(),
            )
        }
        PlaybillBottomSheet(
            show = showPlaybill && channel != null,
            dates = playbillDates,
            programs = playbillPrograms,
            selectedDate = selectedPlaybillDate,
            isLoading = isLoadingPlaybill,
            error = playbillError,
            onSelectDate = onSelectPlaybillDate,
            onPlayReplay = onPlayReplay,
            isPlaying = isPlaying,
            playingProgramTitle = playingProgramTitle,
            onTogglePlayPause = onTogglePlayPause,
            onPlayLive = onPlayLive,
            onDismiss = onTogglePlaybill,
        )
        return
    }

    Column(
        modifier = modifier
            .fillMaxHeight()
            .background(MaterialTheme.colorScheme.surface)
            .padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        var coverPreview by remember { mutableStateOf<Long?>(null) }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            if (channel != null && channel.image.isNotBlank()) {
                AsyncImage(
                    model = channel.image,
                    contentDescription = channel.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f),
                )
            } else {
                Text(
                    text = "📻",
                    style = MaterialTheme.typography.displayLarge,
                )
            }
            if (coverPreview != null) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.55f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "${formatTime(coverPreview!!)}/${formatTime(durationMs)}",
                        style = MaterialTheme.typography.headlineMedium,
                        color = Color.White,
                    )
                }
            }
        }

        Text(
            text = titleAnnotated,
            style = MaterialTheme.typography.headlineSmall,
            color = Color.White,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 24.dp),
        )

        Text(
            text = subtitleText,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
        )

        PlaybackProgressBar(
            positionMs = positionMs,
            durationMs = durationMs,
            seekable = seekable,
            capsuleThumb = false,
            onSeekTo = onSeekTo,
            onDragPreview = { coverPreview = it },
            placeholder = playingProgramTitle != null,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 20.dp),
        )

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 32.dp),
            contentAlignment = Alignment.Center,
        ) {
            PlayPauseButton(
                isPlaying = isPlaying,
                isBuffering = isBuffering,
                enabled = channel != null,
                onClick = onTogglePlayPause,
                focusRequester = playButtonFocusRequester,
            )
            SleepTimerButton(
                remainingMinutes = sleepTimerRemainingMinutes,
                totalMinutes = sleepTimerTotalMinutes,
                enabled = channel != null,
                onSelect = onSetSleepTimer,
                phone = false,
                modifier = Modifier
                    .align(Alignment.Center)
                    .offset(x = -(36 + 16 + 22).dp),
            )
            PlaybillButton(
                active = showPlaybill,
                enabled = channel != null,
                onClick = onTogglePlaybill,
                modifier = Modifier
                    .align(Alignment.Center)
                    .offset(x = (36 + 16 + 22).dp),
            )
        }
    }
}

/** 进度条快进步长（单击 15s / 长按连续 5s）与长按/防抖节奏（ms）。 */
private const val SEEK_CLICK_MS = 15_000L
private const val SEEK_HOLD_STEP_MS = 5_000L
private const val SEEK_HOLD_STEP_MAX_MS = 60_000L
private const val SEEK_HOLD_THRESHOLD_MS = 400L
private const val SEEK_HOLD_INTERVAL_MS = 200L
private const val SEEK_COMMIT_DEBOUNCE_MS = 1_000L

private const val SEEK_SETTLE_TOLERANCE_MS = 2_000L
private const val SEEK_SETTLE_TIMEOUT_MS = 2_500L

/** 手机进度条自身高度；布局按其一半上移，使轨道落在播放栏顶边、胶囊跨骑边界。 */
private val PhoneProgressBarHeight = 24.dp

/** 播放时长格式化：<1h 为 mm:ss，≥1h 为 h:mm:ss。例：1425000→"23:45"、3600000→"1:00:00"。 */
private fun formatTime(ms: Long): String {
    val totalSec = (ms / 1000).coerceAtLeast(0)
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}

/**
 * 播放进度条。回放（[seekable]）可拖动定位：TV 遥控左右键单击 ±15s、长按连续步进；
 * 手机触摸拖动。拖动中不 seek，只更新预览 [onDragPreview]（TV 用于封面遮罩）；停手
 * [SEEK_COMMIT_DEBOUNCE_MS] 后关闭预览并 [onSeekTo]（开始加载）。直播只读、不可聚焦。
 * [durationMs]<=0（未知）时不渲染。[capsuleThumb]=true 时拖动点为胶囊并内显 当前/总（手机）。
 */
@Composable
private fun PlaybackProgressBar(
    positionMs: Long,
    durationMs: Long,
    seekable: Boolean,
    capsuleThumb: Boolean,
    onSeekTo: (Long) -> Unit,
    onDragPreview: (Long?) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: Boolean = false,
) {
    val loading = durationMs <= 0L
    if (loading && !placeholder) return

    var dragTarget by remember { mutableStateOf<Long?>(null) }
    var committedTarget by remember { mutableStateOf<Long?>(null) }
    var holdDir by remember { mutableIntStateOf(0) }
    var focused by remember { mutableStateOf(false) }

    val shownMs = dragTarget ?: committedTarget ?: positionMs
    val displayMs = if (loading) 0L else shownMs.coerceIn(0L, durationMs)
    val ratio = if (loading) 0f else (displayMs.toFloat() / durationMs).coerceIn(0f, 1f)

    fun step(delta: Long) {
        dragTarget =
            ((dragTarget ?: committedTarget ?: positionMs) + delta).coerceIn(0L, durationMs)
    }

    LaunchedEffect(holdDir) {
        if (holdDir == 0) return@LaunchedEffect
        delay(SEEK_HOLD_THRESHOLD_MS.milliseconds)
        var stepMs = SEEK_HOLD_STEP_MS
        while (true) {
            step(holdDir * stepMs)
            stepMs = (stepMs + SEEK_HOLD_STEP_MS).coerceAtMost(SEEK_HOLD_STEP_MAX_MS)
            delay(SEEK_HOLD_INTERVAL_MS.milliseconds)
        }
    }
    LaunchedEffect(dragTarget) {
        val target = dragTarget ?: return@LaunchedEffect
        onDragPreview(target)
        delay(SEEK_COMMIT_DEBOUNCE_MS.milliseconds)
        onSeekTo(target)
        onDragPreview(null)
        committedTarget = target
        dragTarget = null
    }
    LaunchedEffect(positionMs, committedTarget) {
        val target = committedTarget ?: return@LaunchedEffect
        if (abs(positionMs - target) <= SEEK_SETTLE_TOLERANCE_MS) committedTarget = null
    }
    LaunchedEffect(committedTarget) {
        if (committedTarget == null) return@LaunchedEffect
        delay(SEEK_SETTLE_TIMEOUT_MS.milliseconds)
        committedTarget = null
    }

    val primary = MaterialTheme.colorScheme.primary
    var barModifier = modifier.height(if (capsuleThumb) PhoneProgressBarHeight else 28.dp)
    if (seekable) {
        barModifier = barModifier
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .onKeyEvent { e ->
                val dir = when (e.key) {
                    Key.DirectionLeft -> -1
                    Key.DirectionRight -> 1
                    else -> return@onKeyEvent false
                }
                when (e.type) {
                    KeyEventType.KeyDown -> {
                        if (holdDir != dir) {
                            step(dir * SEEK_CLICK_MS)
                            holdDir = dir
                        }
                        true
                    }

                    KeyEventType.KeyUp -> {
                        holdDir = 0
                        true
                    }

                    else -> false
                }
            }
            .pointerInput(durationMs) {
                detectHorizontalDragGestures { change, _ ->
                    val r = (change.position.x / size.width).coerceIn(0f, 1f)
                    dragTarget = (r * durationMs).toLong()
                }
            }
    }

    BoxWithConstraints(modifier = barModifier, contentAlignment = Alignment.CenterStart) {
        val widthPx = constraints.maxWidth.toFloat()
        Canvas(modifier = Modifier.fillMaxSize()) {
            val cy = size.height / 2f
            val r = 7.dp.toPx()
            val trackH = (if (capsuleThumb) 3.dp else 4.dp).toPx()
            val inset = if (capsuleThumb) trackH / 2f else r
            val right = size.width - inset
            val thumbX = inset + (right - inset) * ratio
            drawLine(
                color = Color(0x66FFFFFF),
                start = Offset(inset, cy), end = Offset(right, cy),
                strokeWidth = trackH, cap = StrokeCap.Round,
            )
            drawLine(
                color = primary,
                start = Offset(inset, cy), end = Offset(thumbX, cy),
                strokeWidth = trackH, cap = StrokeCap.Round,
            )
            if (!capsuleThumb) {
                if (focused) drawCircle(Color.White, r + 4.dp.toPx(), Offset(thumbX, cy))
                drawCircle(primary, r, Offset(thumbX, cy))
            }
        }
        if (capsuleThumb) {
            var thumbW by remember { mutableIntStateOf(0) }
            Box(
                modifier = Modifier
                    .offset {
                        IntOffset(
                            (ratio * (widthPx - thumbW)).roundToInt().coerceAtLeast(0),
                            0
                        )
                    }
                    .onSizeChanged { thumbW = it.width }
                    .clip(RoundedCornerShape(50))
                    .background(primary)
                    .padding(horizontal = 10.dp, vertical = 2.dp),
            ) {
                Text(
                    text = "${formatTime(displayMs)}/${formatTime(durationMs)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun PlayPauseButton(
    isPlaying: Boolean,
    isBuffering: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
) {
    var focused by remember { mutableStateOf(false) }
    val container = when {
        !enabled -> MaterialTheme.colorScheme.surfaceVariant
        focused -> Color.White
        else -> MaterialTheme.colorScheme.primary
    }
    val iconColor = if (focused && enabled) Color.Black else Color.White

    Box(
        modifier = modifier
            .size(72.dp)
            .scale(if (focused) 1.1f else 1f)
            .focusableChrome(
                shape = CircleShape,
                container = container,
                focused = focused,
                onFocusChanged = { focused = it },
                onClick = onClick,
                enabled = enabled,
                focusRequester = focusRequester,
            ),
        contentAlignment = Alignment.Center,
    ) {
        PlayGlyph(
            isPlaying = isPlaying,
            isBuffering = enabled && isBuffering,
            color = iconColor,
            glyphSize = 30.dp,
        )
    }
}

/**
 * 播放状态图标：缓冲中转圈、播放中两根竖条（暂停）、暂停时三角形（播放）。
 * 供圆形播放键与手机封面叠加图标共用。
 */
@Composable
private fun PlayGlyph(
    isPlaying: Boolean,
    isBuffering: Boolean,
    color: Color,
    glyphSize: Dp,
    strokeWidth: Dp = 3.dp,
) {
    if (isBuffering) {
        SpinningArc(color = color, size = glyphSize, strokeWidth = strokeWidth)
        return
    }
    Canvas(modifier = Modifier.size(glyphSize)) {
        if (isPlaying) {
            val barW = size.width * 0.26f
            val gap = size.width * 0.18f
            val total = barW * 2 + gap
            val startX = (size.width - total) / 2f
            drawRect(color, topLeft = Offset(startX, 0f), size = Size(barW, size.height))
            drawRect(
                color,
                topLeft = Offset(startX + barW + gap, 0f),
                size = Size(barW, size.height),
            )
        } else {
            val left = size.width * 0.16f
            val right = size.width * 0.90f
            val path = Path().apply {
                moveTo(left, 0f)
                lineTo(right, size.height / 2f)
                lineTo(left, size.height)
                close()
            }
            drawPath(path, color)
        }
    }
}
