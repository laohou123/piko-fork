package dev.piko.shared.library

import dev.piko.shared.data.runSuspendCatching
import dev.piko.shared.log.PikoLog
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import java.net.URLEncoder

/**
 * 迅雷字幕源的接口版（api-shoulei-ssl.xunlei.com），与 SubtitleCat 那条 HTML 爬虫并列。
 *
 * 接口一次返回整组结果，每条本身就是可直接下载的 srt 直链，没有「详情页」这一层：
 *  - `GET /oracle/subtitle?name={番号}` → `{"code":0,"data":[{url,name,ext,duration,...}]}`
 *  - `duration` 是片长（毫秒），同一部片常有多条不同片长的字幕，列表里显示出来方便对时长
 *
 * 仍按 [SubtitleSource] 的三段式实现：搜索把结果记进 [cache]，详情按直链取回那一条，
 * 下载直接拉字节。这样界面不用为某个源写特例分支。
 *
 * 只收 srt：App 里字幕链路的文件名与 mpv 的加载都按 srt 走，ass/ssa 拉下来也用不上。
 */
class XunleiSubtitleSource(
    private val http: HttpClient,
    private val endpoint: String = DEFAULT_ENDPOINT,
    private val minRequestIntervalMillis: Long = 300L,
) : SubtitleSource {

    /** 最近一次搜索的结果，key 是直链。详情与下载都从这里取，不额外请求。 */
    private val cache = LinkedHashMap<String, XunleiEntry>()
    private val cacheLock = Mutex()
    private val gate = Mutex()
    private var lastRequestAt = 0L

    override suspend fun search(query: String): List<SubtitleHit> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return emptyList()

        val url = endpoint + "?name=" + URLEncoder.encode(trimmed, "UTF-8")
        val body = runSuspendCatching {
            awaitTurn()
            val response: HttpResponse = http.get(url) { header("User-Agent", USER_AGENT) }
            if (response.status != HttpStatusCode.OK) {
                PikoLog.w(TAG, "搜索 HTTP ${response.status.value}")
                null
            } else {
                response.bodyAsText()
            }
        }.getOrElse { error ->
            PikoLog.w(TAG, "搜索失败：${error.message}")
            null
        } ?: return emptyList()

        val entries = parseXunleiResponse(body)
        cacheLock.withLock {
            cache.clear()
            entries.forEach { cache[it.url] = it }
        }
        PikoLog.i(TAG, "搜「$trimmed」：${entries.size} 条")
        return entries.map { it.toHit() }
    }

    override suspend fun detail(pagePath: String): SubtitleDetail? {
        val url = pagePath.trim()
        if (url.isEmpty()) return null
        val entry = cacheLock.withLock { cache[url] }
        val fileName = entry?.name ?: url.substringAfterLast('/').substringBefore('?')
        val label = if (entry != null && entry.durationMillis > 0) {
            "原始语言 · ${formatDurationLabel(entry.durationMillis)}"
        } else {
            "原始语言"
        }
        return SubtitleDetail(
            name = fileName,
            candidates = listOf(
                SubtitleCandidate(
                    language = "orig",
                    label = label,
                    path = url,
                    original = true,
                ),
            ),
        )
    }

    override suspend fun download(path: String): ByteArray? {
        val url = path.trim()
        // 只接受 http(s)：这个路径只会来自搜索结果，这里再挡一次非网络地址
        if (!url.startsWith("https://") && !url.startsWith("http://")) {
            PikoLog.w(TAG, "拒绝下载非 http 路径：$url")
            return null
        }
        val raw = runSuspendCatching {
            awaitTurn()
            val response: HttpResponse = http.get(url) { header("User-Agent", USER_AGENT) }
            if (response.status != HttpStatusCode.OK) {
                PikoLog.w(TAG, "下载 HTTP ${response.status.value} $url")
                null
            } else {
                response.bodyAsBytes()
            }
        }.getOrElse { error ->
            PikoLog.w(TAG, "下载失败 $url：${error.message}")
            null
        } ?: return null
        return SubtitleTextCodec.decode(raw)
    }

    private suspend fun awaitTurn() {
        gate.withLock {
            val wait = lastRequestAt + minRequestIntervalMillis - System.currentTimeMillis()
            if (wait > 0) kotlinx.coroutines.delay(wait)
            lastRequestAt = System.currentTimeMillis()
        }
    }

    companion object {
        const val DEFAULT_ENDPOINT = "https://api-shoulei-ssl.xunlei.com/oracle/subtitle"
        private const val TAG = "XunleiSubtitle"
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
    }
}

/** 接口返回的一条字幕。字段名与接口一致，解析时做了容错（缺字段就跳过）。 */
internal data class XunleiEntry(
    val url: String,
    val name: String,
    val durationMillis: Long,
    val uploader: String,
) {
    fun toHit(): SubtitleHit = SubtitleHit(
        name = buildString {
            append(name.removeSuffix(".srt").removeSuffix(".SRT"))
            if (uploader.isNotBlank()) append(" ").append(uploader)
        },
        pagePath = url,
        durationMillis = durationMillis,
    )
}

/**
 * 解析接口返回。单独拎出来便于单测：接口挂了也能用固定 JSON 验证解析逻辑。
 *
 * 容错规则：`code != 0` 视为空结果；条目缺 url 或 url 不是 http(s) 直接跳过；
 * 明确标了非 srt 的也跳过（没写 ext 的保留，下面按字节解码，能读出文本就是字幕）。
 */
internal fun parseXunleiResponse(json: String): List<XunleiEntry> {
    val root = runCatching { Json.parseToJsonElement(json) as? JsonObject }.getOrNull() ?: return emptyList()
    val code = (root["code"] as? JsonPrimitive)?.intOrNull ?: return emptyList()
    if (code != 0) return emptyList()
    val data = root["data"] as? JsonArray ?: return emptyList()

    val seen = mutableSetOf<String>()
    val out = mutableListOf<XunleiEntry>()
    for (element in data) {
        val obj = element as? JsonObject ?: continue
        val url = obj.str("url")?.trim()?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
            ?: continue
        val ext = obj.str("ext")?.lowercase().orEmpty()
        if (ext.isNotEmpty() && ext != "srt") continue
        if (!seen.add(url)) continue
        val name = obj.str("name")?.trim()?.ifBlank { url.substringAfterLast('/') } ?: continue
        val duration = (obj["duration"] as? JsonPrimitive)?.longOrNull ?: 0L
        val uploader = obj.str("extra_name").orEmpty()
        out.add(XunleiEntry(url = url, name = name, durationMillis = duration, uploader = uploader))
    }
    return out
}

private fun JsonObject.str(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNull
