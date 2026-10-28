package dev.piko.ui.screens.poster

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.AddPhotoAlternate
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material.icons.outlined.Subtitles
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.ImageNotSupported
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.compose.SubcomposeAsyncImage
import dev.piko.shared.library.ScannedWorkFile
import dev.piko.shared.library.WorkDetail
import dev.piko.shared.media.PreparedPlayback
import dev.piko.shared.media.player.PlaybackTarget
import dev.piko.ui.LocalPikoServices
import dev.piko.ui.components.PikoDialog
import dev.piko.ui.components.PikoEmptyState
import dev.piko.ui.components.PikoScaffold
import dev.piko.ui.components.PikoSheet
import dev.piko.ui.components.PikoTopBar
import dev.piko.ui.components.iconBarItem
import dev.piko.ui.platform.LocalPikoPlatform
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 一部作品的详情，版式照参考图从上到下：
 * 预览片（**Piko 自己的播放后端**播这部片，没有文件就显示封面）→ 番号与标题 → 摘要
 * → 元数据卡片 → **截图墙**（第一张是封面，点开可全屏左右翻）→ 标签 → 女优 → 文件。
 *
 * 底部是固定操作栏：播放 / 字幕 / 收藏，滚动时不跟着走。
 * 演员与标签点一下就是按它筛选（回到海报墙列表）。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WorkDetailScreen(
    code: String,
    onBackClick: () -> Unit,
    onPlay: (ScannedWorkFile) -> Unit,
    onFilterActor: (String) -> Unit,
    onFilterTag: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val services = LocalPikoServices.current
    val platform = LocalPikoPlatform.current
    val scope = rememberCoroutineScope()
    // 设置里可以关掉自动预览：关掉后顶部只显示封面，不占网盘连接
    val autoPreview by services.preferences.posterAutoPreviewFlow.collectAsState(initial = true)
    // 预览片最高画面高度（0 = 自动/原画）
    val previewMaxHeight by services.preferences.posterPreviewMaxHeightFlow.collectAsState(initial = 0)
    var detail by remember { mutableStateOf<WorkDetail?>(null) }
    var loading by remember { mutableStateOf(true) }
    var subtitleFile by remember { mutableStateOf<ScannedWorkFile?>(null) }
    var favorite by remember { mutableStateOf(false) }
    // 顶栏「刮削这部」进行中
    var scraping by remember { mutableStateOf(false) }
    // 截图全屏查看：点开的是第几张
    var viewerIndex by remember { mutableStateOf<Int?>(null) }
    // 多文件时从底栏「播放」向上展开的文件列表
    var showFiles by remember { mutableStateOf(false) }
    // 标题：AI 翻译进行中 / 手动编辑框
    var translatingTitle by remember { mutableStateOf(false) }
    var editingTitle by remember { mutableStateOf(false) }
    // 详情页「标签」那一行末尾的 + ：自定义标签
    var editingTags by remember { mutableStateOf(false) }
    var tagDraft by remember { mutableStateOf("") }
    // 以前用过的自定义标签：打开对话框时读一次，点一下就能加
    var tagCandidates by remember { mutableStateOf<List<String>>(emptyList()) }
    LaunchedEffect(editingTags) {
        if (editingTags) tagCandidates = services.libraryStore?.customTagCandidates().orEmpty()
    }

    // 顶栏按钮与「还没有封面」占位卡片共用：刮这一部，刮完重读详情
    val scrapeThis: () -> Unit = {
        scope.launch {
            scraping = true
            services.posterWall?.scrapeOne(code)
            // 刮完重读详情，封面/截图/标签一起更新
            detail = services.libraryStore?.workDetail(code)
            scraping = false
        }
    }

    // 刮不到的作品（多半是国内源上就没这部）：让用户自己传一张封面。
    // 只写 cover_url，不动 scraped_at，所以它在列表里仍然算「未刮削」。
    val pickCover: () -> Unit = platform.coverPicker.rememberLauncher { path ->
        scope.launch {
            services.libraryStore?.setWorkCover(code, path)
            detail = services.libraryStore?.workDetail(code)
            services.posterWall?.refreshList()
        }
    }

    LaunchedEffect(code) {
        loading = true
        detail = services.libraryStore?.workDetail(code)
        favorite = services.libraryStore?.isWorkFavorite(code) ?: false
        loading = false
    }

    PikoScaffold(
        modifier = modifier,
        topBar = {
            PikoTopBar(
                title = detail?.work?.displayTitle ?: code,
                onBackClick = onBackClick,
                actions = buildList {
                    // 刮不到的作品：占位卡片被换掉之后（已经有图了），这里是再换一次封面的入口。
                    // 还没图时卡片上就有一个同样的按钮，顶栏不重复放。
                    if (detail?.work?.scraped == false && detail?.work?.coverUrl != null) {
                        add(
                            iconBarItem(
                                Icons.Outlined.AddPhotoAlternate,
                                "上传封面",
                                priority = 1,
                                onClick = pickCover,
                            ),
                        )
                    }
                    // 收藏移到底栏后，这里改成「只刮这一部」：MetaTube 重刮一次当前作品
                    add(
                        iconBarItem(
                            Icons.Outlined.AutoAwesome,
                            if (scraping) "正在刮削…" else "刮削这部",
                            priority = 1,
                            enabled = !scraping,
                            onClick = scrapeThis,
                        ),
                    )
                },
            )
        },
        // 固定底栏：播放 / 字幕 / 收藏（参考图），不随内容滚动
        bottomBar = {
            detail?.let { d ->
                DetailActionBar(
                    canPlay = d.files.isNotEmpty(),
                    onPlay = {
                        if (d.files.size > 1) showFiles = true else d.files.firstOrNull()?.let(onPlay)
                    },
                    canSubtitle = d.files.isNotEmpty(),
                    onSubtitle = { d.files.firstOrNull()?.let { subtitleFile = it } },
                    favorite = favorite,
                    onFavorite = {
                        scope.launch {
                            favorite = services.libraryStore?.toggleWorkFavorite(code) ?: false
                            services.posterWall?.refreshList()
                        }
                    },
                )
            }
        },
    ) { padding ->
        when {
            loading -> Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }

            detail == null -> PikoEmptyState(
                title = "作品不存在",
                description = "它可能已不在扫描目录里，重新扫描后试试",
                modifier = Modifier.fillMaxSize().padding(padding),
            )

            else -> {
                val d = detail!!
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .verticalScroll(rememberScrollState()),
                ) {
                    // 顶部预览：用 Piko 自己的播放后端播这部片的第一个文件（静音、循环一小段）。
                    // 没有视频文件或后端不可用时退回封面。
                    // 既没刮到封面、也没刮到元数据的作品不放这块：没有封面兜底时它是一块黑底播放器，
                    // 失败就只剩黑块与转圈，整页看着像坏了。改成一张带「刮削这部」的占位卡片。
                    if (d.work.scraped || d.work.coverUrl != null) {
                        WorkPreview(
                            file = d.files.firstOrNull(),
                            coverUrl = d.work.coverUrl,
                            code = d.work.code,
                            enabled = autoPreview,
                            quality = previewMaxHeight,
                            modifier = Modifier.fillMaxWidth().aspectRatio(CoverAspectRatio),
                        )
                    } else {
                        MissingCoverHeader(
                            onUpload = pickCover,
                            modifier = Modifier.fillMaxWidth().aspectRatio(CoverAspectRatio),
                        )
                    }
                    Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                        // 编号（认不出番号时这一串就是文件名，可能很长）与两个按钮同一行。
                        // 文字占满剩余宽度、最多三行；提示单独一行放在下面——
                        // 以前提示跟编号挤在同一个 Row 里，编号一长提示就被压成一个字宽，逐字换行成一列，
                        // 把这一块撑得老高，整页看着就乱了。
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(top = 12.dp),
                        ) {
                            Text(
                                text = d.work.code.substringBeforeLast('.', d.work.code),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            IconButton(
                                enabled = !translatingTitle,
                                onClick = {
                                    scope.launch {
                                        translatingTitle = true
                                        services.posterWall?.translateOneTitle(code)
                                        detail = services.libraryStore?.workDetail(code)
                                        translatingTitle = false
                                    }
                                },
                                modifier = Modifier.size(32.dp),
                            ) {
                                Icon(
                                    Icons.Outlined.Translate,
                                    contentDescription = if (translatingTitle) "正在翻译" else "翻译片名",
                                    modifier = Modifier.size(18.dp),
                                    tint = if (translatingTitle) {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    } else {
                                        MaterialTheme.colorScheme.primary
                                    },
                                )
                            }
                            IconButton(
                                onClick = { editingTitle = true },
                                modifier = Modifier.size(32.dp),
                            ) {
                                Icon(
                                    Icons.Outlined.Edit,
                                    contentDescription = "修改片名",
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        }
                        if (!d.work.scraped) {
                            Text(
                                text = "元数据还没刮到，可点右上角「刮削这部」重试",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.padding(top = 2.dp),
                            )
                        }
                        // 有标题（刮到的原名或译名）才占一行：没有的时候上面那串就是唯一的名字，再显示一遍是重复
                        d.work.displayTitle?.let { title ->
                            Text(
                                text = title,
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(top = 6.dp),
                            )
                        }
                        // 信息网格：发行日期 / 时长 / 片商 / 系列
                        val info = buildList {
                            d.work.releaseDate?.let { add("发行日期" to it) }
                            d.work.durationMinutes?.let { add("时长" to "$it 分钟") }
                            d.studio?.let { add("片商" to it) }
                            d.series?.let { add("系列" to it) }
                        }
                        if (info.isNotEmpty()) {
                            Surface(
                                color = MaterialTheme.colorScheme.surfaceContainerLow,
                                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    info.forEach { (label, value) ->
                                        Row(modifier = Modifier.padding(vertical = 3.dp)) {
                                            Text(
                                                text = label,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.size(width = 72.dp, height = 20.dp),
                                            )
                                            Text(
                                                text = value,
                                                style = MaterialTheme.typography.bodySmall,
                                                maxLines = 2,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                        // 截图墙：元数据下、标签与女优上（参考图的位置）。第一张是封面
                        if (d.images.isNotEmpty()) {
                            SectionLabel("截图（${d.images.size}）")
                            LazyRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                contentPadding = PaddingValues(vertical = 2.dp),
                            ) {
                                itemsIndexed(d.images) { index, url ->
                                    Thumbnail(url = url, onClick = { viewerIndex = index })
                                }
                            }
                        }
                        // 标签这一行始终显示：末尾有一个 +，就算一部片子一个标签都没有也能加
                        SectionLabel("标签")
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            d.tags.forEach { tag ->
                                FavoriteChip(
                                    name = tag.name,
                                    favorite = tag.favorite,
                                    onClick = { onFilterTag(tag.name) },
                                    onLongClick = {
                                        scope.launch {
                                            services.libraryStore?.toggleTagFavorite(tag.name)
                                            detail = services.libraryStore?.workDetail(code)
                                        }
                                    },
                                )
                            }
                            AddTagChip(onClick = { tagDraft = ""; editingTags = true })
                        }
                        if (d.actors.isNotEmpty()) {
                            SectionLabel("女优")
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                d.actors.forEach { actor ->
                                    FavoriteChip(
                                        name = actor.name,
                                        favorite = actor.favorite,
                                        onClick = { onFilterActor(actor.name) },
                                        onLongClick = {
                                            scope.launch {
                                                services.libraryStore?.toggleActorFavorite(actor.name)
                                                detail = services.libraryStore?.workDetail(code)
                                            }
                                        },
                                    )
                                }
                            }
                        }
                        Box(modifier = Modifier.padding(bottom = 24.dp))
                    }
                }
            }
        }
    }

    // 全屏看图：左右翻页，点关闭或返回键退出
    viewerIndex?.let { start ->
        val images = detail?.images.orEmpty()
        if (images.isNotEmpty()) {
            ImageViewerDialog(
                images = images,
                startIndex = start.coerceIn(0, images.lastIndex),
                onDismiss = { viewerIndex = null },
            )
        } else {
            viewerIndex = null
        }
    }

    // 多文件：底栏播放按钮向上展开，右侧每行一个播放键
    // 手动改片名：改完写入 title_zh，海报墙与详情页都跟着变；清空即恢复原文
    if (editingTitle) {
        var draft by remember { mutableStateOf(detail?.work?.displayTitle.orEmpty()) }
        PikoDialog(
            onDismissRequest = { editingTitle = false },
            title = { Text("修改片名") },
            text = {
                Column {
                    Text(
                        text = "留空则恢复原标题。这里改的是显示名，不影响刮削结果。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = draft,
                        onValueChange = { draft = it },
                        singleLine = false,
                        maxLines = 4,
                        label = { Text("片名") },
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    editingTitle = false
                    services.posterWall?.setTitleZh(code, draft)
                    scope.launch { detail = services.libraryStore?.workDetail(code) }
                }) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = { editingTitle = false }) { Text("取消") }
            },
        )
    }

    // 自定义标签：输入框加已有的自定义标签（可删）
    if (editingTags) {
        val customTags = detail?.tags?.filter { it.custom }.orEmpty()
        PikoDialog(
            onDismissRequest = { editingTags = false },
            title = { Text("自定义标签") },
            text = {
                Column {
                    Text(
                        text = "只加在这部作品上，重新刮削也不会被冲掉。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(top = 8.dp),
                    ) {
                        OutlinedTextField(
                            value = tagDraft,
                            onValueChange = { tagDraft = it },
                            singleLine = true,
                            label = { Text("新标签") },
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(
                            enabled = tagDraft.isNotBlank(),
                            onClick = {
                                val name = tagDraft.trim()
                                tagDraft = ""
                                scope.launch {
                                    services.libraryStore?.addCustomTag(code, name)
                                    detail = services.libraryStore?.workDetail(code)
                                }
                            },
                            modifier = Modifier.padding(start = 8.dp),
                        ) { Text("添加") }
                    }
                    // 用过的标签：点一下直接加到这部作品上，已经在的就不列了
                    val onThisWork = customTags.map { it.name }.toSet()
                    val suggestions = tagCandidates.filterNot { it in onThisWork }
                    if (suggestions.isNotEmpty()) {
                        Text(
                            text = "用过的标签（点一下直接加）",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 12.dp),
                        )
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.padding(top = 6.dp),
                        ) {
                            suggestions.forEach { name ->
                                SuggestionChip(
                                    name = name,
                                    onClick = {
                                        scope.launch {
                                            services.libraryStore?.addCustomTag(code, name)
                                            detail = services.libraryStore?.workDetail(code)
                                        }
                                    },
                                )
                            }
                        }
                    }
                    if (customTags.isNotEmpty()) {
                        Text(
                            text = "已加的自定义标签",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 12.dp),
                        )
                        customTags.forEach { tag ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                            ) {
                                Text(
                                    text = tag.name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                                IconButton(
                                    onClick = {
                                        scope.launch {
                                            services.libraryStore?.removeTag(code, tag.name)
                                            detail = services.libraryStore?.workDetail(code)
                                        }
                                    },
                                    modifier = Modifier.size(32.dp),
                                ) {
                                    Icon(
                                        Icons.Filled.Close,
                                        contentDescription = "删除标签 ${tag.name}",
                                        modifier = Modifier.size(16.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { editingTags = false }) { Text("完成") }
            },
        )
    }

    if (showFiles) {
        val files = detail?.files.orEmpty()
        PikoSheet(
            onDismissRequest = { showFiles = false },
            sideSheetTitle = "选择文件（${files.size}）",
        ) {
            files.forEach { file ->
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                    ) {
                        Text(
                            text = file.name,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = {
                            showFiles = false
                            onPlay(file)
                        }) {
                            Icon(Icons.Outlined.PlayArrow, contentDescription = "播放")
                        }
                    }
                }
            }
            Box(modifier = Modifier.padding(bottom = 16.dp))
        }
    }

    subtitleFile?.let { file ->
        SubtitleSearchSheet(
            code = code,
            targetFolderId = file.folderId,
            targetFolderName = "视频所在目录",
            onDismissRequest = { subtitleFile = null },
        )
    }
}

/**
 * 顶部预览片：**用 Piko 自带的播放后端**（mpv）播这部作品的第一个文件，静音、循环一小段。
 *
 * 和 MetaTube 的 `preview_video_url` 不是一回事——那个字段 JavBus 源根本不返回；
 * 这里播的是用户网盘里的正片，所以任何作品都有画面。取流走 `preparePlayback`
 * （本机代理，计入账号连接预算），离开页面释放。
 */
@Composable
private fun WorkPreview(
    file: ScannedWorkFile?,
    coverUrl: String?,
    code: String,
    enabled: Boolean,
    quality: Int,
    modifier: Modifier = Modifier,
) {
    val platform = LocalPikoPlatform.current
    val videoPreview = platform.videoPreview
    val repository = LocalPikoServices.current.mediaRepository

    // 设置里关了自动预览、没有文件、或平台没有播放后端：直接显示封面
    if (!enabled || file == null || videoPreview == null) {
        CoverImage(code = code, coverUrl = coverUrl, modifier = modifier)
        return
    }

    // 稍等一下再建播放器：快速滑过详情页时不必反复初始化 mpv（那是最容易崩的一步）
    var ready by remember(file.fileId) { mutableStateOf(false) }
    LaunchedEffect(file.fileId) {
        delay(300)
        ready = true
    }
    if (!ready) {
        CoverImage(code = code, coverUrl = coverUrl, modifier = modifier)
        return
    }

    val player = videoPreview.rememberPreviewBackend(keyframeStart = true)
    var url by remember(file.fileId) { mutableStateOf("") }
    var prepared by remember(file.fileId) { mutableStateOf<PreparedPlayback?>(null) }
    var failed by remember(file.fileId) { mutableStateOf(false) }
    // 起点：时长未知时先落在 30 秒，拿到时长后按番号定位到中段
    var previewStart by remember(file.fileId) { mutableStateOf(PreviewFallbackStartMillis) }
    var seeded by remember(file.fileId) { mutableStateOf(false) }
    val currentStart by rememberUpdatedState(previewStart)

    LaunchedEffect(file.fileId, quality) {
        val result = repository.preparePlayback(file.fileId, defaultMaxHeight = quality).getOrNull()
        prepared = result
        url = result?.let { it.proxyUrl ?: it.info.currentUrl }.orEmpty()
        if (url.isBlank()) failed = true
    }
    LaunchedEffect(url) {
        if (url.isNotBlank()) {
            player.open(PlaybackTarget.Url(url), startMillis = previewStart, playWhenReady = true)
            // 静音：进详情页就播，不能突然出声
            player.setVolume(0f)
        }
    }
    // 拿到时长后按「番号哈希 + 时长比例」定位到正片中段：同一部片每次都一样，
    // 不同片各落在不同位置，避开片头黑场与片尾演职员表。
    LaunchedEffect(player) {
        snapshotFlow { player.durationMillis }.collect { duration ->
            if (!seeded && duration > 0L) {
                seeded = true
                previewStart = previewStartFor(code, duration)
                player.seekTo(previewStart)
            }
        }
    }
    // 循环：播过一小段就回到起点。只在越线后的一小段窗口里 seek 一次，避免连环触发。
    LaunchedEffect(player) {
        snapshotFlow { player.positionMillis }.collect { position ->
            val loopEnd = currentStart + PreviewLoopMillis
            if (position in loopEnd..(loopEnd + 3_000L)) player.seekTo(currentStart)
        }
    }
    DisposableEffect(player) {
        onDispose {
            player.release()
            prepared?.close()
        }
    }

    Box(modifier = modifier.background(Color.Black), contentAlignment = Alignment.Center) {
        if (url.isNotBlank() && !failed) {
            videoPreview.Surface(player, Modifier.fillMaxSize())
            if (player.isBuffering) CircularProgressIndicator(color = Color.White)
            // 右上角标出实际在放的档位：设置里选 480P 但该片只生成了 1080P 转码时，
            // 挑不到 ≤480 的档会按原画播，这里能一眼看出来
            prepared?.let { p ->
                val label = if (p.info.isOrigin) "原画" else "${p.info.currentResolution}P"
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp)
                        .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(4.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        } else {
            CoverImage(code = code, coverUrl = coverUrl, modifier = Modifier.fillMaxSize())
        }
    }
}

/** 还不知道时长时先从这里起播（约半分钟，跳过片头黑场）。 */
private const val PreviewFallbackStartMillis = 30_000L

/**
 * 预览起点：用番号哈希取 25%~35% 的时长位置。
 * 同一部片每次都落在同一处（稳定），不同片各不相同，像随手抓了一段正片。
 *
 * 区间从 35%~45% 往前挪了一档：越往后越可能撞上正片后半的剧情段落（多是对话与室内镜头），
 * 1/4 到 1/3 这段通常已经过了铺垫、画面还热闹，挑片时一眼能看明白是什么片。
 */
internal fun previewStartFor(code: String, durationMillis: Long): Long {
    if (durationMillis <= 0L) return PreviewFallbackStartMillis
    val bucket = ((code.hashCode().toLong() and 0x7FFFFFFFL) % 26L)   // 0..25
    // 26 个等分把 0.10 的区间切完：bucket=0 → 0.25，bucket=25 → 0.35
    val fraction = 0.25 + bucket * 0.10 / 25.0
    return (durationMillis * fraction).toLong()
}

/** 每段循环 30 秒，够看清画面又不至于把整片读下来。 */
private const val PreviewLoopMillis = 30_000L

/** 底部固定操作栏：左「播放」大按钮，右字幕与收藏两个方形按钮（参考图）。 */
@Composable
private fun DetailActionBar(
    canPlay: Boolean,
    onPlay: () -> Unit,
    canSubtitle: Boolean,
    onSubtitle: () -> Unit,
    favorite: Boolean,
    onFavorite: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 18.dp),
        ) {
            FilledTonalButton(
                onClick = onPlay,
                enabled = canPlay,
                modifier = Modifier.weight(1f).height(56.dp),
                shape = RoundedCornerShape(14.dp),
            ) {
                Icon(Icons.Outlined.PlayArrow, contentDescription = null, modifier = Modifier.size(24.dp))
                Text("播放", modifier = Modifier.padding(start = 6.dp))
            }
            IconButton(
                onClick = onSubtitle,
                enabled = canSubtitle,
                modifier = Modifier.padding(start = 10.dp).size(56.dp),
            ) {
                Icon(Icons.Outlined.Subtitles, contentDescription = "字幕", modifier = Modifier.size(26.dp))
            }
            IconButton(
                onClick = onFavorite,
                modifier = Modifier.padding(start = 6.dp).size(56.dp),
            ) {
                Icon(
                    if (favorite) Icons.Filled.Star else Icons.Outlined.StarOutline,
                    contentDescription = if (favorite) "取消收藏" else "收藏",
                    modifier = Modifier.size(26.dp),
                )
            }
        }
    }
}

/**
 * 既没刮到封面、也没刮到元数据时的顶部占位：
 * 一块底色加一句说明与「上传封面」，比一块黑底播放器（失败后只剩黑与转圈）清楚，页面也不会看着像坏了。
 *
 * 这里的按钮是**上传封面**而不是「刮削这部」：会走到这一屏的，基本都是刮削源里根本没有的片，
 * 再点一次刮削多半还是空手而归；用户手里有图，让他直接补一张更实在。刮削入口仍在顶栏。
 */
@Composable
private fun MissingCoverHeader(
    onUpload: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.background(MaterialTheme.colorScheme.surfaceContainerHighest),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                Icons.Outlined.ImageNotSupported,
                contentDescription = null,
                modifier = Modifier.size(36.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "还没有封面与元数据",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
            Text(
                text = "刮削源里没有这部，可以自己传一张封面",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
            FilledTonalButton(
                onClick = onUpload,
                modifier = Modifier.padding(top = 12.dp),
            ) {
                Icon(Icons.Outlined.AddPhotoAlternate, contentDescription = null, modifier = Modifier.size(18.dp))
                Text("上传封面", modifier = Modifier.padding(start = 6.dp))
            }
        }
    }
}

/**
 * 演员/标签芯片：点一下按它筛选，**长按收藏/取消收藏**。
 * 不用 AssistChip——它自己消费点击，套一层也接不到长按；这里用 Surface + combinedClickable 自己画。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FavoriteChip(
    name: String,
    favorite: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = if (favorite) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh
        },
        modifier = Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            if (favorite) {
                Icon(
                    Icons.Filled.Star,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(14.dp),
                )
                Spacer(modifier = Modifier.width(4.dp))
            }
            Text(text = name, style = MaterialTheme.typography.labelLarge)
        }
    }
}

/** 截图墙的一张小图：16:9 裁切，加载失败给一块底色，不塌陷。 */
@Composable
private fun Thumbnail(url: String, onClick: () -> Unit) {
    SubcomposeAsyncImage(
        model = url,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = Modifier
            .width(160.dp)
            .aspectRatio(CoverAspectRatio)
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick),
        loading = { ThumbnailPlaceholder() },
        error = { ThumbnailPlaceholder() },
    )
}

@Composable
private fun ThumbnailPlaceholder() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
    )
}

/** 全屏查看：黑底、Fit 不变形，左右滑翻页，顶部显示页码与关闭。 */
@Composable
private fun ImageViewerDialog(images: List<String>, startIndex: Int, onDismiss: () -> Unit) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        val pagerState = rememberPagerState(initialPage = startIndex) { images.size }
        Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
            HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
                SubcomposeAsyncImage(
                    model = images[page],
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                    loading = {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(color = Color.White)
                        }
                    },
                )
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(12.dp),
            ) {
                Text(
                    text = "${pagerState.currentPage + 1}/${images.size}",
                    color = Color.White,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(end = 8.dp),
                )
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Filled.Close, contentDescription = "关闭", tint = Color.White)
                }
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
    )
}

/**
 * 标签行末尾的「+ 标签」。外观与旁边的标签芯片一致（同样的圆角、底色、内边距与字号），
 * 只是内容是一个加号加两个字——放在一排里不该看着像另一种控件。
 */
@Composable
private fun AddTagChip(onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.clickable(onClick = onClick),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Icon(
                Icons.Filled.Add,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(14.dp),
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(text = "标签", style = MaterialTheme.typography.labelLarge)
        }
    }
}

/** 候选标签：与详情页标签同款外形，点一下直接加到作品上。 */
@Composable
private fun SuggestionChip(name: String, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.clickable(onClick = onClick),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Icon(
                Icons.Filled.Add,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(14.dp),
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(text = name, style = MaterialTheme.typography.labelLarge)
        }
    }
}
