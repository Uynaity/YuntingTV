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
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
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
    // 进度条：positionMs/durationMs 来自 VM 的 ProgressState；seekable 仅回放为真。
    positionMs: Long = 0L,
    durationMs: Long = 0L,
    seekable: Boolean = false,
    onSeekTo: (Long) -> Unit = {},
    sleepTimerRemainingMinutes: Int = 0,
    onSetSleepTimer: (Int) -> Unit = {},
    // 节目单与回放
    showPlaybill: Boolean = false,
    onTogglePlaybill: () -> Unit = {},
    playbillDates: List<PlaybillDate> = emptyList(),
    playbillPrograms: List<Program> = emptyList(),
    selectedPlaybillDate: Long = 0L,
    isLoadingPlaybill: Boolean = false,
    playbillError: String? = null,
    onSelectPlaybillDate: (Long) -> Unit = {},
    onPlayReplay: (Program) -> Unit = {},
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
        Column(
            modifier = modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface),
        ) {
            // 进度条置于底部播放条顶部；胶囊 thumb 内显示 当前/总（durationMs<=0 时自隐）。
            PlaybackProgressBar(
                positionMs = positionMs,
                durationMs = durationMs,
                seekable = seekable,
                capsuleThumb = true,
                onSeekTo = onSeekTo,
                onDragPreview = {},
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, end = 20.dp, top = 10.dp),
            )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 封面即播放/暂停按钮：点击切换，中央叠加半透明底衬 + 白色状态图标。
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
                enabled = channel != null,
                onSelect = onSetSleepTimer,
            )
            Spacer(modifier = Modifier.size(16.dp))
            PlaybillButton(
                active = showPlaybill,
                enabled = channel != null,
                onClick = onTogglePlaybill,
            )
        }
        }
        // 竖屏无大封面区：节目单以底部滑入的 50% 高度面板呈现。
        // 无条件调用、由 show 控制显隐，好让所有关闭（点回听/返回键/点遮罩）都走滑出动画。
        PlaybillBottomSheet(
            show = showPlaybill && channel != null,
            dates = playbillDates,
            programs = playbillPrograms,
            selectedDate = selectedPlaybillDate,
            isLoading = isLoadingPlaybill,
            error = playbillError,
            onSelectDate = onSelectPlaybillDate,
            onPlayReplay = onPlayReplay,
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
        // 拖动进度条时的时间预览（当前 ms）；非空则封面上方叠加大字遮罩。
        var coverPreview by remember { mutableStateOf<Long?>(null) }
        // 封面区：始终显示封面。横屏（TV）节目单改由右侧列表区呈现（见 RadioScreen），
        // 此处不再就地替换，避免左侧 1/3 面板过窄。
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
            // 拖动中：封面上方半透明遮罩 + 大字显示 当前/总。
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

        // 电台名称（已收藏时标题前加金色星标）
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

        // 当前节目
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

        // 进度条：置于节目名与播放键之间。回放可遥控拖动（拖动时封面上方大字显示时间）。
        PlaybackProgressBar(
            positionMs = positionMs,
            durationMs = durationMs,
            seekable = seekable,
            capsuleThumb = false,
            onSeekTo = onSeekTo,
            onDragPreview = { coverPreview = it },
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 20.dp),
        )

        // 播放/暂停按钮居中；定时按钮以偏移贴在其左侧（播放键 72、定时键 44、间距 16）。
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
                enabled = channel != null,
                onSelect = onSetSleepTimer,
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
        active -> Accent
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
private const val SEEK_HOLD_THRESHOLD_MS = 400L
private const val SEEK_HOLD_INTERVAL_MS = 200L
private const val SEEK_COMMIT_DEBOUNCE_MS = 1_000L

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
) {
    if (durationMs <= 0L) return

    var dragTarget by remember { mutableStateOf<Long?>(null) }
    var holdDir by remember { mutableIntStateOf(0) }
    var focused by remember { mutableStateOf(false) }

    val displayMs = (dragTarget ?: positionMs).coerceIn(0L, durationMs)
    val ratio = (displayMs.toFloat() / durationMs).coerceIn(0f, 1f)

    fun step(delta: Long) {
        dragTarget = ((dragTarget ?: positionMs) + delta).coerceIn(0L, durationMs)
    }

    // 长按：阈值后转定时器平滑步进（每 200ms +5s）；KeyUp 置 0 即取消本效果。
    LaunchedEffect(holdDir) {
        if (holdDir == 0) return@LaunchedEffect
        delay(SEEK_HOLD_THRESHOLD_MS.milliseconds)
        while (true) {
            step(holdDir * SEEK_HOLD_STEP_MS)
            delay(SEEK_HOLD_INTERVAL_MS.milliseconds)
        }
    }
    // 防抖：dragTarget 每变一次重启本效果；静止 1 秒后关闭预览并真正 seek。
    LaunchedEffect(dragTarget) {
        val target = dragTarget ?: return@LaunchedEffect
        onDragPreview(target)
        delay(SEEK_COMMIT_DEBOUNCE_MS.milliseconds)
        onSeekTo(target)
        onDragPreview(null)
        dragTarget = null
    }

    val primary = MaterialTheme.colorScheme.primary
    var barModifier = modifier.height(if (capsuleThumb) 36.dp else 28.dp)
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
                        // 首个 KeyDown（holdDir 尚非本方向）触发单击 15s 并启动长按监测；
                        // 系统自动重复的后续 KeyDown（holdDir 已==dir）忽略，交由长按定时器步进。
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
            val trackH = 4.dp.toPx()
            val left = r
            val right = size.width - r
            val thumbX = left + (right - left) * ratio
            drawLine(
                color = Color(0x66FFFFFF),
                start = Offset(left, cy), end = Offset(right, cy),
                strokeWidth = trackH, cap = StrokeCap.Round,
            )
            drawLine(
                color = primary,
                start = Offset(left, cy), end = Offset(thumbX, cy),
                strokeWidth = trackH, cap = StrokeCap.Round,
            )
            if (focused) drawCircle(Color.White, r + 4.dp.toPx(), Offset(thumbX, cy))
            drawCircle(primary, r, Offset(thumbX, cy))
        }
        // 手机拖动态：胶囊 thumb 内显 当前/总；随比例在轨道内平移（夹在两端不越界）。
        if (capsuleThumb && dragTarget != null) {
            var thumbW by remember { mutableIntStateOf(0) }
            Box(
                modifier = Modifier
                    .offset { IntOffset((ratio * (widthPx - thumbW)).roundToInt().coerceAtLeast(0), 0) }
                    .onSizeChanged { thumbW = it.width }
                    .clip(RoundedCornerShape(50))
                    .background(primary)
                    .padding(horizontal = 12.dp, vertical = 4.dp),
            ) {
                Text(
                    text = "${formatTime(displayMs)}/${formatTime(durationMs)}",
                    style = MaterialTheme.typography.labelMedium,
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
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier) {
        // 左列：日期
        LazyColumn(
            modifier = Modifier
                .width(88.dp)
                .fillMaxHeight(),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            // 上下留白：首尾项不贴边；滚动时项在留白区内滑过，不裁切、不留黑边。
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
        // 右列：节目
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
                else -> LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    // 右侧留白：回放图标聚焦放大(1.1x)时不贴屏幕右缘、高亮不溢出。
                    // 上下留白：首尾项不贴边；滚动时项在留白区内滑过，不裁切、不留黑边。
                    contentPadding = PaddingValues(top = 12.dp, bottom = 12.dp, end = 12.dp),
                ) {
                    items(programs, key = { "${it.startTime}-${it.title}" }) { program ->
                        ProgramRow(program = program, onPlayReplay = onPlayReplay)
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
        selected -> Accent
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
    onPlayReplay: (Program) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 左侧上下排布：时间段在上，节目名在下（更大字号）。
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
        if (program.canReplay) {
            Spacer(modifier = Modifier.width(12.dp))
            ReplayIcon(onClick = { onPlayReplay(program) })
        }
    }
}

@Composable
private fun ReplayIcon(onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val container = if (focused) Color.White else MaterialTheme.colorScheme.surfaceVariant
    val contentColor = if (focused) Color.Black else Accent
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

/**
 * 节目单底部面板（竖屏用）：固定占屏幕下半 50% 高度，从底部滑入。
 *
 * 不用可拖拽的 ModalBottomSheet —— 它的「上滑展开」与列表「上滑滚动」是同一手势、
 * 靠嵌套滚动仲裁，必然冲突；固定单锚点又会在用力上滑时过冲抖动。这里锁定高度、
 * 内层两列独占上下滑动手势，既不打架也不抖动。点遮罩 / 返回键关闭。
 *
 * 显隐由 [show] 驱动而非条件挂载：关闭时（无论点遮罩/返回键，还是外部把状态置否，
 * 如点回听）都先播滑出动画，动画结束再真正卸载，保证所有关闭路径一致下滑。
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
    onDismiss: () -> Unit,
) {
    // render：是否挂载（含滑出动画期间）；visible：动画方向（true 滑入 / false 滑出）。
    var render by remember { mutableStateOf(false) }
    var visible by remember { mutableStateOf(false) }
    // show 打开 → 先挂载；show 关闭 → 触发滑出（render 待动画结束再撤）。
    LaunchedEffect(show) { if (show) render = true else visible = false }
    // 挂载后的下一帧再 visible=true，才能从 0 播放滑入动画（同帧置真会直接跳到终态）。
    LaunchedEffect(render) { if (render) visible = true }
    if (!render) return

    val anim by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(220),
        finishedListener = {
            if (!visible) {
                render = false
                // 若关闭由弹层内操作发起（点遮罩/返回键，此时 show 仍为真）→ 回写状态关闭；
                // 若 show 已为假（外部已关，如点回听）→ 跳过，避免把状态又切回打开。
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
            // 遮罩：随动画淡入淡出，点击关闭。
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
            // 底部面板：锁定 50% 高度，按自身高度从底部滑入（translationY 用 size.height）。
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
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

/** 金色高亮（与全局收藏/选中风格一致）。 */
private val Accent = Color(0xFFFFC107)

/** 睡眠定时步长（分钟）与最大档位：0..8 -> 0..120 分钟，0 = 关闭。 */
private const val SLEEP_STEP_MINUTES = 15
private const val SLEEP_MAX_STEP = 8

/**
 * 睡眠定时按钮：暂停键左侧的圆形按钮。未设定显示时钟图标，计时中显示剩余分钟。
 * 点击就地弹出滑块（[SleepTimerSlider]）：触屏拖动松手即启，遥控左右键调值静止 1 秒即启。
 */
@Composable
private fun SleepTimerButton(
    remainingMinutes: Int,
    enabled: Boolean,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    var focused by remember { mutableStateOf(false) }
    val active = remainingMinutes > 0
    val container = when {
        !enabled -> MaterialTheme.colorScheme.surfaceVariant
        focused -> Color.White
        active -> Accent
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
                onClick = { if (enabled) expanded = true },
                enabled = enabled,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = if (active) remainingMinutes.toString() else "⏰",
            style = MaterialTheme.typography.titleSmall,
            color = contentColor,
        )
    }

    if (expanded) {
        SleepTimerOverlay(
            initialMinutes = remainingMinutes,
            onCommit = onSelect,
            onDismiss = { expanded = false },
        )
    }
}

/**
 * 睡眠定时弹层：全屏 [Dialog] 居中显示滑块，四周半透明遮罩压暗背景。
 * 用 `decorFitsSystemWindows = false` + `usePlatformDefaultWidth = false` 让弹窗窗口铺满整屏
 * （含状态栏、底部导航条区域），遮罩才能全屏覆盖系统栏。
 * 渐入渐隐：内容整体透明度动画，关闭时先播放渐隐再真正移除（[onDismiss] 在动画结束回调触发）。
 * 遮罩点击 / 返回键 / 选定档位均触发关闭动画。
 */
@Composable
private fun SleepTimerOverlay(
    initialMinutes: Int,
    onCommit: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { visible = true }
    val alpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(180),
        finishedListener = { if (!visible) onDismiss() },
        label = "sleepOverlayAlpha",
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

        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { this.alpha = alpha },
            contentAlignment = Alignment.Center,
        ) {
            // 遮罩：点击空白处关闭（无水波纹）
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.6f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = startClose,
                    ),
            )
            // 弹窗卡片：吞掉点击避免穿透到遮罩
            Box(
                modifier = Modifier.clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {},
                ),
            ) {
                SleepTimerSlider(
                    initialMinutes = initialMinutes,
                    onCommit = { minutes ->
                        onCommit(minutes)
                        startClose()
                    },
                )
            }
        }
    }
}

/**
 * 睡眠定时滑块：0..[SLEEP_MAX_STEP] 档，每档 [SLEEP_STEP_MINUTES] 分钟，0 = 关闭。
 * 触屏 [detectHorizontalDragGestures] 拖动改档、松手提交；遥控 [onKeyEvent] 左右键改档，
 * 值变后 1 秒无再动则提交（防抖）。仅在用户动过后才提交，避免打开即重置计时。
 */
@Composable
private fun SleepTimerSlider(
    initialMinutes: Int,
    onCommit: (Int) -> Unit,
) {
    var step by remember {
        mutableIntStateOf((initialMinutes / SLEEP_STEP_MINUTES).coerceIn(0, SLEEP_MAX_STEP))
    }
    var touched by remember { mutableStateOf(false) }
    var focused by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focusRequester.requestFocus() } }

    // 遥控防抖提交：档位变化且用户动过后，静止 1 秒即提交（值再变会取消重启本效果）。
    LaunchedEffect(step) {
        if (!touched) return@LaunchedEffect
        delay(1000.milliseconds)
        onCommit(step * SLEEP_STEP_MINUTES)
    }

    Column(
        modifier = Modifier
            .width(260.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 20.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = if (step == 0) "关闭定时" else "${step * SLEEP_STEP_MINUTES} 分钟后暂停",
            style = MaterialTheme.typography.titleMedium,
            color = Color.White,
        )
        Spacer(modifier = Modifier.size(12.dp))
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(32.dp)
                .focusRequester(focusRequester)
                .onFocusChanged { focused = it.isFocused }
                .focusable()
                .onKeyEvent { e ->
                    if (e.type != KeyEventType.KeyDown) return@onKeyEvent false
                    when (e.key) {
                        Key.DirectionLeft -> {
                            if (step > 0) {
                                touched = true; step--
                            }; true
                        }

                        Key.DirectionRight -> {
                            if (step < SLEEP_MAX_STEP) {
                                touched = true; step++
                            }; true
                        }

                        else -> false
                    }
                }
                .pointerInput(Unit) {
                    detectHorizontalDragGestures(
                        onDragEnd = { touched = true; onCommit(step * SLEEP_STEP_MINUTES) },
                    ) { change, _ ->
                        touched = true
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
                color = Accent,
                start = Offset(r, cy), end = Offset(thumbX, cy),
                strokeWidth = trackH, cap = StrokeCap.Round,
            )
            if (focused) drawCircle(Color.White, r + 4.dp.toPx(), Offset(thumbX, cy))
            drawCircle(Accent, r, Offset(thumbX, cy))
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
            // 暂停：两根竖条（水平居中）
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
            // 播放：三角形（光学居中，整体略向右偏移）
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
