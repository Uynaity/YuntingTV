package cn.radio.tv.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import cn.radio.tv.data.model.Channel

/** 收藏星标用的金色。 */
private val GoldStar = Color(0xFFFFC107)

/** 左侧播放器面板：封面 + 名称 + 当前节目 + 播放/暂停按钮。 */
@Composable
fun PlayerPanel(
    channel: Channel?,
    isPlaying: Boolean,
    isBuffering: Boolean,
    isFavorite: Boolean,
    onTogglePlayPause: () -> Unit,
    modifier: Modifier = Modifier,
    playButtonFocusRequester: FocusRequester? = null,
) {
    Column(
        modifier = modifier
            .fillMaxHeight()
            .background(MaterialTheme.colorScheme.surface)
            .padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        // 封面
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
                    modifier = Modifier.fillMaxWidth().aspectRatio(1f),
                )
            } else {
                Text(
                    text = "📻",
                    style = MaterialTheme.typography.displayLarge,
                )
            }
        }

        // 电台名称（已收藏时标题前加金色星标）
        val titleText = channel?.title?.trim() ?: "未在播放"
        Text(
            text = if (channel != null && isFavorite) {
                buildAnnotatedString {
                    withStyle(SpanStyle(color = GoldStar)) { append("★ ") }
                    append(titleText)
                }
            } else {
                AnnotatedString(titleText)
            },
            style = MaterialTheme.typography.headlineSmall,
            color = Color.White,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
        )

        // 当前节目
        Text(
            text = when {
                channel == null -> "请选择一个电台"
                isBuffering -> "缓冲中…"
                else -> channel.subtitle.ifBlank { "暂无节目单" }
            },
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        )

        // 播放/暂停按钮
        PlayPauseButton(
            isPlaying = isPlaying,
            isBuffering = isBuffering,
            enabled = channel != null,
            onClick = onTogglePlayPause,
            focusRequester = playButtonFocusRequester,
            modifier = Modifier.padding(top = 32.dp),
        )
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
            .let { if (focusRequester != null) it.focusRequester(focusRequester) else it }
            .onFocusChanged { focused = it.isFocused }
            .clip(CircleShape)
            .clickable(
                enabled = enabled,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .background(container, CircleShape)
            .border(2.dp, if (focused) Color.White else Color.Transparent, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (enabled && isBuffering) {
            // 缓冲中：旋转加载圈替代播放/暂停图标
            SpinningArc(color = iconColor, size = 30.dp, strokeWidth = 3.dp)
            return@Box
        }
        Canvas(modifier = Modifier.size(30.dp)) {
            if (isPlaying) {
                // 暂停：两根竖条（水平居中）
                val barW = size.width * 0.26f
                val gap = size.width * 0.18f
                val total = barW * 2 + gap
                val startX = (size.width - total) / 2f
                drawRect(iconColor, topLeft = Offset(startX, 0f), size = Size(barW, size.height))
                drawRect(
                    iconColor,
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
                drawPath(path, iconColor)
            }
        }
    }
}
