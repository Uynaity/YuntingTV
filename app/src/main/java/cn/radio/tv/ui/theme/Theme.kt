package cn.radio.tv.ui.theme

import androidx.compose.runtime.Composable
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun RadioTvTheme(
    content: @Composable () -> Unit,
) {
    val colorScheme = darkColorScheme(
        primary = YtmRed,
        onPrimary = YtmOnSurface,
        background = YtmBackground,
        onBackground = YtmOnSurface,
        surface = YtmSurface,
        onSurface = YtmOnSurface,
        surfaceVariant = YtmSurfaceVariant,
        onSurfaceVariant = YtmOnSurfaceMuted,
    )
    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
