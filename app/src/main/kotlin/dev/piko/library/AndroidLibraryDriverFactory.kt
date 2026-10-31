package dev.piko.library

import android.content.Context
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import dev.piko.shared.library.LibraryDriverFactory
import dev.piko.shared.library.sanitizeAccount

/**
 * 海报墙索引库的 Android 驱动：每个账号一个 SQLite 文件，放在应用的数据库目录下
 * （卸载即随之清除，不占用户的可见空间）。账号是邮箱，先净化成安全的文件名。
 */
class AndroidLibraryDriverFactory(private val context: Context) : LibraryDriverFactory {
    override fun create(account: String): SqlDriver =
        AndroidSqliteDriver(Library.Schema, context, "piko-library-${sanitizeAccount(account)}.db")
}
