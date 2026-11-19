package dev.piko.shared.sync

import dev.piko.shared.data.PikoClientProvider
import dev.piko.shared.library.LibraryStore
import dev.piko.shared.library.MetadataBackup
import dev.piko.shared.log.PikoLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlin.time.Clock

/**
 * 元数据整份备份与恢复，落到网盘 `.piko/meta-backup-<毫秒时间戳>.json`。
 *
 * 与 [LibrarySync] 分工：那个是增量（译名、收藏），改一处推一处；这个是整份快照，
 * 只在用户点按钮时写，**每次新建一份、不删旧的**——点错了或恢复错了，还有上一份能退回去。
 *
 * 内容与合并规则见 [MetadataBackup] 与 [LibraryStore.restoreMetadataBackup]。
 */
class MetadataBackupSync(
    private val clients: PikoClientProvider,
    /** 用 `meta-backup-` 前缀构造：读的时候它自己会挑时间戳最大的那一份。 */
    private val remote: DriveSettingsStore,
    private val store: LibraryStore,
    private val appVersion: String = "",
    /** 恢复后让海报墙重读列表，不然要退出去再进来才看得到。 */
    private val onRestored: suspend () -> Unit = {},
    private val now: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    private val json = Json {
        ignoreUnknownKeys = true
        // 可空字段都有默认值，关掉默认值写出后 null 与空表都不落文件：
        // 5000 部时能省下几 MB 的 "xxx":null
        encodeDefaults = false
    }
    private val lock = Mutex()

    sealed interface BackupOutcome {
        data class Success(val works: Int, val bytes: Int, val fileName: String) : BackupOutcome

        /** [reason] 给界面判断用：not-signed-in / no-library / 其它异常文本。 */
        data class Failure(val reason: String) : BackupOutcome
    }

    sealed interface RestoreOutcome {
        data class Success(val restored: Int, val skipped: Int, val total: Int, val createdAt: Long) : RestoreOutcome
        object NoBackup : RestoreOutcome
        data class Failure(val reason: String) : RestoreOutcome
    }

    /** 立刻备份一次。体积与部数写日志，内容不进日志。 */
    suspend fun backupNow(): BackupOutcome = lock.withLock {
        val account = clients.currentClient.value?.account ?: return@withLock BackupOutcome.Failure("not-signed-in")
        val createdAt = now()
        val backup = store.exportMetadataBackup(createdAt, appVersion)
            ?: return@withLock BackupOutcome.Failure("no-library")
        runCatching {
            val bytes = json.encodeToString(MetadataBackup.serializer(), backup).encodeToByteArray()
            val fileName = MetadataBackup.FILE_PREFIX + createdAt + MetadataBackup.FILE_SUFFIX
            remote.uploadPreserving(account, fileName, bytes)
            PikoLog.i(TAG, "元数据备份完成：${backup.works.size} 部，${bytes.size / 1024} KB，$fileName")
            BackupOutcome.Success(backup.works.size, bytes.size, fileName)
        }.getOrElse { error ->
            if (error is CancellationException) throw error
            PikoLog.w(TAG, "元数据备份失败：${error::class.simpleName}: ${error.message}")
            BackupOutcome.Failure(error.message ?: error::class.simpleName ?: "unknown")
        }
    }

    /** 恢复时间戳最大的那一份。 */
    suspend fun restoreLatest(): RestoreOutcome = lock.withLock {
        val account = clients.currentClient.value?.account ?: return@withLock RestoreOutcome.Failure("not-signed-in")
        val text = try {
            remote.read(account) ?: return@withLock RestoreOutcome.NoBackup
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            PikoLog.w(TAG, "元数据备份读取失败：${error::class.simpleName}: ${error.message}")
            return@withLock RestoreOutcome.Failure(error.message ?: error::class.simpleName ?: "unknown")
        }
        val backup = try {
            json.decodeFromString(MetadataBackup.serializer(), text)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            PikoLog.w(TAG, "元数据备份解析失败：${error::class.simpleName}: ${error.message}")
            return@withLock RestoreOutcome.Failure("invalid-backup")
        }
        // 未来版本可能多了必填字段或换了含义，宁可不恢复也别写坏库
        if (backup.version > MetadataBackup.VERSION) {
            PikoLog.w(TAG, "元数据备份格式版本 ${backup.version} 高于本机 ${MetadataBackup.VERSION}，跳过")
            return@withLock RestoreOutcome.Failure("newer-version")
        }
        val result = store.restoreMetadataBackup(backup)
            ?: return@withLock RestoreOutcome.Failure("no-library")
        if (result.restored > 0) onRestored()
        PikoLog.i(
            TAG,
            "元数据恢复：备份 ${backup.works.size} 部（${backup.createdAt}），" +
                "写入 ${result.restored} 部，跳过 ${result.skipped} 部",
        )
        RestoreOutcome.Success(result.restored, result.skipped, result.total, backup.createdAt)
    }

    private companion object {
        const val TAG = "MetadataBackup"
    }
}
