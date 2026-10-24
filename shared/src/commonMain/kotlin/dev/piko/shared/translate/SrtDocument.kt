package dev.piko.shared.translate

/**
 * 一条字幕：时间轴与序号原样保留，只换文本。
 *
 * 文本在解析时压成一行（原字幕里的换行换成空格）：一条 cue 作为一个整体送去翻译，
 * 模型只要回一行，行数对齐就不会因为「原文两行、译文三行」而错位。播放器会自己折行。
 */
data class SrtCue(
    /** 文件里的序号，渲染时原样写回；缺失时为 -1，渲染时按位置补。 */
    val index: Int,
    /** `00:00:01,000 --> 00:00:03,500` 这一行，原样保留。 */
    val timing: String,
    val text: String,
)

/**
 * 一份 SRT 字幕。只做三件事：解析、切成批、把译文写回去。
 *
 * 解析故意宽松：BOM、CRLF、缺序号、用 `.` 当毫秒分隔（WebVTT 风格）都能读，
 * 因为 SubtitleCat 上的人工上传什么格式都有。读不懂的块整块跳过，不让一条坏行毁掉整个文件。
 */
class SrtDocument private constructor(
    val cues: List<SrtCue>,
) {
    val size: Int get() = cues.size

    /**
     * 按字数分批，绝不把一条 cue 切到两个批次里——那样模型没法保持行数对应。
     * 返回的是 cue 在 [cues] 里的下标分组。
     */
    fun batches(maxChars: Int): List<List<Int>> {
        if (cues.isEmpty()) return emptyList()
        val limit = maxChars.coerceAtLeast(MIN_BATCH_CHARS)
        val result = mutableListOf<List<Int>>()
        var current = mutableListOf<Int>()
        var used = 0
        cues.forEachIndexed { i, cue ->
            val cost = cue.text.length + 1
            // 单条就超限时独占一批：切不了，只能让它自己走一次
            if (current.isNotEmpty() && used + cost > limit) {
                result += current
                current = mutableListOf()
                used = 0
            }
            current += i
            used += cost
        }
        if (current.isNotEmpty()) result += current
        return result
    }

    /** 把 [translated]（下标 → 译文）写回字幕；没翻到的条目保留原文，不丢内容。 */
    fun render(translated: Map<Int, String>): String = buildString {
        cues.forEachIndexed { i, cue ->
            val text = translated[i]?.takeIf { it.isNotBlank() } ?: cue.text
            append(if (cue.index > 0) cue.index else i + 1).append('\n')
            append(cue.timing).append('\n')
            append(text).append('\n')
            append('\n')
        }
    }

    companion object {
        /** 低于这个字数分批没有意义（一条长 cue 自己就超过了）。 */
        const val MIN_BATCH_CHARS = 80

        /** 时间轴行：`00:00:01,000 --> 00:00:03,500`，毫秒分隔符可能是逗号或点。 */
        private val TIMING = Regex(
            """^\s*[0-9]{1,2}:[0-9]{2}:[0-9]{2}[,.][0-9]{1,3}\s*-->\s*[0-9]{1,2}:[0-9]{2}:[0-9]{2}[,.][0-9]{1,3}""",
        )
        private val INDEX = Regex("""^[0-9]{1,6}$""")

        /** 解析失败（没有任何可用字幕）时返回 null。 */
        fun parse(raw: String): SrtDocument? {
            if (raw.isBlank()) return null
            val text = raw.removePrefix("\uFEFF").replace("\r\n", "\n").replace('\r', '\n')
            val cues = mutableListOf<SrtCue>()
            for (block in text.split(Regex("""\n\s*\n"""))) {
                val lines = block.trim('\n').lines().map { it.trimEnd() }.filter { it.isNotEmpty() }
                if (lines.isEmpty()) continue
                var cursor = 0
                var index = -1
                if (INDEX.matches(lines[0].trim())) {
                    index = lines[0].trim().toIntOrNull() ?: -1
                    cursor = 1
                }
                if (cursor >= lines.size || !TIMING.containsMatchIn(lines[cursor])) continue
                val timing = lines[cursor].trim()
                val body = lines.drop(cursor + 1)
                    .joinToString(" ")
                    // 站上不少字幕带 <i> / {\an8} 这类样式标记，留着不影响翻译，
                    // 但会让模型把标记算进译文，这里统一去掉
                    .replace(STYLE_TAG, "")
                    .replace(Regex("""\s{2,}"""), " ")
                    .trim()
                // 纯音乐/音效行也给翻译，但空文本的 cue 跳过
                if (body.isEmpty()) continue
                cues += SrtCue(index = index, timing = timing, text = body)
            }
            return cues.takeIf { it.isNotEmpty() }?.let { SrtDocument(it) }
        }

        /**
         * 样式标记：<i>、</font> 这类 HTML 标签，以及 {\an8} 这类 ASS 定位标记。
         *
         * 结尾那个 } 一定要转义：Java 的 Pattern 容忍裸的 }，Android 底下是 ICU，
         * 直接抛 PatternSyntaxException；这个正则写在 companion object 里，一抛就是
         * ExceptionInInitializerError，整个 SrtDocument 类用不了，字幕翻译一进来就失败。
         */
        private val STYLE_TAG = Regex("""</?[a-zA-Z][^>]*>|\{\\[^}]*\}""")
    }
}
