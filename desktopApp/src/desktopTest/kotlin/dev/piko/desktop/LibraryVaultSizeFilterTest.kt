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
 * 引用视频按体积过滤：播不了的那批先藏起来。
 *
 * 钉住四件事：按单集大小判（不是整部作品的总和）、同番号有真实版本的不藏、
 * 被藏的要能从计数里说清楚、阈值放宽后自己回来。
 */
class LibraryVaultSizeFilterTest {

    private val gb = 1024L * 1024L * 1024L

    private fun store(): LibraryStore {
        val factory = object : LibraryDriverFactory {
            override fun create(account: String): SqlDriver =
                JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { Library.Schema.create(it) }
        }
        return LibraryStore(factory) { "test" }
    }

    private fun work(code: String, size: Long) = ScannedWork(
        code = code,
        files = listOf(ScannedWorkFile("$code-0", "$code.mp4", size, "folder", null)),
    )

    private fun vaultWork(code: String, size: Long) = ScannedWork(
        code = code,
        files = listOf(
            ScannedWorkFile(
                // 条目 ID 里带着 gcid 与大小，见 VaultEntry.entryIdOf
                fileId = "piko-vault:entry-$code/GCID$code/$size/$code.mp4",
                name = "$code.mp4",
                size = size,
                folderId = "archive-folder",
                part = null,
            ),
        ),
    )

    @Test
    fun oversizedReferencesAreHiddenFromList(): Unit = runBlocking {
        val store = store()
        store.saveVaultWorks(
            listOf(vaultWork("BIG-001", 7 * gb), vaultWork("FIT-002", 5 * gb), vaultWork("EDGE-003", 6 * gb)),
        )

        val rows = store.works(actor = null, tag = null, maxVaultFileBytes = 6 * gb)
        assertEquals(listOf("EDGE-003", "FIT-002"), rows.map { it.code }.sorted())
        assertEquals(1L, store.hiddenVaultWorkCount(6 * gb))

        // 不过滤时三部都在
        assertEquals(3, store.works(actor = null, tag = null).size)
        assertEquals(0L, store.hiddenVaultWorkCount(100 * gb))
    }

    @Test
    fun scannedVersionKeepsTheWorkVisible(): Unit = runBlocking {
        val store = store()
        // 同番号：网盘里有真实文件，又引用保存了一份大体积的破解版
        store.saveScan("root", "根目录", listOf(work("ABC-123", 10 * gb)))
        store.saveVaultWorks(listOf(vaultWork("ABC-123", 7 * gb)))

        // 真实版本能在云端直接用，不需要临时空间：按引用那版的大小藏掉它没有道理
        assertEquals(listOf("ABC-123"), store.works(actor = null, tag = null, maxVaultFileBytes = 6 * gb).map { it.code })
        assertEquals(0L, store.hiddenVaultWorkCount(6 * gb))
    }

    @Test
    fun multiFileWorkUsesTheLargestEpisode(): Unit = runBlocking {
        val store = store()
        // 分成两集，各 4 GB：总大小 8 GB 超过阈值，但单集 4 GB 装得下，照样能播
        store.saveVaultWorks(
            listOf(
                ScannedWork(
                    code = "TWO-001",
                    files = (0..1).map {
                        ScannedWorkFile("piko-vault:entry-TWO-001-$it/GCID/TWO-001-0$it/$it", "TWO-001-$it.mp4", 4 * gb, "archive-folder", null)
                    },
                ),
            ),
        )

        assertTrue(store.works(actor = null, tag = null, maxVaultFileBytes = 6 * gb).any { it.code == "TWO-001" })
    }

    @Test
    fun autoLimitFollowsRemainingSpace() {
        // 自动模式取剩余空间的九成，与 SDK 借出对象时的预算一致
        assertEquals(90 * gb, dev.piko.data.auth.VaultSizeLimit.limitBytes(dev.piko.data.auth.VaultSizeFilter.AUTO, 6, 100 * gb))
        // 配额取不到时不过滤：宁可多显示，也别让人以为片子丢了
        assertEquals(null, dev.piko.data.auth.VaultSizeLimit.limitBytes(dev.piko.data.auth.VaultSizeFilter.AUTO, 6, null))
        assertEquals(null, dev.piko.data.auth.VaultSizeLimit.limitBytes(dev.piko.data.auth.VaultSizeFilter.AUTO, 6, 0))
        assertEquals(6 * gb, dev.piko.data.auth.VaultSizeLimit.limitBytes(dev.piko.data.auth.VaultSizeFilter.MANUAL, 6, 100 * gb))
        assertEquals(null, dev.piko.data.auth.VaultSizeLimit.limitBytes(dev.piko.data.auth.VaultSizeFilter.OFF, 6, 100 * gb))
    }

    @Test
    fun hiddenScrapedCountMatchesTheList(): Unit = runBlocking {
        val store = store()
        store.saveVaultWorks(listOf(vaultWork("BIG-001", 7 * gb), vaultWork("FIT-002", 5 * gb)))
        store.markScraped(
            code = "BIG-001",
            title = "大片",
            coverUrl = null,
            releaseDate = null,
            durationMinutes = null,
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

        // 统计行要减掉被藏的那批，否则「已刮削 1」而列表里一部都看不见
        assertEquals(1L, store.scrapedWorkCount())
        assertEquals(1L, store.hiddenScrapedVaultWorkCount(6 * gb))
        assertEquals(1L, store.hiddenVaultWorkCount(6 * gb))
    }
}
