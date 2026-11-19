package dev.piko.shared.library

import dev.piko.data.auth.PikoUserPreferences
import dev.piko.shared.log.PikoLog
import dev.piko.shared.naming.av.matchAvFile
import dev.piko.shared.naming.av.normalizeAvCode
import dev.piko.shared.scrape.MetaTubeClient
import dev.piko.shared.scrape.MetaTubeImages
import dev.piko.shared.scrape.MetaTubeMovie
import io.ktor.client.HttpClient
import kotlinx.coroutines.flow.first

/**
 * MetaTube 刮削源：按番号搜 → 命中的那条拉详情 → 把图片全部换成服务端的代理地址。
 *
 * 为什么图片一定走 MetaTube：`v1/images/...` 是 public 接口，服务端自己去源站取图、
 * 重新编码成 JPEG 再返回，客户端既不碰防盗链，也不受源站在手机网络里不可达的影响。
 * 这一版砍掉 JavBus 直抓就是因为直连源站这条路走不通（反爬 + 图片 403）。
 *
 * 地址与令牌复用设置里的 MetaTube 配置：用户只配一次，改名与刮削共用。
 */
class MetaTubeScraper(
    private val preferences: PikoUserPreferences,
    newHttpClient: () -> HttpClient,
) : LibraryScraper {
    private val http by lazy(newHttpClient)

    override val providerId: String = PROVIDER_ID
    override val displayName: String = "MetaTube"

    /**
     * 探一下服务通不通：拿 MetaTube 自己的 `/v1/db/version` 试一次（与设置里「测试连接」同一个端点）。
     * 通就返回 null；不通给出一句用户看得懂的原因，好区分「服务不通」与「这片子没有」。
     */
    override suspend fun probe(): String? {
        val base = preferences.metaTubeUrlFlow.first().trim()
        if (base.isEmpty()) return "设置里还没填 MetaTube 地址"
        val client = MetaTubeClient(http, base, preferences.metaTubeTokenFlow.first())
        return client.checkToken().exceptionOrNull()?.let { error ->
            PikoLog.w(TAG, "MetaTube 探测失败：${error.message}")
            // 只留说得清的那部分：地址、令牌或超时，够用户知道下一步查什么
            error.message?.takeIf { it.isNotBlank() } ?: "连接失败"
        }
    }

    override suspend fun scrape(code: String): ScrapedMovie? {
        // 文件名认不出番号的作品（key 就是文件名）不可能搜到，直接跳过，不发无谓请求
        if (matchAvFile(code) == null) return null
        val base = preferences.metaTubeUrlFlow.first().trim()
        if (base.isEmpty()) return null
        val client = MetaTubeClient(http, base, preferences.metaTubeTokenFlow.first())

        val found = client.search(code).getOrElse { error ->
            PikoLog.w(TAG, "$code: 搜索失败 ${error.message}")
            return null
        }
        // 同一番号可能在多个源上都有，各源字段覆盖差别很大（同一个番号，A 源有简介/演员/截图，
        // B 源只有标题甚至只有番号）。先按源优先级排，再逐个拉详情比较完整度。
        val candidates = found.filter { matches(it.number, code) }
            .sortedBy { providerRank(it.provider) }
        if (candidates.isEmpty()) {
            PikoLog.i(TAG, "$code: 没有数据源命中")
            return null
        }

        // 前两个候选一律拉详情：搜索结果比详情少字段，只看搜索结果无法判断哪个源更全。
        // 详情请求失败就退回搜索结果（字段少，但至少还有 provider/id）。
        val detailed = candidates.take(2).map { candidate ->
            candidate to client.movie(candidate.provider, candidate.id).getOrElse { candidate }
        }
        // 主源按「内容完整度」选，而不是「谁有预览片」。以前只要第一个候选没预览片就换下一个源，
        // 常常从字段齐全的源换到只有标题的源，最后写进库里的是个空壳。
        val (chosen, info) = detailed.maxWithOrNull(
            compareBy<Pair<MetaTubeMovie, MetaTubeMovie>> { completeness(it.second) }
                .thenByDescending { providerRank(it.first.provider) },
        )!!

        val provider = chosen.provider
        val id = chosen.id
        if (provider.isBlank() || id.isBlank()) return null

        // 一个字段都没拿到（标题、简介、截图全空）：这不是「刮到了」，不入库，留给下一轮重试。
        // 以前这种记录照样写库并打上 scraped_at，之后 codesNeedingScrape 再也不会选中它，
        // 于是「搜索有命中、详情是空的」片子会永久停在只有封面没有标题的状态。
        if (info.title.isBlank() && info.summary.isBlank() && info.previewImages.isNullOrEmpty()) {
            PikoLog.w(TAG, "$code: ${candidates.size} 个源都只有番号没有详情，按没刮到处理，下次重试")
            return null
        }

        // 原图比例：不带 ratio 参数，服务端按 provider 的默认比例输出（封面多为竖版）
        val cover = MetaTubeImages.primary(base, provider, id)
        val shots = info.previewImages.orEmpty()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { MetaTubeImages.byUrl(base, provider, id, it) }

        // 详情接口偶尔比搜索结果少字段：同一个源有时在 detail 里给 actors / genres 空表或 null，
        // 搜索列表里却有。空着不用就会变成「有标题有演员、就是没有标签」——缺哪样就用候选补哪样，
        // 只在 detail 完全没有时才补，免得把另一个源的标签混进来。
        // 候选里既有搜索结果（字段少），也有上面拉到的另一个源的详情（字段全），后者优先。
        fun valuesOf(pick: (MetaTubeMovie) -> List<String>?, source: MetaTubeMovie): List<String> =
            pick(source).orEmpty().map { it.trim() }.filter { it.isNotEmpty() }

        fun complement(pick: (MetaTubeMovie) -> List<String>?): List<String> =
            valuesOf(pick, info).ifEmpty {
                (detailed.map { it.second } + candidates).flatMap { valuesOf(pick, it) }
            }.distinct()

        var tags = complement { it.genres }
        if (tags.isEmpty()) {
            // 候选里也没有：再去别的源的详情问一次。同一部片各源字段覆盖不一样，标签常常只在另一处有。
            // 只在缺标签时多这一次请求，拿到就停
            for (candidate in candidates) {
                if (candidate.provider == provider && candidate.id == id) continue
                val other = runCatching { client.movie(candidate.provider, candidate.id) }
                    .getOrNull()?.getOrNull() ?: continue
                val found = valuesOf({ it.genres }, other)
                if (found.isNotEmpty()) {
                    PikoLog.i(TAG, "$code: 标签取自另一个源 ${candidate.provider}/${candidate.id}")
                    tags = found.distinct()
                    break
                }
            }
        }

        // 预览片：主源没有就从别的源借一个地址。它只是一段几十秒的样片 URL，与主源的
        // provider/id 无关，不需要为此换源（换了 provider/id，封面、截图代理地址全跟着变）。
        var preview = info.previewVideoUrl?.trim().orEmpty()
        if (preview.isEmpty()) {
            preview = detailed.asSequence()
                .mapNotNull { it.second.previewVideoUrl?.trim() }
                .firstOrNull { it.isNotEmpty() }
                .orEmpty()
        }
        if (preview.isEmpty()) {
            // 还没找到：再问最多两个别的源。预览片常常只在 FANZA/DMM 这类源上有
            for (candidate in candidates.drop(2).take(2)) {
                val url = client.movie(candidate.provider, candidate.id).getOrNull()
                    ?.previewVideoUrl?.trim().orEmpty()
                if (url.isNotEmpty()) {
                    preview = url
                    break
                }
            }
        }

        val scraped = ScrapedMovie(
            code = code,
            title = info.title.trim().ifEmpty { null },
            coverUrl = cover,
            bigCoverUrl = MetaTubeImages.backdrop(base, provider, id),
            releaseDate = info.releaseDate?.trim()?.ifEmpty { null },
            durationMinutes = info.runtime.takeIf { it > 0 },
            summary = info.summary.trim().ifEmpty { null },
            studio = info.maker?.trim()?.ifEmpty { null },
            series = info.series?.trim()?.ifEmpty { null },
            actors = complement { it.actors },
            tags = tags,
            provider = PROVIDER_ID,
            previewVideoUrl = preview.ifEmpty { null },
            // 封面固定排第一：详情页截图墙按这个顺序展示，封面也是一张截图
            previewImages = listOf(cover) + shots,
            remoteProvider = provider,
            remoteId = id,
        )
        PikoLog.i(
            TAG,
            "$code: 刮到《${scraped.title ?: "?"}》 via $provider/$id，" +
                "截图 ${scraped.previewImages.size} 张，演员 ${scraped.actors.size}，标签 ${scraped.tags.size}",
        )
        // 有标题有演员却没有标签，多半是源端 genres 空：记一条，下次看日志就知道是源的问题还是解析的问题
        if (scraped.tags.isEmpty()) {
            PikoLog.w(TAG, "$code: 源 $provider/$id 与 ${candidates.size} 个候选都没给标签（genres 为空）")
        }
        return scraped
    }

    /**
     * 数据源优先级：同分时的兜底排序。FANZA（DMM）这类源字段更全、也常带预览片，排前面；
     * JavBus 只抓页面字段、没有预览片，明确排最后。不在列表里的按中档处理。
     */
    private fun providerRank(provider: String): Int {
        val index = PREFERRED_PROVIDERS.indexOfFirst { it.equals(provider, ignoreCase = true) }
        return if (index >= 0) index else PREFERRED_PROVIDERS.size / 2
    }

    /**
     * 一条详情「值不值得入库」的粗评分。标题权重最高：没有标题的卡片在海报墙上等于没刮到；
     * 简介、演员、标签决定详情页有没有内容；截图和预览片只影响观感，分给得低。
     *
     * 只用来在同一番号的几个源之间比大小，绝对值没有意义。
     */
    private fun completeness(movie: MetaTubeMovie): Int {
        var score = 0
        if (movie.title.isNotBlank()) score += 6
        if (movie.summary.isNotBlank()) score += 3
        if (!movie.actors.isNullOrEmpty()) score += 3
        if (!movie.genres.isNullOrEmpty()) score += 3
        if (!movie.previewImages.isNullOrEmpty()) score += 2
        if (!movie.previewVideoUrl.isNullOrBlank()) score += 1
        if (!movie.releaseDate.isNullOrBlank()) score += 1
        if (!movie.maker.isNullOrBlank()) score += 1
        if (!movie.series.isNullOrBlank()) score += 1
        return score
    }

    /** 只认番号对得上的结果，宁可不用也不套上别的片子的名字。 */
    private fun matches(number: String, code: String): Boolean {
        if (number.isBlank()) return false
        if (number.equals(code, ignoreCase = true)) return true
        return runCatching { normalizeAvCode(number) == code }.getOrDefault(false)
    }

    private companion object {
        const val TAG = "MetaTubeScraper"
        const val PROVIDER_ID = "metatube"

        /** 字段更全、通常也带预览片的数据源，越靠前越优先；JavBus 没有预览片，排最后兜底。 */
        val PREFERRED_PROVIDERS = listOf(
            "FANZA", "DMM", "FALENO", "S1", "MOODYZ", "SOD", "MGS", "PRESTIGE", "JAVFREE", "JAVBUS",
        )
    }
}
