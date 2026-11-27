package dev.piko.data.auth

import dev.piko.shared.data.DriveAppearancePrefs
import dev.piko.shared.media.player.PlayerLayoutPrefs
import dev.piko.shared.media.player.SubtitleStylePrefs
import dev.piko.shared.net.ProxySetting
import dev.piko.shared.sync.PortableDigest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/** 将账号标识映射为固定长度的十六进制存储槽位键，避免特殊字符并隐去手机号或邮箱明文。 */
fun accountPreferenceKey(account: String): String =
    PortableDigest.sha256(account.encodeToByteArray()).toHexString()

/** 网盘空间用量。取到之前先用账号列表里记着的上一次的值，免得卡片整块缺席。 */
data class QuotaSnapshot(val usageBytes: Long, val limitBytes: Long)

/** 侧栏的开关与宽度。[widthDp] 为 null 表示从未拖过，取调用方的默认宽度。 */
data class SidePanelPrefs(val open: Boolean, val widthDp: Float?)

/**
 * 引用视频（网盘里只留清单、播放时按 gcid 秒传重建对象的条目）按体积过滤。
 *
 * 这类条目要播出去，云端得先临时放得下整个文件，账号装不下就报空间不足。列表里把它藏起来，
 * 免得点进去才发现播不了。真实文件不受影响：它们已经在网盘里占着地方，播放不需要额外空间。
 */
enum class VaultSizeFilter {
    /** 跟随账号剩余空间：空间变大（开会员、清回收站）后，藏起来的条目自己回来。 */
    AUTO,

    /** 固定上限，见 [VaultSizeLimit.manualBytes]。 */
    MANUAL,

    /** 不按体积过滤。默认。 */
    OFF;

    companion object {
        fun fromStorage(raw: String?): VaultSizeFilter =
            entries.firstOrNull { it.name.equals(raw?.trim(), ignoreCase = true) } ?: OFF
    }
}

object VaultSizeLimit {
    const val GIB = 1024L * 1024L * 1024L

    /** 手动上限的默认值与允许范围（GB）。 */
    const val DEFAULT_MANUAL_GB = 6
    const val MIN_MANUAL_GB = 1
    const val MAX_MANUAL_GB = 500

    /**
     * 自动模式只用剩余空间的九成：SDK 按 gcid 借对象时也是这么算的（见 PikoDriveRepository 的 LeaseBudget），
     * 留一成给云盘自己的元数据与同时借出的别的条目。
     */
    const val AUTO_HEADROOM_PERCENT = 90

    fun manualBytes(gb: Int): Long = gb.coerceIn(MIN_MANUAL_GB, MAX_MANUAL_GB) * GIB

    /** 当前生效的上限；null 表示不过滤。[remainingBytes] 是账号剩余空间，取不到时自动模式退回不过滤。 */
    fun limitBytes(filter: VaultSizeFilter, manualGb: Int, remainingBytes: Long?): Long? = when (filter) {
        VaultSizeFilter.OFF -> null
        VaultSizeFilter.MANUAL -> manualBytes(manualGb)
        VaultSizeFilter.AUTO -> remainingBytes?.takeIf { it > 0 }?.let { it / 100 * AUTO_HEADROOM_PERCENT }
    }
}

/**
 * 蜗牛模式，照 FDM：开着时下载与上传各自不超过设定的带宽，让出网络给别的用途。播放不受限。
 * 上限以 KiB/s 计；开关与上限分开存，关掉再开回到原来的上限。
 */
data class SnailMode(
    val enabled: Boolean = false,
    val downloadKiBps: Int = DEFAULT_DOWNLOAD_KIBPS,
    val uploadKiBps: Int = DEFAULT_UPLOAD_KIBPS,
) {
    companion object {
        const val DEFAULT_DOWNLOAD_KIBPS = 1024
        const val DEFAULT_UPLOAD_KIBPS = 512
    }
}

/** 播放器进退步长与长按倍速的默认值与可选档位。存储两端与播放设置面板共用这一份。 */
object PlayerGestureDefaults {
    const val SEEK_STEP_SECONDS = 10
    val SeekStepChoices = listOf(1, 2, 5, 10, 15, 30)
    const val BOOST_SPEED = 2f
    val BoostSpeedChoices = listOf(1.5f, 2f, 2.5f, 3f)

    /** 播放画质自动档：按网络速度与当前倍速自动升降画质（默认），仅用于在线播放。 */
    const val PLAYBACK_AUTO_HEIGHT = -1

    /** 固定画质上限的档位，画面高度，0 是原画。PikPak 的转码一般是这三档 */
    val MaxHeightChoices = listOf(0, 1080, 720, 480)
}

/** 本地缓存上限与过期清理天数的默认值与可选档位（设备本地设置，不参与跨设备同步）。 */
object CachePolicyDefaults {
    const val DEFAULT_MAX_BYTES: Long = 2L * 1024L * 1024L * 1024L
    const val DEFAULT_MAX_AGE_DAYS: Int = 14
    val MaxBytesChoices: List<Long> = listOf(
        512L * 1024L * 1024L,
        1L * 1024L * 1024L * 1024L,
        2L * 1024L * 1024L * 1024L,
        5L * 1024L * 1024L * 1024L,
        10L * 1024L * 1024L * 1024L,
        0L,
    )
    val MaxAgeDaysChoices: List<Int> = listOf(3, 7, 14, 30, 0)
}

/** 同时下载文件数的默认值与范围（设备本地设置，不参与跨设备同步）。 */
object DownloadConcurrencyDefaults {
    const val MIN = 1
    const val MAX = 8
    const val DEFAULT = 3
    fun sanitize(value: Int?): Int = (value ?: DEFAULT).coerceIn(MIN, MAX)
}

enum class AppLanguage(val tag: String, val nativeLabel: String) {
    SYSTEM("system", ""),
    ZH_CN("zh-CN", "简体中文"),
    ZH_HANT("zh-TW", "繁體中文"),
    KO("ko-KR", "한국어"),
    JA("ja-JP", "日本語"),
    EN("en", "English");

    companion object {
        fun fromStorage(raw: String?): AppLanguage {
            val normalized = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return SYSTEM
            entries.firstOrNull {
                it.name.equals(normalized, ignoreCase = true) || it.tag.equals(normalized, ignoreCase = true)
            }?.let { return it }
            val parts = normalized.lowercase().split('-', '_').filter { it.isNotEmpty() }
            return when (parts.firstOrNull()) {
                "system" -> SYSTEM
                "zh" -> when {
                    "hant" in parts -> ZH_HANT
                    "hans" in parts -> ZH_CN
                    parts.any { it in TRADITIONAL_CHINESE_REGIONS } -> ZH_HANT
                    parts.size == 1 || parts.any { it in SIMPLIFIED_CHINESE_REGIONS } -> ZH_CN
                    else -> SYSTEM
                }
                "ko" -> KO
                "ja" -> JA
                "en" -> EN
                else -> SYSTEM
            }
        }

        private val TRADITIONAL_CHINESE_REGIONS = setOf("tw", "hk", "mo")
        private val SIMPLIFIED_CHINESE_REGIONS = setOf("cn", "sg", "my")
    }
}

/**
 * 启动与高频界面路径的无锁偏好快照，避免在组合或 ViewModel 初始化期间调用 `runBlocking`。
 * 带内存缓存的实现（`StoredPikoPreferences`、已预加载的 `SessionManager`）直接返回最新值；测试替身回退至默认值。
 */
data class PikoPreferencesSnapshot(
    val appLanguage: AppLanguage = AppLanguage.SYSTEM,
    val themeMode: String? = null,
    val themeSeed: String? = null,
    val pureBlack: Boolean = false,
    val einkMode: Boolean = false,
    val spoilerBlurEnabled: Boolean = false,
    val reduceMotion: Boolean = false,
    val motionPreference: String? = null,
    val predictiveBack: Boolean = true,
    val maxConcurrentDownloads: Int = DownloadConcurrencyDefaults.DEFAULT,
    val hardwareDecoding: Boolean = true,
    val playbackMaxHeight: Int = PlayerGestureDefaults.PLAYBACK_AUTO_HEIGHT,
    val driveViewMode: String = "POSTER",
    val driveSortOrder: String = "TIME_DESC",
    val foldersFirst: Boolean = true,
    val perFolderSort: Boolean = false,
    val aiSubtitleLanguage: String = "",
    val aiSubtitleOnDemand: Boolean = true,
    val aiSubtitleSavedSeconds: Long = 0L,
    val galleryShowImages: Boolean = true,
    val sidebarCollapsed: Boolean = false,
    val showExtensions: Boolean = false,
    val proxySetting: ProxySetting = ProxySetting(),
    val tileSizes: Map<String, String> = emptyMap(),
    val playerLayout: PlayerLayoutPrefs = PlayerLayoutPrefs.Default,
    val subtitleStyle: SubtitleStylePrefs = SubtitleStylePrefs.DEFAULT,
    val driveAppearance: DriveAppearancePrefs = DriveAppearancePrefs.DEFAULT,
    val skipFeedExitConfirm: Boolean = false,
    val cacheMaxBytes: Long = CachePolicyDefaults.DEFAULT_MAX_BYTES,
    val cacheMaxAgeDays: Int = CachePolicyDefaults.DEFAULT_MAX_AGE_DAYS,
    val playbackCacheEnabled: Boolean = true,
    val resumePlaybackEnabled: Boolean = true,
    val askDownloadLocation: Boolean = true,
    val clipVolume: Float = 1f,
    val clipMuted: Boolean = false,
) {
    fun tileSize(view: String): String = tileSizes[view].orEmpty()
}

interface PikoUserPreferences {
    /** 无锁读取当前偏好快照，供启动初始化与界面初值使用。 */
    val currentSnapshot: PikoPreferencesSnapshot get() = PikoPreferencesSnapshot()

    /** 无锁读取指定视图的卡片大小偏好，未设置时返回空串。 */
    fun currentTileSize(view: String): String = currentSnapshot.tileSize(view)

    /** 当前设备记住的引用保存选择，不参与跨设备设置同步。 */
    val saveAsReferenceFlow: Flow<Boolean> get() = kotlinx.coroutines.flow.flowOf(false)
    suspend fun setSaveAsReference(enabled: Boolean) {}

    /** 本设备的只读文件夹挂载表，内含账号，不参与云端同步。 */
    val localMountsFlow: Flow<String> get() = flowOf("")
    suspend fun saveLocalMounts(serialized: String) {}

    /** 瀑布流是否一并展示图片，默认开启。 */
    val galleryShowImagesFlow: Flow<Boolean> get() = flowOf(true)
    suspend fun setGalleryShowImages(show: Boolean) {}

    val appLanguageFlow: Flow<AppLanguage> get() = flowOf(AppLanguage.SYSTEM)
    suspend fun setAppLanguage(language: AppLanguage) {}

    suspend fun savePlaybackPosition(fileId: String, positionMs: Long)
    suspend fun getPlaybackPosition(fileId: String): Long
    suspend fun saveLastFolder(folderId: String, folderName: String, stackSerialized: String)
    suspend fun getLastFolder(): Triple<String, String, String>

    val spoilerBlurFlow: Flow<Boolean>
    suspend fun setSpoilerBlurEnabled(enabled: Boolean)
    val heuristicFilterFlow: Flow<Boolean>
    suspend fun setHeuristicFilterEnabled(enabled: Boolean)

    /**
     * 文件名解析总开关，默认开。关闭后网盘列表、磁力面板与选集都照原样列出文件名：不分区、不改标题、
     * 不挂标签；启发式折叠与配套字幕依赖解析，一并失效。
     */
    val nameParsingFlow: Flow<Boolean>
    suspend fun setNameParsingEnabled(enabled: Boolean)

    /** 添加链接时一并保存视频的外挂字幕。字幕在面板里挂在视频行下，不单独勾选，关闭后保存时跳过它们。 */
    val bundleSubtitlesFlow: Flow<Boolean>
    suspend fun setBundleSubtitlesEnabled(enabled: Boolean)

    /**
     * 新建文件夹与重命名时，名称含 PikPak 不支持的内容就直接去掉，不再询问，默认关。
     * 规则见 [dev.piko.shared.data.DriveNames]。
     */
    val autoCleanNamesFlow: Flow<Boolean>
    suspend fun setAutoCleanNamesEnabled(enabled: Boolean)

    /** 把部分设置同步到网盘根目录的 .piko 文件夹，换设备登录时带过去，默认开。见 PikoSettingsSync。 */
    val settingsSyncFlow: Flow<Boolean>
    suspend fun setSettingsSyncEnabled(enabled: Boolean)

    /** 把播放进度上报到 PikPak 的播放历史，与官方客户端共用；没有本机记录时也从那里续播。 */
    val syncPlayHistoryFlow: Flow<Boolean>
    suspend fun setSyncPlayHistoryEnabled(enabled: Boolean)

    /** 深浅模式，存 ThemeMode 的名字（`SYSTEM`、`LIGHT`、`DARK`）；首次未设置时流中初值为 null，按跟随系统处理。 */
    val themeModeFlow: Flow<String?>
    suspend fun setThemeMode(mode: String)

    /** 内置主题色，存 SeedTheme 的名字。为 null 表示系统取色。 */
    val themeSeedFlow: Flow<String?>
    suspend fun setThemeSeed(seed: String?)

    /** 纯黑深色模式（OLED），仅在深色外观下生效，默认关闭。随设置跨设备同步。 */
    val pureBlackFlow: Flow<Boolean> get() = flowOf(false)
    suspend fun setPureBlack(enabled: Boolean) {}

    /** 水墨屏模式（纯黑白高对比度、清晰描边与自动关闭动画），默认关闭。每台设备各自独立，不随账号跨设备同步。 */
    val einkModeFlow: Flow<Boolean> get() = flowOf(false)
    suspend fun setEinkMode(enabled: Boolean) {}

    /**
     * 网盘列表的视图，存 ui 里 DriveViewMode 的名字：列表、海报墙或图库。全局记住，不随进出目录或重启复位。
     * 旧版只存是否海报墙，没有新键时由它换算，已有的选择不丢。
     */
    val driveViewModeFlow: Flow<String>
    suspend fun setDriveViewMode(mode: String)

    /** 网盘全局默认排序方式，存 `PikoFileSortOrder.name`（如 `TIME_DESC`）。跨重启持久化并随设置同步。 */
    val driveSortOrderFlow: Flow<String> get() = kotlinx.coroutines.flow.flowOf("TIME_DESC")
    suspend fun setDriveSortOrder(order: String) {}

    /** AI 实时字幕的目标语言码，空串表示原语言转写。跨重启持久化并随设置同步。 */
    val aiSubtitleLanguageFlow: Flow<String> get() = kotlinx.coroutines.flow.flowOf("")
    suspend fun setAiSubtitleLanguage(language: String) {}

    /** AI 字幕「按需启用」（本地 Silero VAD 跳过无对白段以节省额度），默认开启。 */
    val aiSubtitleOnDemandFlow: Flow<Boolean> get() = kotlinx.coroutines.flow.flowOf(true)
    suspend fun setAiSubtitleOnDemand(enabled: Boolean) {}

    /** 本地 Silero VAD 累计为用户节省的 AI 字幕额度秒数。 */
    val aiSubtitleSavedSecondsFlow: Flow<Long> get() = kotlinx.coroutines.flow.flowOf(0L)
    suspend fun addAiSubtitleSavedSeconds(deltaSeconds: Long) {}

    /**
     * 引用视频按体积过滤的模式，见 [VaultSizeFilter]。每台设备各自的：空间够不够看的是这份账号，
     * 而账号在另一台机器上可能是另一个。
     */
    val vaultSizeFilterFlow: Flow<String>
    suspend fun setVaultSizeFilter(mode: String)

    /** 手动上限，GB，仅在 [VaultSizeFilter.MANUAL] 下生效。 */
    val vaultSizeLimitGbFlow: Flow<Int>
    suspend fun setVaultSizeLimitGb(gb: Int)

    /**
     * 海报墙与图库各自的卡片大小，[view] 是 DriveViewMode 的名字，值是 ui 里 TileSize 的名字，没存过时为空串。
     * 每台设备各自的，不同步：同一档在手机与宽屏上排出的栏数相差很远。
     */
    fun driveTileSizeFlow(view: String): Flow<String>
    suspend fun setDriveTileSize(view: String, size: String)

    /** 大窗口左侧边栏收起成了窄轨。每台设备各自的，不同步：屏幕宽窄因机而异。 */
    val sidebarCollapsedFlow: Flow<Boolean>
    suspend fun setSidebarCollapsed(collapsed: Boolean)

    /**
     * 文件名带不带扩展名显示。默认值两端不同：桌面照资源管理器与 Finder 的习惯显示，手机上宽度金贵，
     * 类型已在副标题里单列，不显示。因此每台设备各自的，不同步。
     */
    val showExtensionsFlow: Flow<Boolean>
    suspend fun setShowExtensions(show: Boolean)

    /**
     * 网盘页的信息流：上次是否开着、宽窗口里侧栏拖到的宽度。开关不改 [driveViewModeFlow]，
     * 关掉信息流即回到原来的列表视图。
     */
    val clipPanelFlow: Flow<SidePanelPrefs>
    suspend fun setClipPanelOpen(open: Boolean)
    suspend fun setClipPanelWidth(widthDp: Float)

    /** PikPak API 用哪个根域名（如 mypikpak.net），空串是自动测速挑选，见 PikPakDomainSelector。每台设备各自的网络，不同步。 */
    val pikpakDomainFlow: Flow<String>
    suspend fun setPikpakDomain(root: String)

    /** 蜗牛模式的开关与上下行上限。每台设备各自的网络，不同步。 */
    val snailModeFlow: Flow<SnailMode>
    suspend fun setSnailMode(mode: SnailMode)

    val downloadDirPathFlow: Flow<String>
    suspend fun setDownloadDirPath(path: String)
    suspend fun getDownloadDirPath(): String

    /** 每次下载前手动选择保存位置，默认开启。仅保存在当前设备，不参与跨设备同步。 */
    val askDownloadLocationFlow: Flow<Boolean> get() = flowOf(true)
    suspend fun setAskDownloadLocation(enabled: Boolean) {}

    /** 本地下载任务表的 JSON。空串表示从未保存。 */
    suspend fun loadDownloadTasks(): String
    suspend fun saveDownloadTasks(serialized: String)

    /** 上传任务表的 JSON，见 PikoUploadCoordinator。会话里的 OSS 凭据已抹去，另存在 [loadUploadCredentials]。空串表示从未保存。 */
    suspend fun loadUploadTasks(): String
    suspend fun saveUploadTasks(serialized: String)

    /**
     * 一个上传任务的 OSS 凭据（12 小时有效），JSON，按任务 ID 存，见 PikoUploadCoordinator。与登录会话同等看待，
     * 由平台加密存放。没有或解不开时为 null，调用方当作凭据已过期。
     */
    suspend fun loadUploadCredentials(taskId: String): String?
    suspend fun saveUploadCredentials(taskId: String, serialized: String)
    suspend fun clearUploadCredentials(taskId: String)

    /** 整包离线任务的跟踪记录，JSON，见 OfflinePackTracker。空串表示从未保存。 */
    suspend fun loadOfflinePacks(): String
    suspend fun saveOfflinePacks(serialized: String)

    /**
     * 1.1.0 全机一份、明文存着的压缩包密码（最近用过的在前的 JSON 列表），没有时为空串。之后按账号存在机密里
     * （ArchivePasswordStore），这一份由 ArchivePasswordVault 并入头一个运行的账号后 [clearLegacyArchivePasswords]。
     */
    suspend fun loadLegacyArchivePasswords(): String
    suspend fun clearLegacyArchivePasswords()

    /** 最近移动到过的目录路径，JSON，见 MoveHistory。空串表示从未保存。 */
    val recentMoveTargetsFlow: Flow<String>
    suspend fun saveRecentMoveTargets(serialized: String)
    fun recentMoveTargetsFlowFor(account: String): Flow<String> =
        if (account.isBlank()) flowOf("") else recentMoveTargetsFlow
    suspend fun saveRecentMoveTargetsFor(account: String, serialized: String) {
        if (account.isNotBlank()) saveRecentMoveTargets(serialized)
    }

    /** 固定到快速访问的文件夹，JSON，见 PinnedFolders。空串表示从未保存。 */
    val pinnedFoldersFlow: Flow<String>
    suspend fun savePinnedFolders(serialized: String)
    fun pinnedFoldersFlowFor(account: String): Flow<String> =
        if (account.isBlank()) flowOf("") else pinnedFoldersFlow
    suspend fun savePinnedFoldersFor(account: String, serialized: String) {
        if (account.isNotBlank()) savePinnedFolders(serialized)
    }

    /** 文件夹优先显示，关闭后与文件按同一排序混排，默认开。 */
    val foldersFirstFlow: Flow<Boolean> get() = flowOf(true)
    suspend fun setFoldersFirst(enabled: Boolean) {}

    /** 每个文件夹独立记住自己的排序方式，默认关（所有文件夹共用同一排序）。 */
    val perFolderSortFlow: Flow<Boolean> get() = flowOf(false)
    suspend fun setPerFolderSort(enabled: Boolean) {}

    /** 按文件夹记住的排序方式，JSON（folderId -> PikoFileSortOrder.name），按账号隔离。 */
    val folderSortOrdersFlow: Flow<String> get() = flowOf("")
    suspend fun saveFolderSortOrders(serialized: String) {}
    fun folderSortOrdersFlowFor(account: String): Flow<String> =
        if (account.isBlank()) flowOf("") else folderSortOrdersFlow
    suspend fun saveFolderSortOrdersFor(account: String, serialized: String) {
        if (account.isNotBlank()) saveFolderSortOrders(serialized)
    }

    /** 批量重命名上次的选项与最近用过的查找、替换串，JSON，见 BatchRenameMemory。空串表示从未保存。每台设备各自的，不同步。 */
    val batchRenameFlow: Flow<String>
    suspend fun saveBatchRename(serialized: String)

    /** 归档对话框上次的三个勾选，JSON，见 VaultArchiveOptions。空串表示从未保存。每台设备各自的，不同步。 */
    val vaultArchiveOptionsFlow: Flow<String>
    suspend fun saveVaultArchiveOptions(serialized: String)

    /**
     * 批量重命名的查找替换写成正则文本，而不是拼积木。默认关（积木）。记的是用户手动切换的结果，
     * 因正则无法图形化而停在文本模式的那一次不算。属于用户的操作习惯，随设置跨设备同步。
     */
    val renameRegexTextModeFlow: Flow<Boolean>
    suspend fun setRenameRegexTextMode(enabled: Boolean)

    /** 应用内网络请求用的代理，见 PikoProxySelector。 */
    val proxySettingFlow: Flow<ProxySetting>
    suspend fun saveProxySetting(setting: ProxySetting)

    /** 开屏自动检查更新，默认开。关掉后只在设置页手动检查。 */
    val autoCheckUpdatesFlow: Flow<Boolean>
    suspend fun setAutoCheckUpdates(enabled: Boolean)

    /** 回到前台时检测剪贴板中的磁力或分享链接，默认关，仅保存在当前设备。 */
    val autoDetectClipboardLinksFlow: Flow<Boolean>
    suspend fun setAutoDetectClipboardLinks(enabled: Boolean)

    /**
     * 每次打开应用（进海报墙、换账号）时自动补刮缺标题/封面的作品，默认开。
     *
     * 关掉后启动不再向刮削源发请求：作品照样按番号显示，只是没有片名与封面；
     * 扫描完成、更换刮削源、手动点「补刮元数据」这三处不受影响。
     * 每台设备各自的，不同步：手机想省流量、桌面无所谓。
     */
    val autoEnrichOnLaunchFlow: Flow<Boolean> get() = flowOf(true)
    suspend fun setAutoEnrichOnLaunch(enabled: Boolean) {}

    /** 设置里的「减少动画」，默认关，与系统的减少动画取或。每台设备各自的，不同步：系统那一半本来就按设备。 */
    val reduceMotionFlow: Flow<Boolean>
    suspend fun setReduceMotion(enabled: Boolean)

    /**
     * 播放时用显卡解码视频，默认开。关掉改用处理器解码，给显卡驱动出问题的机器一条退路
     * （有用户在 Windows 上播 4K 时整机卡死）。每台设备各自的，不同步：好不好用看的是这台的显卡。
     * 播放器打开时读一次，开着的播放器不跟着换。
     */
    val hardwareDecodingFlow: Flow<Boolean>
    suspend fun setHardwareDecoding(enabled: Boolean)

    /**
     * 播放画质设置：-1（[PlayerGestureDefaults.PLAYBACK_AUTO_HEIGHT]）是自动（默认，按网速与倍速调节），
     * 0 是原画，正整数是画面高度上限（播放时挑不高于它的最大一档转码，没有就放原画，见 transcodeNameAtMost）。
     * 每台设备各自的，不同步：手机走流量想要 720P，电脑上要自动或原画。
     */
    val playbackMaxHeightFlow: Flow<Int>
    suspend fun setPlaybackMaxHeight(height: Int)

    /**
     * 默认下载画质：画面高度的上限，0 是原画；null 是未设置，普通下载同样使用原画。指定画质另由显式动作选择，
     * 按它直接挑，规则见 dev.piko.shared.media.downloadQualityOrder，转码档转封装成 MP4 存下。
     * 每台设备各自的，不同步，理由同 [playbackMaxHeightFlow]。
     */
    val downloadMaxHeightFlow: Flow<Int?>
    suspend fun setDownloadMaxHeight(height: Int?)

    /** 播放器双击、方向键进退一步的秒数，默认 10。在播放设置里改，跟着设置同步：这是看片的习惯，不看设备。 */
    val playerSeekStepSecondsFlow: Flow<Int>
    suspend fun setPlayerSeekStepSeconds(seconds: Int)

    /** 长按画面临时加速到的倍速，默认 2。同上，在播放设置里改、跟着设置同步。 */
    val playerBoostSpeedFlow: Flow<Float>
    suspend fun setPlayerBoostSpeed(speed: Float)

    /** 信息流 30 秒片段播完后自动顺延续播当前视频后续内容，默认开启。跟着设置同步。 */
    val clipAutoExtendFlow: Flow<Boolean>
    suspend fun setClipAutoExtend(enabled: Boolean)

    /** 信息流本机音量（0..1）与静音状态。仅保存在当前设备，不参与跨设备同步。 */
    val clipVolumeFlow: Flow<Float> get() = flowOf(1f)
    val clipMutedFlow: Flow<Boolean> get() = flowOf(false)
    suspend fun setClipAudio(volume: Float, muted: Boolean) {}

    /**
     * 界面动画程度（"Reduced" / "Standard" / "Expressive"，null 表示跟随当前平台默认档：桌面 Standard，移动 Expressive）。
     * 仅保存在当前设备，不参与跨设备同步。
     */
    val motionPreferenceFlow: Flow<String?> get() = flowOf(null)
    suspend fun setMotionPreference(style: String?) {}

    /** 系统预测性返回手势（侧滑实时预览上一页），默认开启。仅在支持该能力的平台展示，保存在当前设备。 */
    val predictiveBackFlow: Flow<Boolean> get() = flowOf(true)
    suspend fun setPredictiveBack(enabled: Boolean) {}

    /** 同时下载文件数上限（1..8，默认 3）。仅保存在当前设备，不参与跨设备同步。 */
    val maxConcurrentDownloadsFlow: Flow<Int> get() = flowOf(DownloadConcurrencyDefaults.DEFAULT)
    suspend fun setMaxConcurrentDownloads(count: Int) {}

    /** 播放器四态自定义布局、听音频与手势配置。跟着设置同步。 */
    val playerLayoutPrefsFlow: Flow<PlayerLayoutPrefs> get() = flowOf(PlayerLayoutPrefs.Default)
    suspend fun setPlayerLayoutPrefs(prefs: PlayerLayoutPrefs) {}

    /** 播放器字幕外观偏好（底色、字体、字号、纵向位置与 ASS 覆盖）。跟着设置同步。 */
    val subtitleStylePrefsFlow: Flow<SubtitleStylePrefs> get() = flowOf(SubtitleStylePrefs.DEFAULT)
    suspend fun setSubtitleStylePrefs(prefs: SubtitleStylePrefs) {}

    /** 网盘三视图（列表 / 网格 / 瀑布流）文件外观自定义配置。跟着设置同步。 */
    val driveAppearancePrefsFlow: Flow<DriveAppearancePrefs> get() = flowOf(DriveAppearancePrefs.DEFAULT)
    suspend fun setDriveAppearancePrefs(prefs: DriveAppearancePrefs) {}

    /** 退出瀑布流与信息流时跳过确认弹窗直接退出，默认关闭。跨设备同步。 */
    val skipFeedExitConfirmFlow: Flow<Boolean> get() = flowOf(false)
    suspend fun setSkipFeedExitConfirm(skip: Boolean) {}

    /** 本地缓存容量上限（字节；0 表示无限制）。仅保存在当前设备，不同步。 */
    val cacheMaxBytesFlow: Flow<Long> get() = flowOf(CachePolicyDefaults.DEFAULT_MAX_BYTES)
    suspend fun setCacheMaxBytes(bytes: Long) {}

    /** 本地缓存过期自动清理天数（0 表示永不自动按时间清理）。仅保存在当前设备，不同步。 */
    val cacheMaxAgeDaysFlow: Flow<Int> get() = flowOf(CachePolicyDefaults.DEFAULT_MAX_AGE_DAYS)
    suspend fun setCacheMaxAgeDays(days: Int) {}

    /** 在线播放时将视频分块持久化到磁盘供复播复用，默认开启。仅保存在当前设备，不同步。 */
    val playbackCacheEnabledFlow: Flow<Boolean> get() = flowOf(true)
    suspend fun setPlaybackCacheEnabled(enabled: Boolean) {}

    /** 打开视频时自动续播上次进度，默认开启；关闭后每次从头播放。跨设备同步。 */
    val resumePlaybackEnabledFlow: Flow<Boolean> get() = flowOf(true)
    suspend fun setResumePlaybackEnabled(enabled: Boolean) {}

    /**
     * 网盘页的目录图（FolderMap）开着与钉着，默认都否。两项各自独立：× 关掉不改钉住，再打开照旧钉着；
     * 没钉住的目录图停在边沿时不用就收成把手。每台设备各自的，不同步：目录图只在宽窗口里有，
     * 桌面上钉着它的人未必在另一台机器上也想钉着。
     */
    val folderMapOpenFlow: Flow<Boolean>
    suspend fun setFolderMapOpen(open: Boolean)
    val folderMapPinnedFlow: Flow<Boolean>
    suspend fun setFolderMapPinned(pinned: Boolean)

    /** 开屏提示里点了「忽略此版本」的版本号。只比相等，更新的版本出来照常提示。 */
    suspend fun getIgnoredUpdateVersion(): String?
    suspend fun setIgnoredUpdateVersion(version: String)

    /**
     * 用户自己部署的 MetaTube 服务地址，如「http://192.168.1.2:8080」。空串表示不用刮削，界面上不出现任何刮削入口。
     * 按账号隔离存储，跨设备经 MetaTubeSync 加密同步，不进入普通设置同步文件。
     */
    val metaTubeUrlFlow: Flow<String>
    suspend fun setMetaTubeUrl(url: String)
    fun metaTubeUrlFlowFor(account: String): Flow<String> =
        if (account.isBlank()) flowOf("") else metaTubeUrlFlow
    suspend fun setMetaTubeUrlFor(account: String, url: String) {
        if (account.isNotBlank()) setMetaTubeUrl(url)
    }

    /** MetaTube 的访问令牌（服务端的 TOKEN）。机密，按账号由平台加密存放，跨设备仅经 MetaTubeSync 加密同步。空串表示没填。 */
    val metaTubeTokenFlow: Flow<String>
    suspend fun setMetaTubeToken(token: String)

    /**
     * 作品详情页顶部是否自动播放预览片（播这部片自己的画面），默认开。
     * 关掉后顶部只显示封面，点底部「播放」才进播放器——省流量、省网盘连接。
     */
    val posterAutoPreviewFlow: Flow<Boolean>
    suspend fun setPosterAutoPreviewEnabled(enabled: Boolean)

    /**
     * 详情页预览片最高用多少画面高度（1080/720/480），0 表示自动（原画）。
     * 预览只是看一眼画面，降档能明显省流量与解码开销。
     */
    val posterPreviewMaxHeightFlow: Flow<Int>
    suspend fun setPosterPreviewMaxHeight(height: Int)

    /**
     * AI 翻译用的接口地址，OpenAI 兼容（`/chat/completions`），如 `https://api.deepseek.com/v1`。
     * 空串表示没配，界面上翻译入口置灰。每台设备各自的：本地模型与中转站的地址不通用。
     *
     * 1.3.11 起改由「档案」承载（见 [translationProfilesFlow]）：地址、Key、模型合成一套档案，
     * 这套旧字段只留作升级迁移的源，之后不再单独使用。
     */
    val translationBaseUrlFlow: Flow<String>
    suspend fun setTranslationBaseUrl(url: String)

    /** 翻译用的 API Key。机密，由平台加密存放，不进设置同步。空串表示没填。迁移源，见上。 */
    val translationApiKeyFlow: Flow<String>
    suspend fun setTranslationApiKey(key: String)

    /** 模型名，如 `deepseek-chat`、`gpt-4o-mini`。空串表示没填。迁移源，见上。 */
    val translationModelFlow: Flow<String>
    suspend fun setTranslationModel(model: String)

    /**
     * AI 翻译档案列表的 JSON（含密钥，平台层加密落盘）。空串表示没配过。
     * 升级时由旧三件套迁来第一个档案，见 `TranslationProfileStore`。
     */
    val translationProfilesFlow: Flow<String>
    suspend fun setTranslationProfiles(json: String)

    /** 当前激活的档案 id；空串表示用列表里第一个。 */
    val translationActiveProfileFlow: Flow<String>
    suspend fun setTranslationActiveProfile(id: String)

    /**
     * 推理强度：空串表示关（不传参数，最快），或 `low` / `medium` / `high`。
     * 只对认这个参数的服务生效；不认的会在第一次被拒后自动去掉。
     */
    val translationReasoningFlow: Flow<String>
    suspend fun setTranslationReasoning(level: String)

    /**
     * 字幕翻译的自定义提示词。空串表示用内置默认（中日文影视字幕那一套）。
     * 片名翻译不受这里影响——两者要的规矩不一样。
     */
    val translationPromptFlow: Flow<String>
    suspend fun setTranslationPrompt(prompt: String)

    /**
     * 同时发出的翻译请求数。0 表示默认（[DEFAULT_TRANSLATION_CONCURRENCY]）。
     * 调高换速度，代价是更容易被服务商限流（429）——限流本身有退避重试，不会失败，只是会变慢。
     */
    val translationConcurrencyFlow: Flow<Int>
    suspend fun setTranslationConcurrency(count: Int)

    fun metaTubeTokenFlowFor(account: String): Flow<String> =
        if (account.isBlank()) flowOf("") else metaTubeTokenFlow
    suspend fun setMetaTubeTokenFor(account: String, token: String) {
        if (account.isNotBlank()) setMetaTubeToken(token)
    }
}

/** 没选过并发时的默认值：比早期的 3/4 翻倍，又不至于把免费额度打到限流。 */
const val DEFAULT_TRANSLATION_CONCURRENCY = 6
