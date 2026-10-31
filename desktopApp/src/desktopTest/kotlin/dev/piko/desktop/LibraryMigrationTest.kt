package dev.piko.desktop

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.piko.library.Library
import dev.piko.library.LibraryQueries
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 海报墙索引库 v4 → v5 的迁移：这一版把刮削源从 JavBus 换成 MetaTube，
 * 加了 MetaTube 三列与 work_images 表，并把旧的 poster_settings 删掉。
 *
 * 用真实的老库结构跑迁移，钉住两件事：**老数据不能丢**、新列与截图表确实建出来了。
 */
class LibraryMigrationTest {

    private fun count(driver: JdbcSqliteDriver, sql: String): Long =
        driver.executeQuery(null, sql, { cursor -> QueryResult.Value(cursor.getLong(0) ?: 0L) }, 0).value

    @Test
    fun v4ToLatestKeepsExistingWorksAndAddsNewSchema() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)

        // 照 v4 的真实结构手工建库：works 有 duration、没有 MetaTube 三列，另有旧的 poster_settings。
        // JdbcSqliteDriver 一次只处理一条语句，逐条执行
        listOf(
            """
            CREATE TABLE works (
                code TEXT NOT NULL PRIMARY KEY, title TEXT, cover_url TEXT, release_date TEXT,
                duration INTEGER, summary TEXT, studio TEXT, series TEXT, provider TEXT,
                scraped_at INTEGER NOT NULL DEFAULT 0)
            """.trimIndent(),
            """
            CREATE TABLE work_files (
                file_id TEXT NOT NULL PRIMARY KEY, work_code TEXT NOT NULL, name TEXT NOT NULL,
                size INTEGER NOT NULL, folder_id TEXT NOT NULL, part TEXT)
            """.trimIndent(),
            "CREATE TABLE work_actors (work_code TEXT NOT NULL, actor_name TEXT NOT NULL, PRIMARY KEY (work_code, actor_name))",
            "CREATE TABLE work_tags (work_code TEXT NOT NULL, tag_name TEXT NOT NULL, PRIMARY KEY (work_code, tag_name))",
            """
            CREATE TABLE scan_state (
                id INTEGER NOT NULL PRIMARY KEY CHECK (id = 0), root_folder_id TEXT, root_folder_name TEXT,
                last_scan_at INTEGER, work_count INTEGER NOT NULL DEFAULT 0, scanning INTEGER NOT NULL DEFAULT 0)
            """.trimIndent(),
            "CREATE TABLE favorite_works (code TEXT NOT NULL PRIMARY KEY, created_at INTEGER NOT NULL)",
            "CREATE TABLE favorite_actors (actor_name TEXT NOT NULL PRIMARY KEY, created_at INTEGER NOT NULL)",
            "CREATE TABLE favorite_tags (tag_name TEXT NOT NULL PRIMARY KEY, created_at INTEGER NOT NULL)",
            "CREATE TABLE poster_settings (id INTEGER NOT NULL PRIMARY KEY CHECK (id = 0), scrape_base_url TEXT NOT NULL)",
        ).forEach { sql -> driver.execute(null, sql, 0) }
        driver.execute(null, "INSERT INTO works (code, title, scraped_at) VALUES ('SSIS-001', '老标题', 123)", 0)
        driver.execute(null, "INSERT INTO favorite_works (code, created_at) VALUES ('SSIS-001', 9)", 0)
        driver.execute(null, "INSERT INTO poster_settings (id, scrape_base_url) VALUES (0, 'https://www.javbus.com')", 0)

        // 一路迁到最新：真实用户从 v4 老库直接升上来，不是逐版本手动点
        Library.Schema.migrate(driver, 4L, Library.Schema.version)

        val queries = LibraryQueries(driver)
        // 老数据还在
        assertEquals("老标题", queries.selectWork("SSIS-001").executeAsOne().title)
        assertEquals(1L, count(driver, "SELECT count(*) FROM favorite_works"))

        // 新结构建出来了，旧设置表删掉了
        assertEquals(1L, count(driver, "SELECT count(*) FROM sqlite_master WHERE type = 'table' AND name = 'work_images'"))
        assertEquals(0L, count(driver, "SELECT count(*) FROM sqlite_master WHERE type = 'table' AND name = 'poster_settings'"))

        // 新列可写可读：MetaTube 的数据源名与 ID、预览片地址
        queries.markWorkScraped(
            title = "新标题",
            cover_url = "https://meta.example/v1/images/primary/JavBus/SSIS-001",
            release_date = "2021-01-01",
            duration = 120L,
            summary = null,
            studio = "S1",
            series = null,
            provider = "metatube",
            mt_provider = "JavBus",
            mt_id = "SSIS-001",
            preview_video_url = "https://meta.example/sample.mp4",
            scraped_at = 456L,
            code = "SSIS-001",
        )
        val row = queries.selectWork("SSIS-001").executeAsOne()
        assertEquals("新标题", row.title)
        assertEquals("JavBus", row.mt_provider)
        assertEquals("SSIS-001", row.mt_id)
        assertEquals("https://meta.example/sample.mp4", row.preview_video_url)
        // v6 的译名列也建出来了，默认为空
        assertEquals(null, row.title_zh)

        // 截图墙按 position 排序，0 是封面
        queries.insertWorkImage("SSIS-001", "https://meta.example/cover.jpg", 0)
        queries.insertWorkImage("SSIS-001", "https://meta.example/shot1.jpg", 1)
        assertEquals(
            listOf("https://meta.example/cover.jpg", "https://meta.example/shot1.jpg"),
            queries.selectImagesForWork("SSIS-001").executeAsList(),
        )
    }

    @Test
    fun v5ToV6AddsTitleZhAndKeepsOriginalTitle() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        // 照 v5 的 works 结构建表：有 MetaTube 三列与预览片地址，没有 title_zh
        driver.execute(
            null,
            """
            CREATE TABLE works (
                code TEXT NOT NULL PRIMARY KEY, title TEXT, cover_url TEXT, release_date TEXT,
                duration INTEGER, summary TEXT, studio TEXT, series TEXT, provider TEXT,
                mt_provider TEXT, mt_id TEXT, preview_video_url TEXT,
                scraped_at INTEGER NOT NULL DEFAULT 0)
            """.trimIndent(),
            0,
        )
        driver.execute(null, "INSERT INTO works (code, title, scraped_at) VALUES ('SSIS-001', '禁欲の果てに', 123)", 0)

        Library.Schema.migrate(driver, 5L, 6L)
        val queries = LibraryQueries(driver)

        // 原文标题不能被迁移动过
        assertEquals("禁欲の果てに", queries.selectWork("SSIS-001").executeAsOne().title)
        // 新列默认空，写入后能读出来
        assertEquals(null, queries.selectWork("SSIS-001").executeAsOne().title_zh)
        queries.setWorkTitleZh("禁欲的尽头", "SSIS-001")
        assertEquals("禁欲的尽头", queries.selectWork("SSIS-001").executeAsOne().title_zh)
        // 清空即恢复原文
        queries.setWorkTitleZh(null, "SSIS-001")
        assertEquals("禁欲の果てに", queries.selectWork("SSIS-001").executeAsOne().title)
    }

    @Test
    fun rebuildSwapsMetaTubeBaseWithoutRescraping() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        Library.Schema.create(driver)
        val queries = LibraryQueries(driver)
        queries.insertWork("SSIS-001", null, "https://old.example/v1/images/primary/JavBus/SSIS-001", null, null, null, null, null, "metatube", "JavBus", "SSIS-001", null, 1L)
        queries.insertWorkImage("SSIS-001", "https://old.example/v1/images/primary/JavBus/SSIS-001?url=x", 0)

        // 换 MetaTube 域名：按前缀替换，不用重新刮削
        queries.rebuildCoverUrls("https://old.example", "https://new.example")
        queries.rebuildImageUrls("https://old.example", "https://new.example")

        assertEquals(
            "https://new.example/v1/images/primary/JavBus/SSIS-001",
            queries.selectWork("SSIS-001").executeAsOne().cover_url,
        )
        assertEquals(
            listOf("https://new.example/v1/images/primary/JavBus/SSIS-001?url=x"),
            queries.selectImagesForWork("SSIS-001").executeAsList(),
        )
    }
}
