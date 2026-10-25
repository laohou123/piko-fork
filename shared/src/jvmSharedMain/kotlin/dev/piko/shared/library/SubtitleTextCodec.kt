package dev.piko.shared.library

import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/**
 * 字幕文件的编码兜底：各站上传的字幕编码五花八门，统一在这里转成 UTF-8 字节，
 * 上层（mpv、翻译）只处理 UTF-8。
 *
 * 规则：已经是合法 UTF-8 的原样返回（只去 BOM）；否则把常见旧编码逐个严格解码，
 * 按「文字像不像话」打分取最高。严格解码是关键——带替换字符的宽松解码会把内容
 * 搅碎，后面就无法判断哪种编码猜对了。
 *
 * SubtitleCat 与迅雷两个源共用。原本这套逻辑只长在 SubtitleCatSource 里，
 * 加第二个源时搬出来，行为逐字保持不变。
 */
internal object SubtitleTextCodec {

    /** 已经是合法 UTF-8 就直通，否则试这些编码。顺序只影响同分时的取舍。 */
    private val LEGACY_CHARSETS = listOf("Shift_JIS", "GB18030", "EUC-JP", "windows-1252")

    fun decode(raw: ByteArray): ByteArray {
        if (raw.isEmpty()) return raw
        if (isValidUtf8(raw)) return stripBom(raw)

        var best: ByteArray? = null
        var bestScore = Int.MIN_VALUE
        for (name in LEGACY_CHARSETS) {
            val charset = try {
                Charset.forName(name)
            } catch (_: Exception) {
                continue
            }
            val decoded = decodeStrict(raw, charset) ?: continue
            val score = plausibility(decoded)
            if (score > bestScore) {
                best = decoded
                bestScore = score
            }
        }
        return stripBom(best ?: raw)
    }

    /**
     * 严格解码：有一个不认识的字节就整体失败，避免静默塞替换字符。
     *
     * 注意必须检查 [java.nio.charset.CoderResult]：`CharsetDecoder.decode` 遇到非法字节
     * 是**返回错误结果**而不是抛异常，只看异常会把 GBK/Shift_JIS 误判成「合法 UTF-8」
     * 原样放行，上层按 UTF-8 读就是一片乱码。这一步修过之后才敢说「严格」。
     */
    private fun decodeStrict(raw: ByteArray, charset: Charset): ByteArray? {
        val decoder = charset.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        return try {
            val out = java.nio.CharBuffer.allocate(raw.size * 2)
            val decoded = decoder.decode(java.nio.ByteBuffer.wrap(raw), out, true)
            if (decoded.isError) return null
            val flushed = decoder.flush(out)
            if (flushed.isError) return null
            out.flip()
            val chars = CharArray(out.remaining())
            out.get(chars)
            // 统一按 UTF-8 字节返回，调用方（isValidUtf8 / plausibility）都按 UTF-8 读
            String(chars).toByteArray(StandardCharsets.UTF_8)
        } catch (_: CharacterCodingException) {
            null
        } catch (_: Exception) {
            null
        }
    }

    private fun isValidUtf8(raw: ByteArray): Boolean =
        decodeStrict(raw, StandardCharsets.UTF_8) != null

    /** 解码结果的「可信度」：CJK/假名加分，乱码私用区重扣。 */
    private fun plausibility(text: ByteArray): Int {
        val s = String(text, StandardCharsets.UTF_8)
        var score = 0
        for (r in s) {
            score += when {
                r == '\r' || r == '\n' || r == '\t' -> 0
                r < ' ' -> -1
                r < '\u0080' -> 1
                r in '\u3040'..'\u30FF' -> 3 // 假名
                r in '\u4E00'..'\u9FFF' -> 3 // CJK
                r in '\uAC00'..'\uD7AF' -> 3 // 谚文
                r in '\uFFFD'..'\uFFFF' || r in '\uE000'..'\uF8FF' || r in '\u0080'..'\u009F' -> -5
                else -> -1
            }
        }
        return score
    }

    private fun stripBom(raw: ByteArray): ByteArray {
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        return if (raw.size >= 3 && bom[0] == raw[0] && bom[1] == raw[1] && bom[2] == raw[2]) {
            raw.copyOfRange(3, raw.size)
        } else {
            raw
        }
    }
}
