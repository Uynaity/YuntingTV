package cn.radio.tv.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import cn.radio.tv.data.model.Program
import cn.radio.tv.ui.PlaybillDate
import cn.radio.tv.ui.theme.GoldStar
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * 节目单按钮：仿 [SleepTimerButton] 的 44dp 圆形可聚焦按钮，置于播放/暂停键右侧。
 * 打开态（[active]）用金色高亮，未打开显示日历图标。
 */
@Composable
internal fun PlaybillButton(
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

/**
 * 两列节目单：左列 9 个日期（可上下选择，选中金色高亮），右列所选日期的节目。
 * 右列按状态展示：加载中 / 失败 / 空 / 节目列表（可回放者右侧显示回放图标）。
 * 竖屏由 [PlaybillBottomSheet] 弹层内嵌，横屏由 RadioScreen 直接置于右侧列表区。
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
 * 节目单底部面板（竖屏用）：固定占屏幕下半 50% 高度，从底部滑入（见 [SlideUpSheet]）。
 *
 * 显隐由 [show] 驱动而非条件挂载：关闭时（点遮罩/返回键，或外部把状态置否如点回听）都先播
 * 滑出动画，动画结束再真正卸载，保证所有关闭路径一致下滑。
 */
@Composable
internal fun PlaybillBottomSheet(
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
                // 若关闭由弹层内操作发起（点遮罩/返回键，此时 show 仍为真）→ 回写状态关闭；
                // 若 show 已为假（外部已关，如点回听）→ 跳过，避免把状态又切回打开。
                if (show) onDismiss()
            }
        },
        label = "playbillSheetAnim",
    )

    SlideUpSheet(
        anim = anim,
        onClose = { visible = false },
        panelModifier = Modifier.fillMaxHeight(0.5f),
        contentPadding = PaddingValues(16.dp),
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
