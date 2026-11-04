package dev.piko.desktop

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.piko.library.Library
import dev.piko.shared.library.LibraryDriverFactory
import dev.piko.shared.library.LibraryStore
import dev.piko.shared.library.ScannedWork
import dev.piko.shared.library.ScannedWorkFile
import io.github.nihildigit.pikpak.videoThumbnailUrlOf
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 刮不到封面时的兜底：网盘里的真实文件也要有缩略图。
 *
 * 之前只有引用条目能显示画面——它们的 gcid 写在归档清单里，随时能拼出 PikPak 缩略图；
 * 真实文件扫描时把 gcid 丢了，源站没有这个番号就只剩白卡。
 */
class LibraryCoverFallbackTest {

    private fun store(): LibraryStore {
        val factory = object : LibraryDriverFactory {
            override fun create(account: String): SqlDriver =
                JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { Library.Schema.create(it) }
        }
        return LibraryStore(factory) { "test" }
    }

    private fun work(code: String, gcid: String?) = ScannedWork(
        code = code,
        files = listOf(ScannedWorkFile("$code-file", "$code.mp4", 1024L, "root", null, gcid)),
    )

    @Test
    fun realFileWithoutScrapedCoverShowsThumbnail(): Unit = runBlocking {
        val store = store()
        store.saveScan("root", "根目录", listOf(work("ABC-123", "GCIDABC")))

        // 没刮过，cover_url 为空；列表与详情都该拿到 gcid 缩略图
        assertEquals(videoThumbnailUrlOf("GCIDABC"), store.works(null, null).single().coverUrl)
        assertEquals(videoThumbnailUrlOf("GCIDABC"), store.workDetail("ABC-123")!!.work.coverUrl)
    }

    @Test
    fun scrapedCoverIsNotReplaced(): Unit = runBlocking {
        val store = store()
        store.saveScan("root", "根目录", listOf(work("ABC-123", "GCIDABC")))
        store.markScraped(
            code = "ABC-123",
            title = "片名",
            coverUrl = "https://meta.example/cover.jpg",
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

        // 刮到的封面优先，gcid 只是兜底
        assertEquals("https://meta.example/cover.jpg", store.works(null, null).single().coverUrl)
        assertEquals("https://meta.example/cover.jpg", store.workDetail("ABC-123")!!.work.coverUrl)
    }

    @Test
    fun workWithoutGcidOrCoverStaysEmpty(): Unit = runBlocking {
        val store = store()
        // 老库升级后没重扫：gcid 为空，不该拼出半截地址
        store.saveScan("root", "根目录", listOf(work("ABC-123", null)))
        assertEquals(null, store.works(null, null).single().coverUrl)
        assertEquals(null, store.workDetail("ABC-123")!!.work.coverUrl)
    }
}
