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

/** 一次字幕翻译的进度。 */
data class SubtitleProgress(
    val doneBatches: Int,
    val totalBatches: Int,
    val translatedLines: Int = 0,
    val pendingLines: Int = 0,
) {
    val fraction: Float get() = if (totalBatches <= 0) 0f else doneBatches.toFloat() / totalBatches
}

/**
 * 逐条对齐的字幕翻译。
 *
 * 这四层是「翻完整部片」和「翻到 97% 就散架」的区别，缺一层都会出问题：
 *
 * 1. **按字数分批、不切断单条字幕**：一次请求太大会被截断，切在一条字幕中间就对不齐了；
 * 2. **行数对齐校验**：模型回的行数和送去的不等就整批作废，小批次（≤12 行）退化成逐条重翻，
 *    避免一条字幕错位后整段跟着错位；
 * 3. **2 并发 + 每批重试**：大模型 429 是常态，不能因为一批被限流就放弃整部片；
 * 4. **第二轮串行补漏**：把还空着的行慢慢再翻一遍，目标是完整而不是差不多。
 */
class SubtitleTranslator(
    private val client: TranslationClient,
    private val concurrency: Int = DEFAULT_CONCURRENCY,
    private val batchChars: Int = DEFAULT_BATCH_CHARS,
    /** 用户在设置里改过的提示词；空则用内置的 [SUBTITLE_PROMPT]。 */
    prompt: String = "",
) {
    private val systemPrompt = prompt.trim().ifEmpty { SUBTITLE_PROMPT }
    /**
     * 翻一份 SRT 文本。成功时返回**完整**的 SRT（没翻到的行保留原文，不会留空洞）。
     * 一行都没翻出来才算失败。
     */
    suspend fun translate(
        srtText: String,
        onProgress: (SubtitleProgress) -> Unit = {},
    ): Result<String> {
        val doc = SrtDocument.parse(srtText)
            ?: return Result.failure(TranslationException("这个字幕格式读不懂（只支持 SRT/VTT 文本）"))
        if (doc.size == 0) return Result.failure(TranslationException("字幕里没有可翻译的文本"))

        val batches = doc.batches(batchChars)
        val translated = HashMap<Int, String>(doc.size)
        val mutex = Mutex()
        var done = 0
        var ok = 0
        var pending = 0

        onProgress(SubtitleProgress(doneBatches = 0, totalBatches = batches.size))

        val gate = Semaphore(concurrency.coerceAtLeast(1))
        val leftovers = mutableListOf<List<Int>>()

        coroutineScope {
            batches.map { batch ->
                async {
                    gate.withPermit {
                        val lines = translateBatch(doc, batch)
                        mutex.withLock {
                            if (lines != null) {
                                batch.forEachIndexed { i, cue -> translated[cue] = lines[i] }
                                ok += batch.size
                            } else {
                                leftovers += batch
                                pending += batch.size
                            }
                            done++
                            onProgress(
                                SubtitleProgress(
                                    doneBatches = done,
                                    totalBatches = batches.size,
                                    translatedLines = ok,
                                    pendingLines = pending,
                                ),
                            )
                        }
                    }
                }
            }.awaitAll()
        }

        // 第二轮：把漏掉的行补回来。
        // 用一个小并发（2）而不是纯串行——纯串行时 20 条漏行要等 20 个来回，
        // 而此时的限流压力已经比第一轮小；真被限流了，单条重试照样会退避。
        if (leftovers.isNotEmpty()) {
            val missing = leftovers.flatten().filterNot { it in translated }
            PikoLog.i(TAG, "第二轮补翻 ${missing.size} 行")
            val retryGate = Semaphore(RETRY_CONCURRENCY)
            coroutineScope {
                missing.map { cue ->
                    async {
                        retryGate.withPermit {
                            val line = translateOne(doc.cues[cue].text)
                            mutex.withLock {
                                if (line != null && cue !in translated) {
                                    translated[cue] = line
                                    ok++
                                }
                                pending = doc.size - ok
                                onProgress(
                                    SubtitleProgress(
                                        doneBatches = done.coerceAtMost(batches.size),
                                        totalBatches = batches.size,
                                        translatedLines = ok,
                                        pendingLines = pending,
                                    ),
                                )
                            }
                        }
                    }
                }.awaitAll()
            }
        }

        if (ok == 0) {
            return Result.failure(TranslationException("一行都没翻出来，检查翻译接口的地址、Key 与模型名"))
        }
        if (pending > 0) PikoLog.w(TAG, "$pending 行未能翻译，保留原文")
        // 几千行的拼接放到后台线程，别在 UI 线程上卡一下
        return Result.success(withContext(Dispatchers.Default) { doc.render(translated) })
    }

    /** 一批：整批翻，行数对得上才收；对不上且批次够小就退化成逐条。 */
    private suspend fun translateBatch(doc: SrtDocument, batch: List<Int>): List<String>? {
        val texts = batch.map { doc.cues[it].text }
        val output = client.translate(systemPrompt, texts.joinToString("\n")).getOrElse { error ->
            PikoLog.w(TAG, "批次失败：${error.message}")
            return null
        }
        val lines = alignedLines(output, batch.size)
        if (lines.size == batch.size) return lines

        PikoLog.w(TAG, "行数对不上：送 ${batch.size} 行，回 ${lines.size} 行")
        if (batch.size > PER_LINE_RETRY_LIMIT) return null
        // 逐条重翻，能救几条是几条；救不回的交给第二轮
        val result = MutableList<String?>(batch.size) { null }
        texts.forEachIndexed { i, text ->
            result[i] = translateOne(text)
        }
        return result.takeIf { it.all { line -> line != null } }?.map { it!! }
    }

    private suspend fun translateOne(text: String): String? {
        val output = client.translate(systemPrompt, text).getOrNull() ?: return null
        return alignedLines(output, 1).firstOrNull()
    }

    companion object {
        private const val TAG = "SubtitleTranslate"

        /** 每批字数：小到不会被截断，大到不至于把一部片拆成上百次请求。 */
        const val DEFAULT_BATCH_CHARS = 550

        /** 并发 3：2 条一批太保守，长字幕要多等一半时间；3 是限流与速度的折中。 */
        const val DEFAULT_CONCURRENCY = 3

        /** 超过这么多行就不再逐条重试，逐条只会把请求数翻好几倍。 */
        private const val PER_LINE_RETRY_LIMIT = 12

        /** 第二轮补漏的并发：比第一轮小，给限流留余量。 */
        private const val RETRY_CONCURRENCY = 2

        /**
         * 默认提示词。前六条是「翻得准」的要求，最后三条是这套管线必须的格式约束——
         * 少了它们，模型会把几行合并成一段，行数对不上就只能逐条重翻，速度掉一个数量级。
         * 用户在设置里可以整段替换，但建议保留最后三条。
         */
        val SUBTITLE_PROMPT = """
            将日文影视文字翻译成准确、自然、通顺的简体中文。
            要求：
            1. 充分考虑中日文的表达差异，避免逐词直译。惯用语结合上下文意译，对白自然口语化。
            2. 日文长句可适当调整语序、拆分短句、补充中文标点，避免冗长的"……的"修饰结构，保留原有逻辑关系。
            3. 准确理解人称、人物关系和主被动；不增添情节，不为使情节合理而改写事实。
            4. 人名和番号保持原样，不改动姓名中的假名或汉字，尤其注意末尾演员姓名。NTR 等类型标签可保留。
            5. 忠实保留原意和语气，不扩写、不夸张。输出前检查中文语序与搭配。
            6. 只输出中文译文，不解释、不总结、不添加前缀。

            输入是多行字幕，每行一条。另外必须遵守：
            7. 输出行数必须与输入完全一致，一行对应一行，不要合并、拆分或增删行。
            8. 不要输出行号、时间轴、空行或代码块标记。
            9. 无法翻译的感叹词保留原文，不要遗漏任何一行。
        """.trimIndent()
    }
}

/**
 * 把模型回复收拾成「一条一行」。
 *
 * 模型常有三种小动作：外面包一层 ```代码块、按序号加 `1. ` 前缀、中间插空行。
 * 前两种如果不管，行数就会错位或把序号翻进字幕里，所以这里统一清掉。
 */
internal fun alignedLines(output: String, want: Int): List<String> {
    var text = output.trim()
    if (text.startsWith("```")) {
        text = text.removePrefix("```")
        text = text.substringAfter('\n', text)
        text = text.removeSuffix("```").trim()
    }
    val lines = text.lines()
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .map { it.replace(NUMBERING, "") }
        .filter { it.isNotEmpty() }
    // 行数正好时原样返回；多了就截断（模型爱补一句总结），少了交给上层判定
    return if (lines.size > want) lines.take(want) else lines
}

private val NUMBERING = Regex("""^\s*[0-9]{1,4}[.、)]\s*""")
