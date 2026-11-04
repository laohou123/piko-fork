package dev.piko.desktop

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.piko.library.Library
import dev.piko.shared.library.LibraryDriverFactory
import dev.piko.shared.library.LibraryStore
import dev.piko.shared.library.ScannedWork
import dev.piko.shared.library.ScannedWorkFile
import dev.piko.shared.library.SyncKind
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 详情页手动加的女优：刮削重写演员时不能把它冲掉。
 *
 * 标签早就是这套规矩（自定义标签只加不减），演员这一列是后补的；没有它，
 * 用户补完女优、下次一刮就没了，看起来像保存失败。
 */
class LibraryActorEditTest {

    private fun store(): LibraryStore {
        val factory = object : LibraryDriverFactory {
            override fun create(account: String): SqlDriver =
                JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { Library.Schema.create(it) }
        }
        return LibraryStore(factory) { "test" }
    }

    private fun work(code: String) = ScannedWork(
        code = code,
        files = listOf(ScannedWorkFile("$code-file", "$code.mp4", 1024L, "root", null, null)),
    )

    /** 刮一部：只关心演员，其余元数据给空值。 */
    private suspend fun LibraryStore.scrape(code: String, actors: List<String>) = markScraped(
        code = code,
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
        actors = actors,
        tags = emptyList(),
    )

    @Test
    fun customActorSurvivesRescrape(): Unit = runBlocking {
        val store = store()
        store.saveScan("root", "根目录", listOf(work("ABC-123")))
        store.scrape("ABC-123", listOf("源站女优"))
        store.addCustomActor("ABC-123", "手加女优")

        var detail = store.workDetail("ABC-123")!!
        assertEquals(listOf("手加女优", "源站女优"), detail.actors.map { it.name }.sorted())
        assertTrue(detail.actors.first { it.name == "手加女优" }.custom)

        // 再刮一次，源站这次换了人：手加的那个要留下，旧的刮削演员被换掉
        store.scrape("ABC-123", listOf("新源站女优"))

        detail = store.workDetail("ABC-123")!!
        assertEquals(listOf("手加女优", "新源站女优"), detail.actors.map { it.name }.sorted())
        assertFalse(detail.actors.any { it.name == "源站女优" })
        assertTrue(detail.actors.first { it.name == "手加女优" }.custom)
    }

    @Test
    fun addingNameAlreadyScrapedKeepsItMarkedCustom(): Unit = runBlocking {
        val store = store()
        store.saveScan("root", "根目录", listOf(work("ABC-123")))
        store.scrape("ABC-123", listOf("同名女优"))

        // 手加一个与源站同名的：不该变成两行，且此后算自定义，刮削不会删掉
        store.addCustomActor("ABC-123", "同名女优")
        store.scrape("ABC-123", emptyList())

        val actors = store.workDetail("ABC-123")!!.actors
        assertEquals(listOf("同名女优"), actors.map { it.name })
        assertTrue(actors.single().custom)
    }

    @Test
    fun removingActorOnlyTouchesThatWork(): Unit = runBlocking {
        val store = store()
        store.saveScan("root", "根目录", listOf(work("ABC-123"), work("ABC-456")))
        store.scrape("ABC-123", listOf("源站女优"))
        store.scrape("ABC-456", listOf("源站女优"))

        store.removeActor("ABC-123", "源站女优")

        assertEquals(emptyList(), store.workDetail("ABC-123")!!.actors.map { it.name })
        assertEquals(listOf("源站女优"), store.workDetail("ABC-456")!!.actors.map { it.name })
    }

    @Test
    fun candidatesListWholeLibrary(): Unit = runBlocking {
        val store = store()
        store.saveScan("root", "根目录", listOf(work("ABC-123"), work("ABC-456")))
        store.scrape("ABC-123", listOf("女优甲"))
        store.addCustomActor("ABC-456", "女优乙")

        // 候选列表给的是全库演员，不只是这一部；刮削来的与手加的都在里面
        assertEquals(listOf("女优乙", "女优甲"), store.actors().map { it.name }.sorted())
    }

    @Test
    fun blankNameIsIgnored(): Unit = runBlocking {
        val store = store()
        store.saveScan("root", "根目录", listOf(work("ABC-123")))
        store.addCustomActor("ABC-123", "   ")

        assertEquals(emptyList(), store.workDetail("ABC-123")!!.actors)
    }

    // ---------------------------------------------------------------- 同步

    @Test
    fun customActorIsWrittenAsSyncMark(): Unit = runBlocking {
        val store = store()
        store.saveScan("root", "根目录", listOf(work("ABC-123")))
        store.addCustomActor("ABC-123", "手加女优")

        val mark = store.exportSyncMarks().single { it.kind == SyncKind.CUSTOM_ACTOR }
        assertEquals("ABC-123|手加女优", mark.key)
        assertEquals("手加女优", mark.value)
        assertTrue(mark.on)
    }

    @Test
    fun customActorSyncsToOtherDevice(): Unit = runBlocking {
        val phone = store()
        phone.saveScan("root", "根目录", listOf(work("ABC-123")))
        phone.addCustomActor("ABC-123", "手加女优")

        val tablet = store()
        tablet.saveScan("root", "根目录", listOf(work("ABC-123")))
        tablet.applySyncMarks(phone.exportSyncMarks())

        val actors = tablet.workDetail("ABC-123")!!.actors
        assertEquals(listOf("手加女优"), actors.map { it.name })
        assertTrue(actors.single().custom)
    }

    @Test
    fun removalSyncsAsOffMark(): Unit = runBlocking {
        val phone = store()
        phone.saveScan("root", "根目录", listOf(work("ABC-123")))
        phone.addCustomActor("ABC-123", "手加女优")

        val tablet = store()
        tablet.saveScan("root", "根目录", listOf(work("ABC-123")))
        tablet.applySyncMarks(phone.exportSyncMarks())
        assertEquals(1, tablet.workDetail("ABC-123")!!.actors.size)

        phone.removeActor("ABC-123", "手加女优")
        tablet.applySyncMarks(phone.exportSyncMarks())

        assertEquals(emptyList(), tablet.workDetail("ABC-123")!!.actors)
    }

    /**
     * 新设备刚登录就同步、作品还没扫到：这条女优不能当孤儿清掉。
     * 标签踩过同一个坑，这里钉住同样的顺序——先同步，再扫到别的片，最后才扫到它。
     */
    @Test
    fun syncedActorSurvivesScanThatHasNotReachedItYet(): Unit = runBlocking {
        val phone = store()
        phone.saveScan("root", "根目录", listOf(work("ABC-123")))
        phone.addCustomActor("ABC-123", "手加女优")

        val tablet = store()
        tablet.applySyncMarks(phone.exportSyncMarks())
        tablet.saveScan("root", "根目录", listOf(work("XYZ-999")))
        tablet.saveScan("root", "根目录", listOf(work("ABC-123"), work("XYZ-999")))

        val actors = tablet.workDetail("ABC-123")!!.actors
        assertEquals(listOf("手加女优"), actors.map { it.name })
        assertTrue(actors.single().custom)
    }
}
