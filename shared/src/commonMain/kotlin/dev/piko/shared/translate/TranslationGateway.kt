package dev.piko.shared.translate

import dev.piko.data.auth.DEFAULT_TRANSLATION_CONCURRENCY
import dev.piko.data.auth.PikoUserPreferences
import io.ktor.client.HttpClient
import kotlinx.coroutines.flow.first

/**
 * AI 翻译的统一入口：现读用户配的档案，现建客户端。
 *
 * 不缓存客户端是有意的——用户在设置里改完配置就该立刻生效，而不是重启 App 才认。
 * 没有可用档案（或档案不完整）时返回 null，界面据此把翻译入口置灰并提示去哪儿配。
 *
 * 1.3.11 起配置按「档案」组织：一个档案 = 接口地址 + 若干 Key + 模型名。设置页可切换
 * 激活哪个档案；档案内多把 Key 由 [TranslationKeyPool] 轮询。片名翻译与字幕翻译
 * 共用当前激活的档案。
 */
class TranslationGateway(
    private val preferences: PikoUserPreferences,
    private val httpFactory: () -> HttpClient,
) {
    private val profileStore = TranslationProfileStore(preferences)

    /** 有可用档案才算配好。 */
    suspend fun isConfigured(): Boolean = settings() != null

    /** 当前激活档案的模型名，界面上显示「用 xxx 翻译」用。 */
    suspend fun modelName(): String = profileStore.active()?.model?.trim().orEmpty()

    /**
     * 用给定配置现建客户端。设置页的「测试连接」用它——试的是框里还没保存的值，
     * 不要求先保存。
     */
    fun clientFor(baseUrl: String, apiKey: String, model: String): TranslationClient =
        TranslationClient(httpFactory(), baseUrl.trim(), apiKey.trim(), model.trim())

    /**
     * 同一套档案复用同一组客户端：接口路径（/chat/completions 还是 /v1/chat/completions）
     * 只在第一次探测，之后直接命中。用户改了档案内容就整组重建。
     */
    @Volatile
    private var cached: Pair<String, List<TranslationClient>>? = null

    private fun clientsOf(profile: TranslationProfile, reasoning: String): List<TranslationClient> {
        // 缓存键只在内流通：档案 id + 钥匙集合变了才整组重建
        val cacheKey = profile.id + "|" + profile.effectiveKeys().joinToString("|")
        cached?.let { (k, clients) -> if (k == cacheKey) return clients }
        return profile.effectiveKeys().map { key ->
            TranslationClient(
                http = httpFactory(),
                baseUrl = profile.baseUrl,
                apiKey = key,
                model = profile.model,
                reasoningEffort = reasoning.takeIf { it.isNotBlank() },
            )
        }.also { cached = cacheKey to it }
    }

    suspend fun subtitleTranslator(): SubtitleTranslator? {
        val s = settings() ?: return null
        return SubtitleTranslator(clients = s.clients, concurrency = s.concurrency, prompt = s.prompt)
    }

    suspend fun titleTranslator(): TitleTranslator? = settings()?.let { s ->
        TitleTranslator(clients = s.clients, concurrency = s.concurrency)
    }

    /** 片名有单独的超时偏好吗？没有——片名短，与字幕共用一套即可。 */
    private suspend fun settings(): Settings? {
        val profile = profileStore.active() ?: return null
        if (!profile.complete) return null
        val reasoning = preferences.translationReasoningFlow.first().trim()
        return Settings(
            clients = clientsOf(profile, reasoning),
            reasoning = reasoning,
            prompt = preferences.translationPromptFlow.first(),
            concurrency = preferences.translationConcurrencyFlow.first()
                .takeIf { it > 0 } ?: DEFAULT_TRANSLATION_CONCURRENCY,
        )
    }

    private data class Settings(
        /** 激活档案下每把 Key 一个客户端；至少一把。 */
        val clients: List<TranslationClient>,
        val reasoning: String,
        val prompt: String,
        val concurrency: Int,
    )
}
