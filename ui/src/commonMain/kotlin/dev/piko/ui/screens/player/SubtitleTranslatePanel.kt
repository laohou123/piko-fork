package dev.piko.ui.screens.player

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Subtitles
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.piko.shared.media.player.SubtitleBrowserState
import dev.piko.shared.state.SubtitleTranslateState
import dev.piko.ui.platform.LocalPikoPlatform
import dev.piko.ui.LocalPikoServices
import dev.piko.ui.components.PikoEmptyState

/**
 * AI 翻译网盘里的字幕：先在这个面板里选一份原字幕（通常是日文），翻好的
 * `<番号>.zh.srt` 传回同一目录并当场挂上。
 *
 * 译文落在网盘而不是本机：换设备、重装 App 都能直接用，也不用重复花钱翻。
 */
@Composable
internal fun SubtitleTranslatePanel(
    videoFileId: String,
    code: String,
    onUploaded: (fileId: String, name: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val services = LocalPikoServices.current
    val scope = rememberCoroutineScope()
    val browser = remember(videoFileId) { SubtitleBrowserState(services.driveRepository, scope, videoFileId) }
    val gateway = services.translation
    val httpClient = services.translationHttp
    val uploads = services.uploadManager
    val saver = services.subtitleSaver
    val enabled = gateway != null && httpClient != null && saver != null

    val state = remember(videoFileId, code, enabled) {
        if (!enabled) {
            null
        } else {
            SubtitleTranslateState(
                browser = browser,
                media = services.mediaRepository,
                http = requireNotNull(httpClient),
                uploads = uploads,
                saver = requireNotNull(saver),
                gateway = requireNotNull(gateway),
                targetFolder = { browser.current?.let { it.id to it.name } },
                code = code,
                scope = scope,
                onUploaded = onUploaded,
            )
        }
    }

    if (state == null) {
        PikoEmptyState(
            title = "还没配置 AI 翻译",
            description = "去「设置 → AI 翻译」填好接口、Key 与模型，再回来翻字幕",
            modifier = modifier.fillMaxSize(),
        )
        return
    }

    BackHandler(enabled = browser.path.size > 1) { browser.navigateUp() }

    Column(modifier.fillMaxSize()) {
        Text(
            text = browser.path.joinToString(" / ") { it.name },
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.StartEllipsis,
            modifier = Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 6.dp),
        )

        // 进度与结果都摆在列表上方，翻的时候不用找
        val progress = state.progress
        when {
            state.translatingId != null && state.stage == SubtitleTranslateState.Stage.TRANSLATING -> {
                Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 6.dp)) {
                    Text(
                        text = "正在翻译 ${progress?.doneBatches ?: 0}/${progress?.totalBatches ?: 0} 段" +
                            "（已译 ${progress?.translatedLines ?: 0} 行）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    LinearProgressIndicator(
                        progress = { progress?.fraction ?: 0f },
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                    )
                }
            }
            state.stage == SubtitleTranslateState.Stage.DOWNLOADING -> HintLine("正在读取字幕…")
            state.stage == SubtitleTranslateState.Stage.UPLOADING -> HintLine("正在上传译文…")
            else -> {}
        }
        state.error?.let { HintLine("翻译失败：$it", isError = true) }
        state.result?.let { HintLine(it) }

        LazyColumn(
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 24.dp),
            modifier = Modifier.fillMaxWidth().weight(1f),
        ) {
            if (browser.path.size > 1) {
                item(key = "up") {
                    PickerRow(icon = Icons.Filled.ArrowUpward, label = "上一级", onClick = { browser.navigateUp() })
                }
            }
            items(browser.folders, key = { "d:" + it.id }) { folder ->
                PickerRow(icon = Icons.Outlined.Folder, label = folder.name, onClick = { browser.open(folder) })
            }
            items(browser.subtitles, key = { "f:" + it.id }) { file ->
                PickerRow(
                    icon = Icons.Outlined.Subtitles,
                    label = file.name,
                    // 已经在翻的时候整行不可点，避免同时发两份
                    onClick = { if (state.canTranslate()) state.translate(file) },
                    trailing = {
                        IconButton(
                            enabled = state.canTranslate(),
                            onClick = { state.translate(file) },
                        ) {
                            Icon(
                                Icons.Outlined.Translate,
                                contentDescription = "翻译成中文",
                                tint = if (state.canTranslate()) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                        }
                    },
                )
            }
            when {
                browser.isLoading -> item(key = "loading") {
                    Text(
                        text = "读取目录…",
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth().padding(24.dp),
                    )
                }
                browser.loadError != null -> item(key = "error") {
                    Column(
                        Modifier.fillMaxWidth().padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            "读取目录失败：${browser.loadError}",
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        TextButton(onClick = { browser.reload() }) { Text("重试") }
                    }
                }
                browser.folders.isEmpty() && browser.subtitles.isEmpty() -> item(key = "empty") {
                    Text(
                        text = "这个文件夹里没有字幕文件。可以先用「搜索字幕」下一个日文字幕，再来翻译。",
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth().padding(24.dp),
                    )
                }
            }
        }
    }
}

@Composable
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
private fun HintLine(
    text: String,
    isError: Boolean = false,
    maxLines: Int = Int.MAX_VALUE,
    onLongClick: (() -> Unit)? = null,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (onLongClick != null) {
                    Modifier.combinedClickable(onClick = onLongClick, onLongClick = onLongClick)
                } else {
                    Modifier
                },
            )
            .padding(horizontal = 24.dp, vertical = 6.dp),
    )
}
