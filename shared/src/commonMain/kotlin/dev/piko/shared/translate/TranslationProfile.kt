package dev.piko.shared.translate

import dev.piko.data.auth.PikoUserPreferences
import dev.piko.shared.log.PikoLog
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 一套完整的 AI 翻译配置：接口地址 + 这个接口用的密钥（可多个）+ 模型名。
 *
 * 一个档案就是「一个可切换的翻译通道」：DeepSeek 一个、中转站一个、本地 Ollama 一个，
 * 设置页点一下切换。档案内多个密钥轮着用——几个免费额度、几台服务的 Key 都能挂在一起。
 */
@Serializable
data class TranslationProfile(
    /** 稳定 id，切换激活、同步合并都认它。新建时用随机 id。 */
    val id: String,
    /** 备注名，可空；空着界面上用「模型名」显示。 */
    val label: String = "",
    /** OpenAI 兼容的接口地址，如 `https://api.deepseek.com/v1`。 */
    val baseUrl: String,
    /** 这个接口的 API Key，可多个；第一个是「当前密钥」，其余轮询备用。 */
    val keys: List<String> = emptyList(),
    /** 模型名，如 `deepseek-chat`。 */
    val model: String,
) {
    /** 能用没有：地址、模型、至少一个非空 Key 都齐了才算。 */
    val complete: Boolean
        get() = baseUrl.isNotBlank() && model.isNotBlank() && keys.any { it.isNotBlank() }

    /** 去掉空 Key 后的有效密钥，按添加顺序。 */
    fun effectiveKeys(): List<String> = keys.filter { it.isNotBlank() }

    companion object {
        /** 迁移旧配置时用的固定 id：老设备升级后第一个档案就是它。 */
        const val LEGACY_ID = "legacy"

        /** 新建档案的 id：同一台设备上不会重复（48 位随机十六进制）。 */
        fun newId(): String = "p" + kotlin.random.Random.nextLong(1L shl 48, 1L shl 49).toString(16)
    }
}

/**
 * 档案列表的存取与旧配置迁移。
 *
 * 存储形态：preferences 里一个 JSON 字符串（含密钥；平台层负责加密落盘）+ 一个激活 id。
 * 旧版（1.3.10 及之前）把地址、Key、模型分成三个单值存，升级时把那份三件套转成
 * 第一个档案，用户无感；之后新代码只认档案。
 */
class TranslationProfileStore(private val preferences: PikoUserPreferences) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** 读全部档案。首次读取时若没有档案、旧三件套还在，就地迁移并写回。 */
    suspend fun load(): List<TranslationProfile> {
        val stored = decode(preferences.translationProfilesFlow.first())
        if (stored.isNotEmpty()) return stored
        val migrated = migrateLegacy(
            baseUrl = preferences.translationBaseUrlFlow.first(),
            apiKey = preferences.translationApiKeyFlow.first(),
            model = preferences.translationModelFlow.first(),
        )
        if (migrated.isEmpty()) return emptyList()
        save(migrated, migrated.first().id)
        return migrated
    }

    /** 当前激活的档案 id；没设过时返回 null（调用方用第一个）。 */
    suspend fun activeId(): String? =
        preferences.translationActiveProfileFlow.first().trim().ifEmpty { null }

    /** 当前激活的档案；列表为空返回 null。 */
    suspend fun active(): TranslationProfile? {
        val profiles = load()
        if (profiles.isEmpty()) return null
        val id = activeId()
        return profiles.firstOrNull { it.id == id } ?: profiles.first()
    }

    /** 整表保存（设置页改完档案列表时用），顺带记下激活的是谁。 */
    suspend fun save(profiles: List<TranslationProfile>, activeId: String?) {
        preferences.setTranslationProfiles(encode(profiles))
        preferences.setTranslationActiveProfile(activeId?.trim().orEmpty())
    }

    fun decode(raw: String): List<TranslationProfile> {
        if (raw.isBlank()) return emptyList()
        return runCatching {
            json.decodeFromString<List<TranslationProfile>>(raw)
        }.onFailure {
            // 读不懂就当没有：设置页会引导用户重新配，别让一处坏数据把翻译全堵死
            PikoLog.w("TranslationProfile", "档案解析失败，按未配置处理", it)
        }.getOrDefault(emptyList())
    }

    fun encode(profiles: List<TranslationProfile>): String =
        json.encodeToString(profiles)

    companion object {
        /** 旧三件套 → 一个默认档案。三项齐了才有意义，缺项返回空列表（等于没配过）。 */
        fun migrateLegacy(baseUrl: String, apiKey: String, model: String): List<TranslationProfile> {
            val base = baseUrl.trim()
            val key = apiKey.trim()
            val name = model.trim()
            if (base.isEmpty() || key.isEmpty() || name.isEmpty()) return emptyList()
            return listOf(
                TranslationProfile(
                    id = TranslationProfile.LEGACY_ID,
                    label = "",
                    baseUrl = base,
                    keys = listOf(key),
                    model = name,
                ),
            )
        }
    }
}
