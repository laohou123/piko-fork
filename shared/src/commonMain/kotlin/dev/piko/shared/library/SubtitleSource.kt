package dev.piko.shared.library

/**
 * 字幕源。V1 接 SubtitleCat（无公开 API，纯 HTML 爬虫），站点改版就会挂，
 * 因此接口只承诺三件事：搜、列详情、拿字节；解析细节全在实现里。
 */
interface SubtitleSource {
    /** 按关键词（通常是番号）搜字幕，返回搜索页上的条目。 */
    suspend fun search(query: String): List<SubtitleHit>

    /** 一个字幕详情页：该页面下所有可直接下载的语言文件。 */
    suspend fun detail(pagePath: String): SubtitleDetail?

    /** 下载一个字幕文件，返回 UTF-8 字节（站点上有少量非 UTF-8 的老上传，实现里转码）。 */
    suspend fun download(path: String): ByteArray?
}

/** 搜索结果里的一行。 */
data class SubtitleHit(
    val name: String,
    val pagePath: String,
    val languages: Int = 0,
    val sizeKb: Int = 0,
)

/** 详情页里一个可下载的字幕文件。 */
data class SubtitleCandidate(
    /** 语言标签，如 zh-CN、ja、orig（原始语言）。 */
    val language: String,
    /** 给人看的名字，优先页面上的语言名。 */
    val label: String,
    /** 站点内的文件路径，下载时原样传回。 */
    val path: String,
    /** 是不是详情页推导出的原始字幕（-orig.srt）。 */
    val original: Boolean = false,
)

data class SubtitleDetail(
    val name: String,
    val candidates: List<SubtitleCandidate>,
    /** 站点还支持、但还没人上传的语言（提示用户可以去网站请求翻译）。 */
    val translatable: List<String> = emptyList(),
)
