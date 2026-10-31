package dev.piko.desktop

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.piko.library.Library
import dev.piko.shared.PikoHome
import dev.piko.shared.library.LibraryDriverFactory
import dev.piko.shared.library.sanitizeAccount
import java.nio.file.Files

/**
 * 海报墙索引库的桌面驱动：每个账号一个 SQLite 文件，放在数据根目录（[PikoHome]）下，
 * 与设置、日志同级。
 *
 * JdbcSqliteDriver 不像 Android 驱动那样自己建库/迁移，这里按库里有没有表、版本号
 * 是不是当前的分三种情况处理：
 * - 没有表：新建（并补写 user_version，SQLDelight 的 create 不写，不写下次会重复建）；
 * - 表在、版本号是 0：海报墙索引早期桌面版留下的库——那版建表也不写版本号，
 *   按 v1 迁移到当前版本（补 duration 列与收藏表）；
 * - 表在、版本号更低：正常迁移。
 */
class DesktopLibraryDriverFactory : LibraryDriverFactory {
    override fun create(account: String): SqlDriver {
        val path = PikoHome.root.resolve("library-${sanitizeAccount(account)}.db")
        Files.createDirectories(path.parent)
        return JdbcSqliteDriver("jdbc:sqlite:$path").also { driver ->
            val hasTables = driver.executeQuery(
                null,
                "SELECT count(*) FROM sqlite_master WHERE type = 'table' AND name = 'works'",
                { cursor -> QueryResult.Value(cursor.getLong(0) ?: 0L) },
                0,
            ).value > 0L
            val current = driver.executeQuery(
                null,
                "PRAGMA user_version",
                { cursor -> QueryResult.Value(cursor.getLong(0)?.toInt() ?: 0) },
                0,
            ).value
            val target = Library.Schema.version.toInt()
            when {
                !hasTables -> {
                    Library.Schema.create(driver)
                    driver.execute(null, "PRAGMA user_version = $target", 0)
                }

                current == 0 -> {
                    // 早期桌面库：表在但没写版本号，从 v1 迁到当前版本
                    Library.Schema.migrate(driver, 1L, target.toLong())
                    driver.execute(null, "PRAGMA user_version = $target", 0)
                }

                current < target -> {
                    Library.Schema.migrate(driver, current.toLong(), target.toLong())
                    driver.execute(null, "PRAGMA user_version = $target", 0)
                }

                else -> Unit
            }
        }
    }
}
