package dev.piko.shared.sync

import dev.piko.shared.data.PikoClientProvider
import dev.piko.shared.library.LibraryStore
import dev.piko.shared.library.SyncMark
import dev.piko.shared.log.PikoLog
import dev.piko.shared.log.logFailure
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.time.Clock

/**
 * 把译名与收藏（作品/演员/标签）同步到网盘 `.piko/library-<时间戳>.json`。
 *
 * 与设置同步分开，因为它是库数据：作品多时体积大，且只在翻译或收藏变化时需要传，
 * 不该跟着「改个主题」一起重传。
 *
 * 合并按条进行，每条带最后修改时刻，谁后改谁赢：两台设备各翻各的、各收藏各的，两边的结果都留下。
 * 取消收藏、清空译名同样记一条时刻，所以删除也能传过去——不会被另一台设备又补回来。
 *
 * 什么时候动：登录或换号后拉一次；本机改了译名或收藏，停几秒合并推上去。[enabled] 关掉时不做。
 */
class LibrarySync(
    private val clients: PikoClientProvider,
    private val remote: RemoteSettingsStore,
    private val store: LibraryStore,
    private val scope: CoroutineScope,
    private val enabled: Flow<Boolean>,
    /** 应用了别的设备的改动：界面据此重读列表，不然要退出去再进来才看得到。 */
    private val onApplied: suspend () -> Unit = {},
    private val now: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    private val lock = Mutex()
    private val json = Json { ignoreUnknownKeys = true }

    private val _status = MutableStateFlow(Status.IDLE)
    val status: StateFlow<Status> = _status.asStateFlow()

    private val _lastSynced = MutableStateFlow<Long?>(null)
    val lastSynced: StateFlow<Long?> = _lastSynced.asStateFlow()

    enum class Status { IDLE, SYNCING, SYNCED, FAILED }

    constructor(
        clients: PikoClientProvider,
        remote: RemoteSettingsStore,
        store: LibraryStore,
        scope: CoroutineScope,
        enabled: Flow<Boolean>,
        onApplied: suspend () -> Unit = {},
    ) : this(clients, remote, store, scope, enabled, onApplied, { Clock.System.now().toEpochMilliseconds() })

    @OptIn(FlowPreview::class)
    fun start() {
        scope.launch {
            combine(clients.currentClient, enabled) { client, on -> client?.account.takeIf { on } }
                .distinctUntilChanged()
                .collectLatest { account ->
                    if (account == null) return@collectLatest
                    syncNow()
                    // 本机改了译名或收藏：连着改几部也只等停手后传一次
                    store.changes.debounce(PUSH_DELAY_MS).collect { syncNow() }
                }
        }
    }

    /** 立刻同步一次，「立即同步」也走这里。 */
    suspend fun syncNow(): Boolean = lock.withLock {
        val account = clients.currentClient.value?.account ?: return@withLock false
        _status.value = Status.SYNCING
        val ok = runCatching { sync(account) }
            .onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }
            .logFailure(TAG, "译名与收藏同步失败")
            .isSuccess
        _status.value = if (ok) Status.SYNCED else Status.FAILED
        if (ok) _lastSynced.value = now()
        ok
    }

    private suspend fun sync(account: String) {
        val mine = store.exportSyncMarks()
        val remoteText = remote.read(account)
        val remoteMarks = remoteText?.let { parse(it) }.orEmpty()
        val merged = merge(mine, remoteMarks)

        // 别的设备更新（或本机还没有）的写进本机
        val applied = store.applySyncMarks(merged)
        if (applied > 0) onApplied()

        val changed = merged.size != remoteMarks.size || merged.any { m ->
            remoteMarks.none { it.kind == m.kind && it.key == m.key && it.at == m.at && it.value == m.value && it.on == m.on }
        }
        if (changed) {
            val document = Document(VERSION, merged.map { MarkDto(it.kind, it.key, it.at, it.value, it.on) })
            remote.write(account, json.encodeToString(Document.serializer(), document), now())
        }
        // 只记条数，不记内容：译名与收藏名不该进日志
        PikoLog.i(TAG, "译名与收藏同步：本机 ${mine.size} 条，远端 ${remoteMarks.size} 条，" +
            "写入本机 $applied 条，${if (changed) "已推送 ${merged.size} 条" else "无需推送"}")
    }

    /** 逐条比时刻，同一时刻以远端为准，两台设备才会收敛到同一份。 */
    private fun merge(local: List<SyncMark>, remote: List<SyncMark>): List<SyncMark> {
        val map = LinkedHashMap<Pair<String, String>, SyncMark>()
        local.forEach { map[it.kind to it.key] = it }
        remote.forEach { mark ->
            val current = map[mark.kind to mark.key]
            if (current == null || mark.at >= current.at) map[mark.kind to mark.key] = mark
        }
        return map.values.toList()
    }

    private fun parse(text: String): List<SyncMark> =
        runCatching { json.decodeFromString(Document.serializer(), text).marks }
            .onFailure { PikoLog.w(TAG, "同步文件读不懂，按空的处理：${it.message}") }
            .getOrDefault(emptyList())
            .map { SyncMark(it.kind, it.key, it.at, it.value, it.on) }

    @Serializable
    private data class Document(val version: Int, val marks: List<MarkDto>)

    @Serializable
    private data class MarkDto(
        val kind: String,
        val key: String,
        val at: Long,
        val value: String? = null,
        val on: Boolean = true,
    )

    private companion object {
        const val TAG = "LibrarySync"
        const val VERSION = 1

        /** 连着收藏几部、连着改几个名字，等停手几秒再传一次，别每个动作都传一份文件。 */
        const val PUSH_DELAY_MS = 3_000L
    }
}
