package dev.piko.shared.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.piko.shared.data.DuplicateScanEvent
import dev.piko.shared.data.DuplicateScanner
import dev.piko.shared.data.PikoClientProvider
import dev.piko.shared.data.ScanStop
import dev.piko.shared.data.ScannedFile
import dev.piko.data.repository.isPlayableVideo
import dev.piko.shared.library.LibraryStore
import dev.piko.shared.library.LibraryScraper
import dev.piko.shared.library.NamedCount
import dev.piko.shared.library.ScannedWork
import dev.piko.shared.library.ScannedWorkFile
import dev.piko.shared.library.WorkRow
import dev.piko.shared.log.PikoLog
import dev.piko.shared.naming.av.matchAv
import dev.piko.shared.translate.TranslationGateway
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

/**
 * 海报墙：扫目录 → 按番号归组 → 列表/按演员/按标签 → 补刮元数据。
 *
 * 索引（番号、文件、演员、标签）全在本地 SQLite（[LibraryStore]），网盘只被读：
 * 一轮扫描之后列表、筛选、详情都走本地库，不重复请求网盘。元数据一部一补：
 * 扫到的作品先入库（标题封面都空），[enrich] 再逐个找刮削源补上，失败的留在
 * 「待刮削」里下次补。
 *
 * 扫描复用 [DuplicateScanner]：递归列目录、有并发与上限，按停止键保留已扫部分。
 */
class PosterWallState(
    private val clients: PikoClientProvider,
    private val store: LibraryStore,
    private val scraper: LibraryScraper?,
    /** 当前 MetaTube 地址：换域名后用它把库里的图片地址重拼一遍，不用重新刮削。 */
    private val metaTubeBase: suspend () -> String,
    /** AI 翻译（片名与字幕）。未配置模型时为 null，翻译入口置灰。 */
    private val translation: TranslationGateway? = null,
    private val scope: CoroutineScope,
) {
    enum class Phase { IDLE, SCANNING, DONE, FAILED }

    /** 全部 / 收藏 / 按演员 / 按标签。[filterName] 是选中的演员或标签名。 */
    enum class Filter { ALL, FAVORITE, ACTOR, TAG }

    var phase by mutableStateOf(Phase.IDLE)
        private set

    /** 扫描根目录：没设过时为 null，界面显示「选择目录」。 */
    var rootFolder by mutableStateOf<Pair<String, String>?>(null)
        private set

    var scannedFolders by mutableStateOf(0)
        private set
    var scannedFiles by mutableStateOf(0)
        private set

    /** 扫描提前结束的原因，完整走完为 null。 */
    var scanStop by mutableStateOf<ScanStop?>(null)
        private set
    var scanError by mutableStateOf<String?>(null)
        private set

    /** 列表筛选：全部 / 按演员 / 按标签。[filterName] 是选中的演员或标签名。 */
    var filter by mutableStateOf(Filter.ALL)
        private set
    var filterName by mutableStateOf<String?>(null)
        private set

    /** 搜索词：番号、标题、演员、标签任一处包含就留下。空串表示不搜。 */
    var query by mutableStateOf("")
        private set

    /** 正在单部刮削的番号（详情页「刮削这部」按钮），null 表示没有。 */
    var scrapingCode by mutableStateOf<String?>(null)
        private set

    var works by mutableStateOf<List<WorkRow>>(emptyList())
        private set
    var actors by mutableStateOf<List<NamedCount>>(emptyList())
        private set
    var tags by mutableStateOf<List<NamedCount>>(emptyList())
        private set
    var workCount by mutableStateOf(0L)
        private set

    /** 其中已刮到元数据的数量；[workCount] 减去它就是未刮削的。 */
    var scrapedCount by mutableStateOf(0L)
        private set
    var loading by mutableStateOf(false)
        private set

    /** 补刮元数据的进度：总数、已完成、失败；enriching 为 true 时进行中。 */
    var enriching by mutableStateOf(false)
        private set
    var enrichTotal by mutableStateOf(0)
        private set
    var enrichDone by mutableStateOf(0)
        private set
    var enrichFailed by mutableStateOf(0)
        private set

    /** 还缺中文标题的作品数（设置页显示「还有 N 部没翻」）。 */
    var titlePending by mutableStateOf(0L)
        private set

    /** 批量翻译片名进行中。 */
    var titleTranslating by mutableStateOf(false)
        private set
    var titleTotal by mutableStateOf(0)
        private set
    var titleDone by mutableStateOf(0)
        private set
    var titleTranslated by mutableStateOf(0)
        private set

    /** 给界面弹的一次性提示（保存成功、刮削源已更改之类）。 */
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    private var scanner: DuplicateScanner? = null
    private var scanJob: Job? = null
    private var enrichJob: Job? = null
    private var titleJob: Job? = null

    init {
        scope.launch {
            // MetaTube 换了地址（域名/端口/协议）时，把库里拼好的图片地址整体重写，
            // 封面与截图不用重新刮一遍
            runCatching { store.rebuildMetaTubeBase(metaTubeBase()) }
            rootFolder = store.scanState()?.let { state ->
                state.rootFolderId?.takeIf { it.isNotEmpty() }?.let { it to (state.rootFolderName ?: "") }
            }
            workCount = store.workCount()
            scrapedCount = store.scrapedWorkCount()
            refresh()
            // 上一轮扫描有没刮到的作品（MetaTube 不通、查询失败）：进海报墙就补，
            // 不用等用户手动再扫一轮。没有待刮作品时 enrich 空转返回，不弹消息
            enrich()
        }
    }

    /** 换号：索引按账号各一份，重新读当前账号的扫描目录、作品与刮削源。 */
    fun onAccountChanged() {
        scope.launch {
            runCatching { store.rebuildMetaTubeBase(metaTubeBase()) }
            rootFolder = store.scanState()?.let { state ->
                state.rootFolderId?.takeIf { it.isNotEmpty() }?.let { it to (state.rootFolderName ?: "") }
            }
            workCount = store.workCount()
            scrapedCount = store.scrapedWorkCount()
            phase = Phase.IDLE
            scanStop = null
            scanError = null
            enriching = false
            filter = Filter.ALL
            filterName = null
            refresh()
            // 换号后同样把没刮到的补上：新账号的库可能是旧版本扫的，一样会留着没封面的作品
            enrich()
        }
    }

    // ---------------------------------------------------------------- 扫描

    /** 选定了扫描目录：记住并立刻开始扫描。 */
    fun setRootFolder(folderId: String, folderName: String) {
        rootFolder = folderId to folderName
        startScan()
    }

    fun startScan() {
        val (rootId, _) = rootFolder ?: return
        scanJob?.cancel()
        enrichJob?.cancel()
        enriching = false
        val scanner = DuplicateScanner(clients).also { scanner = it }
        phase = Phase.SCANNING
        scannedFolders = 0
        scannedFiles = 0
        scanStop = null
        scanError = null
        scanJob = scope.launch {
            try {
                store.setScanning(true)
                scanner.scan(rootId).collect { event ->
                    when (event) {
                        is DuplicateScanEvent.Progress -> {
                            scannedFolders = event.folders
                            scannedFiles = event.files
                        }

                        is DuplicateScanEvent.Finished -> {
                            scannedFolders = event.folders
                            scannedFiles = event.files.size
                            scanStop = event.stop
                            phase = Phase.DONE
                            val grouped = groupByCode(event.files)
                            // 提前结束（限流、出错、用户点了停止）时不能拿半份结果去删作品，
                            // 否则没扫到的会被判成"网盘里已删除"
                            store.saveScan(
                                rootFolderId = rootId,
                                rootFolderName = rootFolder?.second,
                                works = grouped,
                                complete = event.stop == null,
                            )
                            store.setScanning(false)
                            workCount = store.workCount()
                            refresh()
                            if (grouped.isNotEmpty()) enrich()
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                PikoLog.w(TAG, "海报墙扫描失败", e)
                scanError = e.message ?: "未知错误"
                phase = Phase.FAILED
                scope.launch { store.setScanning(false) }
            }
        }
    }

    /** 提前结束扫描，已扫到的部分照常入库。 */
    fun stopScan() {
        scanner?.stop()
    }

    /**
     * 扫描到的文件按番号归成作品：认不出番号、不是视频的都不进索引
     * （字幕、图片、nfo 与海报墙无关，混进来只会把网格撑乱）。
     */
    private fun groupByCode(files: List<ScannedFile>): List<ScannedWork> {
        val byCode = LinkedHashMap<String, MutableList<ScannedWorkFile>>()
        for (scanned in files) {
            val stat = scanned.file
            if (!stat.isPlayableVideo() || stat.trashed) continue
            val stem = stat.name.substringBeforeLast('.')
            if (stem.isBlank()) continue
            val av = matchAv(stem, allowLanguageSuffix = true)
            // 认不出番号的也收：用文件名本身当 key，照样能在列表里看到、能播放。
            // 之前这里直接 continue，用户库里有 1000 个视频却只看到 800 部，剩下的莫名其妙没了。
            val code = av?.info?.code ?: stem
            byCode.getOrPut(code) { mutableListOf() } += ScannedWorkFile(
                fileId = stat.id,
                name = stat.name,
                // FileStat.size 是字符串（SDK 直接透传接口的字段），索引里存字节数
                size = stat.size.toLongOrNull() ?: 0L,
                folderId = stat.parentId,
                part = av?.info?.part,
            )
        }
        return byCode.map { (code, list) -> ScannedWork(code, list.sortedBy { it.name }) }
    }

    // ---------------------------------------------------------------- 补刮

    /**
     * 给还没刮到元数据（或封面为空）的作品逐个找刮削源。一部失败不打断整轮：计数后继续。
     * 触发时机：扫描完成后、进海报墙（启动/换号）时、用户更换刮削源后——失败的作品
     * 在这些时机自动重试，不用用户手动干预。
     */
    fun enrich() {
        val scraper = scraper
        if (scraper == null || enriching) return
        enrichJob?.cancel()
        enrichJob = scope.launch {
            try {
                enriching = true
                enrichFailed = 0
                enrichDone = 0
                val pending = store.codesNeedingScrape()
                enrichTotal = pending.size
                // 没有待刮作品：静默返回，不打扰用户（启动与换源都会调到这里）
                if (pending.isEmpty()) return@launch
                for (code in pending) {
                    val movie = runCatching { scraper.scrape(code) }.getOrNull()
                    if (movie == null) {
                        enrichFailed++
                    } else {
                        store.markScraped(
                            code = code,
                            title = movie.title,
                            coverUrl = movie.coverUrl,
                            releaseDate = movie.releaseDate,
                            durationMinutes = movie.durationMinutes,
                            summary = movie.summary,
                            studio = movie.studio,
                            series = movie.series,
                            provider = movie.provider,
                            mtProvider = movie.remoteProvider,
                            mtId = movie.remoteId,
                            previewVideoUrl = movie.previewVideoUrl,
                            previewImages = movie.previewImages,
                            actors = movie.actors,
                            tags = movie.tags,
                        )
                        enrichDone++
                    }
                    refresh()
                }
                _messages.tryEmit(
                    when {
                        // 一部都没刮到：多半是 MetaTube 地址不通或没配，说清楚下一步干嘛
                        enrichDone == 0 && enrichFailed > 0 ->
                            "MetaTube 暂时不可达：$enrichFailed 部没刮到。请检查设置里的 MetaTube 地址与令牌，之后会自动重试"
                        else ->
                            "元数据补刮完成：新刮到 $enrichDone 部" + (if (enrichFailed > 0) "，$enrichFailed 部没找到" else "")
                    }
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                PikoLog.w(TAG, "补刮元数据失败", e)
                _messages.tryEmit("补刮失败：${e.message}")
            } finally {
                enriching = false
                refresh()
            }
        }
    }

    // ---------------------------------------------------------------- 单部刮削

    /**
     * 只刮一部（详情页顶栏的「刮削这部」）。成功返回 true；失败时提示原因，调用方据此决定要不要重读详情。
     * 与 [enrich] 落库走同一套字段，区别只是范围。
     */
    suspend fun scrapeOne(code: String): Boolean {
        val scraper = scraper ?: return false
        scrapingCode = code
        return try {
            val movie = runCatching { scraper.scrape(code) }.getOrNull()
            if (movie == null) {
                _messages.tryEmit("$code 没刮到，检查设置里的 MetaTube 地址与令牌")
                false
            } else {
                store.markScraped(
                    code = code,
                    title = movie.title,
                    coverUrl = movie.coverUrl,
                    releaseDate = movie.releaseDate,
                    durationMinutes = movie.durationMinutes,
                    summary = movie.summary,
                    studio = movie.studio,
                    series = movie.series,
                    provider = movie.provider,
                    mtProvider = movie.remoteProvider,
                    mtId = movie.remoteId,
                    previewVideoUrl = movie.previewVideoUrl,
                    previewImages = movie.previewImages,
                    actors = movie.actors,
                    tags = movie.tags,
                )
                refresh()
                _messages.tryEmit("已刮削 $code")
                true
            }
        } finally {
            scrapingCode = null
        }
    }

    // ---------------------------------------------------------------- 列表与筛选
    fun selectFilter(filter: Filter, name: String? = null) {
        this.filter = filter
        filterName = name
        scope.launch { refresh() }
    }

    /** 搜索框输入：每敲一次重查一遍（数据量是几百条，直接查不卡）。 */
    // 不叫 setQuery：var query 的属性 setter 会生成同 JVM 签名的方法，撞名
    fun updateQuery(value: String) {
        query = value
        scope.launch { refresh() }
    }

    private suspend fun refresh() {
        loading = true
        works = store.works(
            actor = filterName.takeIf { filter == Filter.ACTOR },
            tag = filterName.takeIf { filter == Filter.TAG },
            favoriteOnly = filter == Filter.FAVORITE,
            query = query,
        )
        actors = store.actors()
        tags = store.tags()
        workCount = store.workCount()
        scrapedCount = store.scrapedWorkCount()
        titlePending = store.titleTranslationPendingCount()
        loading = false
    }

    fun refreshList() {
        scope.launch { refresh() }
    }

    // ---------------------------------------------------------------- AI 翻译

    /** 翻译模型配好了没有（三样都填了）。界面上没配就把入口置灰。 */
    suspend fun translationConfigured(): Boolean = translation?.isConfigured() == true

    /**
     * 把所有还没有中文标题的作品翻一遍。已翻好的不动（[store] 只返回待译的），
     * 中途可以停，停之前翻好的都已经落库。
     */
    fun translateTitles() {
        if (titleTranslating) return
        val gateway = translation
        if (gateway == null) {
            _messages.tryEmit("先在设置 → AI 翻译里填好接口、Key 与模型")
            return
        }
        titleJob = scope.launch {
            titleTranslating = true
            titleDone = 0
            titleTranslated = 0
            try {
                val translator = gateway.titleTranslator()
                if (translator == null) {
                    _messages.tryEmit("先在设置 → AI 翻译里填好接口、Key 与模型")
                    return@launch
                }
                val entries = store.titlesNeedingTranslation()
                titleTotal = entries.size
                if (entries.isEmpty()) {
                    _messages.tryEmit("所有作品都已有中文标题")
                    return@launch
                }
                val ok = translator.translateAll(
                    entries = entries,
                    onProgress = { progress ->
                        titleDone = progress.done
                        titleTranslated = progress.translated
                    },
                    // 每翻好一批就落库（一个事务）：断网或被杀进程时已翻的不白花钱，
                    // 又不会因为每条一次 fsync 把整体拖慢
                    onResults = { items -> store.setTitleZhBatch(items) },
                )
                refresh()
                _messages.tryEmit(
                    if (ok == 0) "一条都没翻成，检查翻译接口设置"
                    else "片名翻译完成：$ok/${entries.size} 部",
                )
            } catch (e: CancellationException) {
                refresh()
                throw e
            } catch (e: Throwable) {
                PikoLog.w(TAG, "批量翻译片名失败", e)
                _messages.tryEmit("片名翻译中断：${e.message ?: "未知错误"}")
            } finally {
                titleTranslating = false
                titleJob = null
            }
        }
    }

    /** 停掉批量翻译。已经翻好的都留在库里。 */
    fun cancelTitleTranslation() {
        titleJob?.cancel()
        titleJob = null
        titleTranslating = false
        _messages.tryEmit("已停止翻译，已翻好的已保存")
    }

    /** 只翻一部（详情页标题旁的翻译按钮）。成功返回 true。 */
    suspend fun translateOneTitle(code: String): Boolean {
        val translator = translation?.titleTranslator() ?: return false
        val detail = store.workDetail(code) ?: return false
        val source = detail.work.title?.trim().orEmpty()
        if (source.isEmpty()) return false
        val zh = translator.translateOne(source) ?: return false
        store.setTitleZh(code, zh)
        refresh()
        return true
    }

    /** 手动改标题。[text] 为空表示清掉译名、恢复显示原文标题。 */
    fun setTitleZh(code: String, text: String?) {
        scope.launch {
            store.setTitleZh(code, text)
            refresh()
            _messages.tryEmit(if (text.isNullOrBlank()) "已恢复原标题" else "标题已更新")
        }
    }

    // ---------------------------------------------------------------- 收藏

    /** 作品收藏开关（海报墙卡片、详情页与播放页共用）：切完立即刷新列表。 */
    fun toggleWorkFavorite(code: String) {
        scope.launch {
            val favorite = store.toggleWorkFavorite(code)
            _messages.tryEmit(if (favorite) "已收藏 $code" else "已取消收藏 $code")
            refresh()
        }
    }

    /** 演员收藏开关：列表刷新后收藏的演员置顶。 */
    fun toggleActorFavorite(name: String) {
        scope.launch {
            val favorite = store.toggleActorFavorite(name)
            _messages.tryEmit(if (favorite) "已收藏演员「$name」" else "已取消收藏演员「$name」")
            refresh()
        }
    }

    /** 标签收藏开关：同上。 */
    fun toggleTagFavorite(name: String) {
        scope.launch {
            val favorite = store.toggleTagFavorite(name)
            _messages.tryEmit(if (favorite) "已收藏标签「$name」" else "已取消收藏标签「$name」")
            refresh()
        }
    }

    private companion object {
        const val TAG = "PosterWall"
    }
}
