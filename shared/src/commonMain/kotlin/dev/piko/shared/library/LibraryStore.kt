package dev.piko.shared.library

import app.cash.sqldelight.db.SqlDriver
import dev.piko.library.LibraryQueries
import dev.piko.shared.naming.av.matchAvFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** 每个账号一个数据库文件，由各平台实现：Android 用 AndroidSqliteDriver，桌面用 JdbcSqliteDriver。 */
interface LibraryDriverFactory {
    fun create(account: String): SqlDriver
}

/** 海报墙索引进库时的一部作品：番号加它名下的视频文件（元数据还没刮）。 */
data class ScannedWork(
    val code: String,
    val files: List<ScannedWorkFile>,
)

data class ScannedWorkFile(
    val fileId: String,
    val name: String,
    val size: Long,
    val folderId: String,
    val part: String?,
)

/** 列表里的一行。 */
data class WorkRow(
    val code: String,
    /** 刮削来的原始标题（多为日文）。翻译与手改都不动它。 */
    val title: String?,
    /** 中文标题：AI 翻译或手动修改的结果。空表示还没译过。 */
    val titleZh: String? = null,
    val coverUrl: String?,
    val releaseDate: String?,
    /** 时长（分钟），刮到了才有。 */
    val durationMinutes: Int?,
    val scraped: Boolean,
    val fileCount: Int,
    val totalSize: Long,
    /** 海报卡左下角显示的女优名（有多个时取排序第一个），没有演员时为 null。 */
    val firstActor: String? = null,
) {
    /** 界面统一用这个：有译名显示译名，没有就退回原文。 */
    val displayTitle: String? get() = titleZh?.takeIf { it.isNotBlank() } ?: title
}

data class WorkDetail(
    val work: WorkRow,
    val files: List<ScannedWorkFile>,
    val actors: List<NamedFavorite>,
    val tags: List<NamedFavorite>,
    val studio: String?,
    val series: String?,
    val summary: String?,
    /** 详情页截图墙，第一张是封面；没有时为空表，界面不显示这一段。 */
    val images: List<String> = emptyList(),
    /** 预览片地址（几十秒的样片），详情页顶部自动播放；没有时顶部显示封面。 */
    val previewVideoUrl: String? = null,
)

/** 详情页里的一个演员或标签，带收藏标志（长按切换）。 */
data class NamedFavorite(val name: String, val favorite: Boolean)

data class NamedCount(
    val name: String,
    val count: Long,
    /** 收藏置顶：筛选列表里排在前面并带星标。 */
    val favorite: Boolean = false,
)

data class ScanStateRow(
    val rootFolderId: String?,
    val rootFolderName: String?,
    val lastScanAt: Long?,
    val workCount: Long,
    val scanning: Boolean,
)

/**
 * 海报墙的本地索引。按账号各一个 SQLite 文件（[LibraryDriverFactory]），换号后读写的是新账号的库；
 * 驱动开着不关：一个账号一份，切回去照常用。
 *
 * 写入口只两个：[saveScan]（整轮扫描后整库替换）与 [markScraped]（刮削到一部补一部）。
 * 读入口给状态 holder 投影列表与筛选用。
 */
class LibraryStore(
    private val driverFactory: LibraryDriverFactory,
    private val accountProvider: () -> String?,
) {
    private val lock = Mutex()
    private val drivers = mutableMapOf<String, SqlDriver>()
    private val queries = mutableMapOf<String, LibraryQueries>()

    private fun queriesOf(account: String): LibraryQueries = queries.getOrPut(account) {
        val driver = drivers.getOrPut(account) { driverFactory.create(account) }
        LibraryQueries(driver)
    }

    /** 没登录时没有库，所有操作整体跳过。 */
    private suspend fun <T> withQueries(block: (LibraryQueries) -> T): T? {
        val account = accountProvider() ?: return null
        val q = lock.withLock { queriesOf(account) }
        return withContext(Dispatchers.IO) { block(q) }
    }

    suspend fun scanState(): ScanStateRow? = withQueries { q ->
        q.selectScanState().executeAsOneOrNull()?.let {
            ScanStateRow(
                rootFolderId = it.root_folder_id,
                rootFolderName = it.root_folder_name,
                lastScanAt = it.last_scan_at,
                workCount = it.work_count,
                scanning = it.scanning == 1L,
            )
        }
    }

    /**
     * 整轮扫描的结果整库替换：先清空旧作品与文件，再写入这一轮的。刮削到的元数据随清空丢失，
     * 下一轮 enrichment 重新刮（作品没变就还在库里，[codesNeedingScrape] 只补没刮过的）。
     * 收藏不在 clears 之列：换一轮扫描不该丢用户标过的收藏。
     */
    /**
     * 一轮扫描的结果入库。
     *
     * [complete] 为 true 才删「这一轮没扫到的作品」——网盘里删了视频、重扫后海报墙会跟着少一部，
     * 靠的就是这一步。中途停止或提前结束（限流、出错）时传 false：只补新的、更新扫到的，
     * 一份都不删，否则半份扫描结果会把没扫到的作品全判成"已删除"。
     */
    suspend fun saveScan(
        rootFolderId: String?,
        rootFolderName: String?,
        works: List<ScannedWork>,
        complete: Boolean = true,
    ) = withQueries { q ->
        q.transactionWithResult {
            val codes = works.map { it.code }
            if (complete) {
                // 只有文件是这一轮的真实结果，整表重建；
                // 演员、标签、截图、标题、封面、译名都是刮削来的，重扫时一律不动
                q.clearWorkFiles()
                // 差集分批删：一次 NOT IN 上千个番号会超过 Android SQLite 的 999 变量上限，
                // 抛异常后整个事务回滚，用户看到的就是"扫完了还是旧数量"
                val keep = codes.toHashSet()
                q.allWorkCodes().executeAsList()
                    .filterNot { it in keep }
                    .chunked(DELETE_CHUNK)
                    .forEach { chunk -> q.deleteWorksIn(chunk) }
            }
            works.forEach { work ->
                q.insertWorkIfAbsent(work.code)
                // 没扫全时只重建这一部（它被这一轮看到了），其余作品的旧文件记录留着
                if (!complete) q.deleteWorkFiles(work.code)
                work.files.forEach { f ->
                    q.insertWorkFile(f.fileId, work.code, f.name, f.size, f.folderId, f.part)
                }
            }
            // 作品没了，它的演员/标签/截图/收藏记录才是孤儿；还在的作品的必须留着
            q.deleteOrphanActors()
            q.deleteOrphanTags()
            q.deleteOrphanImages()
            q.deleteOrphanFavoriteWorks()
            val now = System.currentTimeMillis()
            // 用库里的真实条数：没扫全时它比 works.size 大
            q.setScanState(rootFolderId, rootFolderName, now, q.selectWorkCount().executeAsOne(), 0L)
        }
        Unit
    }

    suspend fun workCount(): Long = withQueries { q -> q.selectWorkCount().executeAsOne() } ?: 0L

    /** 已刮到元数据的作品数；与 [workCount] 的差就是还没刮到的（含认不出番号的）。 */
    suspend fun scrapedWorkCount(): Long = withQueries { q -> q.selectScrapedCount().executeAsOne() } ?: 0L

    /** 扫描进行中/结束落库，进程被杀后下次进来也知道上一轮有没有扫完。 */
    suspend fun setScanning(scanning: Boolean) = withQueries { q ->
        val state = q.selectScanState().executeAsOneOrNull()
        q.setScanState(
            state?.root_folder_id,
            state?.root_folder_name,
            state?.last_scan_at,
            state?.work_count ?: 0L,
            if (scanning) 1L else 0L,
        )
        Unit
    }

    /**
     * 还没刮到元数据的番号（刮削失败的也在这里，下次补）。
     * 包含「刮到了页面但封面为空」的作品：源当时没解析出封面，或换源后要重试，
     * 下一轮 enrich 会把它们再刮一遍，封面不会永远空着。
     */
    suspend fun codesNeedingScrape(): List<String> = withQueries { q ->
        q.codesNeedingScrape().executeAsList()
    }.orEmpty().filter { matchAvFile(it) != null }

    suspend fun markScraped(
        code: String,
        title: String?,
        coverUrl: String?,
        releaseDate: String?,
        durationMinutes: Int?,
        summary: String?,
        studio: String?,
        series: String?,
        provider: String,
        mtProvider: String?,
        mtId: String?,
        previewVideoUrl: String?,
        previewImages: List<String>,
        actors: List<String>,
        tags: List<String>,
    ) = withQueries { q ->
        q.transactionWithResult {
            q.markWorkScraped(
                title, coverUrl, releaseDate, durationMinutes?.toLong(), summary, studio, series,
                provider, mtProvider, mtId, previewVideoUrl, System.currentTimeMillis(), code,
            )
            q.deleteWorkActorsFor(code)
            q.deleteWorkTagsFor(code)
            q.deleteWorkImagesFor(code)
            actors.forEach { q.insertWorkActor(code, it) }
            tags.forEach { q.insertWorkTag(code, it) }
            // position 就是展示顺序：0 是封面，其后是片段截图
            previewImages.forEachIndexed { index, url -> q.insertWorkImage(code, url, index.toLong()) }
        }
        Unit
    }

    suspend fun works(
        actor: String?,
        tag: String?,
        favoriteOnly: Boolean = false,
        /** 搜索词：番号、标题、演员、标签任一处包含就留下；空白表示不过滤。 */
        query: String? = null,
    ): List<WorkRow> = withQueries { q ->
        val counts = q.selectFileCounts().executeAsList().associate { it.work_code to (it.count to it.total) }
        // 卡片左下角的女优名：一次批量取出，不逐行查
        val actors = q.selectFirstActors().executeAsList().associate { it.work_code to it.actor }
        // 搜索先用一条 UNION 查出候选番号，再在内存里过滤（数据量是几百条，比逐个 LIKE 简单）
        val matched = query?.trim()?.takeIf { it.isNotEmpty() }?.let { text ->
            q.searchCodes("%$text%").executeAsList().toSet()
        }
        fun List<WorkRow>.applyQuery(): List<WorkRow> =
            if (matched == null) this else filter { it.code in matched }
        // 四个查询各生成一个同构不同名的数据类，公共类型是 Any；逐个映射，不靠 when 推断
        when {
            favoriteOnly -> q.selectFavoriteWorks().executeAsList()
                .map { it.toRow(counts, actors) }
            actor != null -> q.selectWorksByActor(actor).executeAsList()
                .map { it.toRow(counts, actors) }
            tag != null -> q.selectWorksByTag(tag).executeAsList()
                .map { it.toRow(counts, actors) }
            else -> q.selectAllWorks().executeAsList()
                .map { it.toRow(counts, actors) }
        }.applyQuery()
    }.orEmpty()

    /** 列表查询的同构投影：五个字段一张表里取，文件数与总体积走 selectFileCounts，女优名走 selectFirstActors。 */
    private fun dev.piko.library.SelectAllWorks.toRow(
        counts: Map<String, Pair<Long, Long?>>,
        actors: Map<String, String?>,
    ): WorkRow = WorkRow(
        code = code, title = title, titleZh = title_zh, coverUrl = cover_url,
        releaseDate = release_date,
        durationMinutes = duration?.toInt(), scraped = scraped_at > 0L,
        fileCount = fileCountOf(counts, code), totalSize = totalSizeOf(counts, code),
        firstActor = actors[code],
    )

    private fun dev.piko.library.SelectFavoriteWorks.toRow(
        counts: Map<String, Pair<Long, Long?>>,
        actors: Map<String, String?>,
    ): WorkRow = WorkRow(
        code = code, title = title, titleZh = title_zh, coverUrl = cover_url,
        releaseDate = release_date,
        durationMinutes = duration?.toInt(), scraped = scraped_at > 0L,
        fileCount = fileCountOf(counts, code), totalSize = totalSizeOf(counts, code),
        firstActor = actors[code],
    )

    private fun dev.piko.library.SelectWorksByActor.toRow(
        counts: Map<String, Pair<Long, Long?>>,
        actors: Map<String, String?>,
    ): WorkRow = WorkRow(
        code = code, title = title, titleZh = title_zh, coverUrl = cover_url,
        releaseDate = release_date,
        durationMinutes = duration?.toInt(), scraped = scraped_at > 0L,
        fileCount = fileCountOf(counts, code), totalSize = totalSizeOf(counts, code),
        firstActor = actors[code],
    )

    private fun dev.piko.library.SelectWorksByTag.toRow(
        counts: Map<String, Pair<Long, Long?>>,
        actors: Map<String, String?>,
    ): WorkRow = WorkRow(
        code = code, title = title, titleZh = title_zh, coverUrl = cover_url,
        releaseDate = release_date,
        durationMinutes = duration?.toInt(), scraped = scraped_at > 0L,
        fileCount = fileCountOf(counts, code), totalSize = totalSizeOf(counts, code),
        firstActor = actors[code],
    )

    /** 分批删除时每批的番号个数：远低于 SQLite 的 999 变量上限，留足余量。 */
    private companion object {
        const val DELETE_CHUNK = 400
    }

    private fun fileCountOf(counts: Map<String, Pair<Long, Long?>>, code: String): Int =
        counts[code]?.first?.toInt() ?: 0

    private fun totalSizeOf(counts: Map<String, Pair<Long, Long?>>, code: String): Long =
        counts[code]?.second ?: 0L

    suspend fun workDetail(code: String): WorkDetail? = withQueries { q ->
        val w = q.selectWork(code).executeAsOneOrNull() ?: return@withQueries null
        val files = q.selectFilesForWork(code).executeAsList().map {
            ScannedWorkFile(it.file_id, it.name, it.size, it.folder_id, it.part)
        }
        val counts = q.selectFileCounts().executeAsList().firstOrNull { it.work_code == code }
        WorkDetail(
            work = WorkRow(
                code = w.code,
                title = w.title,
                titleZh = w.title_zh,
                coverUrl = w.cover_url,
                releaseDate = w.release_date,
                durationMinutes = w.duration?.toInt(),
                scraped = w.scraped_at > 0L,
                fileCount = counts?.count?.toInt() ?: files.size,
                totalSize = counts?.total ?: files.sumOf { it.size },
            ),
            files = files,
            actors = q.selectActorsForWork(code).executeAsList().map { NamedFavorite(it.name, it.favorite == 1L) },
            tags = q.selectTagsForWork(code).executeAsList().map { NamedFavorite(it.name, it.favorite == 1L) },
            studio = w.studio,
            series = w.series,
            summary = w.summary,
            images = q.selectImagesForWork(code).executeAsList(),
            previewVideoUrl = w.preview_video_url,
        )
    }

    // ---------------------------------------------------------------- 标题翻译

    /** 写入中文标题；[titleZh] 传空串表示清掉译名、恢复显示原文。 */
    suspend fun setTitleZh(code: String, titleZh: String?) = withQueries { q ->
        q.setWorkTitleZh(titleZh?.trim()?.takeIf { it.isNotEmpty() }, code)
        Unit
    }

    /**
     * 一次事务写入多条译名。批量翻片名时每条开一次事务太慢——
     * 800 部就是 800 次 fsync，比网络请求还费时间。
     */
    suspend fun setTitleZhBatch(items: List<Pair<String, String>>) = withQueries { q ->
        if (items.isNotEmpty()) {
            q.transaction {
                items.forEach { (code, titleZh) ->
                    q.setWorkTitleZh(titleZh.trim().takeIf { it.isNotEmpty() }, code)
                }
            }
        }
        Unit
    }

    /** 还缺译名的作品数（有原文、没译名）。 */
    suspend fun titleTranslationPendingCount(): Long = withQueries { q ->
        q.selectCodesNeedingTitleTranslation().executeAsList().size.toLong()
    } ?: 0L

    /** 待译清单：番号 → 原文标题。批量翻译时一次取走，省得逐条查库。 */
    suspend fun titlesNeedingTranslation(): List<Pair<String, String>> = withQueries { q ->
        q.selectTitlePairs().executeAsList().mapNotNull { row ->
            val title = row.title?.trim().orEmpty()
            if (title.isEmpty()) null else row.code to title
        }
    }.orEmpty()

    suspend fun actors(): List<NamedCount> = withQueries { q ->
        q.selectActors().executeAsList().map { NamedCount(it.name, it.count, it.favorite == 1L) }
    }.orEmpty()

    suspend fun tags(): List<NamedCount> = withQueries { q ->
        q.selectTags().executeAsList().map { NamedCount(it.name, it.count, it.favorite == 1L) }
    }.orEmpty()

    // ---------------------------------------------------------------- 收藏

    /** 作品收藏：返回收藏后的新状态（true = 已收藏）。 */
    suspend fun toggleWorkFavorite(code: String): Boolean = withQueries { q ->
        val exists = q.selectFavoriteWorkCodes().executeAsList().any { it == code }
        if (exists) q.deleteFavoriteWork(code) else q.insertFavoriteWork(code, System.currentTimeMillis())
        !exists
    } ?: false

    suspend fun isWorkFavorite(code: String): Boolean = withQueries { q ->
        q.selectFavoriteWorkCodes().executeAsList().any { it == code }
    } ?: false

    /** 演员收藏：返回收藏后的新状态。 */
    suspend fun toggleActorFavorite(name: String): Boolean = withQueries { q ->
        val exists = q.selectFavoriteActorNames().executeAsList().any { it == name }
        if (exists) q.deleteFavoriteActor(name) else q.insertFavoriteActor(name, System.currentTimeMillis())
        !exists
    } ?: false

    /** 标签收藏：返回收藏后的新状态。 */
    suspend fun toggleTagFavorite(name: String): Boolean = withQueries { q ->
        val exists = q.selectFavoriteTagNames().executeAsList().any { it == name }
        if (exists) q.deleteFavoriteTag(name) else q.insertFavoriteTag(name, System.currentTimeMillis())
        !exists
    } ?: false

    // ---------------------------------------------------------------- 兼容修补

    /**
     * MetaTube 地址变了（换域名、换端口、http→https）：库里存的是拼好的图片与预览片
     * 完整地址，按旧 base 统一替换成新的，**不用重新刮削**。
     *
     * 旧 base 从库里任意一条封面地址反推（取 scheme://host:port），不额外存配置。
     */
    suspend fun rebuildMetaTubeBase(newBase: String) = withQueries { q ->
        val target = originOf(newBase)
        if (target.isEmpty()) return@withQueries Unit
        val sample = q.selectSampleCoverUrl().executeAsOneOrNull() ?: return@withQueries Unit
        val current = originOf(sample)
        if (current.isEmpty() || current == target) return@withQueries Unit
        q.transaction {
            q.rebuildCoverUrls(current, target)
            q.rebuildImageUrls(current, target)
            q.rebuildPreviewVideoUrls(current, target)
        }
        Unit
    }

    private fun originOf(url: String): String {
        val trimmed = url.trim()
        val scheme = when {
            trimmed.startsWith("https://", ignoreCase = true) -> "https"
            trimmed.startsWith("http://", ignoreCase = true) -> "http"
            else -> return ""
        }
        val host = trimmed.substringAfter("://").substringBefore('/')
        return if (host.isEmpty()) "" else "$scheme://$host"
    }
}

/**
 * 账号名净化成数据库文件名：账号是邮箱，@ 与点、以及各家账号名里的其他字符都不进文件名。
 * 两端（Android 的数据库目录、桌面的数据根目录）各自建库时调用，共用同一个规则，
 * 换账号时对得上。公开是给两端入口用的，业务代码不需要它。
 */
fun sanitizeAccount(account: String): String =
    account.map { if (it.isLetterOrDigit() || it == '-' || it == '_') it else '_' }
        .joinToString("")
        .take(64)
        .ifEmpty { "default" }
