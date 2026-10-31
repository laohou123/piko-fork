package dev.piko.shared.translate

import dev.piko.shared.log.PikoLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlin.time.TimeSource

/** 批量翻片名的进度。 */
data class TitleProgress(
    val done: Int,
    val total: Int,
    val translated: Int,
) {
    val fraction: Float get() = if (total <= 0) 0f else done.toFloat() / total
}

/**
 * 片名翻译。
 *
 * 与字幕分开一套提示词：字幕要的是逐行对齐，片名要的是像一个片名。
 * 批量时一次送 [BATCH_TITLES] 条（一行一条），省下大量请求；行数对不上就拆成单条重翻，
 * 所以偶尔有模型多吐一句话也不会让整批错位。
 */
class TitleTranslator(
    /** 同一档案下每把 Key 一个客户端（顺序即添加顺序），轮着用、坏的跳过。 */
    private val clients: List<TranslationClient>,
    private val concurrency: Int = DEFAULT_CONCURRENCY,
) {
    /** 一次批量翻译内共用：轮换取 Key，认证/余额坏的跳过。 */
    private val keyPool = TranslationKeyPool(clients.map { it.key })

    /** 翻一条。失败返回 null。多把 Key 时轮着用，认证/余额坏的跳过换下一把。 */
    suspend fun translateOne(title: String): String? {
        val trimmed = title.trim()
        if (trimmed.isEmpty()) return null
        var attempts = 0
        while (attempts < clients.size.coerceAtLeast(1)) {
            attempts++
            val key = keyPool.nextKey() ?: return null
            val client = clients.firstOrNull { it.key == key } ?: continue
            val result = client.translate(TITLE_PROMPT, trimmed, maxTokens = 256)
            val error = result.exceptionOrNull()
            if (error != null && error is TranslationException && error.authFailed) {
                keyPool.reportBroken(key)
                continue
            }
            keyPool.reportOk(key)
            return cleanTitle(result.getOrNull() ?: return null)
        }
        return null
    }

    /**
     * 批量翻。每翻好一批就把结果交出去（[onResults]），调用方一次事务落库，
     * 比每条写一次库快一个数量级。
     *
     * 三个提速点，都是「看起来在转圈」的真实成因：
     * - 整个流程放在 Default 线程，网络回来后的整理与回调不占主线程；
     * - 落库在并发闸门之外，写库的那几十毫秒不占用正在跑的网络请求名额；
     * - 进度按时间节流，不因为每翻一条就重组一次整个设置页而卡顿。
     */
    suspend fun translateAll(
        entries: List<Pair<String, String>>,
        onProgress: (TitleProgress) -> Unit = {},
        onResults: suspend (items: List<Pair<String, String>>) -> Unit = {},
    ): Int = withContext(Dispatchers.Default) {
        if (entries.isEmpty()) return@withContext 0
        val total = entries.size
        val mutex = Mutex()
        var done = 0
        var ok = 0
        var lastReport = TimeSource.Monotonic.markNow()
        onProgress(TitleProgress(0, total, 0))

        fun report(force: Boolean) {
            val now = TimeSource.Monotonic.markNow()
            if (!force && (now - lastReport).inWholeMilliseconds < PROGRESS_INTERVAL_MS) return
            lastReport = now
            onProgress(TitleProgress(done, total, ok))
        }

        val gate = Semaphore(concurrency.coerceAtLeast(1))

        coroutineScope {
            entries.chunked(BATCH_TITLES).map { chunk ->
                async {
                    val results = gate.withPermit { translateChunk(chunk) }
                    val pairs = chunk.mapIndexedNotNull { index, entry ->
                        results.getOrNull(index)?.let { entry.first to it }
                    }
                    // 落库与进度都在闸门外
                    if (pairs.isNotEmpty()) onResults(pairs)
                    mutex.withLock {
                        done += chunk.size
                        ok += pairs.size
                        report(force = done == total)
                    }
                }
            }.awaitAll()
        }
        ok
    }

    /** 一批：整批翻，行数对得上就用；对不上就逐条。多把 Key 时换 Key 重试整批。 */
    private suspend fun translateChunk(chunk: List<Pair<String, String>>): List<String?> {
        if (chunk.size == 1) return listOf(translateOne(chunk[0].second))
        val input = chunk.joinToString("\n") { it.second.trim() }
        var attempts = 0
        while (attempts < clients.size.coerceAtLeast(1)) {
            attempts++
            val key = keyPool.nextKey() ?: break
            val client = clients.firstOrNull { it.key == key } ?: continue
            val result = client.translate(TITLE_PROMPT, input, maxTokens = 256 * chunk.size)
            val error = result.exceptionOrNull()
            if (error != null && error is TranslationException && error.authFailed) {
                PikoLog.w(TAG, "片名批次密钥认证/余额失败，换下一把")
                keyPool.reportBroken(key)
                continue
            }
            keyPool.reportOk(key)
            val output = result.getOrElse { e ->
                PikoLog.w(TAG, "片名批次失败：${e.message}")
                return chunk.map { null }
            }
            val lines = alignedLines(output, chunk.size).map { cleanTitle(it) }
            if (lines.size == chunk.size) return lines
            PikoLog.w(TAG, "片名行数对不上：送 ${chunk.size} 条，回 ${lines.size} 条，改逐条翻")
            return chunk.map { translateOne(it.second) }
        }
        return chunk.map { null }
    }

    /** 模型偶尔会加引号、书名号或「译：」前缀，这里收拾干净。 */
    private fun cleanTitle(raw: String): String? {
        var text = alignedLines(raw, 1).firstOrNull() ?: return null
        text = text.trim().trim('"', '\'', '「', '」', '『', '』', '《', '》')
        text = text.removePrefix("译文：").removePrefix("译：").removePrefix("中文：").trim()
        return text.takeIf { it.isNotEmpty() }
    }

    companion object {
        private const val TAG = "TitleTranslate"

        /** 一批 12 条：片名短，一次送 12 条也就一两百字，请求数压到 1/12。 */
        const val BATCH_TITLES = 12

        /** 4 并发：主流接口（含 DeepSeek）一般扛得住，再多容易吃 429。 */
        const val DEFAULT_CONCURRENCY = 4

        /** 进度最多每 150ms 报一次，避免每条都重组一遍设置页。 */
        const val PROGRESS_INTERVAL_MS = 150L

        const val TITLE_PROMPT = """把用户提供的影视作品标题逐行翻译成简体中文。

要求：
1. 输出行数必须与输入完全一致，一行对应一行，不要合并或增删行。
2. 只输出译文，不要序号、解释、引号或任何标记，不要重复原文。
3. 像片名一样简洁自然，人名、地名按中文习惯翻译。
4. 已经是中文的直接原样输出。"""
    }
}
