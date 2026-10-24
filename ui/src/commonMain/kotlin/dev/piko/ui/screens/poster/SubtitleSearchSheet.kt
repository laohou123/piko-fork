package dev.piko.ui.screens.poster

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.piko.shared.library.SubtitleCandidate
import dev.piko.shared.library.SubtitleHit
import dev.piko.shared.state.SubtitleSearchState
import dev.piko.ui.LocalPikoServices
import dev.piko.ui.components.PikoSheet

/**
 * 给一部片搜字幕：SubtitleCat 搜番号 → 选一条结果看有哪些语言 → 选一个下载。
 * 下载后写成本机文件并排进上传队列，传到 [targetFolderName]（视频旁边），
 * 之后播放器的「网盘字幕」里就能看到它。
 */
@Composable
fun SubtitleSearchSheet(
    code: String,
    targetFolderId: String,
    targetFolderName: String,
    onDismissRequest: () -> Unit,
    /** 字幕传上去了（文件 id、文件名）：播放器用它当场挂载。 */
    onUploaded: (fileId: String, fileName: String) -> Unit = { _, _ -> },
) {
    val services = LocalPikoServices.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val state = remember(code, targetFolderId) {
        SubtitleSearchState(
            source = services.subtitleSource ?: error("字幕源未提供"),
            saver = services.subtitleSaver ?: error("字幕保存未提供"),
            uploads = services.uploadManager,
            scope = scope,
            initialQuery = code,
            onUploaded = onUploaded,
        )
    }

    LaunchedEffect(state) {
        // 进来就按番号搜一次：这个面板九成是因为「这片没字幕」打开的
        state.search(code)
    }

    PikoSheet(onDismissRequest = onDismissRequest, sideSheetTitle = "搜索字幕") {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (state.detail != null) {
                    IconButton(onClick = state::closeDetail) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回搜索结果")
                    }
                }
                OutlinedTextField(
                    value = state.query,
                    onValueChange = state::updateQuery,
                    singleLine = true,
                    label = { Text("番号或关键词") },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { state.search() }),
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { state.search() }, enabled = !state.searching) {
                    Icon(Icons.Outlined.Search, contentDescription = "搜索")
                }
            }
            state.error?.let { error ->
                Text(
                    text = error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            state.result?.let { result ->
                Text(
                    text = result,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            when {
                state.searching -> Row(
                    modifier = Modifier.fillMaxWidth().padding(24.dp),
                    horizontalArrangement = Arrangement.Center,
                ) { CircularProgressIndicator() }

                state.detail != null -> DetailList(
                    state = state,
                    code = code,
                    targetFolderId = targetFolderId,
                    targetFolderName = targetFolderName,
                )

                else -> HitList(state)
            }
        }
    }
}

@Composable
private fun HitList(state: SubtitleSearchState) {
    if (state.hits.isEmpty()) return
    LazyColumn {
        items(state.hits, key = { it.pagePath }) { hit ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { state.openDetail(hit) }
                    .padding(horizontal = 4.dp, vertical = 12.dp),
            ) {
                Text(
                    text = hit.name,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                val meta = buildList {
                    if (hit.languages > 0) add("${hit.languages} 种语言")
                    if (hit.sizeKb > 0) add("${hit.sizeKb} KB")
                }.joinToString(" · ")
                if (meta.isNotEmpty()) {
                    Text(
                        text = meta,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun DetailList(
    state: SubtitleSearchState,
    code: String,
    targetFolderId: String,
    targetFolderName: String,
) {
    val detail = state.detail ?: return
    if (state.loadingDetail) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(24.dp),
            horizontalArrangement = Arrangement.Center,
        ) { CircularProgressIndicator() }
        return
    }
    LazyColumn {
        items(detail.candidates, key = { it.path }) { candidate ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = state.savingPath == null) {
                        if (candidate.translatable) {
                            // 站点上还没有这个语言：请站点翻一份，过几分钟再搜就能下载
                            state.requestTranslation(candidate)
                        } else {
                            state.download(candidate, code, targetFolderId, targetFolderName)
                        }
                    }
                    .padding(horizontal = 4.dp, vertical = 12.dp),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = candidate.label,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        text = candidate.path.substringAfterLast('/'),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (state.savingPath == candidate.path) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp))
                } else {
                    Text(
                        text = when {
                            candidate.translatable -> "翻译"
                            candidate.original -> "原始"
                            else -> "下载"
                        },
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
        if (detail.candidates.none { it.translatable } && detail.translatableCount > 0) {
            item {
                Text(
                    text = "站点还支持翻译成 ${detail.translatableCount} 种语言，可到网站上请求",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 12.dp),
                )
            }
        }
    }
}
