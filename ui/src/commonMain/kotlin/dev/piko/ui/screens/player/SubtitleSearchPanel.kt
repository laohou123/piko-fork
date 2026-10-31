package dev.piko.ui.screens.player

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Subtitles
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.piko.shared.library.SubtitleHit
import dev.piko.shared.state.SubtitleSearchState
import dev.piko.ui.LocalPikoServices
import dev.piko.ui.components.PikoEmptyState

/**
 * 搜字幕的面板内容。播放器的「搜索字幕」（PlayerSheet.SearchSubtitle）与海报页的
 * [dev.piko.ui.screens.poster.SubtitleSearchSheet] 共用这一份，风格与「AI 翻译字幕」面板一致：
 * 顶上一条搜索行、下面是整块列表，不套外层的 PikoSheet 边框。
 *
 * 搜索行左侧留 24dp、列表左右各留 12dp（行内再 12dp），与播放器里其它面板对齐；
 * 输入框的右侧内嵌字幕源按钮（展开选择 SubtitleCat / 迅雷），不用再去别处切换。
 *
 * 下载后的字幕后写本机、排进上传队列传到 [targetFolderName]，传完回调 [onUploaded]
 * 让播放器当场挂上。
 */
@Composable
internal fun SubtitleSearchPanel(
    code: String,
    targetFolderId: String,
    targetFolderName: String,
    onUploaded: (fileId: String, fileName: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val services = LocalPikoServices.current
    val scope = rememberCoroutineScope()
    val sources = services.subtitleSources
    val saver = services.subtitleSaver

    if (sources.isEmpty() || saver == null) {
        PikoEmptyState(
            title = "没有可用的字幕源",
            description = "这个版本没有配置字幕源，暂时只能手动上传字幕",
            modifier = modifier.fillMaxSize(),
        )
        return
    }

    val state = remember(code, targetFolderId) {
        SubtitleSearchState(
            availableSources = sources,
            saver = saver,
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

    Column(modifier.fillMaxSize()) {
        SearchRow(state)

        state.error?.let { HintLine(it, isError = true) }
        state.result?.let { HintLine(it) }

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

@Composable
private fun SearchRow(state: SubtitleSearchState) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 24.dp, end = 12.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
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
            // 源选择内嵌在框里：点开就能换源，换完自动按当前关键词重搜
            trailingIcon = { SourcePicker(state) },
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = { state.search() }, enabled = !state.searching) {
            Icon(Icons.Outlined.Search, contentDescription = "搜索")
        }
    }
}

@Composable
private fun SourcePicker(state: SubtitleSearchState) {
    var expanded by remember { mutableStateOf(false) }
    // 只有一个源时不显示切换入口：点了也没得选
    if (state.sources.size <= 1) return

    Box {
        TextButton(
            onClick = { expanded = true },
            contentPadding = PaddingValues(horizontal = 4.dp),
        ) {
            Text(
                text = state.currentSource.name,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 72.dp),
            )
            Icon(
                Icons.Outlined.ExpandMore,
                contentDescription = "选择字幕源",
                modifier = Modifier.size(18.dp),
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            state.sources.forEachIndexed { index, option ->
                DropdownMenuItem(
                    text = { Text(option.name) },
                    leadingIcon = {
                        if (index == state.selectedSourceIndex) {
                            Icon(Icons.Outlined.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                        } else {
                            Spacer(Modifier.width(18.dp))
                        }
                    },
                    onClick = {
                        expanded = false
                        state.selectSource(index)
                    },
                )
            }
        }
    }
}

@Composable
private fun HintLine(text: String, isError: Boolean = false) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 6.dp),
    )
}

@Composable
private fun HitList(state: SubtitleSearchState) {
    if (state.hits.isEmpty()) return
    LazyColumn(contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 24.dp)) {
        items(state.hits, key = { it.pagePath }) { hit ->
            HitRow(hit) { state.openDetail(hit) }
        }
    }
}

@Composable
private fun HitRow(hit: SubtitleHit, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .heightIn(min = 56.dp)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Outlined.Subtitles,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = hit.name,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            val meta = buildList {
                hit.durationLabel?.let { add(it) }
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
    LazyColumn(contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 24.dp)) {
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
                    .heightIn(min = 56.dp)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                Icon(
                    Icons.Outlined.Subtitles,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = candidate.label,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        text = candidate.path.substringAfterLast('/').substringBefore('?'),
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
                            candidate.original -> "下载"
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
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
                )
            }
        }
    }
}
