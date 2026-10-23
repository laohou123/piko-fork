package dev.piko.ui.screens.poster

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.piko.shared.data.ScanStop
import dev.piko.shared.library.NamedCount
import dev.piko.shared.library.WorkRow
import dev.piko.shared.state.PosterWallState
import dev.piko.shared.naming.av.matchAvFile
import dev.piko.ui.LocalPikoServices
import dev.piko.ui.components.PikoDialog
import dev.piko.ui.components.PikoEmptyState
import dev.piko.ui.components.PikoScaffold
import dev.piko.ui.components.PikoSheet
import dev.piko.ui.components.PikoTopBar
import dev.piko.ui.components.iconBarItem
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * 海报墙：扫一个网盘目录，把里面的番号片收成一部一部的网格。列表、演员与标签
 * 都来自本地索引，滑起来不请求网盘；封面与元数据由 MetaTube 补齐，
 * 没刮到的显示番号占位，可以随时点「补刮」。
 *
 * 顶部三件事（右上角）：选扫描目录、补刮元数据、重新扫描。
 * 列表上方是当前目录与进度，再往下是筛选行：全部 / 收藏 / 演员 / 标签。
 */
@Composable
fun PosterWallScreen(
    onOpenWork: (String) -> Unit,
    onBackClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val services = LocalPikoServices.current
    // 入口在 posterWall 为 null（截图环境）时给了兜底空态，正常到不了这里；仍防一手
    val state = services.posterWall ?: return
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    // 网格位置存在 state 里（普通字段，不是 mutableStateOf）：切 tab、进详情页再回来都在原位，
    // 退出 App 后进程销毁、state 随之丢失，自然回到顶部
    val gridState = rememberLazyGridState(
        initialFirstVisibleItemIndex = state.scrollIndex,
        initialFirstVisibleItemScrollOffset = state.scrollOffset,
    )

    // 滚动时把位置写回 state。值没变就不写；写的是两个 Int，不触发重组
    LaunchedEffect(gridState) {
        snapshotFlow {
            gridState.firstVisibleItemIndex to gridState.firstVisibleItemScrollOffset
        }
            .distinctUntilChanged()
            .collect { (index, offset) -> state.rememberScroll(index, offset) }
    }

    // 筛选或搜索换了，列表整体变了，回顶部；首帧不碰，好让恢复的位置生效
    val listKey = state.filter to state.query
    var prevListKey by remember { mutableStateOf(listKey) }
    LaunchedEffect(listKey) {
        if (listKey != prevListKey) {
            prevListKey = listKey
            gridState.scrollToItem(0)
        }
    }

    var showScrapeDialog by remember { mutableStateOf(false) }
    var showFolderPicker by remember { mutableStateOf(false) }
    var showFilterSheet by remember { mutableStateOf(false) }

    LaunchedEffect(state) {
        state.messages.collect { snackbarHostState.showSnackbar(it, withDismissAction = true) }
    }

    PikoScaffold(
        modifier = modifier,
        topBar = {
            PikoTopBar(
                title = "海报墙",
                onBackClick = onBackClick,
                actions = listOf(
                    iconBarItem(Icons.Outlined.Search, "搜索影片", onClick = { state.setSearchOpen(true) }, priority = 2),
                    iconBarItem(Icons.Outlined.FolderOpen, "扫描目录", onClick = { showScrapeDialog = true }, priority = 1),
                    iconBarItem(Icons.Outlined.AutoAwesome, "补刮元数据", priority = 1, enabled = !state.enriching, onClick = { state.enrich() }),
                    iconBarItem(Icons.Outlined.Refresh, "重新扫描", priority = 0, enabled = state.phase != PosterWallState.Phase.SCANNING, onClick = { state.startScan() }),
                ),
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        // 回顶悬浮按钮：滑过第一屏才出现，点一下平滑滚回海报墙顶部
        floatingActionButton = {
            AnimatedVisibility(
                visible = gridState.firstVisibleItemIndex > 0,
                enter = fadeIn() + scaleIn(),
                exit = fadeOut() + scaleOut(),
            ) {
                FloatingActionButton(
                    onClick = { scope.launch { gridState.animateScrollToItem(0) } },
                ) {
                    Icon(Icons.Filled.ArrowUpward, contentDescription = "回到顶部")
                }
            }
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            // 展开状态存在 state 里：点进作品详情再返回，搜索框还在原来的样子
            if (state.searchActive) {
                SearchBar(
                    query = state.query,
                    onQueryChange = state::updateQuery,
                    onClose = { state.setSearchOpen(false) },
                )
            }
            // 筛选行固定在顶部；目录与进度放在网格里，跟着列表一起滚走（参考图的行为）
            FilterRow(
                state = state,
                onOpenFilterSheet = { showFilterSheet = true },
            )
            when {
                state.loading && state.works.isEmpty() -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator() }

                state.works.isEmpty() -> Column(modifier = Modifier.fillMaxSize()) {
                    ScanHeader(state = state, onStopScan = state::stopScan)
                    PikoEmptyState(
                        title = when {
                            state.rootFolder == null -> "先选一个目录"
                            state.filter == PosterWallState.Filter.FAVORITE -> "还没有收藏"
                            state.query.isNotBlank() -> "没搜到「${state.query}」"
                            state.workCount == 0L -> "这个目录里还没有番号片"
                            else -> "没有符合条件的作品"
                        },
                        description = when {
                            state.rootFolder == null -> "点右上角「扫描目录」选一个网盘目录，海报墙会按番号把视频收成一部一部"
                            state.filter == PosterWallState.Filter.FAVORITE -> "长按演员或标签、或在详情页点心形按钮即可收藏"
                            state.query.isNotBlank() -> "换个关键词试试：番号、标题、女优、标签都能搜"
                            else -> "换个筛选条件，或扫别的目录看看"
                        },
                        icon = Icons.Outlined.GridView,
                        actionText = state.rootFolder?.let { "重新扫描" },
                        onActionClick = state::startScan,
                        modifier = Modifier.weight(1f),
                    )
                }

                else -> LazyVerticalGrid(
                    columns = GridCells.Adaptive(PosterMinWidth),
                    state = gridState,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        ScanHeader(state = state, onStopScan = state::stopScan)
                    }
                    items(state.works, key = { it.code }) { work ->
                        PosterCard(work = work, onClick = { onOpenWork(work.code) })
                    }
                }
            }
        }
    }

    // 扫描目录：元数据由 MetaTube 提供，这里只管选哪个网盘目录
    if (showScrapeDialog) {
        ScanFolderDialog(
            rootFolder = state.rootFolder,
            onDismissRequest = { showScrapeDialog = false },
            onPickFolder = { showFolderPicker = true },
        )
    }

    if (showFolderPicker) {
        ScanFolderPickerSheet(
            driveRepo = services.driveRepository,
            onDismissRequest = { showFolderPicker = false },
            onPick = { folderId, folderName ->
                // 选完即开扫：收掉文件夹面板，也收掉设着它的那个对话框，别盖在正在扫描的墙上
                showFolderPicker = false
                showScrapeDialog = false
                state.setRootFolder(folderId, folderName)
            },
        )
    }

    if (showFilterSheet) {
        val names = when (state.filter) {
            PosterWallState.Filter.ACTOR -> state.actors
            PosterWallState.Filter.TAG -> state.tags
            else -> emptyList()
        }
        ActorTagSheet(
            title = if (state.filter == PosterWallState.Filter.ACTOR) "按演员筛选" else "按标签筛选",
            entries = names,
            onDismissRequest = { showFilterSheet = false },
            onPick = { name ->
                showFilterSheet = false
                state.selectFilter(state.filter, name)
            },
            onToggleFavorite = { name ->
                if (state.filter == PosterWallState.Filter.ACTOR) {
                    state.toggleActorFavorite(name)
                } else {
                    state.toggleTagFavorite(name)
                }
            },
        )
    }
}

/** 扫描目录与进度都收在这一张卡片里：目录只展示（改它去右上角「刮削源与目录」）。 */
@Composable
private fun ScanHeader(
    state: PosterWallState,
    onStopScan: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Outlined.FolderOpen,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
                Text(
                    text = state.rootFolder?.second?.ifEmpty { "根目录" } ?: "未选择目录",
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(start = 8.dp),
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                Text(
                    text = "全部 ${state.workCount} 部 · 已刮削 ${state.scrapedCount} · " +
                        "未刮削 ${(state.workCount - state.scrapedCount).coerceAtLeast(0)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                when (state.phase) {
                    PosterWallState.Phase.SCANNING -> {
                        TextButton(onClick = onStopScan) { Text("停止") }
                    }

                    else -> Unit
                }
            }
            if (state.phase == PosterWallState.Phase.SCANNING) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
                Text(
                    text = "正在扫描：${state.scannedFolders} 个目录、${state.scannedFiles} 个文件" +
                        (state.scanStop?.let { "（${stopText(it)}）" } ?: ""),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
            } else if (state.scannedFiles > 0) {
                // 文件数与作品数不是一个口径：同一部片的分集/多版本会归成一部，
                // 字幕、图片、非视频文件也计入扫描数但不成为作品。这里直接说明，省得以为是丢片。
                // 提前结束时还要讲清楚：没扫到的作品不会被当成"已删除"
                val stop = state.scanStop
                Text(
                    text = if (stop != null) {
                        "扫描未完成（${stopText(stop)}）：本次看到 ${state.scannedFiles} 个文件、" +
                            "归成 ${state.workCount} 部；没扫到的作品保持原样，不会被删"
                    } else {
                        "本次扫描 ${state.scannedFiles} 个文件，按番号归成 ${state.workCount} 部" +
                            "（同番号的分集算一部；字幕、图片等非视频不计）"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            if (state.enriching) {
                LinearProgressIndicator(
                    progress = { if (state.enrichTotal > 0) state.enrichDone.toFloat() / state.enrichTotal else 0f },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
                Text(
                    text = "补刮元数据：${state.enrichDone + state.enrichFailed}/${state.enrichTotal}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            if (state.phase == PosterWallState.Phase.FAILED) {
                Text(
                    text = "扫描失败：${state.scanError ?: "未知错误"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
    }
}

private fun stopText(stop: ScanStop): String = when (stop) {
    ScanStop.FOLDER_LIMIT -> "目录太多，只扫了一部分"
    ScanStop.FILE_LIMIT -> "文件太多，只扫了一部分"
    ScanStop.TIMEOUT -> "扫描超时，只扫了一部分"
    ScanStop.CANCELLED -> "已停止"
}

/** 筛选行：全部 / 收藏 / 演员 / 标签，四个按钮等宽平分整行。 */
@Composable
private fun FilterRow(
    state: PosterWallState,
    onOpenFilterSheet: () -> Unit,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
    ) {
        FixedFilterChip(
            modifier = Modifier.weight(1f),
            selected = state.filter == PosterWallState.Filter.ALL,
            label = "全部",
            onClick = { state.selectFilter(PosterWallState.Filter.ALL) },
        )
        FixedFilterChip(
            modifier = Modifier.weight(1f),
            selected = state.filter == PosterWallState.Filter.FAVORITE,
            label = "收藏",
            onClick = { state.selectFilter(PosterWallState.Filter.FAVORITE) },
            leadingIcon = {
                Icon(
                    if (state.filter == PosterWallState.Filter.FAVORITE) Icons.Filled.Star else Icons.Outlined.StarOutline,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                )
            },
        )
        // 已选中某个演员时再点一次是「取消筛选」（回全部），不再弹列表；
        // 没选中时才设好类型并弹列表。选中态看 filterName 而不是只看类型，
        // 否则弹了列表又直接关掉会留下一个高亮却没筛选的假状态。
        FixedFilterChip(
            modifier = Modifier.weight(1f),
            selected = state.filter == PosterWallState.Filter.ACTOR && state.filterName != null,
            label = state.filterName?.takeIf { state.filter == PosterWallState.Filter.ACTOR } ?: "演员",
            onClick = {
                if (state.filter == PosterWallState.Filter.ACTOR && state.filterName != null) {
                    state.selectFilter(PosterWallState.Filter.ALL)
                } else {
                    state.selectFilter(PosterWallState.Filter.ACTOR)
                    onOpenFilterSheet()
                }
            },
        )
        FixedFilterChip(
            modifier = Modifier.weight(1f),
            selected = state.filter == PosterWallState.Filter.TAG && state.filterName != null,
            label = state.filterName?.takeIf { state.filter == PosterWallState.Filter.TAG } ?: "标签",
            onClick = {
                if (state.filter == PosterWallState.Filter.TAG && state.filterName != null) {
                    state.selectFilter(PosterWallState.Filter.ALL)
                } else {
                    state.selectFilter(PosterWallState.Filter.TAG)
                    onOpenFilterSheet()
                }
            },
        )
    }
}

/**
 * 等宽筛选按钮：宽度由外面的 weight(1f) 平分，四个按钮一样长。
 * 不用 M3 的 FilterChip——它在受限宽度下会把 label 挤成省略号；这里自己排，
 * 文字单行居中，放不下省略成「三上…」。
 */
@Composable
private fun FixedFilterChip(
    selected: Boolean,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leadingIcon: (@Composable () -> Unit)? = null,
) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
        border = BorderStroke(
            1.dp,
            if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.outlineVariant,
        ),
        // 与 M3 FilterChip 一致地补 48dp 最小触摸区（视觉 32dp、布局 48dp），
        // 否则四个按钮在 Row 里高度不一，看着上下错位
        modifier = modifier
            .minimumInteractiveComponentSize()
            .height(32.dp)
            .clickable(onClick = onClick),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
            modifier = Modifier.fillMaxSize().padding(horizontal = 6.dp),
        ) {
            leadingIcon?.invoke()
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = if (selected) {
                    MaterialTheme.colorScheme.onSecondaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = if (leadingIcon != null) 4.dp else 0.dp),
            )
        }
    }
}

/** 顶部搜索框：按番号、标题、女优、标签搜，输入即过滤；点 X 退出并清空。 */
@Composable
private fun SearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    onClose: () -> Unit,
) {
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        // 等一帧再要焦点：组合刚建立时焦点节点还没挂上，直接 requestFocus 会抛异常
        withFrameNanos { }
        runCatching { focusRequester.requestFocus() }
    }
    // 光标跟着输入框走而不是留在开头：搜索框收起再展开、或从详情页返回时组件重建，
    // 值还在而内部的光标位置丢了，直接敲字会插到已有文字的最前面
    var field by remember(query) {
        mutableStateOf(TextFieldValue(query, selection = TextRange(query.length)))
    }
    OutlinedTextField(
        value = field,
        onValueChange = { field = it; onQueryChange(it.text) },
        singleLine = true,
        placeholder = { Text("搜索番号、标题、女优或标签") },
        leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
        trailingIcon = {
            IconButton(onClick = onClose) {
                Icon(Icons.Filled.Close, contentDescription = "关闭搜索")
            }
        },
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .focusRequester(focusRequester),
    )
}

/**
 * 一张作品：左边横向封面（16:9，Crop 填满），右边番号、标题、发行日期与时长。
 * 封面为空时是占位底色与番号；多文件的在封面右上角标出个数。
 */
@Composable
private fun PosterCard(work: WorkRow, onClick: () -> Unit) {
    // 固定高度：卡片在网格里高低不齐很难看；封面用 fillMaxSize 撑满这一格。
    // 之前用 IntrinsicSize.Min + 异步图片做固有测量，在 Lazy 网格里会直接崩，这里不再用固有测量。
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth().height(PosterCardHeight)) {
        Row(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .width(PosterCoverWidth)
                    .fillMaxHeight()
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest),
            ) {
                // 封面用 Fit 完整显示（不裁两侧），留下的边由底色补上
                CoverImage(
                    code = work.code,
                    coverUrl = work.coverUrl,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
                if (work.fileCount > 1) {
                    Surface(
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        modifier = Modifier.align(Alignment.TopEnd).padding(6.dp),
                    ) {
                        Text(
                            text = "${work.fileCount} 个文件",
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }
                }
                // 封面左下角压一条半透明底，显示女优名（参考图的位置）
                work.firstActor?.let { actor ->
                    Text(
                        text = actor,
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .background(Color.Black.copy(alpha = 0.55f))
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }
            Column(
                modifier = Modifier.weight(1f).fillMaxHeight().padding(horizontal = 10.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.Center,
            ) {
                // 文件名认不出番号的作品：key 就是文件名，直接把文件名当标题显示，
                // 不贴「未识别番号」这种标签——对用户来说那就是个能播的文件
                val matched = remember(work.code) { matchAvFile(work.code) != null }
                if (matched) {
                    Text(
                        text = work.code,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                    )
                    Text(
                        // 有中文标题就显示译名，没有则原文
                        text = work.displayTitle ?: if (work.scraped) "（无标题）" else "待刮削",
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                } else {
                    Text(
                        text = work.code,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                val meta = buildList {
                    work.releaseDate?.let { add(it) }
                    work.durationMinutes?.let { add("$it 分钟") }
                }.joinToString(" · ")
                if (meta.isNotEmpty()) {
                    Text(
                        text = meta,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
        }
    }
}

/**
 * 演员/标签列表：收藏的置顶（带实心星），点一行按它筛选，长按收藏/取消收藏。
 * 排序由索引查询给出（收藏在前，其余按作品数）。
 */
@Composable
private fun ActorTagSheet(
    title: String,
    entries: List<NamedCount>,
    onDismissRequest: () -> Unit,
    onPick: (String) -> Unit,
    onToggleFavorite: (String) -> Unit,
) {
    PikoSheet(onDismissRequest = onDismissRequest, sideSheetTitle = title) {
        if (entries.isEmpty()) {
            Text(
                text = "还没有数据：先扫描目录并补刮元数据",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(24.dp),
            )
            return@PikoSheet
        }
        LazyColumn {
            items(entries, key = { it.name }) { entry ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .combinedClickable(
                            onClick = { onPick(entry.name) },
                            onLongClick = { onToggleFavorite(entry.name) },
                        )
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                ) {
                    if (entry.favorite) {
                        Icon(
                            Icons.Filled.Star,
                            contentDescription = "已收藏",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp).padding(end = 2.dp),
                        )
                    }
                    Text(
                        text = entry.name,
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f).padding(start = 4.dp),
                    )
                    Text(
                        text = "${entry.count}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        Text(
            text = "长按收藏/取消收藏，收藏的排在最前面",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * 扫描目录：选哪个网盘目录。元数据与封面由设置里配的 MetaTube 提供，这里不再让用户
 * 填站点地址（JavBus 直抓已删除）。
 */
@Composable
private fun ScanFolderDialog(
    rootFolder: Pair<String, String>?,
    onDismissRequest: () -> Unit,
    onPickFolder: () -> Unit,
) {
    PikoDialog(
        onDismissRequest = onDismissRequest,
        title = { Text("扫描目录") },
        text = {
            Column {
                Text(
                    text = "元数据与封面来自设置里配置的 MetaTube 服务。选好目录后立即开始扫描。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                ) {
                    Icon(
                        Icons.Outlined.FolderOpen,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp),
                    )
                    Text(
                        text = rootFolder?.second?.ifEmpty { "根目录" } ?: "未选择目录",
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                    )
                    TextButton(onClick = onPickFolder) {
                        Text(if (rootFolder == null) "选择目录" else "更改")
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismissRequest) { Text("关闭") }
        },
    )
}

// 横向卡片：一屏放不下两张，最小 300dp 让封面有足够宽度
private val PosterMinWidth = 300.dp
private val PosterCoverWidth = 160.dp

/** 卡片固定高度：网格里高低不齐很难看，也让封面有确定的填充区域。 */
private val PosterCardHeight = 100.dp

/** 演员/标签 chip 的固定宽度：名字再长也占这么大，超出的省略。 */
private val FilterChipWidth = 96.dp
