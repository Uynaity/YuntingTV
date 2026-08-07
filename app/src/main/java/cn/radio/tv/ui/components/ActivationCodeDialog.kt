package cn.radio.tv.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.Text

/**
 * 激活码字符集。与服务端 `activation.go:codeAlphabet` **必须一致** ——
 * 剔除了易混淆的 `0/O`、`1/I/L`，因为这串码要用电视遥控器逐字输入，
 * 认错一个字符就得整串重来。
 */
private const val CODE_ALPHABET = "23456789ABCDEFGHJKMNPQRSTUVWXYZ"

/** 码长（不含分隔横杠）：3 组 × 4 字符。 */
private const val CODE_LENGTH = 12

/** 键盘列数。31 个字符 + 退格 = 32 键，8 列正好 4 行，方键在电视上不至于太小。 */
private const val KEYBOARD_COLUMNS = 8

/**
 * 激活码输入弹窗。两端共用同一个提交逻辑（[onSubmit]），只是输入控件按设备形态分流：
 *
 * - **TV（[useGridKeyboard] = true）**：复用 [KeyboardGrid] 的 D-pad 网格键盘。
 * - **手机**：普通 [OutlinedTextField] + 系统输入法。
 *
 * 弹窗用 [Dialog] 而非 Popup（见 compose-ui-guidelines.md）；样式复用 [UpdateDialog]
 * 的 Surface + [DialogButton]，不另起一套。
 */
@Composable
fun ActivationCodeDialog(
    useGridKeyboard: Boolean,
    submitting: Boolean,
    onSubmit: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var code by remember { mutableStateOf("") }
    val complete = code.length == CODE_LENGTH

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            colors = SurfaceDefaults.colors(
                containerColor = MaterialTheme.colorScheme.surface,
            ),
            // TV 网格键盘要 8 列方键，比普通弹窗宽得多；手机走窄档。
            modifier = Modifier.widthIn(
                min = if (useGridKeyboard) 560.dp else 320.dp,
                max = if (useGridKeyboard) 720.dp else 420.dp,
            ),
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    text = "输入激活码",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )

                if (useGridKeyboard) {
                    CodeDisplay(code)
                    val firstKey = remember { FocusRequester() }
                    LaunchedEffect(Unit) { runCatching { firstKey.requestFocus() } }
                    KeyboardGrid(
                        keys = CODE_ALPHABET.map { c ->
                            // 输满即忽略后续字符，不静默截断也不吞掉退格。
                            KeyboardKey(c.toString()) {
                                if (code.length < CODE_LENGTH) code += c
                            }
                        } + KeyboardKey("⌫") { code = code.dropLast(1) },
                        columns = KEYBOARD_COLUMNS,
                        firstKey = firstKey,
                    )
                } else {
                    MobileCodeField(
                        code = code,
                        onCodeChange = { code = it },
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(
                        16.dp,
                        Alignment.CenterHorizontally,
                    ),
                ) {
                    DialogButton(text = "取消", onClick = onDismiss)
                    DialogButton(
                        text = if (submitting) "激活中…" else "激活",
                        // 码没输满或请求在途时不放行 —— 省掉一次注定失败的往返，
                        // 也挡住遥控器连按导致的重复提交。
                        onClick = { if (complete && !submitting) onSubmit(code) },
                    )
                }
            }
        }
    }
}

/**
 * TV 侧的码回显：按 4-4-4 分组显示，未输入的位用 `·` 占位，
 * 让用户一眼看出还差几位 —— 遥控器输入慢，进度可见很重要。
 */
@Composable
private fun CodeDisplay(code: String) {
    val padded = code.padEnd(CODE_LENGTH, '·')
    Text(
        text = padded.chunked(4).joinToString(" - "),
        style = MaterialTheme.typography.headlineSmall,
        color = Color.White,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
            .padding(vertical = 12.dp),
    )
}

/** 手机侧输入框。参照 [MobileSearchBar] 的输入法用法：进来即弹键盘。 */
@Composable
private fun MobileCodeField(code: String, onCodeChange: (String) -> Unit) {
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) {
        runCatching { focusRequester.requestFocus() }
        keyboard?.show()
    }
    OutlinedTextField(
        value = code,
        // 归一化在客户端也做一遍：小写、横杠、空格都容忍（服务端同样容忍，
        // 见 activation.go:normalizeCode），但字符集外的输入直接丢，不让用户
        // 输完一整串才被告知无效。
        onValueChange = { raw ->
            onCodeChange(
                raw.uppercase().filter { it in CODE_ALPHABET }.take(CODE_LENGTH),
            )
        },
        modifier = Modifier
            .fillMaxWidth()
            .focusRequester(focusRequester),
        singleLine = true,
        placeholder = { androidx.compose.material3.Text("XXXX-XXXX-XXXX") },
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
            capitalization = KeyboardCapitalization.Characters,
        ),
    )
}
