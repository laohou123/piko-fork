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
import kotlin.test.assertTrue

/**
 * 引用保存的条目与扫描到的作品同住一张表，但各有各的重建时机。
 *
 * 钉住三件事：整轮重扫不碰引用条目、引用条目整批重建时留着刮削结果、
 * 同一个番号两边都有时只留扫描到的那份。
 */
class LibraryVaultMergeTest {

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

    private fun vaultWork(code: String, name: String, gcid: String = "GCID$code") = ScannedWork(
        code = code,
        files = listOf(
            ScannedWorkFile(
                fileId = "piko-vault:entry-$code/$gcid/1024/$name",
                name = name,
                size = 1024L,
                folderId = "archive-folder",
                part = null,
            ),
        ),
    )

    @Test
    fun rescanKeepsVaultEntries(): Unit = runBlocking {
        val store = store()
        store.saveScan("root", "根目录", listOf(work("SSIS-001", "SSIS-001.mp4")))
        store.saveVaultWorks(listOf(vaultWork("ABC-123", "ABC-123.mp4")))

        assertEquals(1L, store.workCount())
        assertEquals(1L, store.vaultWorkCount())

        // 整轮重扫：网盘里只有 SSIS-001，引用条目不该被当成「没扫到」
        store.saveScan("root", "根目录", listOf(work("SSIS-001", "SSIS-001.mp4")))

        assertEquals(1L, store.workCount())
        assertEquals(1L, store.vaultWorkCount())
        assertEquals(1, store.works(actor = null, tag = null, vault = true).size)
        assertEquals(listOf("SSIS-001"), store.works(actor = null, tag = null).map { it.code })
    }

    @Test
    fun rebuildKeepsScrapedMetadata(): Unit = runBlocking {
        val store = store()
        store.saveVaultWorks(listOf(vaultWork("ABC-123", "ABC-123.mp4")))
        store.markScraped(
            code = "ABC-123",
            title = "タイトル",
            coverUrl = "https://meta.example/cover.jpg",
            releaseDate = "2024-01-01",
            durationMinutes = 120,
            summary = null,
            studio = null,
            series = null,
            provider = "metatube",
            mtProvider = null,
            mtId = null,
            previewVideoUrl = null,
            previewImages = emptyList(),
            actors = emptyList(),
            tags = emptyList(),
        )

        // 下一轮扫描再次解析出同一部：不能把刮到的标题封面冲掉
        store.saveVaultWorks(listOf(vaultWork("ABC-123", "ABC-123.mp4")))

        val row = store.works(actor = null, tag = null, vault = true).single()
        assertEquals("タイトル", row.title)
        assertTrue(row.scraped)
        assertEquals(1L, store.vaultWorkCount())
    }

    @Test
    fun scanWinsWhenSameCode(): Unit = runBlocking {
        val store = store()
        store.saveScan("root", "根目录", listOf(work("ABC-123", "ABC-123.mp4")))
        store.saveVaultWorks(listOf(vaultWork("ABC-123", "ABC-123.mp4")))

        // 引用条目跳过，不抢占真实文件的作品
        assertEquals(1L, store.workCount())
        assertEquals(0L, store.vaultWorkCount())
        val row = store.works(actor = null, tag = null).single()
        assertEquals(1, row.fileCount)
    }

    @Test
    fun removedEntryDisappearsOnNextRebuild(): Unit = runBlocking {
        val store = store()
        store.saveVaultWorks(
            listOf(vaultWork("ABC-123", "ABC-123.mp4"), vaultWork("DEF-456", "DEF-456.mp4")),
        )
        assertEquals(2L, store.vaultWorkCount())

        store.saveVaultWorks(listOf(vaultWork("ABC-123", "ABC-123.mp4")))
        assertEquals(1L, store.vaultWorkCount())
        assertEquals(listOf("ABC-123"), store.works(actor = null, tag = null, vault = true).map { it.code })
    }
}
