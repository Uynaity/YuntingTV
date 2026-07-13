package cn.radio.tv.ui.components

import android.graphics.drawable.BitmapDrawable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.palette.graphics.Palette
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import cn.radio.tv.data.model.Channel
import cn.radio.tv.data.model.Program
import cn.radio.tv.ui.PlaybillDate
import cn.radio.tv.ui.theme.GoldStar
import coil.compose.AsyncImage
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
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
    /** 竖排封面按确认键展开全屏播放（仅横屏左侧面板用；无当前电台不触发）。 */
    onOpenFullscreen: () -> Unit = {},
) {
    val titleAnnotated = playerTitle(channel, isFavorite)
    val subtitleText = playerSubtitle(channel, isBuffering, retrySeconds, playingProgramTitle)

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
        var coverFocused by remember { mutableStateOf(false) }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .onFocusChanged { coverFocused = it.isFocused }
                .clickable(enabled = channel != null, onClick = onOpenFullscreen)
                .border(
                    2.dp,
                    if (coverFocused) Color.White else Color.Transparent,
                    RoundedCornerShape(16.dp),
                ),
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
            } else if (coverFocused && channel != null) {
                // 聚焦提示：叠一个「展开全屏」图标，仿手机封面上的暂停按钮。
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.45f)),
                    contentAlignment = Alignment.Center,
                ) {
                    FullscreenGlyph(color = Color.White, size = 24.dp)
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
    accentColor: Color? = null,
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

    val primary = accentColor ?: MaterialTheme.colorScheme.primary
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
    accentColor: Color? = null,
    /** true 时无圆形底色，直接渲染 Icons.Filled 填充图标；焦点用放大 + 变白表示。 */
    bare: Boolean = false,
) {
    var focused by remember { mutableStateOf(false) }

    if (bare) {
        val iconColor = when {
            !enabled -> Color.White.copy(alpha = 0.4f)
            focused -> Color.White
            else -> accentColor ?: Color.White
        }
        Box(
            modifier = modifier
                .scale(if (focused) 1.2f else 1f)
                .let { if (focusRequester != null) it.focusRequester(focusRequester) else it }
                .onFocusChanged { focused = it.isFocused }
                .clickable(
                    enabled = enabled,
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onClick,
                ),
            contentAlignment = Alignment.Center,
        ) {
            PlayGlyph(
                isPlaying = isPlaying,
                isBuffering = enabled && isBuffering,
                color = iconColor,
                glyphSize = 40.dp,
            )
        }
        return
    }

    val container = when {
        !enabled -> MaterialTheme.colorScheme.surfaceVariant
        focused -> Color.White
        else -> accentColor ?: MaterialTheme.colorScheme.primary
    }
    // 图标色随容器明暗取反，避免白色强调色下白图标不可见。
    val iconColor = if (container.luminance() > 0.5f) Color.Black else Color.White

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

/** 「展开全屏」图标：四角向外的 L 形括号。 */
@Composable
private fun FullscreenGlyph(color: Color, size: Dp) {
    Canvas(modifier = Modifier.size(size)) {
        val w = this.size.width
        val stroke = w * 0.10f
        val arm = w * 0.30f
        val inset = stroke / 2f
        val far = w - inset
        // 左上
        drawLine(color, Offset(inset, inset), Offset(inset + arm, inset), stroke)
        drawLine(color, Offset(inset, inset), Offset(inset, inset + arm), stroke)
        // 右上
        drawLine(color, Offset(far, inset), Offset(far - arm, inset), stroke)
        drawLine(color, Offset(far, inset), Offset(far, inset + arm), stroke)
        // 左下
        drawLine(color, Offset(inset, far), Offset(inset + arm, far), stroke)
        drawLine(color, Offset(inset, far), Offset(inset, far - arm), stroke)
        // 右下
        drawLine(color, Offset(far, far), Offset(far - arm, far), stroke)
        drawLine(color, Offset(far, far), Offset(far, far - arm), stroke)
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

/** 标题：收藏时前缀金色 ★。供竖排/横排/全屏共用。 */
private fun playerTitle(channel: Channel?, isFavorite: Boolean): AnnotatedString {
    val titleText = channel?.title?.trim() ?: "未在播放"
    return if (channel != null && isFavorite) {
        buildAnnotatedString {
            withStyle(SpanStyle(color = GoldStar)) { append("★ ") }
            append(titleText)
        }
    } else {
        AnnotatedString(titleText)
    }
}

/** 副标题：缓冲态 > 回放节目名 > 节目单。 */
private fun playerSubtitle(
    channel: Channel?,
    isBuffering: Boolean,
    retrySeconds: Int,
    playingProgramTitle: String?,
): String = when {
    channel == null -> "请选择一个电台"
    isBuffering -> if (retrySeconds > 0) "缓冲中… ${retrySeconds}s" else "缓冲中…"
    !playingProgramTitle.isNullOrBlank() -> playingProgramTitle
    else -> channel.subtitle.ifBlank { "暂无节目单" }
}

/** 无操作多久后自动隐藏进度条与播放键。 */
private const val CONTROLS_IDLE_TIMEOUT_MS = 5_000L

/** 控件显示时，进度条顶边与节目名底边之间保证的最小间距。 */
private val CONTENT_CONTROLS_MIN_GAP = 48.dp

/**
 * 全屏播放界面（横屏 TV）：LOGO 取色 + 高斯模糊背景 + 居中大封面 / 电台名 / 节目名，
 * 右上角当前时间；底部悬浮进度条 + 播放键（取 LOGO 强调色，不用首页红）。
 *
 * 控件自动隐藏：进入即显示，[CONTROLS_IDLE_TIMEOUT_MS] 内无遥控操作则下移+渐隐；按任意
 * 非返回键唤醒（上移+渐显，焦点回落播放键）。控件显示时整体上移，避免遮挡电台/节目名。
 * 进/出淡入淡出与返回退出由 RadioScreen 统一控制。
 */
@Composable
fun FullScreenPlayer(
    channel: Channel?,
    isPlaying: Boolean,
    isBuffering: Boolean,
    retrySeconds: Int,
    isFavorite: Boolean,
    positionMs: Long,
    durationMs: Long,
    seekable: Boolean,
    playingProgramTitle: String?,
    sleepTimerRemainingMinutes: Int,
    onTogglePlayPause: () -> Unit,
    onSeekTo: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = rememberPlayerPalette(
        channel?.image,
        bgFallback = MaterialTheme.colorScheme.background,
        accentFallback = MaterialTheme.colorScheme.primary,
    )
    val tint = palette.background
    val accent = palette.accent

    // 全屏播放期间保持屏幕常亮，退出全屏时恢复系统默认息屏。
    val view = LocalView.current
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }

    val playFocus = remember { FocusRequester() }
    var controlsVisible by remember { mutableStateOf(true) }
    var interactionTick by remember { mutableIntStateOf(0) }
    var wakingPress by remember { mutableStateOf(false) }
    var coverPreview by remember { mutableStateOf<Long?>(null) }

    // 空闲计时：显示态且 5s 无操作 -> 隐藏。每次操作 interactionTick++ 重置。
    LaunchedEffect(controlsVisible, interactionTick) {
        if (!controlsVisible) return@LaunchedEffect
        delay(CONTROLS_IDLE_TIMEOUT_MS.milliseconds)
        controlsVisible = false
    }
    LaunchedEffect(controlsVisible) {
        if (controlsVisible) runCatching { playFocus.requestFocus() }
    }

    // 控件动画：显示时归位不透明，隐藏时下移 + 渐隐。
    val ctrlAlpha by animateFloatAsState(
        if (controlsVisible) 1f else 0f, tween(300), label = "ctrlAlpha",
    )
    // 下滑量不超过控件底部内边距（见下方 padding），否则动画中途会滑出屏幕下缘被裁切。
    val ctrlOffsetY by animateDpAsState(
        if (controlsVisible) 0.dp else 20.dp, tween(300), label = "ctrlOffset",
    )

    // 内容上移量按实际遮挡计算：只在控件顶边侵入「节目名底边 + 最小间距」时上移，且只移
    // 侵入的那部分，而非控件的全高。三块尺寸各自测量，与 offset 无关，无反馈回环。
    val density = LocalDensity.current
    var rootH by remember { mutableIntStateOf(0) }
    var contentH by remember { mutableIntStateOf(0) }
    var controlsH by remember { mutableIntStateOf(0) }
    val minGapPx = with(density) { CONTENT_CONTROLS_MIN_GAP.toPx() }
    val shiftPx = if (rootH > 0 && contentH > 0 && controlsH > 0) {
        val contentBottom = rootH / 2f + contentH / 2f   // 居中时内容自然底边
        val controlsTop = rootH - controlsH              // 底部锚定时控件顶边（含底部内边距）
        (contentBottom + minGapPx - controlsTop).coerceAtLeast(0f)
    } else 0f
    val shiftDp = with(density) { shiftPx.toDp() }
    val contentOffsetY by animateDpAsState(
        if (controlsVisible) -shiftDp else 0.dp, tween(300), label = "contentOffset",
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .onSizeChanged { rootH = it.height }
            .background(tint)
            .onPreviewKeyEvent { e ->
                if (e.key == Key.Back) return@onPreviewKeyEvent false
                when (e.type) {
                    KeyEventType.KeyDown -> {
                        interactionTick++
                        if (!controlsVisible) {
                            controlsVisible = true
                            wakingPress = true
                            true // 唤醒按键不透传给控件
                        } else false
                    }

                    KeyEventType.KeyUp -> if (wakingPress) {
                        wakingPress = false
                        true
                    } else false

                    else -> false
                }
            }
            // 触屏点击任意空白处切换控件显隐（进度条/播放键区域的点击由它们自身消费）。
            .pointerInput(Unit) {
                detectTapGestures {
                    interactionTick++
                    controlsVisible = !controlsVisible
                }
            },
    ) {
        // 模糊底图：API 31+ 用 RenderEffect 真高斯模糊铺满原图；低版本降级为把取色用的
        // 64px 缩略图放大铺满（Image 低质插值天然柔化），保留 LOGO 色彩分布而非纯色。
        if (android.os.Build.VERSION.SDK_INT >= 31 && !channel?.image.isNullOrBlank()) {
            AsyncImage(
                model = channel.image,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .scale(1.5f)
                    .blur(60.dp)
                    .alpha(0.6f),
            )
        } else palette.thumbnail?.let { thumb ->
            Image(
                bitmap = thumb,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                filterQuality = FilterQuality.Low,
                modifier = Modifier
                    .fillMaxSize()
                    .scale(1.5f)
                    .alpha(0.6f),
            )
        }
        // 可读性保证层：与 LOGO 明暗无关的均匀暗色。
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(tint.copy(alpha = 0.30f)),
        )
        // 底部加深：仅在控件显示时随其淡入淡出，衬托底部进度条/播放键。
        Box(
            modifier = Modifier
                .fillMaxSize()
                .alpha(ctrlAlpha)
                .background(
                    Brush.verticalGradient(
                        0.7f to Color.Transparent,
                        1f to Color.Black.copy(alpha = 0.6f),
                    )
                ),
        )

        ClockText(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(28.dp),
        )

        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .offset { IntOffset(0, contentOffsetY.roundToPx()) }
                .fillMaxWidth(0.8f)
                .onSizeChanged { contentH = it.height },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.3f)
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color.White.copy(alpha = 0.08f)),
                contentAlignment = Alignment.Center,
            ) {
                if (channel != null && channel.image.isNotBlank()) {
                    AsyncImage(
                        model = channel.image,
                        contentDescription = channel.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Text(text = "📻", style = MaterialTheme.typography.displayLarge)
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
                            style = MaterialTheme.typography.headlineSmall,
                            color = Color.White,
                        )
                    }
                }
            }

            Text(
                text = playerTitle(channel, isFavorite),
                style = MaterialTheme.typography.headlineMedium,
                color = Color.White,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 28.dp),
            )
            Text(
                text = playerSubtitle(channel, isBuffering, retrySeconds, playingProgramTitle),
                style = MaterialTheme.typography.titleMedium,
                color = Color.White.copy(alpha = 0.7f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp),
            )
            Text(
                text = if (sleepTimerRemainingMinutes > 0) {
                    "睡眠定时：剩余 $sleepTimerRemainingMinutes 分钟"
                } else {
                    "睡眠定时：关"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.5f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
            )
        }

        // 底部悬浮控件：始终在组合树内（保持可聚焦以持续接收按键），靠动画隐藏而非移除。
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth(0.6f)
                .padding(bottom = 28.dp)
                .onSizeChanged { controlsH = it.height }
                .offset { IntOffset(0, ctrlOffsetY.roundToPx()) }
                .alpha(ctrlAlpha),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            PlaybackProgressBar(
                positionMs = positionMs,
                durationMs = durationMs,
                seekable = seekable,
                capsuleThumb = false,
                onSeekTo = onSeekTo,
                onDragPreview = { coverPreview = it },
                placeholder = playingProgramTitle != null,
                accentColor = accent,
                modifier = Modifier.fillMaxWidth(),
            )
            PlayPauseButton(
                isPlaying = isPlaying,
                isBuffering = isBuffering,
                enabled = channel != null,
                onClick = onTogglePlayPause,
                focusRequester = playFocus,
                accentColor = accent,
                bare = true,
                modifier = Modifier.padding(top = 12.dp),
            )
        }
    }
}

private data class PlayerPalette(
    val background: Color,
    val accent: Color,
    /** 取色用的 64px 缩略位图；低版本放大铺满做模糊底图（AsyncImage 会无视 size，故直接复用）。 */
    val thumbnail: ImageBitmap? = null,
)

/**
 * 从 LOGO 提取背景主调与强调色（一次解码）：Coil 取 64px 缩略位图 → Palette。
 * 背景取深色系（darkVibrant…dominant），过亮向黑收敛，保证白字清晰；
 * 强调色取鲜艳系（vibrant…dominant），过暗向白提亮，保证在暗背景上可见。
 * 加载中/失败返回各自 fallback。
 */
@Composable
private fun rememberPlayerPalette(
    imageUrl: String?,
    bgFallback: Color,
    accentFallback: Color,
): PlayerPalette {
    val context = LocalContext.current
    var result by remember(imageUrl) { mutableStateOf(PlayerPalette(bgFallback, accentFallback)) }
    LaunchedEffect(imageUrl, bgFallback, accentFallback) {
        if (imageUrl.isNullOrBlank()) {
            result = PlayerPalette(bgFallback, accentFallback)
            return@LaunchedEffect
        }
        val req = ImageRequest.Builder(context)
            .data(imageUrl)
            .allowHardware(false)
            .size(64)
            .build()
        val bmp = (context.imageLoader.execute(req) as? SuccessResult)
            ?.let { (it.drawable as? BitmapDrawable)?.bitmap } ?: return@LaunchedEffect
        val p = withContext(Dispatchers.Default) { Palette.from(bmp).generate() }

        var bg = (p.darkVibrantSwatch ?: p.darkMutedSwatch ?: p.vibrantSwatch ?: p.dominantSwatch)
            ?.rgb?.let { Color(it) } ?: bgFallback
        if (bg.luminance() > 0.5f) bg = lerp(bg, Color.Black, 0.5f)

        var accent =
            (p.vibrantSwatch ?: p.lightVibrantSwatch ?: p.lightMutedSwatch ?: p.dominantSwatch)
                ?.rgb?.let { Color(it) } ?: accentFallback
        if (accent.luminance() < 0.4f) accent = lerp(accent, Color.White, 0.6f)

        result = PlayerPalette(bg, accent, bmp.asImageBitmap())
    }
    return result
}
