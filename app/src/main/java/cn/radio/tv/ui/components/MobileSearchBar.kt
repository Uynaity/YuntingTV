package cn.radio.tv.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SearchBar
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.dp

/**
 * 手机端内联搜索栏。它替换普通顶栏，但不会盖住结果列表：用户输入后可以直接触摸结果，
 * 键盘的搜索键只收起输入法，返回箭头或系统返回键才真正退出搜索。
 *
 * 页面外层已经消费 safeDrawing insets，因此这里把 SearchBar 自带的 windowInsets 置零，
 * 避免状态栏高度被重复计算。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MobileSearchBar(
    query: String,
    isSearching: Boolean,
    resultCount: Int,
    onQueryChange: (String) -> Unit,
    onClose: () -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current

    LaunchedEffect(Unit) {
        runCatching { focusRequester.requestFocus() }
        keyboardController?.show()
    }

    Column(modifier = modifier.fillMaxWidth()) {
        SearchBar(
            inputField = {
                SearchBarDefaults.InputField(
                    query = query,
                    onQueryChange = onQueryChange,
                    onSearch = { keyboardController?.hide() },
                    expanded = false,
                    onExpandedChange = {},
                    modifier = Modifier.focusRequester(focusRequester),
                    placeholder = { Text("电台名称或拼音") },
                    leadingIcon = {
                        IconButton(onClick = onClose) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "退出搜索",
                            )
                        }
                    },
                    trailingIcon = {
                        when {
                            isSearching -> CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp,
                            )

                            query.isNotEmpty() -> IconButton(onClick = onClear) {
                                Icon(Icons.Default.Close, contentDescription = "清空搜索内容")
                            }
                        }
                    },
                )
            },
            expanded = false,
            onExpandedChange = {},
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            windowInsets = WindowInsets(0.dp),
            content = {},
        )

        // 空串而非提示语：搜索范围是当前来源的整份目录，没有「在 X 里搜」这回事可说，
        // 而输入提示已经在输入框的 placeholder 上。保留这一行是为了占住高度 ——
        // 出结果时状态文案原地出现，列表不会整体往上跳。
        Text(
            text = when {
                query.isBlank() -> ""
                isSearching -> "正在搜索…"
                else -> "$resultCount 个结果"
            },
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(start = 28.dp, end = 28.dp, bottom = 4.dp),
        )
    }
}
