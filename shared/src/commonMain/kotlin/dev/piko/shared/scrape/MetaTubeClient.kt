package dev.piko.shared.scrape

import dev.piko.shared.data.runSuspendCatching
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.http.encodeURLPathPart
import io.ktor.http.isSuccess
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * 用户自己部署的 MetaTube 服务（metatube-sdk-go 的 HTTP 接口），只读。
 *
 * Piko 不内置、不推荐任何实例，地址与令牌都由用户填写。接口照服务端源码（route/route.go）：
 * /v1/providers 公开；/v1/movies/search 与 /v1/movies/{provider}/{id} 要 `Authorization: Bearer <令牌>`，
 * 服务端没设令牌时不校验。响应包在 `{"data": …}` 里，出错是 `{"error": {"code", "message"}}`。
 *
 * 走调用方给的 HttpClient：两端传入的是 OkHttp 引擎，建客户端时取进程默认的 ProxySelector，代理设置照样生效。
 */
class MetaTubeClient(
    private val http: HttpClient,
    baseUrl: String,
    private val token: String,
    private val timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
) {
    private val base = baseUrl.trim().trimEnd('/')

    /** 服务端支持的影片数据源名称。地址填错、服务没起来时在这一步失败。 */
    suspend fun providers(): Result<List<String>> = call("/v1/providers") { body ->
        val data = json.parseToJsonElement(body).jsonObject["data"] as? JsonObject
        (data?.get("movie_providers") as? JsonObject)?.keys?.toList().orEmpty()
    }

    /** 验令牌：取数据库版本，要令牌的接口里最轻的一个。令牌不对时失败为 401 的 [MetaTubeException]。 */
    suspend fun checkToken(): Result<Unit> = call("/v1/db/version") { }

    /**
     * 按番号搜影片。[provider] 为空时服务端在全部数据源里找。
     * 哪里都没有时服务端回 404「info not found」而不是空表（实测），这里按查不到处理，不算出错。
     */
    suspend fun search(query: String, provider: String? = null): Result<List<MetaTubeMovie>> =
        call("/v1/movies/search", { parameter("q", query); provider?.let { parameter("provider", it) } }) { body ->
            json.decodeFromString(MovieListResponse.serializer(), body).data
        }.recoverCatching { error ->
            if (error is MetaTubeException && error.status == HttpStatusCode.NotFound) emptyList() else throw error
        }

    /** 某个数据源上一部影片的详情。 */
    suspend fun movie(provider: String, id: String): Result<MetaTubeMovie> =
        call("/v1/movies/${provider.encodeURLPathPart()}/${id.encodeURLPathPart()}") { body ->
            json.decodeFromString(MovieResponse.serializer(), body).data
        }

    private suspend fun <T> call(
        path: String,
        params: io.ktor.client.request.HttpRequestBuilder.() -> Unit = {},
        parse: (String) -> T,
    ): Result<T> = runSuspendCatching {
        // 超时要当失败返回，不能用 withTimeout：它抛的是 CancellationException，会被当成调用方自己被取消而一路抛上去
        val body = withTimeoutOrNull(timeoutMillis) {
            val response: HttpResponse = http.get(base + path) {
                if (token.isNotBlank()) header("Authorization", "Bearer ${token.trim()}")
                params()
            }
            val text = response.bodyAsText()
            if (!response.status.isSuccess()) throw MetaTubeException(response.status, errorMessageOf(text))
            text
        } ?: throw MetaTubeTimeoutException(timeoutMillis)
        parse(body)
    }

    private fun errorMessageOf(body: String): String? = runCatching {
        json.decodeFromString(ErrorResponse.serializer(), body).error?.message
    }.getOrNull()

    companion object {
        const val DEFAULT_TIMEOUT_MILLIS = 15_000L
        private val json = Json { ignoreUnknownKeys = true }
    }
}

/** 服务端回了非 2xx。[status] 为 401 是令牌不对或没填。 */
class MetaTubeException(val status: HttpStatusCode, val serverMessage: String?) :
    Exception("MetaTube 返回 HTTP ${status.value}" + (serverMessage?.let { "：$it" } ?: ""))

class MetaTubeTimeoutException(timeoutMillis: Long) : Exception("MetaTube 在 ${timeoutMillis / 1000} 秒内没有响应")

/**
 * 搜索结果与详情共有的几项，外加详情返回的完整字段。
 *
 * 服务端 model/movie.go 里 `MovieInfo` 的全部 22 个字段都在这里；Nullable 的一律可空——
 * Go 的 `pq.StringArray` 为空时序列化成 null，按非空类型接会直接抛序列化异常。
 */
@Serializable
data class MetaTubeMovie(
    val id: String = "",
    val number: String = "",
    val title: String = "",
    val provider: String = "",
    val homepage: String = "",
    @SerialName("cover_url") val coverUrl: String = "",
    @SerialName("big_cover_url") val bigCoverUrl: String? = null,
    @SerialName("thumb_url") val thumbUrl: String? = null,
    @SerialName("big_thumb_url") val bigThumbUrl: String? = null,
    /** 片段截图（剧照），一般 8~10 张；有的数据源不提供，为 null 或空表。 */
    @SerialName("preview_images") val previewImages: List<String>? = null,
    @SerialName("preview_video_url") val previewVideoUrl: String? = null,
    @SerialName("preview_video_hls_url") val previewVideoHlsUrl: String? = null,
    /** 发行日期，形如 2021-01-01；没有时服务端给空串或 null。 */
    @SerialName("release_date") val releaseDate: String? = null,
    val summary: String = "",
    /** 时长（分钟）。0 表示服务端没给。 */
    val runtime: Int = 0,
    val score: Double = 0.0,
    val director: String? = null,
    val actors: List<String>? = null,
    val genres: List<String>? = null,
    /** 厂牌（对应 JavBus 的「發行商」）。 */
    val maker: String? = null,
    val label: String? = null,
    val series: String? = null,
)

/**
 * MetaTube 的图片代理端点（`v1/images/...`，在 public 路由组里，**不需要令牌**）。
 *
 * 关键点：图片是服务端自己去源站下载、重新编码成 JPEG 再返回的，所以 App 端既不用带
 * Referer 绕防盗链，也不受源站在客户端网络里不可达的影响。
 * `provider`/`id` 必须用搜索接口返回的值，不能按番号猜（DMM 在 MetaTube 里叫 FANZA）。
 */
object MetaTubeImages {
    /**
     * 封面。默认 **`ratio=0`：不裁剪、保留原始比例**（实测：一张 288×288 的原图，
     * 不带参数会被裁成 192×288 竖版，`ratio=0` 才原样返回）。传其它 [ratio] 才裁剪。
     */
    fun primary(base: String, provider: String, id: String, ratio: Double = 0.0, quality: Int = 85): String =
        "${normalize(base)}/v1/images/primary/${encode(provider)}/${encode(id)}?ratio=$ratio&quality=$quality"

    fun thumb(base: String, provider: String, id: String): String =
        "${normalize(base)}/v1/images/thumb/${encode(provider)}/${encode(id)}"

    /** 详情页大图（背景图，比 cover 更宽）。 */
    fun backdrop(base: String, provider: String, id: String): String =
        "${normalize(base)}/v1/images/backdrop/${encode(provider)}/${encode(id)}"

    /**
     * 用服务端代理任意图片地址：片段截图（`preview_images` 里是源站原始 URL，客户端直连
     * 往往 403 或不通）走这里，由服务端代取。
     *
     * **必须带 `ratio=0`**：不带的话服务端会按默认海报比例（约 2:3）裁一刀，
     * 横向截图会被裁成竖图——这就是「详情页截图全变竖的」的原因。
     */
    fun byUrl(base: String, provider: String, id: String, url: String, quality: Int = 85): String =
        "${normalize(base)}/v1/images/primary/${encode(provider)}/${encode(id)}" +
            "?url=${encode(url)}&ratio=0&quality=$quality"

    private fun normalize(base: String) = base.trim().trimEnd('/')

    private fun encode(value: String) = value.encodeURLPathPart()
}

@Serializable
private class MovieListResponse(val data: List<MetaTubeMovie> = emptyList())

@Serializable
private class MovieResponse(val data: MetaTubeMovie)

@Serializable
private class ErrorResponse(val error: ErrorBody? = null)

@Serializable
private class ErrorBody(val code: Int = 0, val message: String? = null)
