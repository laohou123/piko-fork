package dev.piko.shared.translate

import dev.piko.shared.data.runSuspendCatching
import dev.piko.shared.log.PikoLog
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 调用户自己填的大模型接口（OpenAI 兼容的 `/chat/completions`）。
 *
 * 支持 DeepSeek、OpenAI、通义、各家中转站、本地 Ollama / LM Studio —— 只要它认这套协议。
 * 地址填到哪一层都行：`https://api.deepseek.com`、`https://api.deepseek.com/v1`、
 * 甚至完整的 `.../chat/completions` 都接受，路径在第一次成功调用后记住。
 *
 * 失败重试只针对「等一会可能就好了」的错误（429、5xx、超时）；401/403 这类配置错误立刻失败，
 * 免得用户对着转圈等两分钟才看到「未授权」。
 */
class TranslationClient(
    private val http: HttpClient,
    baseUrl: String,
    private val apiKey: String,
    private val model: String,
    /** null 表示不传推理参数（最快）；`low`/`medium`/`high` 见 [REASONING_LEVELS]。 */
    private val reasoningEffort: String? = null,
    private val timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
) {
    private val base = baseUrl.trim().trimEnd('/')

    /** 第一次成功后记下来，之后不再探测。 */
    @Volatile
    private var resolvedEndpoint: String? = null

    /** 接口明确拒绝过 reasoning_effort：之后一律不带，免得每次都白挨一次 400。 */
    @Volatile
    private var reasoningUnsupported = false

    /** 配好了没有：三样都填了才算。 */
    val configured: Boolean get() = base.isNotEmpty() && apiKey.isNotEmpty() && model.isNotEmpty()

    /**
     * 翻一段文本。[system] 是规则，[input] 是待译内容，返回模型输出的原文（未清洗）。
     */
    suspend fun translate(system: String, input: String, maxTokens: Int = 0): Result<String> {
        if (!configured) return Result.failure(TranslationException("还没配置翻译模型"))
        // 推理模式的思考过程也算 token，给少了会被截断；调用方显式给了就听调用方的
        val budget = if (maxTokens > 0) maxTokens else if (effectiveReasoning != null) 4096 else 2048
        return runSuspendCatching { callWithRetry(system, input, budget) }
    }

    private val effectiveReasoning: String?
        get() = reasoningEffort?.takeIf { !reasoningUnsupported }

    private suspend fun callWithRetry(system: String, input: String, maxTokens: Int): String {
        var backoff = RETRY_BASE_DELAY_MS
        var last: Throwable? = null
        repeat(MAX_ATTEMPTS) { attempt ->
            try {
                return callOnce(system, input, maxTokens)
            } catch (e: TranslationException) {
                // 接口不认识 reasoning_effort：去掉它重来一次，别让用户为此改配置
                if (e.reasoningRejected && !reasoningUnsupported) {
                    PikoLog.i(TAG, "接口不接受 reasoning_effort，本次起改用普通模式")
                    reasoningUnsupported = true
                    return callOnce(system, input, maxTokens)
                }
                last = e
                // 配置类错误不重试：重试也还是同一个答案
                if (!e.retryable || attempt == MAX_ATTEMPTS - 1) throw e
            } catch (e: Throwable) {
                last = e
                if (attempt == MAX_ATTEMPTS - 1) throw e
            }
            PikoLog.w(TAG, "翻译请求失败，${backoff}ms 后重试：${last?.message}")
            delay(backoff)
            backoff *= 2
        }
        throw last ?: TranslationException("翻译请求失败")
    }

    private suspend fun callOnce(system: String, input: String, maxTokens: Int): String {
        val payload = json.encodeToString(
            ChatRequest.serializer(),
            ChatRequest(
                model = model,
                messages = listOf(
                    ChatMessage(role = "system", content = system),
                    ChatMessage(role = "user", content = input),
                ),
                maxTokens = maxTokens,
                reasoningEffort = effectiveReasoning,
            ),
        )
        val candidates = listOfNotNull(resolvedEndpoint) + endpointsToTry()
        var lastError: TranslationException? = null
        for (endpoint in candidates.distinct()) {
            val result = runPOST(endpoint, payload)
            result.onSuccess {
                resolvedEndpoint = endpoint
                return it
            }
            val error = result.exceptionOrNull() as? TranslationException
            // 只有「路径不对」才换下一个候选；401/429 这些换路径也一样
            if (error == null || !error.notFound) {
                throw error ?: TranslationException("翻译请求失败")
            }
            lastError = error
        }
        throw lastError ?: TranslationException("翻译请求失败：地址不可用")
    }

    private suspend fun runPOST(endpoint: String, payload: String): Result<String> = runSuspendCatching {
        // 超时当失败返回。用 withTimeoutOrNull 而不是 withTimeout：后者抛 CancellationException，
        // 会被上层当成「调用方自己被取消」，一路静默中止整批翻译
        val response: HttpResponse? = withTimeoutOrNull(timeoutMillis) {
            http.post(endpoint) {
                contentType(ContentType.Application.Json)
                header("Authorization", "Bearer $apiKey")
                setBody(payload)
            }
        }
        if (response == null) throw TranslationException("翻译接口超时（${timeoutMillis / 1000} 秒）", retryable = true)

        val body = response.bodyAsText()
        if (!response.status.isSuccess()) {
            val rejectedReasoning = response.status.value == 400 &&
                body.contains("reasoning", ignoreCase = true)
            throw TranslationException(
                message = describeStatus(response.status, body),
                retryable = response.status.value == 429 || response.status.value >= 500,
                notFound = response.status == HttpStatusCode.NotFound,
                reasoningRejected = rejectedReasoning,
            )
        }
        val parsed = runCatching { json.decodeFromString(ChatResponse.serializer(), body) }.getOrNull()
        val content = parsed?.choices?.firstOrNull()?.message?.content
        if (content.isNullOrBlank()) {
            // 有的中转站把错误塞在 200 里：原文进日志，界面上说清这是服务端的问题
            PikoLog.w(TAG, "翻译接口返回空内容：${body.take(2_000)}")
            throw TranslationException(
                "翻译接口返回了空内容（服务端问题，不是网络断了）\n原始响应：${body.trim().take(200)}",
                retryable = true,
            )
        }
        content
    }

    /** 用户可能填到域名、/v1、或完整路径，按可能性给出候选，第一个 404 就换下一个。 */
    private fun endpointsToTry(): List<String> = when {
        base.endsWith("/chat/completions") -> listOf(base)
        base.endsWith("/v1") -> listOf("$base/chat/completions")
        else -> listOf("$base/chat/completions", "$base/v1/chat/completions")
    }

    private fun describeStatus(status: HttpStatusCode, body: String): String {
        // 完整响应先进日志：服务商给的错误往往只有一两个字的码（例如 "wb6"），
        // 界面里再截断就更没线索了，日志里留着原文，排查时翻得到
        PikoLog.w(TAG, "翻译接口返回 ${status.value}：${body.take(2_000)}")
        val detail = runCatching {
            json.parseToJsonElement(body).let { element ->
                (element as? kotlinx.serialization.json.JsonObject)
                    ?.get("error")
                    ?.let { it as? kotlinx.serialization.json.JsonObject }
                    ?.get("message")
                    ?.toString()?.trim('"')
            }
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: body.trim().take(200)
        // 第一行说人话（是哪一类问题），第二行放服务端原话，方便对照文档
        val head = when (status.value) {
            401, 403 -> "翻译接口拒绝访问（${status.value}），检查 API Key 与账户余额"
            404 -> "翻译接口地址不对（404），检查 Base URL"
            429 -> "翻译接口限流（429），稍后会自动重试"
            400 -> "翻译接口不接受这次请求（400），多半是模型名或内容被拒"
            413 -> "请求内容太长（413），可减少并发后重试"
            else -> "翻译接口出错（${status.value}）"
        }
        return if (detail.isBlank()) head else "$head\n服务端说明：$detail"
    }

    private companion object {
        const val TAG = "Translation"
        const val MAX_ATTEMPTS = 4
        const val RETRY_BASE_DELAY_MS = 2_000L

        /** 一批 550 字，正常十几秒；给 90 秒是留给慢模型与中转站排队。 */
        const val DEFAULT_TIMEOUT_MILLIS = 90_000L

        /** 可选的推理强度。空串（不传）最快，适合字幕这种大量短请求。 */
        val REASONING_LEVELS = listOf("", "low", "medium", "high")

        val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            explicitNulls = false
        }
    }
}

/** 翻译接口的错误。带两个标记，决定要不要重试、要不要换路径。 */
class TranslationException(
    message: String,
    val retryable: Boolean = false,
    val notFound: Boolean = false,
    /** 400 且响应里提到 reasoning：说明接口不认这个参数，去掉重试即可。 */
    val reasoningRejected: Boolean = false,
) : Exception(message)

@Serializable
private data class ChatMessage(val role: String, val content: String)

@Serializable
private data class ChatRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val temperature: Double = 0.2,
    val stream: Boolean = false,
    @SerialName("max_tokens") val maxTokens: Int = 2048,
    /** null 时不序列化（Json 配置了 explicitNulls = false）：不支持的服务照常能用。 */
    @SerialName("reasoning_effort") val reasoningEffort: String? = null,
)

@Serializable
private data class ChatResponse(val choices: List<ChatChoice> = emptyList())

@Serializable
private data class ChatChoice(val message: ChatMessage? = null)
