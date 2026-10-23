package dev.piko.shared.translate

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SrtDocumentTest {
    private val sample = """
        1
        00:00:01,000 --> 00:00:03,500
        こんにちは

        2
        00:00:04,000 --> 00:00:06,200
        元気ですか

        3
        00:00:07,000 --> 00:00:09,000
        はい
    """.trimIndent()

    @Test
    fun parsesCuesWithTiming() {
        val doc = assertNotNull(SrtDocument.parse(sample))
        assertEquals(3, doc.size)
        assertEquals(1, doc.cues[0].index)
        assertEquals("00:00:01,000 --> 00:00:03,500", doc.cues[0].timing)
        assertEquals("こんにちは", doc.cues[0].text)
    }

    @Test
    fun handlesCrlfAndBom() {
        val raw = "\uFEFF" + sample.replace("\n", "\r\n")
        val doc = assertNotNull(SrtDocument.parse(raw))
        assertEquals(3, doc.size)
        // 时间轴里不能残留 \r
        assertTrue(doc.cues.all { !it.timing.contains('\r') })
    }

    @Test
    fun toleratesMissingIndexAndDotMilliseconds() {
        // WebVTT 风格：没有序号，毫秒用点
        val raw = """
            00:00:01.000 --> 00:00:03.500
            テスト

            00:00:04.000 --> 00:00:06.000
            二行目
        """.trimIndent()
        val doc = assertNotNull(SrtDocument.parse(raw))
        assertEquals(2, doc.size)
        assertEquals(-1, doc.cues[0].index)
        assertEquals("テスト", doc.cues[0].text)
    }

    @Test
    fun joinsMultilineCueAndStripsStyleTags() {
        val raw = """
            1
            00:00:01,000 --> 00:00:03,000
            <i>一行目</i>
            {\an8}二行目
        """.trimIndent()
        val doc = assertNotNull(SrtDocument.parse(raw))
        assertEquals(1, doc.size)
        assertEquals("一行目 二行目", doc.cues[0].text)
    }

    @Test
    fun rejectsTextWithoutCues() {
        assertNull(SrtDocument.parse("这不是字幕"))
        assertNull(SrtDocument.parse(""))
    }

    @Test
    fun batchesNeverSplitACue() {
        val doc = assertNotNull(SrtDocument.parse(sample))
        val batches = doc.batches(20)
        // 每条被分且只被分进一批
        val flat = batches.flatten()
        assertEquals(doc.size, flat.size)
        assertEquals(doc.size, flat.toSet().size)
        // 按顺序覆盖全部条目
        assertEquals(listOf(0, 1, 2), flat)
    }

    @Test
    fun oversizedCueGetsItsOwnBatch() {
        val raw = """
            1
            00:00:01,000 --> 00:00:03,000
            ${"あ".repeat(200)}

            2
            00:00:04,000 --> 00:00:06,000
            短い
        """.trimIndent()
        val doc = assertNotNull(SrtDocument.parse(raw))
        val batches = doc.batches(100)
        assertEquals(2, batches.size)
        assertEquals(listOf(0), batches[0])
        assertEquals(listOf(1), batches[1])
    }

    @Test
    fun rendersTranslationKeepingTiming() {
        val doc = assertNotNull(SrtDocument.parse(sample))
        val out = doc.render(mapOf(0 to "你好", 2 to "是的"))
        assertTrue(out.contains("00:00:01,000 --> 00:00:03,500"))
        assertTrue(out.contains("你好"))
        // 没翻的第 2 条保留原文，不留空洞
        assertTrue(out.contains("元気ですか"))
        assertTrue(out.contains("是的"))
        // 序号按位置补齐
        assertTrue(out.startsWith("1\n"))
    }

    @Test
    fun emptyTranslationFallsBackToSource() {
        val doc = assertNotNull(SrtDocument.parse(sample))
        val out = doc.render(mapOf(0 to "   "))
        assertTrue(out.contains("こんにちは"))
    }
}

class AlignedLinesTest {
    @Test
    fun stripsCodeFences() {
        val output = """
            ```
            第一行
            第二行
            ```
        """.trimIndent()
        assertEquals(listOf("第一行", "第二行"), alignedLines(output, 2))
    }

    @Test
    fun stripsNumberingPrefix() {
        val output = "1. 第一行\n2、第二行\n3) 第三行"
        assertEquals(listOf("第一行", "第二行", "第三行"), alignedLines(output, 3))
    }

    @Test
    fun dropsBlankLines() {
        val output = "第一行\n\n第二行\n"
        assertEquals(listOf("第一行", "第二行"), alignedLines(output, 2))
    }

    @Test
    fun truncatesExtraLines() {
        // 模型爱在末尾补一句总结，不能让它变成一条字幕
        val output = "第一行\n第二行\n以上是翻译结果"
        assertEquals(listOf("第一行", "第二行"), alignedLines(output, 2))
    }

    @Test
    fun keepsFewerLinesAsIs() {
        // 少于期望时交给上层判断（可能整批失败），这里不补齐
        assertEquals(listOf("只有一行"), alignedLines("只有一行", 3))
    }
}
