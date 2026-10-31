package dev.piko.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.piko.shared.translate.TranslationGateway
import dev.piko.shared.translate.TranslationProfile
import dev.piko.ui.components.PikoTextField
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * 新建 / 编辑一个翻译档案：备注名、接口地址、模型名，以及这个接口的密钥列表。
 *
 * 密钥可以挂多把：翻译时轮着用，某把报「Key 无效 / 余额不足」就自动跳过换下一把，
 * 所以几个平台的 Key、同平台的几把备用 Key 都可以填在一起。
 */
@Composable
internal fun TranslationProfileEditorDialog(
    /** null 表示新建；非 null 表示编辑这个档案（保留原 id）。 */
    initial: TranslationProfile?,
    gateway: TranslationGateway,
    /** 能不能删：列表里只剩一个档案时不给删（删了就没了）。 */
    canDelete: Boolean,
    onSave: (TranslationProfile) -> Unit,
    onDelete: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val isNew = initial == null
    var label by remember { mutableStateOf(initial?.label.orEmpty()) }
    var baseUrl by remember { mutableStateOf(initial?.baseUrl.orEmpty()) }
    var model by remember { mutableStateOf(initial?.model.orEmpty()) }
    // 至少留一行空框，方便直接输入；空串项保存时剔除
    var keys by remember {
        mutableStateOf(
            initial?.keys.orEmpty().ifEmpty { listOf("") },
        )
    }
    var result by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    var testing by remember { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()

    val trimmedUrl = baseUrl.trim()
    val urlInvalid = trimmedUrl.isNotEmpty() &&
        !trimmedUrl.startsWith("http://") && !trimmedUrl.startsWith("https://")
    val effectiveKeys = keys.map { it.trim() }.filter { it.isNotEmpty() }
    val complete = trimmedUrl.isNotEmpty() && !urlInvalid && model.isNotBlank() && effectiveKeys.isNotEmpty()

    SettingsInputDialog(
        icon = Icons.Outlined.Translate,
        title = if (isNew) "新档案" else "编辑档案",
        description = "一个档案就是一条翻译通道：接口地址 + 密钥 + 模型名。" +
            "密钥可填多把，翻译时轮着用，某把失效自动换下一把。",
        onSave = {
            onSave(
                TranslationProfile(
                    id = initial?.id ?: TranslationProfile.newId(),
                    label = label.trim(),
                    baseUrl = trimmedUrl,
                    keys = effectiveKeys,
                    model = model.trim(),
                ),
            )
        },
        saveEnabled = complete,
        onDismiss = onDismiss,
    ) {
        PikoTextField(
            value = label,
            onValueChange = { label = it; result = null },
            label = "备注名",
            placeholder = "如：DeepSeek 主力 / 中转站备用",
            supportingText = "可不填，列表里用模型名显示",
        )
        PikoTextField(
            value = baseUrl,
            onValueChange = { baseUrl = it; result = null },
            label = "接口地址",
            placeholder = "https://api.deepseek.com/v1",
            isError = urlInvalid,
            supportingText = "填到 /v1 为止即可，也可以填完整的 /chat/completions",
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        )
        PikoTextField(
            value = model,
            onValueChange = { model = it; result = null },
            label = "模型名",
            placeholder = "deepseek-chat",
            supportingText = "照服务商文档填，如 deepseek-chat、gpt-4o-mini、qwen-plus",
        )

        Text(
            text = "API Key（${effectiveKeys.size} 把）",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        keys.forEachIndexed { index, key ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                PikoTextField(
                    value = key,
                    onValueChange = { value ->
                        keys = keys.mapIndexed { i, old -> if (i == index) value else old }
                        result = null
                    },
                    label = "密钥 ${index + 1}",
                    modifier = Modifier.weight(1f),
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                )
                if (keys.size > 1) {
                    IconButton(onClick = { keys = keys.filterIndexed { i, _ -> i != index } }) {
                        Icon(
                            Icons.Outlined.Delete,
                            contentDescription = "删除这把密钥",
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }
        }
        if (keys.size < MAX_KEYS) {
            TextButton(onClick = { keys = keys + "" }) {
                Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Text("  添加密钥")
            }
        }
        Text(
            text = "只存在本机（系统加密），随账号同步时整体加密后进网盘。轮询规则：每批请求轮流用，" +
                "某把报 Key 无效或余额不足，本次翻译内自动跳过它。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(
                enabled = complete && testing?.isActive != true,
                onClick = {
                    if (testing?.isActive == true) return@OutlinedButton
                    result = null
                    testing = scope.launch {
                        // 用框里还没保存的值现试一句
                        val client = gateway.clientFor(trimmedUrl, effectiveKeys.first(), model.trim())
                        val outcome = client.translate(
                            system = "把用户提供的内容翻译成简体中文，只输出译文。",
                            input = "テスト",
                            maxTokens = 64,
                        )
                        result = outcome.fold(
                            onSuccess = { "连接正常，模型回了「${it.trim().take(20)}」" to true },
                            onFailure = { (it.message ?: "测试失败") to false },
                        )
                    }
                },
            ) {
                Text(if (testing?.isActive == true) "测试中…" else "测试连接")
            }
            result?.let { (text, ok) ->
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (ok) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                    modifier = Modifier.weight(1f),
                )
            }
        }

        if (initial != null && canDelete) {
            TextButton(
                onClick = { onDelete(initial.id) },
                modifier = Modifier.padding(top = 4.dp),
            ) {
                Icon(Icons.Outlined.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                Text("  删除这个档案")
            }
        }
    }
}

/** 一个档案最多挂几把 Key：再多对个人用户没意义，也能防误操作。 */
private const val MAX_KEYS = 8
