package cn.radio.tv.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
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
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import cn.radio.tv.data.model.Channel
import cn.radio.tv.data.model.Program
import cn.radio.tv.ui.PlaybillDate
import cn.radio.tv.ui.theme.GoldStar
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Locale
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

/**
 * 节目单按钮：仿 [SleepTimerButton] 的 44dp 圆形可聚焦按钮，置于播放/暂停键右侧。
 * 打开态（[active]）用金色高亮，未打开显示日历图标。
 */
@Composable
private fun PlaybillButton(
    active: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    val container = when {
        !enabled -> MaterialTheme.colorScheme.surfaceVariant
        focused -> Color.White
        active -> GoldStar
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    val contentColor = if ((focused || active) && enabled) Color.Black else Color.White

    Box(
        modifier = modifier
            .size(44.dp)
            .scale(if (focused) 1.1f else 1f)
            .focusableChrome(
                shape = CircleShape,
                container = container,
                focused = focused,
                onFocusChanged = { focused = it },
                onClick = { if (enabled) onClick() },
                enabled = enabled,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = "📅", style = MaterialTheme.typography.titleSmall, color = contentColor)
    }
}

/** HH:mm 起止时间格式化（设备本地时区）。 */
private val hhmm = SimpleDateFormat("HH:mm", Locale.getDefault())

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

/**
 * 两列节目单：左列 9 个日期（可上下选择，选中金色高亮），右列所选日期的节目。
 * 右列按状态展示：加载中 / 失败 / 空 / 节目列表（可回放者右侧显示回放图标）。
 * 竖屏由 [PlaybillOverlay] 弹层内嵌，横屏由 RadioScreen 直接置于右侧列表区。
 */
@Composable
fun PlaybillContent(
    dates: List<PlaybillDate>,
    programs: List<Program>,
    selectedDate: Long,
    isLoading: Boolean,
    error: String?,
    onSelectDate: (Long) -> Unit,
    onPlayReplay: (Program) -> Unit,
    isPlaying: Boolean = false,
    playingProgramTitle: String? = null,
    onTogglePlayPause: () -> Unit = {},
    onPlayLive: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier) {
        LazyColumn(
            modifier = Modifier
                .width(88.dp)
                .fillMaxHeight(),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            contentPadding = PaddingValues(vertical = 12.dp),
        ) {
            items(dates, key = { it.dayStartMillis }) { date ->
                DateItem(
                    label = date.label,
                    selected = date.dayStartMillis == selectedDate,
                    onClick = { onSelectDate(date.dayStartMillis) },
                )
            }
        }
        Spacer(modifier = Modifier.width(8.dp))
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
        ) {
            when {
                isLoading -> LoadingIndicator()
                error != null && programs.isEmpty() ->
                    PlaybillHint(error)

                programs.isEmpty() -> PlaybillHint("暂无节目单")
                else -> {
                    val now = remember(programs) { System.currentTimeMillis() }
                    val listeningLive = playingProgramTitle.isNullOrBlank()
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        contentPadding = PaddingValues(top = 12.dp, bottom = 12.dp, end = 12.dp),
                    ) {
                        items(programs, key = { "${it.startTime}-${it.title}" }) { program ->
                            val isLive = program.startTime <= now && now < program.endTime
                            val isPlayingThis = !listeningLive &&
                                    program.title == playingProgramTitle && !isLive
                            ProgramRow(
                                program = program,
                                isLive = isLive,
                                isPlayingThis = isPlayingThis,
                                isPlaying = isPlaying,
                                listeningLive = listeningLive,
                                onPlayReplay = onPlayReplay,
                                onTogglePlayPause = onTogglePlayPause,
                                onPlayLive = onPlayLive,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PlaybillHint(text: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun DateItem(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val container = when {
        focused -> Color.White
        selected -> GoldStar
        else -> Color.Transparent
    }
    val contentColor = if (focused || selected) Color.Black else Color.White
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .focusableChrome(
                shape = RoundedCornerShape(8.dp),
                container = container,
                focused = focused,
                onFocusChanged = { focused = it },
                onClick = onClick,
            )
            .padding(vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = contentColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun ProgramRow(
    program: Program,
    isLive: Boolean,
    isPlayingThis: Boolean,
    isPlaying: Boolean,
    listeningLive: Boolean,
    onPlayReplay: (Program) -> Unit,
    onTogglePlayPause: () -> Unit,
    onPlayLive: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "${hhmm.format(program.startTime)}-${hhmm.format(program.endTime)}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
            Text(
                text = program.title,
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        when {
            isLive -> {
                Spacer(modifier = Modifier.width(12.dp))
                LiveBadge(clickable = !listeningLive, onClick = onPlayLive)
            }

            isPlayingThis -> {
                Spacer(modifier = Modifier.width(12.dp))
                ReplayIcon(showPause = isPlaying, onClick = onTogglePlayPause)
            }

            program.canReplay -> {
                Spacer(modifier = Modifier.width(12.dp))
                ReplayIcon(onClick = { onPlayReplay(program) })
            }
        }
    }
}

/** 圆形回放键。[showPause] 为真时画暂停双竖条（回放中的当前节目），否则画播放三角。 */
@Composable
private fun ReplayIcon(showPause: Boolean = false, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val container = if (focused) Color.White else MaterialTheme.colorScheme.surfaceVariant
    val contentColor = if (focused) Color.Black else GoldStar
    Box(
        modifier = Modifier
            .size(32.dp)
            .scale(if (focused) 1.1f else 1f)
            .focusableChrome(
                shape = CircleShape,
                container = container,
                focused = focused,
                onFocusChanged = { focused = it },
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.size(12.dp)) {
            if (showPause) {
                val barW = size.width * 0.32f
                drawRect(contentColor, topLeft = Offset(0f, 0f), size = Size(barW, size.height))
                drawRect(
                    contentColor,
                    topLeft = Offset(size.width - barW, 0f),
                    size = Size(barW, size.height),
                )
            } else {
                val path = Path().apply {
                    moveTo(size.width * 0.1f, 0f)
                    lineTo(size.width * 0.95f, size.height / 2f)
                    lineTo(size.width * 0.1f, size.height)
                    close()
                }
                drawPath(path, contentColor)
            }
        }
    }
}

/**
 * 「直播中」徽标：正在直播的节目档右侧。[clickable]=false（正在听直播）时为金色实心静态标识；
 * =true（回听其他节目）时为可聚焦按钮，点击 [onClick] 切回直播。
 */
@Composable
private fun LiveBadge(clickable: Boolean, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val container = when {
        !clickable -> GoldStar
        focused -> Color.White
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    val contentColor = if (!clickable || focused) Color.Black else GoldStar
    val shape = RoundedCornerShape(50)
    val base = if (clickable) {
        Modifier
            .scale(if (focused) 1.1f else 1f)
            .focusableChrome(
                shape = shape,
                container = container,
                focused = focused,
                onFocusChanged = { focused = it },
                onClick = onClick,
            )
    } else {
        Modifier
            .clip(shape)
            .background(container, shape)
    }
    Box(
        modifier = base.padding(horizontal = 12.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "直播中",
            style = MaterialTheme.typography.bodyMedium,
            color = contentColor,
            maxLines = 1,
        )
    }
}

/**
 * 节目单底部面板（竖屏用）：固定占屏幕下半 50% 高度，从底部滑入。
 *
 * 固定高度并使用独立列表滚动，避免面板手势与节目列表滚动冲突。
 */
@Composable
private fun PlaybillBottomSheet(
    show: Boolean,
    dates: List<PlaybillDate>,
    programs: List<Program>,
    selectedDate: Long,
    isLoading: Boolean,
    error: String?,
    onSelectDate: (Long) -> Unit,
    onPlayReplay: (Program) -> Unit,
    isPlaying: Boolean,
    playingProgramTitle: String?,
    onTogglePlayPause: () -> Unit,
    onPlayLive: () -> Unit,
    onDismiss: () -> Unit,
) {
    var render by remember { mutableStateOf(false) }
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(show) { if (show) render = true else visible = false }
    LaunchedEffect(render) { if (render) visible = true }
    if (!render) return

    val anim by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(220),
        finishedListener = {
            if (!visible) {
                render = false
                if (show) onDismiss()
            }
        },
        label = "playbillSheetAnim",
    )
    val startClose = { visible = false }

    Dialog(
        onDismissRequest = startClose,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        val dialogWindow = (LocalView.current.parent as? DialogWindowProvider)?.window
        LaunchedEffect(dialogWindow) { dialogWindow?.setDimAmount(0f) }

        Box(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { this.alpha = anim }
                    .background(Color.Black.copy(alpha = 0.6f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = startClose,
                    ),
            )
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .fillMaxHeight(0.5f)
                    .graphicsLayer { translationY = (1f - anim) * size.height }
                    .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {},
                    )
                    .padding(16.dp),
            ) {
                PlaybillContent(
                    dates = dates,
                    programs = programs,
                    selectedDate = selectedDate,
                    isLoading = isLoading,
                    error = error,
                    onSelectDate = onSelectDate,
                    onPlayReplay = onPlayReplay,
                    isPlaying = isPlaying,
                    playingProgramTitle = playingProgramTitle,
                    onTogglePlayPause = onTogglePlayPause,
                    onPlayLive = onPlayLive,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

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
private fun SleepTimerButton(
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
 * 睡眠定时底部菜单（手机）：从屏幕下方滑入，高度按内容自适应（仅容纳标题 + 滑块）。
 * 结构仿 [PlaybillBottomSheet]：全屏 [Dialog] + 自绘遮罩 + 底部面板 translationY 滑入/滑出；
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

    Dialog(
        onDismissRequest = startClose,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        // 关掉窗口自带的 dim，只保留下方可随动画渐变的自绘遮罩（否则两层遮罩叠加）。
        val dialogWindow = (LocalView.current.parent as? DialogWindowProvider)?.window
        LaunchedEffect(dialogWindow) { dialogWindow?.setDimAmount(0f) }

        Box(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { this.alpha = anim }
                    .background(Color.Black.copy(alpha = 0.6f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = startClose,
                    ),
            )
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .wrapContentHeight()
                    .graphicsLayer { translationY = (1f - anim) * size.height }
                    .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {},
                    )
                    .padding(horizontal = 24.dp, vertical = 20.dp),
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
