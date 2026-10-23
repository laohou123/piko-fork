package dev.piko.shared.library

/**
 * 从刮削源拿到的一部影片的元数据。站点页面缺字段是常态，这里全部可空；
 * 海报墙对空标题显示番号、空封面显示占位图。
 */
data class ScrapedMovie(
    val code: String,
    val title: String? = null,
    val coverUrl: String? = null,
    val releaseDate: String? = null,
    /** 时长（分钟），页面上是「長度: 120分鐘」这种写法；没有时为 null。 */
    val durationMinutes: Int? = null,
    val summary: String? = null,
    val studio: String? = null,
    val series: String? = null,
    val actors: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
    val provider: String,
    /** 详情页大图（MetaTube 的 backdrop）；没有时详情页退回 [coverUrl]。 */
    val bigCoverUrl: String? = null,
    /** 预览片（几十秒的样片），详情页顶部自动播放；没有时顶部显示封面。 */
    val previewVideoUrl: String? = null,
    /**
     * 详情页截图墙的全部图片，顺序即展示顺序，**第一张是封面**。
     * 已换成可直连的代理地址；没有时为空表，详情页不显示截图区。
     */
    val previewImages: List<String> = emptyList(),
    /**
     * MetaTube 的数据源名（JavBus / FANZA / FC2…）。与 [remoteId] 一起拼图片代理地址，
     * 也是「换 MetaTube 域名后重拼图片链接」的依据。
     */
    val remoteProvider: String? = null,
    /** MetaTube 内的影片 ID，同上。 */
    val remoteId: String? = null,
)

/**
 * 元数据刮削源。[scrape] 返回 null 表示没刮到：这个源上没有这部片、页面解析不出
 * 信息、或网络出错，都由实现方内部消化后统一回 null，调用方按「没刮到」处理
 * （海报墙会把它留在待刮削列表里下次补）。
 */
interface JavScraper {
    /** 入库时记在 works.provider 上，标识这部片的元数据是谁刮的。 */
    val providerId: String

    suspend fun scrape(code: String): ScrapedMovie?
}

/**
 * 海报墙用的刮削入口。源地址（JavBus 这类站点经常换域名）由设置项提供，
 * 每次刮削现取，用户在设置里改了域名下一部就生效。
 */
interface LibraryScraper {
    val providerId: String
    val displayName: String

    suspend fun scrape(code: String): ScrapedMovie?
}
