package dev.piko.shared.translate

import dev.piko.shared.smoke.MemoryPreferences
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 翻译档案（1.3.11 多档案 + 多密钥）的存储、迁移与轮询。
 *
 * 迁移是重点：老设备升级时本机只有旧的单套配置（地址 / Key / 模型三个单值），
 * 第一次读档案要把它转成第一个档案并写回，之后新代码只认档案。
 */
class TranslationProfileTest {
    @Test
    fun `legacy config migrates into the first profile and is written back`() = runBlocking {
        val prefs = MemoryPreferences()
        prefs.setTranslationBaseUrl("https://api.deepseek.com/v1")
        prefs.setTranslationApiKey("sk-old")
        prefs.setTranslationModel("deepseek-chat")

        val store = TranslationProfileStore(prefs)
        val profiles = store.load()

        assertEquals(1, profiles.size)
        val profile = profiles.single()
        assertEquals("https://api.deepseek.com/v1", profile.baseUrl)
        assertEquals(listOf("sk-old"), profile.keys)
        assertEquals("deepseek-chat", profile.model)
        assertTrue(profile.complete)
        // 写回了：再读一次不再依赖旧字段
        assertTrue(prefs.translationProfilesFlow.first().contains("sk-old"))
        // 激活的是迁来的这个
        assertEquals(profile.id, store.active()?.id)
    }

    @Test
    fun `incomplete legacy config does not produce a profile`() = runBlocking {
        val prefs = MemoryPreferences()
        prefs.setTranslationBaseUrl("https://api.deepseek.com/v1")
        // Key 与模型都没填：等于没配过
        assertTrue(TranslationProfileStore(prefs).load().isEmpty())
        assertNull(TranslationProfileStore(prefs).active())
    }

    @Test
    fun `stored profiles win over legacy fields once migrated`() = runBlocking {
        val prefs = MemoryPreferences()
        prefs.setTranslationBaseUrl("https://legacy.example.com/v1")
        prefs.setTranslationApiKey("sk-legacy")
        prefs.setTranslationModel("legacy-model")
        // 先迁成档案
        val store = TranslationProfileStore(prefs)
        store.load()

        // 之后用户在设置里加了一个新档案：列表以新档案为准，旧字段不再参与
        val added = TranslationProfile(
            id = "p2",
            baseUrl = "https://api.moonshot.cn/v1",
            keys = listOf("sk-new"),
            model = "kimi-k2",
        )
        store.save(store.load() + added, activeId = "p2")

        val profiles = store.load()
        assertEquals(2, profiles.size)
        assertEquals("p2", store.active()?.id)
    }

    @Test
    fun `active id falls back to the first profile when unknown`() = runBlocking {
        val prefs = MemoryPreferences()
        val store = TranslationProfileStore(prefs)
        store.save(
            listOf(
                TranslationProfile(id = "a", baseUrl = "https://a/v1", keys = listOf("k1"), model = "m1"),
                TranslationProfile(id = "b", baseUrl = "https://b/v1", keys = listOf("k2"), model = "m2"),
            ),
            activeId = "gone",
        )
        // 激活的 id 不在列表里（换设备同步串了）：用第一个，别让翻译入口消失
        assertEquals("a", store.active()?.id)
    }

    @Test
    fun `broken json is treated as unconfigured instead of crashing`() {
        val store = TranslationProfileStore(MemoryPreferences())
        assertTrue(store.decode("{ 这不是 json").isEmpty())
        assertTrue(store.decode("").isEmpty())
    }

    @Test
    fun `encode decode round trip keeps every field`() {
        val profiles = listOf(
            TranslationProfile(
                id = "p1",
                label = "主力",
                baseUrl = "https://api.deepseek.com/v1",
                keys = listOf("sk-a", "sk-b"),
                model = "deepseek-chat",
            ),
        )
        val decoded = TranslationProfileStore(MemoryPreferences()).decode(
            TranslationProfileStore(MemoryPreferences()).encode(profiles),
        )
        assertEquals(profiles, decoded)
        // 空 Key 不算完整
        assertFalse(
            TranslationProfile(id = "x", baseUrl = "https://x/v1", keys = listOf("", " "), model = "m").complete,
        )
    }
}

/** 密钥池：轮着用、坏的跳过、恢复、全坏。 */
class TranslationKeyPoolTest {
    @Test
    fun `keys are handed out round robin`() {
        val pool = TranslationKeyPool(listOf("k1", "k2", "k3"))
        assertEquals("k1", pool.nextKey())
        assertEquals("k2", pool.nextKey())
        assertEquals("k3", pool.nextKey())
        assertEquals("k1", pool.nextKey())
    }

    @Test
    fun `broken keys are skipped until reported ok`() {
        val pool = TranslationKeyPool(listOf("k1", "k2"))
        pool.reportBroken("k1")
        // k1 坏着：两次都该拿到 k2
        assertEquals("k2", pool.nextKey())
        assertEquals("k2", pool.nextKey())
        // 恢复后再转一圈
        pool.reportOk("k1")
        assertEquals("k1", pool.nextKey())
        assertEquals("k2", pool.nextKey())
    }

    @Test
    fun `all broken returns null instead of looping forever`() {
        val pool = TranslationKeyPool(listOf("k1", "k2"))
        pool.reportBroken("k1")
        pool.reportBroken("k2")
        assertNull(pool.nextKey())
    }

    @Test
    fun `blank keys are dropped and duplicates collapse`() {
        val pool = TranslationKeyPool(listOf("k1", "", " ", "k1"))
        assertEquals(1, pool.size)
        assertEquals("k1", pool.nextKey())
    }

    @Test
    fun `empty pool has nothing to hand out`() {
        assertNull(TranslationKeyPool(emptyList()).nextKey())
    }
}
