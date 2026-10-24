package dev.piko.desktop

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.piko.library.Library
import dev.piko.shared.library.LibraryDriverFactory
import dev.piko.shared.library.LibraryStore
import dev.piko.shared.library.ScannedWork
import dev.piko.shared.library.ScannedWorkFile
import dev.piko.shared.library.SyncKind
import dev.piko.shared.library.SyncMark
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 译名与收藏经网盘在两台设备间合并：谁后改谁赢，各改各的都留下，
 * 取消收藏与清空译名也会传过去（不会被另一台又补回来）。
 */
class LibrarySyncMergeTest {

    private fun store(): LibraryStore {
        val factory = object : LibraryDriverFactory {
            override fun create(account: String): SqlDriver =
                JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { Library.Schema.create(it) }
        }
        return LibraryStore(factory) { "test" }
    }

    private suspend fun LibraryStore.addWork(
        code: String,
        title: String,
        actors: List<String> = emptyList(),
        tags: List<String> = emptyList(),
    ) {
        saveScan(
            rootFolderId = "folder",
            rootFolderName = "test",
            works = listOf(
                ScannedWork(
                    code = code,
                    files = listOf(ScannedWorkFile("$code-0", "$code.mp4", 1024L, "folder", null)),
                ),
            ),
        )
        markScraped(
            code = code, title = title, coverUrl = null, releaseDate = null, durationMinutes = null,
            summary = null, studio = null, series = null, provider = "metatube", mtProvider = null, mtId = null,
            previewVideoUrl = null, previewImages = emptyList(), actors = actors, tags = tags,
        )
    }

    @Test
    fun `a new device takes titles and favourites from the other one`() = runBlocking {
        val a = store()
        val b = store()
        // 两台设备扫的是同一个目录
        a.addWork("ABC-123", "原名一", actors = listOf("演员甲"), tags = listOf("标签甲"))
        b.addWork("ABC-123", "原名一", actors = listOf("演员甲"), tags = listOf("标签甲"))

        a.setTitleZh("ABC-123", "译名一")
        a.toggleWorkFavorite("ABC-123")
        a.toggleActorFavorite("演员甲")
        a.toggleTagFavorite("标签甲")

        assertTrue(b.applySyncMarks(a.exportSyncMarks()) >= 4)
        assertEquals("译名一", b.workDetail("ABC-123")?.work?.titleZh)
        assertTrue(b.isWorkFavorite("ABC-123"))
        assertTrue(b.actors().any { it.name == "演员甲" && it.favorite })
        assertTrue(b.tags().any { it.name == "标签甲" && it.favorite })
    }

    @Test
    fun `titles pulled before the first scan are filled in when the scan finishes`() = runBlocking {
        val a = store()
        val b = store()
        a.addWork("ABC-123", "原名一")
        a.setTitleZh("ABC-123", "译名一")

        // B 还没扫描就先拉了记录：此刻没有作品可写，记录留着
        b.applySyncMarks(a.exportSyncMarks())
        // 扫描完成后，作品入库时把记录里的译名补上
        b.addWork("ABC-123", "原名一")
        assertEquals("译名一", b.workDetail("ABC-123")?.work?.titleZh)
    }

    @Test
    fun `the later change wins and deletions travel too`() = runBlocking {
        val a = store()
        val b = store()
        a.addWork("ABC-123", "原名")
        b.addWork("ABC-123", "原名")
        a.setTitleZh("ABC-123", "旧译名")
        b.applySyncMarks(a.exportSyncMarks())
        assertEquals("旧译名", b.workDetail("ABC-123")?.work?.titleZh)

        // B 后改：A 再合并时拿到 B 的新名字
        b.setTitleZh("ABC-123", "新译名")
        a.applySyncMarks(b.exportSyncMarks())
        assertEquals("新译名", a.workDetail("ABC-123")?.work?.titleZh)

        // B 先收藏，A 同步后也有；再取消，A 跟着恢复
        b.toggleWorkFavorite("ABC-123")
        a.applySyncMarks(b.exportSyncMarks())
        assertTrue(a.isWorkFavorite("ABC-123"))
        b.toggleWorkFavorite("ABC-123")
        b.setTitleZh("ABC-123", null)
        a.applySyncMarks(b.exportSyncMarks())
        assertEquals(null, a.workDetail("ABC-123")?.work?.titleZh)
        assertTrue(!a.isWorkFavorite("ABC-123"))
    }

    @Test
    fun `an older record does not overwrite a newer one`() = runBlocking {
        val a = store()
        a.addWork("ABC-123", "原名")
        a.setTitleZh("ABC-123", "本机的新名")

        val stale = listOf(SyncMark(SyncKind.TITLE, "ABC-123", 1L, "很久以前的旧名", true))
        assertEquals(0, a.applySyncMarks(stale))
        assertEquals("本机的新名", assertNotNull(a.workDetail("ABC-123")).work.titleZh)
    }
}
