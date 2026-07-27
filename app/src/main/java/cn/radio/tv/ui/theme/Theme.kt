package cn.radio.tv.ui.theme

import androidx.compose.runtime.Composable
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme
import androidx.compose.material3.MaterialTheme as M3MaterialTheme
import androidx.compose.material3.darkColorScheme as m3DarkColorScheme

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
    // 竖屏用到的少量 Material3 组件（SearchBar）读的是 androidx.compose.material3 的主题，
    // 与 tv-material 的是两套独立 Local。不套一层的话它们会退回默认浅紫配色。
    M3MaterialTheme(
        colorScheme = m3DarkColorScheme(
            primary = YtmRed,
            onPrimary = YtmOnSurface,
            background = YtmBackground,
            onBackground = YtmOnSurface,
            surface = YtmSurface,
            onSurface = YtmOnSurface,
            surfaceVariant = YtmSurfaceVariant,
            onSurfaceVariant = YtmOnSurfaceMuted,
            // SearchBar 的容器色取自 surfaceContainerHigh，默认值在深色下偏灰紫。
            surfaceContainerHigh = YtmSurfaceVariant,
        ),
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography,
            content = content
        )
    }
}
