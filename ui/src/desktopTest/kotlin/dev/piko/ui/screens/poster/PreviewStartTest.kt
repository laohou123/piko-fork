package dev.piko.ui.screens.poster

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 预览起点的规则：按番号哈希落在时长的 20%~45% 之间。
 *
 * 这条规则换过一次——最早固定从第 30 秒起播，结果所有片都停在同一个位置，
 * 有些片那里还是黑场。钉住三点：同一部片稳定、不同片散开、永远落在中段。
 */
class PreviewStartTest {

    private val twoHours = 7_200_000L

    @Test
    fun sameCodeAlwaysLandsOnSameSpot() {
        assertEquals(previewStartFor("SSIS-001", twoHours), previewStartFor("SSIS-001", twoHours))
    }

    @Test
    fun positionFallsInTheMiddleOfTheVideo() {
        listOf("SSIS-001", "IPX-192", "FC2-1234567", "ABC-123", "MIDE-999").forEach { code ->
            val fraction = previewStartFor(code, twoHours).toDouble() / twoHours
            assertTrue(fraction in 0.20..0.45, "$code 落在 $fraction，超出 20%~45%")
        }
    }

    @Test
    fun differentCodesSpreadOut() {
        val starts = listOf("SSIS-001", "IPX-192", "FC2-1234567", "ABC-123", "MIDE-999")
            .map { previewStartFor(it, twoHours) }
        assertTrue(starts.toSet().size > 1, "所有番号都落在同一位置：$starts")
    }

    @Test
    fun zeroDurationFallsBackToTheDefaultStart() {
        assertEquals(30_000L, previewStartFor("SSIS-001", 0L))
    }
}
