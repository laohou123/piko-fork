package dev.piko.shared.translate

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 多密钥翻译：一把 Key 报认证错，换下一把把同一批翻完，不丢批次。
 *
 * 用 MockEngine 扮服务端：`bad` 这把 Key 一律 401，`good` 正常返回。
 * 这是 1.3.11 多密钥的核心行为——个人用户最常见的场景就是备用 Key 顶上。
 */
class SubtitleTranslatorKeyRotationTest {
    private val json = headersOf(HttpHeaders.ContentType, "application/json")

    /** 按 Authorization 头决定谁 401 谁正常；记录每把 Key 被叫了几次。 */
    private fun engine(calls: MutableMap<String, Int>) = MockEngine { request ->
        val key = request.headers["Authorization"]?.removePrefix("Bearer ")
        calls[key ?: "?"] = (calls[key ?: "?"] ?: 0) + 1
        if (key == "bad") {
            respond(
                """{"error":{"message":"invalid api key"}}""",
                HttpStatusCode.Unauthorized,
                json,
            )
        } else {
            respond(
                """{"choices":[{"message":{"role":"assistant","content":"第一行译文\n第二行译文"}}]}""",
                HttpStatusCode.OK,
                json,
            )
        }
    }

    private fun client(http: HttpClient, key: String) =
        TranslationClient(http, baseUrl = "https://api.test/v1", apiKey = key, model = "test-model")

    private fun srt() = """
        1
        00:00:01,000 --> 00:00:02,000
        テスト

        2
        00:00:03,000 --> 00:00:04,000
        二行目
    """.trimIndent()

    @Test
    fun `a broken key is skipped and the next one finishes the batch`() = runBlocking {
        val calls = mutableMapOf<String, Int>()
        val http = HttpClient(engine(calls))
        // bad 排在前面：第一批先撞上 401，换 good 重试同一批
        val translator = SubtitleTranslator(clients = listOf(client(http, "bad"), client(http, "good")))

        val result = translator.translate(srt())

        assertTrue(result.isSuccess, "换 Key 后应翻成功：${result.exceptionOrNull()?.message}")
        val rendered = result.getOrThrow()
        assertTrue(rendered.contains("第一行译文"), rendered)
        assertTrue(rendered.contains("第二行译文"), rendered)
        // bad 被叫过一次就标记为坏，后续批次不再理它
        assertEquals(1, calls["bad"])
        assertTrue((calls["good"] ?: 0) >= 1)
    }

    @Test
    fun `all keys broken fails the batch instead of hanging`() = runBlocking {
        val calls = mutableMapOf<String, Int>()
        val engine = MockEngine { request ->
            val key = request.headers["Authorization"]?.removePrefix("Bearer ")
            calls[key ?: "?"] = (calls[key ?: "?"] ?: 0) + 1
            respond("""{"error":{"message":"invalid api key"}}""", HttpStatusCode.Unauthorized, json)
        }
        val http = HttpClient(engine)
        val translator = SubtitleTranslator(
            clients = listOf(client(http, "bad1"), client(http, "bad2")),
        )

        val result = translator.translate(srt())

        assertTrue(result.isFailure)
        // 每把 Key 只各叫一次，不会反复重试烧时间
        assertEquals(1, calls["bad1"])
        assertEquals(1, calls["bad2"])
    }
}
