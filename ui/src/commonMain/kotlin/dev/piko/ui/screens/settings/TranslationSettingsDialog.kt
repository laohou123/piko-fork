package dev.piko.ui.screens.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.piko.data.auth.DEFAULT_TRANSLATION_CONCURRENCY
import dev.piko.shared.translate.SubtitleTranslator
import dev.piko.shared.translate.TranslationGateway
import dev.piko.shared.translate.TranslationProfile
import dev.piko.ui.components.PikoTextField
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * AI 翻译的配置面板：OpenAI 兼容的地址、密钥、模型名，按「档案」组织。
 *
 * 一个档案就是一条可切换的翻译通道：接口地址 + 这个接口的密钥（可挂多把，轮着用）+ 模型名。
 * 顶部卡片显示当前激活的档案，点它展开档案列表（切换 / 编辑 / 删除 / 新增），
 * 推理强度、并发数、提示词是全局的，所有档案共用。
 *
 * 「测试」用当前激活档案的第一把密钥现试一句，省得保存完翻字幕时才发现填错。
 */
@Composable
internal fun TranslationSettingsDialog(
    gateway: TranslationGateway,
    profiles: List<TranslationProfile>,
    activeProfileId: String?,
    currentReasoning: String,
    currentPrompt: String,
    currentConcurrency: Int,
    onSave: (
        profiles: List<TranslationProfile>,
        activeProfileId: String?,
        reasoning: String,
        prompt: String,
        concurrency: Int,
    ) -> Unit,
    onDismiss: () -> Unit,
) {
    // 档案列表在对话框内是草稿：增删改都先落在这里，保存才写回
    var draft by remember { mutableStateOf(profiles) }
    var activeId by remember { mutableStateOf(activeProfileId ?: profiles.firstOrNull()?.id) }
    var expanded by remember { mutableStateOf(false) }
    var editorOpen by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<TranslationProfile?>(null) }
    var reasoning by remember { mutableStateOf(currentReasoning) }
    var concurrency by remember {
        mutableStateOf(currentConcurrency.takeIf { it > 0 } ?: DEFAULT_TRANSLATION_CONCURRENCY)
    }
    // 输入框里始终显示"实际会生效的提示词"：用户没改过就填默认的那套，
    // 看得见才好改；保存时若与默认一致就存空串，以后默认提示词升级能跟着更新
    var prompt by remember {
        mutableStateOf(currentPrompt.ifBlank { SubtitleTranslator.SUBTITLE_PROMPT })
    }
    var result by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    var testing by remember { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()

    val active = draft.firstOrNull { it.id == activeId } ?: draft.firstOrNull()

    SettingsInputDialog(
        icon = Icons.Outlined.Translate,
        title = "AI 翻译",
        description = "翻译字幕与片名都用当前激活的档案。一个档案 = 接口地址 + 密钥（可多把轮着用）+ 模型名，" +
            "DeepSeek、OpenAI、中转站、本地 Ollama 都行，只要认 /chat/completions 这套协议。",
        onSave = {
            val custom = prompt.trim()
            onSave(
                draft,
                activeId,
                reasoning,
                // 与默认一致就存空，恢复成"跟随内置提示词"
                if (custom == SubtitleTranslator.SUBTITLE_PROMPT.trim()) "" else custom,
                concurrency,
            )
        },
        saveEnabled = true,
        onDismiss = onDismiss,
    ) {
        // ---------------- 当前档案：点开展开列表，右边加号新建 ----------------
        Row(verticalAlignment = Alignment.CenterVertically) {
            ProfileCard(
                profile = active,
                expanded = expanded,
                onClick = { expanded = !expanded },
                modifier = Modifier.weight(1f),
            )
            IconButton(
                onClick = {
                    editing = null
                    editorOpen = true
                },
            ) {
                Icon(Icons.Outlined.Add, contentDescription = "新档案")
            }
        }

        if (expanded) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.padding(vertical = 4.dp)) {
                    draft.forEach { profile ->
                        ProfileRow(
                            profile = profile,
                            selected = profile.id == active?.id,
                            canDelete = draft.size > 1,
                            onSelect = { activeId = profile.id },
                            onEdit = {
                                editing = profile
                                editorOpen = true
                            },
                            onDelete = { id ->
                                val rest = draft.filterNot { it.id == id }
                                draft = rest
                                if (activeId == id) activeId = rest.firstOrNull()?.id
                            },
                        )
                    }
                    TextButton(
                        onClick = {
                            editing = null
                            editorOpen = true
                        },
                        modifier = Modifier.padding(start = 4.dp),
                    ) {
                        Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text("  新档案")
                    }
                }
            }
            if (active == null) {
                Text(
                    text = "还没有档案：点右边「+」新建一个，翻译入口才会亮起来。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

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

        Text(
            text = "同时请求数",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            CONCURRENCY_OPTIONS.forEach { (value, label) ->
                FilterChip(
                    selected = concurrency == value,
                    onClick = { concurrency = value },
                    label = { Text(label, maxLines = 1) },
                )
            }
        }
        Text(
            text = "调高能快几倍，但更容易被服务商限流（429）。被限流不会失败，只是会退避重试，反而更慢——" +
                "所以觉得没快反而更慢时，往回调一档。",
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
        // 大多行框：提示词动辄十几行，用单行框会塌成一条（1.3.11 改档案 UI 时踩过）
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

        // ---------------- 测试：用当前激活档案的第一把密钥 ----------------
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val testable = active != null && active.complete
            OutlinedButton(
                enabled = testable && testing?.isActive != true,
                onClick = {
                    if (testing?.isActive == true) return@OutlinedButton
                    result = null
                    testing = scope.launch {
                        val profile = active ?: return@launch
                        val key = profile.effectiveKeys().first()
                        val client = gateway.clientFor(profile.baseUrl, key, profile.model)
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

    if (editorOpen) {
        TranslationProfileEditorDialog(
            initial = editing,
            gateway = gateway,
            canDelete = draft.size > 1,
            onSave = { saved ->
                val exists = draft.any { it.id == saved.id }
                draft = if (exists) draft.map { if (it.id == saved.id) saved else it } else draft + saved
                if (active == null) activeId = saved.id
                editorOpen = false
            },
            onDelete = { id ->
                val rest = draft.filterNot { it.id == id }
                draft = rest
                if (activeId == id) activeId = rest.firstOrNull()?.id
                editorOpen = false
            },
            onDismiss = { editorOpen = false },
        )
    }
}

/** 当前档案卡：名称 + 地址 + 模型与密钥数，点它展开/收起档案列表。 */
@Composable
private fun ProfileCard(
    profile: TranslationProfile?,
    expanded: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = MaterialTheme.shapes.medium,
        modifier = modifier.clickable(onClick = onClick),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = profile?.let { it.label.ifBlank { it.model } } ?: "未配置",
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                )
                if (profile != null) {
                    Text(
                        text = profile.baseUrl,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                    Text(
                        text = "${profile.model} · ${profile.effectiveKeys().size} 把密钥",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                } else {
                    Text(
                        text = "点右边「+」新建一个档案",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Icon(
                imageVector = if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                contentDescription = if (expanded) "收起档案列表" else "展开档案列表",
            )
        }
    }
}

/** 档案列表里的一行：单选切换激活，右侧编辑与删除。 */
@Composable
private fun ProfileRow(
    profile: TranslationProfile,
    selected: Boolean,
    canDelete: Boolean,
    onSelect: () -> Unit,
    onEdit: () -> Unit,
    onDelete: (String) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect)
            .padding(start = 4.dp, end = 4.dp),
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = profile.label.ifBlank { profile.model },
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
            )
            Text(
                text = "${profile.baseUrl} · ${profile.model} · ${profile.effectiveKeys().size} 把密钥",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
        IconButton(onClick = onEdit) {
            Icon(Icons.Outlined.Edit, contentDescription = "编辑档案", modifier = Modifier.size(18.dp))
        }
        if (canDelete) {
            IconButton(onClick = { onDelete(profile.id) }) {
                Icon(Icons.Outlined.Delete, contentDescription = "删除档案", modifier = Modifier.size(18.dp))
            }
        }
    }
}

/** 可选的同时请求数。3 保守，6 默认，10 激进。 */
private val CONCURRENCY_OPTIONS = listOf(
    3 to "保守 3",
    6 to "标准 6",
    10 to "激进 10",
)

/** 推理强度：值 → 界面文案。空串是不传参数（最快）。 */
private val REASONING_OPTIONS = listOf(
    "" to "关闭",
    "low" to "低",
    "medium" to "中",
    "high" to "高",
)
