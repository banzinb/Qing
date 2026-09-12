package com.zhousl.aether.data

import java.text.SimpleDateFormat
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DeviceCapabilityHandlerTest {
    private fun expected(pattern: String, text: String): Long =
        SimpleDateFormat(pattern, Locale.US).parse(text)!!.time

    @Test
    fun `reads the date shapes a person writes`() {
        assertEquals(
            expected("yyyy-MM-dd HH:mm", "2026-09-13 15:00"),
            parseLocalDateTimeMillis("2026-09-13 15:00"),
        )
        assertEquals(
            expected("yyyy-MM-dd HH:mm:ss", "2026-09-13 15:00:30"),
            parseLocalDateTimeMillis("2026-09-13 15:00:30"),
        )
        assertEquals(
            expected("yyyy-MM-dd HH:mm", "2026-09-13 15:00"),
            parseLocalDateTimeMillis("2026-09-13T15:00"),
        )
        assertEquals(
            expected("yyyy-MM-dd", "2026-09-13"),
            parseLocalDateTimeMillis("2026-09-13"),
        )
        assertEquals(
            expected("yyyy-MM-dd HH:mm", "2026-09-13 15:00"),
            parseLocalDateTimeMillis("  2026-09-13 15:00  "),
        )
    }

    @Test
    fun `rejects anything it cannot read exactly`() {
        assertNull(parseLocalDateTimeMillis(""))
        assertNull(parseLocalDateTimeMillis("明天下午三点"))
        assertNull(parseLocalDateTimeMillis("2026-13-01 15:00"))
        assertNull(parseLocalDateTimeMillis("2026-09-13 25:00"))
        assertNull(parseLocalDateTimeMillis("2026-09-13 15:00 顺便"))
    }

    @Test
    fun `escapes wildcards in a contact query`() {
        assertEquals("""张\%伟""", escapeLike("张%伟"))
        assertEquals("""100\%""", escapeLike("100%"))
        assertEquals("""a\_b""", escapeLike("a_b"))
        assertEquals("""a\\b""", escapeLike("""a\b"""))
        assertEquals("张伟", escapeLike("张伟"))
    }
}
