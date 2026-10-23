package dev.piko.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import dev.piko.shared.translate.SubtitleTranslator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
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
import dev.piko.ui.components.PikoTextField
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * AI 翻译的接口配置：OpenAI 兼容的地址、Key、模型名。
 *
 * 不绑定任何厂商——DeepSeek、OpenAI、通义、中转站、本地 Ollama 都行，只要认
 * `/chat/completions` 这套协议。「测试」用框里还没保存的值现试一句，省得保存完
 * 翻字幕时才发现填错。
 */
@Composable
internal fun TranslationSettingsDialog(
    gateway: TranslationGateway,
    currentBaseUrl: String,
    currentApiKey: String,
    currentModel: String,
    currentReasoning: String,
    currentPrompt: String,
    onSave: (baseUrl: String, apiKey: String, model: String, reasoning: String, prompt: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var baseUrl by remember { mutableStateOf(currentBaseUrl) }
    var apiKey by remember { mutableStateOf(currentApiKey) }
    var model by remember { mutableStateOf(currentModel) }
    var reasoning by remember { mutableStateOf(currentReasoning) }
    // 输入框里始终显示"实际会生效的提示词"：用户没改过就填默认的那套，
    // 看得见才好改；保存时若与默认一致就存空串，以后默认提示词升级能跟着更新
    var prompt by remember {
        mutableStateOf(currentPrompt.ifBlank { SubtitleTranslator.SUBTITLE_PROMPT })
    }
    var result by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    var testing by remember { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()

    val trimmedUrl = baseUrl.trim()
    val urlInvalid = trimmedUrl.isNotEmpty() &&
        !trimmedUrl.startsWith("http://") && !trimmedUrl.startsWith("https://")
    val complete = trimmedUrl.isNotEmpty() && apiKey.trim().isNotEmpty() && model.trim().isNotEmpty()

    SettingsInputDialog(
        icon = Icons.Outlined.Translate,
        title = "AI 翻译",
        description = "翻译字幕与片名都用这套配置。填 OpenAI 兼容的接口即可（DeepSeek、OpenAI、中转站、本地 Ollama 都行）。",
        onSave = {
            val custom = prompt.trim()
            onSave(
                trimmedUrl,
                apiKey.trim(),
                model.trim(),
                reasoning,
                // 与默认一致就存空，恢复成"跟随内置提示词"
                if (custom == SubtitleTranslator.SUBTITLE_PROMPT.trim()) "" else custom,
            )
        },
        saveEnabled = !urlInvalid,
        onDismiss = onDismiss,
    ) {
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
            value = apiKey,
            onValueChange = { apiKey = it; result = null },
            label = "API Key",
            supportingText = "只存在本机（系统加密），不上传",
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        )
        PikoTextField(
            value = model,
            onValueChange = { model = it; result = null },
            label = "模型名",
            placeholder = "deepseek-chat",
            supportingText = "照服务商文档填，如 deepseek-chat、gpt-4o-mini、qwen-plus",
        )
        Text(
            text = "推理强度",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            REASONING_OPTIONS.forEach { (value, label) ->
                FilterChip(
                    selected = reasoning == value,
                    onClick = { reasoning = value },
                    label = { Text(label, maxLines = 1) },
                )
            }
        }
        Text(
            text = "关掉最快，适合字幕这种一次几百条的小请求；翻译质量不满意再把档位调高。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "字幕翻译提示词",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { prompt = SubtitleTranslator.SUBTITLE_PROMPT }) {
                Text("恢复默认")
            }
        }
        OutlinedTextField(
            value = prompt,
            onValueChange = { prompt = it },
            minLines = 6,
            maxLines = 12,
            textStyle = MaterialTheme.typography.bodySmall,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            text = "末尾的「行数必须一致」「不要行号」那几条别删：删了模型会把多行合并，" +
                "程序对不上行就只能逐条重翻，速度会掉很多。片名翻译不受这里影响。",
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
                        // 用框里当前的值现建客户端，不依赖是否已保存
                        val client = gateway.clientFor(trimmedUrl, apiKey.trim(), model.trim())
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
    }
}

/** 推理强度：值 → 界面文案。空串是不传参数（最快）。 */
private val REASONING_OPTIONS = listOf(
    "" to "关闭",
    "low" to "低",
    "medium" to "中",
    "high" to "高",
)
