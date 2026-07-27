package cn.radio.tv

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.util.UnstableApi
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import cn.radio.tv.ui.RadioScreen
import cn.radio.tv.ui.RadioViewModel
import cn.radio.tv.ui.theme.RadioTvTheme

class MainActivity : ComponentActivity() {

    private val requestNotificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    @androidx.annotation.OptIn(UnstableApi::class)
    @OptIn(ExperimentalTvMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configureSystemBars()
        ensureNotificationPermission()
        setContent {
            RadioTvTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    shape = RectangleShape,
                    // edge-to-edge 区域（包括透明系统栏下方）与页面主背景保持一致。
                    colors = androidx.tv.material3.SurfaceDefaults.colors(
                        containerColor = MaterialTheme.colorScheme.background,
                    ),
                ) {
                    val viewModel: RadioViewModel = viewModel()
                    RadioScreen(viewModel = viewModel)
                }
            }
        }
    }

    /**
     * TV 保持原来的沉浸式体验；手机按 Android edge-to-edge 规范绘制到透明系统栏后方，
     * 具体可交互内容由 Compose 的 safeDrawing insets 避让刘海、状态栏和手势导航区。
     */
    private fun configureSystemBars() {
        val isTv = packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
        if (isTv) {
            WindowCompat.setDecorFitsSystemWindows(window, false)
            WindowInsetsControllerCompat(window, window.decorView).apply {
                hide(WindowInsetsCompat.Type.statusBars())
                systemBarsBehavior =
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            // 页面使用深色背景，系统栏保持透明并使用浅色图标。
            enableEdgeToEdge(
                statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
                navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            )
        }
    }

    /** Android 13+ 发媒体通知需运行时授权 POST_NOTIFICATIONS；未授权则请求一次。 */
    private fun ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
