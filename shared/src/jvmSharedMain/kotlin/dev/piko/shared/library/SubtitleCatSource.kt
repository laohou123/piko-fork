package dev.piko.shared.library

import dev.piko.shared.data.runSuspendCatching
import dev.piko.shared.log.PikoLog
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.HttpStatusCode
import io.ktor.http.encodeURLPathPart
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.jsoup.Jsoup
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/**
 * SubtitleCat 字幕源，移植自 JavBoss 的 subtitlecat 适配器（Go）：
 * - 搜索：`/index.php?search={关键词}`，结果在 `table.sub-table tbody tr`，每行第一个链接进详情页；
 * - 详情：`div.sub-single` 一块一种语言，`img.flag` 的 alt 是语言代码，`a[href$=".srt"]` 是下载链接；
 * - 原始字幕不在页面上，但按 `{页面名}-orig.srt` 的规律摆在旁边，直接拼路径就能下；
 * - 文件名尾缀（-zh-CN）即语言，页面没写语言名时用它兜底。
 *
 * 所有路径都必须是本站 /subs/ 下的：用户能传进来的只有搜索结果里的链接，
 * 仍按参考实现的做法校验一遍，防路径穿越把请求打到别的站上。
 */
class SubtitleCatSource(
    private val http: HttpClient,
    baseUrl: String = DEFAULT_BASE_URL,
    private val minRequestIntervalMillis: Long = 500L,
) : SubtitleSource {

    private val base = baseUrl.trim().trimEnd('/')
    private val ownHost: String = base.substringAfter("://").substringBefore('/')

    private val gate = Mutex()
    private var lastRequestAt = 0L

    override suspend fun search(query: String): List<SubtitleHit> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return emptyList()
        val html = get("/index.php?search=${trimmed.encodeURLPathPart()}") ?: return emptyList()
        val doc = Jsoup.parse(html, base)

        val hits = mutableListOf<SubtitleHit>()
        for (row in doc.select("table.sub-table tbody tr")) {
            val link = row.select("a[href]").firstOrNull() ?: continue
            val pagePath = normalizeResultPath(link.attr("href")) ?: continue
            val name = link.text().trim().ifEmpty {
                pagePath.substringAfterLast('/').removeSuffix(".html")
            }
            var languages = 0
            var sizeKb = 0
            for (cell in row.select("td")) {
                val text = cell.text().trim()
                val lower = text.lowercase()
                when {
                    lower.contains("language") -> languages = leadingInt(text)
                    lower.contains("download") -> Unit // 下载次数，用不上
                    else -> {
                        val size = parseSizeKb(text)
                        if (size != null) sizeKb = size
                    }
                }
            }
            hits.add(SubtitleHit(name = name, pagePath = pagePath, languages = languages, sizeKb = sizeKb))
        }
        PikoLog.i(TAG, "搜「$trimmed」：${hits.size} 条")
        return hits
    }

    override suspend fun detail(pagePath: String): SubtitleDetail? {
        val clean = normalizeSubsPath(pagePath) ?: return null
        val html = get(clean) ?: return null
        val doc = Jsoup.parse(html, base)

        val candidates = mutableListOf<SubtitleCandidate>()
        val seen = mutableSetOf<String>()
        val translatable = mutableListOf<String>()

        for (block in doc.select("div.sub-single")) {
            val code = block.selectFirst("img.flag")?.attr("alt")?.trim()?.takeIf { LANG_CODE.matches(it) }.orEmpty()
            // 语言名在纯文本 span 里：旗帜 span 有 img、操作 span 有链接或按钮，都跳过
            var label = ""
            for (span in block.children().filter { it.tagName() == "span" }) {
                if (span.select("a, button, img").isNotEmpty()) continue
                val text = span.text().trim()
                if (text.isEmpty()) continue
                label = text
                break
            }
            if (label.isEmpty()) label = code

            val download = block.select("a[href$=\".srt\"]").firstOrNull()
            val href = download?.attr("href")
            if (!href.isNullOrBlank()) {
                val path = normalizeResultPath(href)
                if (path != null && seen.add(path)) {
                    val language = languageFromPath(path).ifEmpty { code }.ifEmpty { "und" }
                    candidates.add(
                        SubtitleCandidate(
                            language = language,
                            label = label.ifEmpty { language },
                            path = path,
                        ),
                    )
                }
                continue
            }
            // 没有下载链接：这个语言站点上还没有，只是能翻译
            if (code.isNotEmpty() && label.isNotEmpty()) translatable.add(label)
        }

        // 原始字幕摆在详情页旁边，规律固定，拼出来放最前面
        val origPath = originalSubtitlePath(clean)
        if (origPath != null && seen.add(origPath)) {
            candidates.add(0, SubtitleCandidate(language = "orig", label = "原始语言", path = origPath, original = true))
        }

        val name = doc.selectFirst("title")?.text()?.trim().orEmpty()
        PikoLog.i(TAG, "详情 $clean：${candidates.size} 个可下")
        return SubtitleDetail(name = name, candidates = candidates, translatable = translatable)
    }

    override suspend fun download(path: String): ByteArray? {
        val clean = normalizeSubsPath(path) ?: return null
        if (!clean.lowercase().endsWith(".srt")) {
            PikoLog.w(TAG, "拒绝下载非 srt 路径：$clean")
            return null
        }
        val raw = getBytes(clean) ?: return null
        return decodeSubtitleText(raw)
    }

    // ---------------------------------------------------------------- 网络层

    private suspend fun get(target: String): String? = getBytes(target)?.toString(Charsets.UTF_8)

    private suspend fun getBytes(target: String): ByteArray? {
        awaitTurn()
        return runSuspendCatching {
            val response: HttpResponse = http.get(base + target) {
                header("User-Agent", USER_AGENT)
                header("Accept", ACCEPT)
                header("Accept-Language", "en,ja;q=0.8,zh-CN;q=0.7,*;q=0.5")
            }
            val body = response.bodyAsBytes()
            if (response.status != HttpStatusCode.OK) {
                PikoLog.w(TAG, "HTTP ${response.status.value} $target")
                null
            } else {
                body
            }
        }.getOrElse { error ->
            PikoLog.w(TAG, "请求失败 $target：${error.message}")
            null
        }
    }

    private suspend fun awaitTurn() {
        gate.withLock {
            val wait = lastRequestAt + minRequestIntervalMillis - System.currentTimeMillis()
            if (wait > 0) kotlinx.coroutines.delay(wait)
            lastRequestAt = System.currentTimeMillis()
        }
    }

    // ---------------------------------------------------------------- 路径与语言

    /** 接受站内相对路径或完整 URL，归一为 /subs/ 开头的路径；不是本站的一律拒绝。 */
    private fun normalizeSubsPath(raw: String): String? {
        var trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        if (trimmed.contains("://")) {
            val host = trimmed.substringAfter("://").substringBefore('/')
            if (!host.equals(ownHost, ignoreCase = true)) {
                PikoLog.w(TAG, "拒绝非本站路径：$raw")
                return null
            }
            trimmed = trimmed.substringAfter("://").substringAfter('/', "")
        }
        trimmed = trimmed.trimStart('/')
        if (trimmed.contains("..") || !trimmed.startsWith(SUBS_DIR)) return null
        return "/$trimmed"
    }

    /** 详情页链接/下载链接的归一：与 [normalizeSubsPath] 同规则，拿不到就丢这一行。 */
    private fun normalizeResultPath(href: String): String? {
        val trimmed = href.trim()
        if (trimmed.isEmpty() || trimmed.startsWith("javascript:") || trimmed.startsWith("#")) return null
        return normalizeSubsPath(trimmed)
    }

    /** "{页面名}-orig.srt"：详情页只链翻译版，原始文件按规律摆在旁边。 */
    private fun originalSubtitlePath(pagePath: String): String? {
        if (!pagePath.endsWith(".html")) return null
        return pagePath.removeSuffix(".html") + "-orig.srt"
    }

    /** "Name-zh-CN.srt" → "zh-CN"；尾缀即语言。 */
    private fun languageFromPath(path: String): String {
        val stem = path.substringAfterLast('/').removeSuffix(".srt")
        val match = LANGUAGE_SUFFIX.find(stem) ?: return ""
        return match.groupValues[1]
    }

    private fun leadingInt(text: String): Int =
        Regex("([0-9][0-9,]*)").find(text)?.groupValues?.get(1)?.replace(",", "")?.toIntOrNull() ?: 0

    private fun parseSizeKb(text: String): Int? {
        val m = Regex("(?i)([0-9]+(?:\\.[0-9]+)?)\\s*(KB|MB|B)\\b").find(text) ?: return null
        val value = m.groupValues[1].toDoubleOrNull() ?: return null
        return when (m.groupValues[2].uppercase()) {
            "B" -> (value / 1024).toInt()
            "MB" -> (value * 1024).toInt()
            else -> value.toInt()
        }
    }

    // ---------------------------------------------------------------- 编码

    /**
     *  SubtitleCat 的文件大多是 UTF-8，但老上传里有 Shift_JIS / GB18030 / EUC-JP /
     *  windows-1252，不转会全乱码。已是合法 UTF-8 的原样返回；否则每种编码都试着
     *  严格解码，按「文字像不像话」打分取最高，避免猜错编码把内容搅碎。
     */
    private fun decodeSubtitleText(raw: ByteArray): ByteArray {
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

    /** 严格解码：有一个不认识的字节就整体失败，避免静默塞替换字符。 */
    private fun decodeStrict(raw: ByteArray, charset: Charset): ByteArray? {
        val decoder = charset.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        return try {
            val out = java.nio.CharBuffer.allocate(raw.size * 2)
            decoder.decode(java.nio.ByteBuffer.wrap(raw), out, true)
            decoder.flush(out)
            out.flip()
            val chars = CharArray(out.remaining())
            out.get(chars)
            // 统一按 UTF-8 字节返回，调用方（isValidUtf8 / plausibility）都按 UTF-8 读
            String(chars).toByteArray(StandardCharsets.UTF_8)
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

    companion object {
        const val DEFAULT_BASE_URL = "https://www.subtitlecat.com"
        private const val TAG = "SubtitleCat"
        private const val SUBS_DIR = "subs/"
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
        private const val ACCEPT = "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"

        /** 语言代码形如 en、zh-CN。 */
        private val LANG_CODE = Regex("^[A-Za-z][A-Za-z0-9-]{0,11}$")
        /** 文件名尾缀："Name-zh-CN" 的 "zh-CN"，可能带地区。 */
        private val LANGUAGE_SUFFIX = Regex("(?:^|[-._])([A-Za-z]{2,3}(?:-[A-Za-z]{2,4})?)$")

        private val LEGACY_CHARSETS = listOf("Shift_JIS", "GB18030", "EUC-JP", "windows-1252")
    }
}
