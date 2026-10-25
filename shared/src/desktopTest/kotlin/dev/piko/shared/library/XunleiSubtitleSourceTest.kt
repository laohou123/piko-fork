package dev.piko.shared.library

import java.nio.charset.Charset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 迅雷字幕接口的解析与共用编码兜底。
 *
 * 只测纯函数，不打网络：接口字段变了、返回里混进垃圾时，这里先炸，
 * 而不是等到真机上「搜索没结果」才去猜。
 */
class XunleiSubtitleSourceTest {

    // 取自真实返回，删减到两条，字段保持原样（duration 是毫秒）
    private val realPayload = """
        {"code":0,"data":[
          {"gcid":"49041A60","cid":"650CDBE8","url":"https://subtitle.v.geilijiasu.com/65/0C/650CDBE8.srt",
           "ext":"srt","name":"ipx-459.srt","duration":6978610,"languages":[""],
           "source":0,"score":0,"fingerprintf_score":0,"extra_name":"（网友上传）","mt":2},
          {"gcid":"05F96BB5","cid":"C66BD51C","url":"https://subtitle.v.geilijiasu.com/C6/6B/C66BD51C.srt",
           "ext":"srt","name":"IPX459.srt","duration":7085194,"languages":[""],
           "source":0,"score":0,"fingerprintf_score":0,"extra_name":"","mt":2}
        ]}
    """.trimIndent()

    @Test
    fun `real payload parses into entries with duration and uploader`() {
        val entries = parseXunleiResponse(realPayload)
        assertEquals(2, entries.size)
        assertEquals("https://subtitle.v.geilijiasu.com/65/0C/650CDBE8.srt", entries[0].url)
        assertEquals(6978610L, entries[0].durationMillis)
        assertEquals("（网友上传）", entries[0].uploader)
        assertEquals("", entries[1].uploader)
    }

    @Test
    fun `hit name drops extension and keeps uploader hint`() {
        val hits = parseXunleiResponse(realPayload).map { it.toHit() }
        assertEquals("ipx-459 （网友上传）", hits[0].name)
        assertEquals("IPX459", hits[1].name)
        assertEquals("1:56:18", hits[0].durationLabel)
    }

    @Test
    fun `non-zero code and malformed json are empty, not crashes`() {
        assertTrue(parseXunleiResponse("""{"code":1,"data":[]}""").isEmpty())
        assertTrue(parseXunleiResponse("not json at all").isEmpty())
        assertTrue(parseXunleiResponse("{}").isEmpty())
        assertTrue(parseXunleiResponse("""{"code":0}""").isEmpty())
    }

    @Test
    fun `entries without usable url or with foreign ext are skipped`() {
        val payload = """
            {"code":0,"data":[
              {"url":"ftp://x/y.srt","ext":"srt","name":"a.srt"},
              {"ext":"srt","name":"no-url.srt"},
              {"url":"https://cdn/a.ass","ext":"ass","name":"a.ass"},
              {"url":"https://cdn/b.srt","name":"no-ext-given.srt"},
              {"url":"https://cdn/b.srt","ext":"srt","name":"dup.srt"}
            ]}
        """.trimIndent()
        val entries = parseXunleiResponse(payload)
        // 只留下 b.srt 一条：ftp 与非 http URL、ass、重复直链都丢掉
        assertEquals(1, entries.size)
        assertEquals("https://cdn/b.srt", entries[0].url)
        assertEquals("no-ext-given.srt", entries[0].name)
    }

    @Test
    fun `duration label formats hours and minutes`() {
        assertEquals("1:56:18", formatDurationLabel(6_978_610))
        assertEquals("23:01", formatDurationLabel(1_381_000))
        assertEquals("0:05", formatDurationLabel(5_000))
    }

    @Test
    fun `codec passes utf8 through and strips bom`() {
        val text = "1\n00:00:01,000 --> 00:00:02,000\n你好\n"
        val plain = text.toByteArray(Charsets.UTF_8)
        assertEquals(text, String(SubtitleTextCodec.decode(plain), Charsets.UTF_8))

        val withBom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + plain
        val decoded = SubtitleTextCodec.decode(withBom)
        assertTrue(decoded.isNotEmpty())
        assertEquals(text, String(decoded, Charsets.UTF_8))
    }

    @Test
    fun `codec converts legacy gb18030 to utf8`() {
        val text = "1\n00:00:01,000 --> 00:00:02,000\n字幕测试\n"
        val gb = text.toByteArray(Charset.forName("GB18030"))
        val decoded = SubtitleTextCodec.decode(gb)
        assertNotNull(decoded)
        assertEquals(text, String(decoded, Charsets.UTF_8))
    }
}
