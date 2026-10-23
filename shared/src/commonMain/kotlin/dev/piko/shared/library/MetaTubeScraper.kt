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
        // 同一番号可能在多个源上都有：按优先级排，带预览片的源排前面。
        // JavBus 只抓页面字段、从不返回 preview_video_url，所以排最后兜底。
        val candidates = found.filter { matches(it.number, code) }
            .sortedBy { providerRank(it.provider) }
        if (candidates.isEmpty()) {
            PikoLog.i(TAG, "$code: 没有数据源命中")
            return null
        }

        // 依次取前两个候选的详情：第一个没有预览片就再试一个（多一次请求，只在这种情况发生）
        var movie: MetaTubeMovie? = null
        var chosen: MetaTubeMovie = candidates.first()
        for (candidate in candidates.take(2)) {
            val detail = client.movie(candidate.provider, candidate.id).getOrElse { candidate }
            chosen = candidate
            movie = detail
            if (!detail.previewVideoUrl.isNullOrBlank()) break
        }
        val info = movie ?: chosen

        val provider = chosen.provider
        val id = chosen.id
        if (provider.isBlank() || id.isBlank()) return null

        // 原图比例：不带 ratio 参数，服务端按 provider 的默认比例输出（封面多为竖版）
        val cover = MetaTubeImages.primary(base, provider, id)
        val shots = info.previewImages.orEmpty()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { MetaTubeImages.byUrl(base, provider, id, it) }

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
            actors = info.actors.orEmpty().map { it.trim() }.filter { it.isNotEmpty() },
            tags = info.genres.orEmpty().map { it.trim() }.filter { it.isNotEmpty() },
            provider = PROVIDER_ID,
            previewVideoUrl = info.previewVideoUrl?.trim()?.ifEmpty { null },
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
        return scraped
    }

    /**
     * 数据源优先级：FANZA（DMM）这类源会返回 preview_video_url（预览片）与更全的字段，
     * 排前面；JavBus 只抓页面字段、没有预览片，排最后兜底。不在列表里的按中档处理。
     */
    private fun providerRank(provider: String): Int {
        val index = PREFERRED_PROVIDERS.indexOfFirst { it.equals(provider, ignoreCase = true) }
        return if (index >= 0) index else PREFERRED_PROVIDERS.size / 2
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

        /** 会返回预览片的数据源，越靠前越优先；JavBus 不在其中（它没有预览片）。 */
        val PREFERRED_PROVIDERS = listOf("FANZA", "DMM", "FALENO", "S1", "MOODYZ", "SOD", "MGS", "PRESTIGE", "JAVFREE")
    }
}
