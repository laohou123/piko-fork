package dev.piko.shared.translate

import dev.piko.data.auth.PikoUserPreferences
import io.ktor.client.HttpClient
import kotlinx.coroutines.flow.first

/**
 * AI 翻译的统一入口：现读用户填的地址、Key、模型，现建客户端。
 *
 * 不缓存客户端是有意的——用户在设置里改完 Key 就该立刻生效，而不是重启 App 才认。
 * 三样没填齐时返回 null，界面据此把翻译入口置灰并提示去哪儿配。
 */
class TranslationGateway(
    private val preferences: PikoUserPreferences,
    private val httpFactory: () -> HttpClient,
) {
    /** 三样都填了才算可用。 */
    suspend fun isConfigured(): Boolean = settings() != null

    /** 当前配置里的模型名，界面上显示「用 xxx 翻译」用。 */
    suspend fun modelName(): String = preferences.translationModelFlow.first().trim()

    /**
     * 用给定配置现建客户端。设置页的「测试连接」用它——试的是框里还没保存的值，
     * 不要求先保存。
     */
    fun clientFor(baseUrl: String, apiKey: String, model: String): TranslationClient =
        TranslationClient(httpFactory(), baseUrl.trim(), apiKey.trim(), model.trim())

    /**
     * 同一套配置复用同一个客户端：接口路径（/chat/completions 还是 /v1/chat/completions）
     * 只在第一次探测，之后直接命中。用户改了配置就重建。
     */
    @Volatile
    private var cached: Pair<Triple<String, String, String>, TranslationClient>? = null

    private fun clientOf(s: Settings): TranslationClient {
        val key = Triple(s.base, s.key, s.model)
        cached?.let { (k, client) -> if (k == key) return client }
        return TranslationClient(
            http = httpFactory(),
            baseUrl = s.base,
            apiKey = s.key,
            model = s.model,
            reasoningEffort = s.reasoning.takeIf { it.isNotBlank() },
        ).also { cached = key to it }
    }

    suspend fun subtitleTranslator(): SubtitleTranslator? {
        val s = settings() ?: return null
        return SubtitleTranslator(clientOf(s), prompt = s.prompt)
    }

    suspend fun titleTranslator(): TitleTranslator? = settings()?.let { s ->
        TitleTranslator(clientOf(s))
    }

    /** 片名有单独的超时偏好吗？没有——片名短，与字幕共用一套即可。 */
    private suspend fun settings(): Settings? {
        val base = preferences.translationBaseUrlFlow.first().trim()
        val key = preferences.translationApiKeyFlow.first().trim()
        val model = preferences.translationModelFlow.first().trim()
        if (base.isEmpty() || key.isEmpty() || model.isEmpty()) return null
        return Settings(
            base = base,
            key = key,
            model = model,
            reasoning = preferences.translationReasoningFlow.first().trim(),
            prompt = preferences.translationPromptFlow.first(),
        )
    }

    private data class Settings(
        val base: String,
        val key: String,
        val model: String,
        val reasoning: String,
        val prompt: String,
    )
}
