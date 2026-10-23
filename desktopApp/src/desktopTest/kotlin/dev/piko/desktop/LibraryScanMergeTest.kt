package dev.piko.desktop

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.piko.library.Library
import dev.piko.shared.library.LibraryDriverFactory
import dev.piko.shared.library.LibraryStore
import dev.piko.shared.library.ScannedWork
import dev.piko.shared.library.ScannedWorkFile
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * 重扫不能丢东西。
 *
 * 以前 [LibraryStore.saveScan] 是「整库清空再重建」，用户每点一次重新扫描，刮削到的标题、
 * 封面、译名就全没了。现在改成增量：只补新作品、删这一轮没有的。
 *
 * 这里钉住三件事：作品数只增不减（同一批文件重扫）、已刮的元数据留着、译名留着。
 */
class LibraryScanMergeTest {

    private fun store(): LibraryStore {
        val factory = object : LibraryDriverFactory {
            override fun create(account: String): SqlDriver =
                JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { Library.Schema.create(it) }
        }
        return LibraryStore(factory) { "test" }
    }

    private fun work(code: String, vararg names: String) = ScannedWork(
        code = code,
        files = names.mapIndexed { i, name ->
            ScannedWorkFile(
                fileId = "$code-$i",
                name = name,
                size = 1024L,
                folderId = "folder",
                part = null,
            )
        },
    )

    @Test
    fun rescanKeepsScrapedMetadataAndTitleTranslation(): Unit = runBlocking {
        val store = store()
        store.saveScan("root", "根目录", listOf(work("SSIS-001", "SSIS-001.mp4"), work("ABC-123", "ABC-123.mp4")))

        store.markScraped(
            code = "SSIS-001",
            title = "オリジナルタイトル",
            coverUrl = "https://meta.example/v1/images/primary/JavBus/SSIS-001",
            releaseDate = "2021-01-01",
            durationMinutes = 120,
            summary = "あらすじ",
            studio = "S1",
            series = null,
            provider = "metatube",
            mtProvider = "JavBus",
            mtId = "SSIS-001",
            previewVideoUrl = null,
            previewImages = emptyList(),
            actors = listOf("葵つかさ"),
            tags = listOf("単体作品"),
        )
        store.setTitleZh("SSIS-001", "葵司的某部作品")

        // 同一批文件再扫一次：元数据与译名都必须还在
        store.saveScan("root", "根目录", listOf(work("SSIS-001", "SSIS-001.mp4"), work("ABC-123", "ABC-123.mp4")))

        val detail = assertNotNull(store.workDetail("SSIS-001"))
        assertEquals("オリジナルタイトル", detail.work.title)
        assertEquals("葵司的某部作品", detail.work.titleZh)
        assertEquals("https://meta.example/v1/images/primary/JavBus/SSIS-001", detail.work.coverUrl)
        assertEquals(120, detail.work.durationMinutes)
        assertEquals(1, detail.actors.size)
        assertEquals("葵つかさ", detail.actors.first().name)
    }

    @Test
    fun rescanAddsNewWorksAndDropsRemovedOnes(): Unit = runBlocking {
        val store = store()
        store.saveScan("root", "根目录", listOf(work("ABC-123", "ABC-123.mp4")))
        assertEquals(1L, store.workCount())

        // 多了一部、少了一部
        store.saveScan("root", "根目录", listOf(work("XYZ-999", "XYZ-999.mp4")))
        assertEquals(1L, store.workCount())

        val removed = store.workDetail("ABC-123")
        assertEquals(null, removed)
        assertNotNull(store.workDetail("XYZ-999"))
    }

    @Test
    fun incompleteScanNeverRemovesWorks(): Unit = runBlocking {
        val store = store()
        store.saveScan(
            "root", "根目录",
            listOf(work("AAA-001", "AAA-001.mp4"), work("BBB-002", "BBB-002.mp4"), work("CCC-003", "CCC-003.mp4")),
        )
        assertEquals(3L, store.workCount())

        // 只扫到一部就停了（限流 / 用户点了停止）：不能把另外两部当「已从网盘删除」
        store.saveScan("root", "根目录", listOf(work("AAA-001", "AAA-001.mp4")), complete = false)

        assertEquals(3L, store.workCount())
        assertNotNull(store.workDetail("BBB-002"))
        assertNotNull(store.workDetail("CCC-003"))
    }

    @Test
    fun completeScanDropsDeletedWorksAndTheirFavorites(): Unit = runBlocking {
        val store = store()
        store.saveScan("root", "根目录", listOf(work("AAA-001", "AAA-001.mp4"), work("BBB-002", "BBB-002.mp4")))
        store.toggleWorkFavorite("BBB-002")
        assertEquals(1, store.works(actor = null, tag = null, favoriteOnly = true).size)

        // 网盘里把 BBB-002 删了，完整重扫：作品与它的收藏记录一起消失
        store.saveScan("root", "根目录", listOf(work("AAA-001", "AAA-001.mp4")))

        assertEquals(1L, store.workCount())
        assertEquals(null, store.workDetail("BBB-002"))
        assertEquals(0, store.works(actor = null, tag = null, favoriteOnly = true).size)
    }

    @Test
    fun scanWithManyWorksKeepsAllOfThem1500(): Unit = runBlocking {
        // 「1200 个文件只看到 800 部」那类问题：入库后条数必须与传入的一致，
        // 不能被 NOT IN 参数绑定之类的问题悄悄吃掉
        val store = store()
        val works = (1..1500).map { work("CODE-$it", "CODE-$it.mp4") }
        store.saveScan("root", "根目录", works)
        assertEquals(1500L, store.workCount())

        // 再来一轮，数量不变
        store.saveScan("root", "根目录", works)
        assertEquals(1500L, store.workCount())
    }
}
