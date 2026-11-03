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
import dev.piko.shared.data.VaultEdits
import dev.piko.shared.data.VaultEntry
import dev.piko.shared.data.VaultStore
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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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
    /**
     * 归档清单：扫目录时收下的 `.piko-vault-*.json` 由它解析成条目。
     * 为空时引用库一直是空的（不影响普通扫描）。
     */
    private val vaultStore: VaultStore? = null,
    private val scope: CoroutineScope,
) {
    enum class Phase { IDLE, SCANNING, DONE, FAILED }

    /**
     * 全部 / 收藏 / 按演员 / 按标签 / 只看已刮削 / 只看未刮削 / 只看引用保存。
     * [filterName] 只在 ACTOR 与 TAG 下有意义。
     */
    enum class Filter { ALL, FAVORITE, ACTOR, TAG, SCRAPED, UNSCRAPED, VAULT }

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

    /**
     * 搜索框是否展开。放在 state 里而不是界面的 remember：海报墙是底部导航的一页，
     * 点进作品详情再返回时这一页会重新组合，remember 的值会丢，搜索框就自己收起来了。
     */
    var searchActive by mutableStateOf(false)
        private set

    /**
     * 这次展开搜索要不要把光标放进输入框。只有用户刚点搜索时为 true；
     * 从详情页返回、切 tab 回来时海报墙会重新组合，若每次都抢焦点就会自动弹起输入法。
     */
    var searchFocusPending by mutableStateOf(false)
        private set

    // 不叫 setSearchActive：var searchActive 的属性 setter 会生成同 JVM 签名的方法，撞名
    fun setSearchOpen(active: Boolean) {
        if (active && !searchActive) searchFocusPending = true
        searchActive = active
        // 收起搜索就把词清掉：留下一个看不见的词，列表还按它过滤，摸不着头脑
        if (!active && query.isNotEmpty()) updateQuery("")
    }

    /** 取走「要不要抢焦点」，取过就清掉：同一次展开只抢一次。 */
    fun consumeSearchFocus(): Boolean {
        val pending = searchFocusPending
        searchFocusPending = false
        return pending
    }

    /**
     * 海报墙的滚动位置（首项下标与像素偏移）。
     *
     * 故意**不是** Compose 的 mutableStateOf：界面只在首次组合时读一次作为初始值，
     * 滚动时每帧都写，若进了快照系统会把整页重组几百次。切到别的 tab 再回来接着看，
     * 进程退出后这里随对象重建归零，正好是"重开 App 回到顶部"。
     */
    var scrollIndex: Int = 0
        private set
    var scrollOffset: Int = 0
        private set

    /** 界面滚动时回写位置。 */
    fun rememberScroll(index: Int, offset: Int) {
        scrollIndex = index
        scrollOffset = offset
    }

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
    /** 引用保存的作品数：网盘里没有原文件、只记在归档清单里的那批。 */
    var vaultCount by mutableStateOf(0L)
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
    private var vaultJob: Job? = null
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
            enrich(silent = true)
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
            enrich(silent = true)
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
                            // 引用库整批对齐：清单没扫全时不动，否则半份结果会把没遇到的条目当成已移除。
                            // 解析要逐份下载清单，不挡扫描出结果：先把墙画出来，引用条目在后台补上
                            if (event.stop == null) {
                                vaultJob?.cancel()
                                vaultJob = scope.launch {
                                    runCatching { syncVault(event.manifests) }
                                        .onFailure { PikoLog.w(TAG, "解析归档清单失败", it) }
                                    vaultCount = store.vaultWorkCount()
                                    refresh()
                                }
                            }
                            store.setScanning(false)
                            workCount = store.workCount()
                            vaultCount = store.vaultWorkCount()
                            refresh()
                            if (grouped.isNotEmpty()) enrich(silent = true)
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

    /**
     * 把这一轮遇到的归档清单解析成作品。
     *
     * 引用条目没有网盘文件，只能靠清单里的片名、大小与 gcid 摆上墙：片名认番号，gcid 拼缩略图，
     * 播放时按 gcid 借一份对象（见 LeasedFile）。同一份内容被引用到好几个目录时按 gcid + 大小去重，
     * 删除时再把各处条目一起抹掉（[removeVaultWork]）。
     */
    private suspend fun syncVault(manifests: List<ScannedFile>) {
        val vault = vaultStore ?: return
        if (manifests.isEmpty()) {
            store.saveVaultWorks(emptyList())
            return
        }
        // 一次读四份清单：每份都是一次下载，串行读上千个目录会很慢
        val entries = mutableListOf<Pair<VaultEntry, String>>()
        // 一个目录可能有历史上写下的好几份清单（旧版本、输掉的竞争），只有胜出的那份算数，
        // 挨个读会把已经移除的条目又翻出来
        val winners = manifests.groupBy { it.file.parentId }
            .mapNotNull { (_, group) -> VaultStore.winnerOf(group.map { it.file }) }
        for (batch in winners.chunked(4)) {
            entries += coroutineScope {
                batch.map { manifest ->
                    async {
                        val folderId = manifest.parentId?.takeIf { it.isNotEmpty() }
                            ?: return@async emptyList()
                        runCatching { vault.entriesIn(manifest) }.getOrDefault(emptyList())
                            // 已取回的条目在网盘里有真实文件，扫描会收它，这里再来一份就重复了
                            .filter { it.isArchived && it.name.isPlayableVideo() }
                            .map { it to folderId }
                    }
                }.awaitAll().flatten()
            }
        }
        val seen = HashSet<String>()
        val byCode = LinkedHashMap<String, MutableList<ScannedWorkFile>>()
        for ((entry, folderId) in entries) {
            if (!seen.add("${entry.gcid}:${entry.size}")) continue
            val stem = entry.name.substringBeforeLast('.')
            if (stem.isBlank()) continue
            val code = matchAv(stem, allowLanguageSuffix = true)?.info?.code ?: stem
            byCode.getOrPut(code) { mutableListOf() } += ScannedWorkFile(
                fileId = entry.virtualId,
                name = entry.name,
                size = entry.size,
                folderId = folderId,
                part = null,
            )
        }
        store.saveVaultWorks(byCode.map { (code, files) -> ScannedWork(code, files.sortedBy { it.name }) })
    }

    /**
     * 从海报墙移除一部引用作品：删归档清单里的条目，网盘里没有文件可删。
     *
     * 同一条目可能被引用到多个目录（列表上按 gcid 去重合成一部），所以按文件记录里的目录分组，
     * 每一处都抹掉，否则下次扫描它又会出现。
     */
    suspend fun removeVaultWork(code: String): Result<Unit> {
        val vault = vaultStore ?: return Result.failure(IllegalStateException("归档清单未就绪"))
        val files = store.workDetail(code)?.files.orEmpty()
        val byFolder = files.mapNotNull { file ->
            val id = VaultEntry.entryIdOf(file.fileId) ?: return@mapNotNull null
            file.folderId to id
        }.groupBy({ it.first }, { it.second })
        if (byFolder.isEmpty()) return Result.failure(IllegalStateException("这部作品下没有引用条目"))
        for ((folderId, ids) in byFolder) {
            vault.update(folderId = folderId, edit = VaultEdits.remove(ids.toSet()))
                .exceptionOrNull()?.let { return Result.failure(it) }
        }
        store.removeWork(code)
        vaultCount = store.vaultWorkCount()
        refresh()
        return Result.success(Unit)
    }

    // ---------------------------------------------------------------- 补刮

    /**
     * 给还没刮到元数据（或封面为空）的作品逐个找刮削源。一部失败不打断整轮：计数后继续。
     * 触发时机：扫描完成后、进海报墙（启动/换号）时、用户更换刮削源后——失败的作品
     * 在这些时机自动重试，不用用户手动干预。
     */
    fun enrich(silent: Boolean = false) {
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
                // 先探一次源站：不通就别挨个刮了——八百部逐个超时要很久，且每一部都会被算成
                // 「没刮到」，最后只能笼统报一句不可达，分不清是源站不通还是这片子源站里没有
                val unreachable = scraper.probe()
                if (unreachable != null) {
                    PikoLog.w(TAG, "补刮前探测未通过：$unreachable，跳过这一轮（待刮 $enrichTotal 部）")
                    // 自动补刮（启动、换号、扫完）不弹提示：没人在等这个结果，之后还会再试
                    if (!silent) _messages.tryEmit("MetaTube 连不上：$unreachable")
                    return@launch
                }
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
                // 源站已经探通了，剩下的失败就是「这部片子源站里没有」，不再说成不可达
                _messages.tryEmit(
                    "元数据补刮完成：新刮到 $enrichDone 部" + (if (enrichFailed > 0) "，$enrichFailed 部没找到" else "")
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
        // 列表内容整个换了，停在上次的位置没有意义
        scrollIndex = 0
        scrollOffset = 0
        scope.launch { refresh() }
    }

    /**
     * 当前列表的样子：筛的谁、滚到哪。
     *
     * 从详情页点演员/标签会临时把筛选换成那一位，筛选页关掉后要能原样变回去，
     * 所以进去之前先存一份。
     */
    data class FilterSnapshot(
        val filter: Filter,
        val name: String?,
        val scrollIndex: Int,
        val scrollOffset: Int,
    )

    fun filterSnapshot(): FilterSnapshot = FilterSnapshot(filter, filterName, scrollIndex, scrollOffset)

    /**
     * 恢复 [filterSnapshot]：筛选与滚动位置一起回去。
     *
     * 先换列表再给位置：界面要按新列表的长度去滚，早给就会落在旧长度上。
     * 筛选本来就没变（回到的是同一位演员）时不发恢复信号，免得下次切筛选误用这个位置。
     */
    fun restoreFilter(snapshot: FilterSnapshot) {
        if (filter == snapshot.filter && filterName == snapshot.name) {
            scrollIndex = snapshot.scrollIndex
            scrollOffset = snapshot.scrollOffset
            return
        }
        filter = snapshot.filter
        filterName = snapshot.name
        scope.launch {
            refresh()
            pendingScrollRestore = snapshot.scrollIndex to snapshot.scrollOffset
        }
    }

    /**
     * 待恢复的滚动位置，界面滚过去之后取走。
     *
     * [scrollIndex] 是普通字段、写它不触发重组，界面没法"看到"恢复动作，所以另给一个
     * 状态量当信号：不为 null 就是要滚一次。
     */
    var pendingScrollRestore by mutableStateOf<Pair<Int, Int>?>(null)
        private set

    /** 取走待恢复的位置；没有时返回 null。 */
    fun takePendingScrollRestore(): Pair<Int, Int>? {
        val pending = pendingScrollRestore ?: return null
        pendingScrollRestore = null
        return pending
    }

    /**
     * 统计行里点「已刮削 / 未刮削」：已经筛着这一批了再点就回到全部，否则切过去。
     * 入口只有一行小字，做成开关比"再找一个地方点全部"顺手。
     */
    fun toggleScrapeFilter(target: Filter) {
        selectFilter(if (filter == target) Filter.ALL else target)
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
            scraped = when (filter) {
                Filter.SCRAPED -> true
                Filter.UNSCRAPED -> false
                else -> null
            },
            vault = filter == Filter.VAULT,
        )
        actors = store.actors()
        tags = store.tags()
        workCount = store.workCount()
        scrapedCount = store.scrapedWorkCount()
        vaultCount = store.vaultWorkCount()
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
