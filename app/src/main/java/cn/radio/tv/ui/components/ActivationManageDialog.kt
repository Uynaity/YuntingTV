package cn.radio.tv.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.Text

/**
 * 激活码管理弹窗。已激活时从设置页「管理激活码」进入，两个动作：换一个码、或解绑本设备。
 *
 * **同一个 Dialog 内切两态，不叠两层 Dialog**：TV 上多层弹窗的焦点管理很容易出问题
 * （上层关闭后焦点不回下层、返回键层层穿透）。这里用一个 [confirmingUnbind] 在「选择态」
 * 与「解绑确认态」之间换内容，焦点只需在切换时 requestFocus 一次。
 *
 * @param onUpdate 用户选「更新激活码」—— 调用方据此关掉本弹窗、打开 [ActivationCodeDialog]。
 * @param onUnbind 用户已完成二次确认，真的要解绑。
 * @param onDismiss 取消/返回键关闭。
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun ActivationManageDialog(
    expiryText: String?,
    onUpdate: () -> Unit,
    onUnbind: () -> Unit,
    onDismiss: () -> Unit,
) {
    var confirmingUnbind by remember { mutableStateOf(false) }
    // 破坏性操作的默认焦点必须落在安全选项上：遥控器的确定键就在手边，
    // 默认选中「解绑」等于给误触留门（同 ExitConfirmDialog 的约定）。
    val safeFocusRequester = remember { FocusRequester() }

    LaunchedEffect(confirmingUnbind) {
        runCatching { safeFocusRequester.requestFocus() }
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            colors = SurfaceDefaults.colors(
                containerColor = MaterialTheme.colorScheme.surface,
            ),
            modifier = Modifier.widthIn(min = 320.dp, max = 460.dp),
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 28.dp, vertical = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = if (confirmingUnbind) "解绑本设备" else "管理激活码",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = if (confirmingUnbind) {
                        // 「剩余有效期不变」必须写出来 —— 这正是用户最担心的事（怕一解绑码就废了）。
                        "解绑后本设备将立即失去 TuneIn 代理权限。\n" +
                            "激活码会回到未使用状态，剩余有效期不变，可在任意设备重新兑换。"
                    } else {
                        expiryText ?: "本设备已激活"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 12.dp, bottom = 24.dp),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(
                        16.dp,
                        Alignment.CenterHorizontally,
                    ),
                ) {
                    if (confirmingUnbind) {
                        DialogButton(
                            text = "取消",
                            onClick = { confirmingUnbind = false },
                            focusRequester = safeFocusRequester,
                        )
                        DialogButton(text = "确定", onClick = onUnbind)
                    } else {
                        DialogButton(
                            text = "更换",
                            onClick = onUpdate,
                            focusRequester = safeFocusRequester,
                        )
                        DialogButton(
                            text = "解绑",
                            onClick = { confirmingUnbind = true },
                        )
                    }
                }
            }
        }
    }
}
