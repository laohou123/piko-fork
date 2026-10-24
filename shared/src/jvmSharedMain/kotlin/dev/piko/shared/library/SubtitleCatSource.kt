package dev.piko.shared.library

import dev.piko.shared.data.runSuspendCatching
import dev.piko.shared.log.PikoLog
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.http.encodeURLPathPart
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import org.jsoup.Jsoup
import java.net.URLEncoder
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
        // 站点把「能翻但还没人翻」的语言也列出来（一百多个），全列出来会刷屏，
        // 所以只有中文进列表当一行，其余的只报个总数
        val translatable = mutableListOf<SubtitleCandidate>()
        var otherTranslatable = 0

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
                            label = friendlyLabel(language, label),
                            path = path,
                        ),
                    )
                }
                continue
            }
            // 没有下载链接：站点上还没有这个语言，页面上给的是 Translate 按钮。
            // 按钮里带着翻译要用的三样东西——目标语言码、原始字幕名、字幕所在目录。
            if (code.isEmpty()) continue
            val origin = translateOrigin(block)
            if (origin == null) { otherTranslatable++; continue }
            val originPath = normalizeSubsPath("${origin.folder.trimEnd('/')}/${origin.file}")
            if (originPath == null) { otherTranslatable++; continue }
            val entry = SubtitleCandidate(
                language = code,
                label = friendlyLabel(code, label),
                path = originPath,
                translatable = true,
            )
            if (code.startsWith("zh")) translatable.add(entry) else otherTranslatable++
        }

        // 原始字幕摆在详情页旁边，规律固定，拼出来放最前面
        val origPath = originalSubtitlePath(clean)
        if (origPath != null && seen.add(origPath)) {
            candidates.add(0, SubtitleCandidate(language = "orig", label = "原始语言", path = origPath, original = true))
        }

        val name = doc.selectFirst("title")?.text()?.trim().orEmpty()
        // 中文可翻译的排在可下载的后头：先给现成的，没有再让它翻
        val all = candidates + translatable
        PikoLog.i(TAG, "详情 $clean：${candidates.size} 个可下，中文可翻 ${translatable.size} 个，其它可翻 $otherTranslatable 种")
        return SubtitleDetail(name = name, candidates = all, translatableCount = otherTranslatable)
    }

    /** 站点上写的是英文语言名，中文那几条换成中文说法，看着顺一点。 */
    private fun friendlyLabel(code: String, siteLabel: String): String {
        if (!code.startsWith("zh")) return siteLabel.ifEmpty { code }
        return when (code) {
            "zh-CN", "zh-Hans", "zh" -> "中文（简体）"
            "zh-TW", "zh-HK", "zh-Hant" -> "中文（繁体）"
            else -> "中文"
        }
    }

    /** 从 Translate 按钮的 onclick 里取「语言码、原始字幕名、目录」三样。 */
    private fun translateOrigin(block: org.jsoup.nodes.Element): TranslateOrigin? {
        val onclick = block.selectFirst("button[onclick*=translate_from_server_folder]")
            ?.attr("onclick").orEmpty()
        val args = TRANSLATE_CALL.find(onclick) ?: return null
        return TranslateOrigin(file = args.groupValues[2], folder = args.groupValues[3])
    }

    private class TranslateOrigin(val file: String, val folder: String)

    override suspend fun download(path: String): ByteArray? {
        val clean = normalizeSubsPath(path) ?: return null
        if (!clean.lowercase().endsWith(".srt")) {
            PikoLog.w(TAG, "拒绝下载非 srt 路径：$clean")
            return null
        }
        val raw = getBytes(clean) ?: return null
        return decodeSubtitleText(raw)
    }

    override suspend fun requestTranslation(candidate: SubtitleCandidate): String? {
        if (!candidate.translatable) return "这一条已经能直接下载了"
        val origPath = normalizeSubsPath(candidate.path) ?: return "拿不到原始字幕的路径"
        val raw = download(origPath) ?: return "站点上找不到原始字幕，没法翻译"
        val text = raw.toString(Charsets.UTF_8)
        if (text.isBlank()) return "原始字幕是空的，没法翻译"

        val target = candidate.language
        val fileName = origPath.substringAfterLast('/').replace(ORIG_SUFFIX, ".srt")
        PikoLog.i(TAG, "请求翻译 $origPath → $target")

        val result = translateByBatches(text, target)
        if (result == null) {
            // 站点网页用的就是 Google 的免费接口：本机访问不到，就照它那样翻不出来。
            // 退一步把任务挂到站点的共享队列上——那边有别的访客的浏览器能翻
            return if (enqueueSharedTranslation(text, target)) {
                "本机连不上站点用的翻译接口，已把这一条挂到站点队列，过几分钟重新搜索看看"
            } else {
                "翻不了：站点用的免费翻译接口（translate.googleapis.com）本机连不上，可到网站上点 Translate"
            }
        }
        val error = uploadTranslation(fileName, result.first, target, result.second)
        return error ?: "已把译好的字幕传回站点，过几分钟重新搜索就能下载"
    }

    /**
     * 照站点网页的做法翻一份字幕：跳过序号与时间轴，其余按 500 字一批送去翻译。
     * 返回「译文全文 + 源语言」，翻译接口连不上时给 null。
     */
    private suspend fun translateByBatches(text: String, target: String): Pair<String, String>? {
        val lines = text.split('\n')
        val out = lines.toMutableList()
        val batches = mutableListOf<List<Int>>()
        var current = mutableListOf<Int>()
        var chars = 0
        for (i in lines.indices) {
            val line = lines[i]
            // 序号行与时间轴不翻：翻了反而对不上格式
            if (line.isBlank() || STRUCTURAL_LINE.matches(line)) continue
            if (chars + line.length + 1 >= BATCH_CHARS && current.isNotEmpty()) {
                batches.add(current)
                current = mutableListOf()
                chars = 0
            }
            current.add(i)
            chars += line.length + 1
        }
        if (current.isNotEmpty()) batches.add(current)
        if (batches.isEmpty()) return null

        var sourceLanguage = ""
        for (batch in batches) {
            val input = batch.joinToString("\n") { lines[it].sanitize() }
            val translated = googleTranslate(input, target)
            if (translated == null) {
                PikoLog.w(TAG, "翻译接口不可用，中止这一轮")
                return null
            }
            sourceLanguage = translated.second.ifEmpty { sourceLanguage }
            val parts = translated.first.split('\n')
            if (parts.size == batch.size) {
                batch.forEachIndexed { k, index -> out[index] = parts[k] }
            } else {
                // 行数对不上就逐行翻，宁可多请求几次也不能让字幕错位
                for (index in batch) {
                    val one = googleTranslate(lines[index].sanitize(), target) ?: return null
                    out[index] = one.first
                }
            }
        }
        return out.joinToString("\n") to sourceLanguage
    }

    /** 站点网页就是这么调的：client=gtx 的免费接口。返回「译文 + 源语言」。 */
    private suspend fun googleTranslate(text: String, target: String): Pair<String, String>? {
        if (text.isBlank()) return "" to ""
        val url = GOOGLE_TRANSLATE +
            "?client=gtx&sl=auto&tl=${URLEncoder.encode(target, "UTF-8")}" +
            "&dt=t&q=${URLEncoder.encode(text, "UTF-8")}"
        return runSuspendCatching {
            val response: HttpResponse = http.get(url) { header("User-Agent", USER_AGENT) }
            if (response.status != HttpStatusCode.OK) {
                PikoLog.w(TAG, "翻译接口 HTTP ${response.status.value}")
                null
            } else {
                val body = response.bodyAsText()
                val root = Json.parseToJsonElement(body) as? JsonArray
                val segments = root?.getOrNull(0) as? JsonArray
                val value = segments?.joinToString("") { (it as? JsonArray)?.getOrNull(0).textOrEmpty() }
                val from = root?.getOrNull(2).textOrEmpty()
                if (value.isNullOrEmpty()) null else value to from
            }
        }.getOrElse { error ->
            PikoLog.w(TAG, "翻译请求失败：${error.message}")
            null
        }
    }

    private fun JsonElement?.textOrEmpty(): String =
        (this as? kotlinx.serialization.json.JsonPrimitive)?.content ?: ""

    /** 把译文传回站点存档：传完之后这个语言就跟其它语言一样能搜到、能下载。 */
    private suspend fun uploadTranslation(
        fileName: String,
        content: String,
        language: String,
        origLanguage: String,
    ): String? {
        val body = listOf(
            "filename" to fileName,
            "content" to content,
            "language" to language,
            "orig_language" to origLanguage,
            "private_id" to "0",
        ).joinToString("&") { (key, value) ->
            "${URLEncoder.encode(key, "UTF-8")}=${URLEncoder.encode(value, "UTF-8")}"
        }
        awaitTurn()
        return runSuspendCatching {
            val response: HttpResponse = http.post(base + "/upload_subtitles.php") {
                header("User-Agent", USER_AGENT)
                header("Content-Type", "application/x-www-form-urlencoded")
                setBody(body)
            }
            val text = response.bodyAsText()
            if (response.status != HttpStatusCode.OK || text.contains("error", ignoreCase = true)) {
                PikoLog.w(TAG, "上传译文失败 ${response.status.value}：$text")
                "传回站点失败（${response.status.value}）"
            } else {
                null
            }
        }.getOrElse { error ->
            PikoLog.w(TAG, "上传译文异常：${error.message}")
            "传回站点失败：${error.message}"
        }
    }

    /** 本机翻不动时的退路：挂到站点的共享队列，让别人（或网站自己）去翻。 */
    private suspend fun enqueueSharedTranslation(text: String, target: String): Boolean {
        val body = listOf(
            "content" to text.take(SHARED_LIMIT),
            "to_language" to target,
        ).joinToString("&") { (key, value) ->
            "${URLEncoder.encode(key, "UTF-8")}=${URLEncoder.encode(value, "UTF-8")}"
        }
        awaitTurn()
        return runSuspendCatching {
            val response: HttpResponse = http.post(base + "/request_shared_translation.php") {
                header("User-Agent", USER_AGENT)
                header("Content-Type", "application/x-www-form-urlencoded")
                setBody(body)
            }
            response.status == HttpStatusCode.OK && !response.bodyAsText().contains("error", ignoreCase = true)
        }.getOrElse { false }
    }

    private fun String.sanitize(): String = replace(FONT_OPEN, "").replace(FONT_CLOSE, "").replace("&", "and")

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

        /** 详情页 Translate 按钮：translate_from_server_folder('语言码', '原名-orig.srt', '/subs/1234/') */
        private val TRANSLATE_CALL =
            Regex("""translate_from_server_folder\(\s*'([^']*)'\s*,\s*'([^']*)'\s*,\s*'([^']*)'""")
        private val ORIG_SUFFIX = Regex("-orig\\.srt$", RegexOption.IGNORE_CASE)
        /** 序号行与时间轴：原样保留，不送去翻译。 */
        private val STRUCTURAL_LINE = Regex("""^[0-9 \r]*$|^[0-9,: ]*-->[0-9,: \r]*$""")
        private val FONT_OPEN = Regex("<font[^>]*>", RegexOption.IGNORE_CASE)
        private val FONT_CLOSE = Regex("</font>", RegexOption.IGNORE_CASE)
        private const val GOOGLE_TRANSLATE = "https://translate.googleapis.com/translate_a/single"
        /** 与站点网页一致：一批 500 字，太少请求多、太多接口拒。 */
        private const val BATCH_CHARS = 500
        private const val SHARED_LIMIT = 4_000

        /** 语言代码形如 en、zh-CN。 */
        private val LANG_CODE = Regex("^[A-Za-z][A-Za-z0-9-]{0,11}$")
        /** 文件名尾缀："Name-zh-CN" 的 "zh-CN"，可能带地区。 */
        private val LANGUAGE_SUFFIX = Regex("(?:^|[-._])([A-Za-z]{2,3}(?:-[A-Za-z]{2,4})?)$")

        private val LEGACY_CHARSETS = listOf("Shift_JIS", "GB18030", "EUC-JP", "windows-1252")
    }
}
