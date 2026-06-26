package cn.radio.tv

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.util.UnstableApi
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Surface
import cn.radio.tv.ui.RadioScreen
import cn.radio.tv.ui.RadioViewModel
import cn.radio.tv.ui.theme.RadioTvTheme

class MainActivity : ComponentActivity() {
    @androidx.annotation.OptIn(UnstableApi::class)
    @OptIn(ExperimentalTvMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            RadioTvTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    shape = RectangleShape,
                ) {
                    val viewModel: RadioViewModel = viewModel()
                    RadioScreen(viewModel = viewModel)
                }
            }
        }
    }
}
