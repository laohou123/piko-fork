package dev.piko.ui

import dev.piko.shared.sync.ArchivePasswordSync
import dev.piko.shared.sync.DriveSettingsStore
import dev.piko.shared.sync.LibrarySync
import dev.piko.shared.sync.PikoSettingsSync
import dev.piko.shared.sync.SyncCipher
import dev.piko.shared.sync.SourceLedgerSync
import dev.piko.shared.sync.VaultTreeSync
import dev.piko.ui.components.SegmentSession
import androidx.compose.runtime.staticCompositionLocalOf
import dev.piko.data.auth.PikoUserPreferences
import dev.piko.data.repository.DriveRepository
import dev.piko.shared.data.VaultStore
import dev.piko.shared.data.AccountScopedPreferences
import dev.piko.shared.data.ArchivePasswordVault
import dev.piko.shared.data.InstantMagnetRepository
import dev.piko.shared.data.MoveHistory
import dev.piko.shared.data.OfflinePackTracker
import dev.piko.shared.data.PikoCacheStore
import dev.piko.shared.data.PikoAccountRepository
import dev.piko.shared.data.PikoClientManager
import dev.piko.shared.data.PreviewTempFolder
import dev.piko.shared.data.TaskRepository
import dev.piko.shared.download.PikoDownloadCoordinator
import dev.piko.shared.library.LibraryDriverFactory
import dev.piko.shared.library.LibraryScraper
import dev.piko.shared.library.LibraryStore
import dev.piko.shared.translate.TranslationGateway
import dev.piko.shared.library.MetaTubeScraper
import dev.piko.shared.library.SubtitleFileSaver
import dev.piko.shared.library.SubtitleSource
import dev.piko.shared.library.SubtitleSourceOption
import dev.piko.shared.media.PikoMediaRepository
import dev.piko.shared.state.ArchiveBrowser
import dev.piko.shared.state.ArchiveExtractSession
import dev.piko.shared.state.FolderVaultSession
import dev.piko.shared.state.ClipFeedSession
import dev.piko.shared.state.DuplicateFinderState
import dev.piko.shared.state.DuplicateSession
import dev.piko.shared.state.InstantSaveRecords
import dev.piko.shared.state.InstantSession
import dev.piko.shared.state.InstantSheetState
import dev.piko.shared.state.PosterWallState
import dev.piko.shared.state.SubtitleSearchState
import dev.piko.shared.state.launchOnAccountLeave
import dev.piko.shared.upload.PikoUploadCoordinator
import dev.piko.shared.upload.PikoUploadSources
import dev.piko.shared.net.PikPakDomainSelector
import dev.piko.shared.log.PikoLog
import dev.piko.shared.log.logFailure
import dev.piko.shared.scrape.MetaTubeService
import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

/**
 * 界面用到的进程级对象。各端在入口按自己的偏好、会话存储与下载实现拼好一份，
 * 经 [LocalPikoServices] 交给界面。这些对象的生命周期是进程，不是某个屏幕。
 */
class PikoServices(
    platformPreferences: PikoUserPreferences,
    val clientManager: PikoClientManager,
    val downloadManager: PikoDownloadCoordinator,
    val mediaRepository: PikoMediaRepository,
    uploadSources: PikoUploadSources,
    /** 记下的文件夹内容跨进程保留在这里，见 FolderContentMemory。 */
    internal val cacheStore: PikoCacheStore? = null,
    /** 压缩包密码同步进网盘前的加密，两端都传 JvmSyncCipher；为 null 时不同步它们（截图环境）。 */
    syncCipher: SyncCipher? = null,
    /** 用户自己部署的 MetaTube，两端传入；为 null 时设置里没有刮削一组（截图环境）。 */
    val metaTube: MetaTubeService? = null,
    /**
     * 海报墙索引库的驱动工厂：每个账号一个 SQLite 文件，Android 与桌面在各自入口实现
     * （见 [LibraryDriverFactory]）。为 null 时整个海报墙不可用（截图环境）。
     */
    libraryDriverFactory: LibraryDriverFactory? = null,
    /**
     * 海报墙刮削源与字幕源共用的 HTTP 客户端工厂（引擎在各端入口建，原因同 MetaTube）。
     * 为 null 时海报墙只有扫描与列表，不能刮元数据、不能搜字幕。
     */
    scrapeHttpClientFactory: (() -> HttpClient)? = null,
    /**
     * 用 [scrapeHttpClientFactory] 的客户端建字幕源列表（jvmSharedMain 里实现，各端入口传入）。
     * 列表顺序就是面板里「字幕源」下拉的顺序，第一项是默认源。
     */
    subtitleSourceFactory: ((HttpClient) -> List<SubtitleSourceOption>)? = null,
    /**
     * 翻译用的 HTTP 客户端。单独一个是因为刮削/字幕客户端压了 30 秒超时，
     * 而一次翻 550 字可能要几十秒，用同一个会被自己的超时切断。
     */
    translationHttpClientFactory: (() -> HttpClient)? = null,
    /** 抓到的字幕写本机、交出上传 uri 的实现（Android 走 FileProvider，桌面写文件）。 */
    subtitleSaver: SubtitleFileSaver? = null,
    localFileAccess: (((String) -> Unit) -> dev.piko.shared.data.LocalFileAccess)? = null,
) {
    private val appNoticesChannel = Channel<String>(Channel.BUFFERED)
    val appNotices: Flow<String> = appNoticesChannel.receiveAsFlow()
    fun postAppNotice(message: String) {
        appNoticesChannel.trySend(message)
    }

    // 不随任何界面结束的后台工作：离线任务的跟踪与 Piko-Temp 的清理
    private val backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** 平台偏好，其中记着文件夹 ID 的几项换成按账号存的，见 AccountScopedPreferences。 */
    val preferences: PikoUserPreferences =
        cacheStore?.let { AccountScopedPreferences(platformPreferences, clientManager, it) } ?: platformPreferences

    val driveRepository: DriveRepository = DriveRepository(clientManager, preferences, cacheStore).also {
        val repository = it
        it.localMounts.access = localFileAccess?.invoke { folder ->
            repository.applyChange(dev.piko.shared.data.DriveChange.ContentsChanged(setOf(folder)))
        }
        mediaRepository.localMounts = it.localMounts
    }
    internal val presentationStores = dev.piko.ui.workbench.AccountPresentationStores()
    val fileExecutionScopes = dev.piko.shared.state.AccountExecutionScopes(
        driveRepository, CoroutineScope(backgroundScope.coroutineContext + Dispatchers.Main.immediate),
    )
    val fileWrites = dev.piko.shared.state.FileWriteSession(
        driveRepository, CoroutineScope(backgroundScope.coroutineContext + Dispatchers.Main.immediate),
    )
    val historyEdits = dev.piko.shared.state.HistoryEditSession(
        driveRepository, CoroutineScope(backgroundScope.coroutineContext + Dispatchers.Main.immediate),
    )
    val vaultEntryEdits = dev.piko.shared.state.VaultEntryEditSession(
        driveRepository, CoroutineScope(backgroundScope.coroutineContext + Dispatchers.Main.immediate),
    )
    val shareSaves = dev.piko.shared.state.ShareSaveSession(
        driveRepository, CoroutineScope(backgroundScope.coroutineContext + Dispatchers.Main.immediate),
    )
    val driveWorkspace by lazy { dev.piko.shared.presentation.DriveWorkspace(driveRepository, cacheStore, backgroundScope) }
    internal val saveTargets = dev.piko.shared.state.SaveTargetBinding({ driveRepository.currentAccount }, driveRepository.accountChanges)
    private val _openDriveRequested = MutableStateFlow(false)
    val openDriveRequested: StateFlow<Boolean> = _openDriveRequested.asStateFlow()
    fun requestOpenDrive() {
        _openDriveRequested.value = true
        driveRepository.applyChange(dev.piko.shared.data.DriveChange.ContentsChanged(null))
    }
    fun consumeOpenDriveRequest() { _openDriveRequested.value = false }
    class FileHighlightRequest internal constructor(val account: String, val ids: Set<String>)
    private val _fileHighlightRequested = MutableStateFlow<FileHighlightRequest?>(null)
    val fileHighlightRequested: StateFlow<FileHighlightRequest?> = _fileHighlightRequested.asStateFlow()
    fun requestFileHighlight(ids: Set<String>) {
        val account = driveRepository.currentAccount ?: return
        _fileHighlightRequested.value = FileHighlightRequest(account, ids.toSet())
    }
    fun consumeFileHighlight(request: FileHighlightRequest) { _fileHighlightRequested.compareAndSet(request, null) }
    val accountRepository: PikoAccountRepository = PikoAccountRepository(clientManager, driveRepository, backgroundScope)
    val instantMagnetRepository: InstantMagnetRepository = InstantMagnetRepository(clientManager)
    val taskRepository: TaskRepository = TaskRepository(clientManager, driveRepository, instantMagnetRepository)

    val previewTempFolder = PreviewTempFolder(clientManager, driveRepository, instantMagnetRepository, backgroundScope)

    init {
        // 打开归档条目时借的对象放进 Piko-Temp：取到直链就删，偶有删不掉的也随 Piko-Temp 一起清走
        mediaRepository.leaseFolder = { owner -> previewTempFolder.folderId(owner).getOrThrow() }
    }

    val vaultStore: VaultStore get() = driveRepository.vault

    val offlinePacks = OfflinePackTracker(instantMagnetRepository, driveRepository, preferences)

    val moveHistory = MoveHistory(preferences, driveRepository, backgroundScope)

    /** 部分设置同步到网盘的 .piko 文件夹，登录后自己开始，见 PikoSettingsSync。 */
    val settingsSync = PikoSettingsSync(
        clientManager,
        driveRepository,
        preferences,
        cacheStore,
        backgroundScope,
        preferences.settingsSyncFlow,
        // MetaTube 令牌与翻译 Key 跟着设置走，但要加密：密钥由账号密码派生
        cipher = syncCipher,
        passwordOf = clientManager::savedPassword,
    )
        .also { it.start() }
    private val vaultTreeSync = VaultTreeSync(clientManager, driveRepository, backgroundScope, preferences.settingsSyncFlow)
        .also { it.start() }
    private val vaultStarSync = dev.piko.shared.sync.VaultStarSync(clientManager, driveRepository, backgroundScope, preferences.settingsSyncFlow)
        .also { it.start() }
    private val sourceLedgerSync = SourceLedgerSync(clientManager, driveRepository, backgroundScope, preferences.settingsSyncFlow)
        .also { it.start() }
    /** 当前账号存过的压缩包密码，解压、浏览压缩包与设置页共用这一个，见 ArchivePasswordVault。 */
    val archivePasswords = ArchivePasswordVault(clientManager, clientManager.archivePasswordStore, preferences)
        .also { it.start(backgroundScope) }

    private val archivePasswordSync = syncCipher?.let {
        ArchivePasswordSync(clientManager, driveRepository, archivePasswords, it, backgroundScope, preferences.settingsSyncFlow).also { sync -> sync.start() }
    }

    /** API 走哪个根域名：用户固定的，或登录后测速自动挑的，见 PikPakDomainSelector。 */
    val domainSelector = PikPakDomainSelector(clientManager, preferences, backgroundScope).also { it.start() }

    val uploadManager = PikoUploadCoordinator(clientManager, preferences, uploadSources, driveRepository, backgroundScope)

    // 海报墙：索引库（按账号一个 SQLite 文件）+ 刮削源（MetaTube）+ 字幕源。
    // 刮削源的地址与令牌复用设置里的 MetaTube 配置，用户只配一次。
    val libraryStore: LibraryStore? = libraryDriverFactory?.let { factory ->
        // 每次现取当前账号：换号后读写的是新账号的库，provider 在每回操作时被调用
        LibraryStore(factory) { clientManager.currentClient.value?.account }
    }
    /**
     * 刮削走 MetaTube：图片与元数据都经服务端代理，客户端不直连源站
     * （JavBus 直抓那条路已删除——反爬与图片防盗链都绕不过去）。
     */
    val libraryScraper: LibraryScraper? = libraryStore?.let {
        scrapeHttpClientFactory?.let { http -> MetaTubeScraper(platformPreferences, http) }
    }
    /** 可搜的字幕源，面板上的「字幕源」下拉按这个列表铺；空列表表示这台机器上没有可用的源。 */
    val subtitleSources: List<SubtitleSourceOption> = scrapeHttpClientFactory?.let { http ->
        subtitleSourceFactory?.invoke(http())
    }.orEmpty()

    /** 默认源（列表第一个）。旧调用点只认一个源时用它。 */
    val subtitleSource: SubtitleSource? = subtitleSources.firstOrNull()?.source
    val subtitleSaver: SubtitleFileSaver? = subtitleSaver

    /**
     * AI 翻译：片名与字幕共用一套配置（地址 / Key / 模型），每次调用现读偏好，
     * 用户改完设置立即生效。没配齐时 [TranslationGateway.isConfigured] 为 false。
     */
    /** 翻译用的 HTTP 客户端；字幕翻译面板读本机代理流也用它。 */
    val translationHttp: HttpClient? = translationHttpClientFactory?.invoke()

    val translation: TranslationGateway? = translationHttp?.let { http ->
        TranslationGateway(platformPreferences) { http }
    }

    /** 海报墙页的进程级状态：扫描与补刮不随离开页面而停。 */
    val posterWall: PosterWallState? = libraryStore?.let { store ->
        PosterWallState(
            clients = clientManager,
            store = store,
            scraper = libraryScraper,
            metaTubeBase = { platformPreferences.metaTubeUrlFlow.first().trim() },
            translation = translation,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
        )
    }

    /**
     * 译名与收藏（作品/演员/标签）随账号同步到网盘 `.piko/library-*.json`。
     * 单独一份：它们是库数据，体积随作品数长，不该跟着改设置一起重传。
     */
    val librarySync: LibrarySync? = libraryStore?.let { store ->
        LibrarySync(
            clients = clientManager,
            remote = DriveSettingsStore(clientManager, driveRepository, "library-"),
            store = store,
            scope = backgroundScope,
            enabled = preferences.settingsSyncFlow,
            // 别的设备改了译名或收藏：让海报墙重读一遍，不用退出去再进来
            onApplied = { posterWall?.refreshList() },
        ).also { it.start() }
    }

    val instantSaveRecords = InstantSaveRecords(clientManager, cacheStore, backgroundScope)
    val receivingActivity by lazy { dev.piko.shared.presentation.ReceivingActivity(instantSaveRecords, uploadManager, instantMagnetRepository.offlineSubmissions, archiveExtractSession, offlinePacks, backgroundScope) }

    val instantSession: InstantSession by lazy {
        InstantSession(
            newScope = { CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate) },
            newState = { scope, magnet ->
                InstantSheetState(
                    instantMagnetRepository,
                    driveRepository,
                    preferences,
                    previewTempFolder,
                    offlinePacks,
                    instantSaveRecords,
                    scope,
                    magnet,
                    saveTargetContext = saveTargets.context,
                    shareSaves = shareSaves,
                )
            },
            newStateForDraft = { scope, saveScope, magnet, target ->
                InstantSheetState(
                    instantMagnetRepository, driveRepository, preferences, previewTempFolder,
                    offlinePacks, instantSaveRecords, scope, magnet,
                    saveTargetContext = target, shareSaves = shareSaves, saveScope = saveScope,
                )
            },
        )
    }

    val addLink by lazy { dev.piko.shared.presentation.AddLinkViewModel(instantSession) }

    /** 下载片段的面板收起后选好的区间还在，见 [SegmentSession]。 */
    val segmentSession: SegmentSession by lazy { SegmentSession() }

    val trashSession by lazy {
        dev.piko.shared.state.TrashSession(driveRepository, CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate))
    }

    val duplicateSessions = androidx.compose.runtime.mutableStateListOf<DuplicateSession>()

    fun newDuplicateSession(): DuplicateSession =
        DuplicateSession(
            newScope = { CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate) },
            newState = { scope, root -> DuplicateFinderState(clientManager, driveRepository, scope, root, trashSession) },
        ).also(duplicateSessions::add)

    fun releaseDuplicateSession(session: DuplicateSession) {
        session.end()
        duplicateSessions.remove(session)
    }

    val duplicateSession: DuplicateSession by lazy { newDuplicateSession() }

    // 主线程且与进程同寿：会话在这个作用域上改 Compose 状态，离开网盘页后解压仍要继续
    val archiveExtractSession: ArchiveExtractSession by lazy {
        ArchiveExtractSession(
            clientProvider = clientManager,
            driveRepository = driveRepository,
            vault = archivePasswords,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
        )
    }

    // 网盘页重建后，进过的压缩包的密码与列过的层还在，见 ArchiveBrowser
    val archiveBrowser: ArchiveBrowser by lazy {
        ArchiveBrowser(clientManager, archivePasswords, scratchFolder = { owner -> previewTempFolder.folderId(owner) })
    }

    // 主线程且与进程同寿：离开网盘页后归档仍要继续
    val folderVaultSession: FolderVaultSession by lazy {
        FolderVaultSession(driveRepository, CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)).also { session ->
            session.conflictReason = { targets ->
                val commands = dev.piko.shared.presentation.FileCommands(driveRepository, fileWrites, cleanup = trashSession, vault = session, vaultEdits = vaultEntryEdits, shareSaves = shareSaves, receiving = receivingActivity)
                (commands.vaultAvailability(targets) as? dev.piko.shared.presentation.ActionAvailability.Blocked)?.reason
            }
        }
    }

    // 点进行中的系统通知要看传输页。应用可能正冷启动、还没登录，所以是待办而不是事件，由主界面组合时取走，
    // 外部通知要求打开任务页，主界面出现后再消费。
    private val _openTransfersRequested = MutableStateFlow(false)
    val openTransfersRequested: StateFlow<Boolean> = _openTransfersRequested.asStateFlow()

    fun requestOpenTransfers() {
        _openTransfersRequested.value = true
    }

    fun consumeOpenTransfersRequest() {
        _openTransfersRequested.value = false
    }

    // 切到传输页时一并给的提示（如刚存下默认下载画质）。发出时传输页还没组合出来，SharedFlow 没有订阅者会丢掉，
    // 所以用 Channel 存着，等传输页取走
    private val transfersNotices = Channel<String>(Channel.BUFFERED)
    val transfersNotice: Flow<String> = transfersNotices.receiveAsFlow()

    fun noticeOnTransfers(message: String) {
        transfersNotices.trySend(message)
    }

    // 主线程且与进程同寿：打开完整播放器时随机片段页可能被销毁，队列要留着回来接着看
    val clipFeedSession: ClipFeedSession by lazy {
        ClipFeedSession(clientManager, driveRepository, mediaRepository, cacheStore, CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate))
    }

    init {
        // 未完成的添加链接、查重、解压、片段下载、信息流与归档都属于上一个账号：保存目标、文件 ID 都是那边的。
        // 主线程上当场结束，会话的状态只在主线程上改
        CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).launchOnAccountLeave(clientManager) { left ->
            PikoLog.i(TAG, "${if (clientManager.currentClient.value == null) "退出登录" else "换号"}：结束上一账号的进程级会话")
            presentationStores.leave(left)
            fileWrites.clear(left)
            historyEdits.clear(left)
            vaultEntryEdits.clear(left)
            instantSaveRecords.clearAttempts(left)
            shareSaves.clear(left)
            instantMagnetRepository.offlineSubmissions.clear(left)
            driveRepository.localMounts.access?.cancelPending()
            driveRepository.enterAccount(clientManager.currentClient.value?.account)
            _fileHighlightRequested.value?.takeIf { it.account == left }?.let(::consumeFileHighlight)
            instantSession.clear()
            trashSession.clear()
            duplicateSessions.toList().forEach { it.end() }
            duplicateSessions.clear()
            archiveExtractSession.clear()
            archiveBrowser.clear()
            segmentSession.end()
            clipFeedSession.endAccount(left)
            folderVaultSession.cancel()
        }
        backgroundScope.launch {
            val cleanedAccounts = mutableSetOf<String>()
            // 海报墙索引按账号各一份。启动时登录态是异步恢复的，PosterWallState.init
            // 往往跑在账号就绪之前——那时库还打不开，列表为空、目录显示「未选择」。
            // 这里在账号第一次就绪时补一次重载，换号与重新登录同样走这条路
            var loadedPosterAccount: String? = null
            clientManager.currentClient.collectLatest { client ->
                if (client == null) {
                    // 登出：海报墙不能留着上一个账号的作品与目录
                    if (loadedPosterAccount != null) {
                        loadedPosterAccount = null
                        posterWall?.onAccountChanged()
                    }
                    return@collectLatest
                }
                if (client.account != loadedPosterAccount) {
                    loadedPosterAccount = client.account
                    posterWall?.onAccountChanged()
                }
                // 上次进程被杀时面板来不及清 Piko-Temp，登录后补上。每个账号只清一次：断线重连
                // 也会换一个新的 client，那时面板可能正开着，预览的文件还要用
                if (cleanedAccounts.add(client.account)) previewTempFolder.clear(client).logFailure(TAG, "登录后清理 Piko-Temp 失败")
                // 换号或退出登录时 collectLatest 取消它，换成新账号的记录重来
                offlinePacks.run(client.account)
            }
        }
    }
}

private const val TAG = "Services"

val LocalPikoServices = staticCompositionLocalOf<PikoServices> {
    error("PikoServices 未提供，入口要用 PikoApp 包一层")
}
