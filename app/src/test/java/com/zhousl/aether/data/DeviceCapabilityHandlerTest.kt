package com.zhousl.aether.data

import android.Manifest
import android.provider.MediaStore
import java.text.SimpleDateFormat
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    @Test
    fun `photo query narrows by album, name and age`() {
        val now = 1_760_000_000_000L
        val query = buildPhotoQuery(album = "Camera", nameContains = "IMG", days = 7, nowMillis = now)

        assertEquals(
            "${MediaStore.Images.Media.BUCKET_DISPLAY_NAME} = ? AND " +
                "${MediaStore.Images.Media.DISPLAY_NAME} LIKE ? ESCAPE '\\' AND " +
                "(CASE WHEN ${MediaStore.Images.Media.DATE_TAKEN} > 0 THEN " +
                "${MediaStore.Images.Media.DATE_TAKEN} ELSE ${MediaStore.Images.Media.DATE_ADDED} * 1000 END) >= ?",
            query.selection,
        )
        assertEquals(
            listOf("Camera", "%IMG%", (now - 7 * 24 * 60 * 60 * 1000L).toString()),
            query.arguments,
        )
    }

    @Test
    fun `an unfiltered photo query asks for everything`() {
        val query = buildPhotoQuery(album = "  ", nameContains = "", days = 0, nowMillis = 0L)

        assertEquals("", query.selection)
        assertTrue(query.arguments.isEmpty())
    }

    @Test
    fun `a file name with wildcards cannot widen the photo query`() {
        val query = buildPhotoQuery(album = "", nameContains = "100%", days = null, nowMillis = 0L)

        assertEquals("""%100\%%""", query.arguments.single())
    }

    @Test
    fun `the photo permissions follow the android version`() {
        // Android 13+ keeps the legacy permission in the list: Qing targets API
        // 28, so that is the one the platform actually enforces.
        assertEquals(
            listOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_EXTERNAL_STORAGE,
            ),
            photoPermissionsForSdk(33),
        )
        assertEquals(
            listOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_EXTERNAL_STORAGE,
            ),
            photoPermissionsForSdk(36),
        )
        assertEquals(
            listOf(Manifest.permission.READ_EXTERNAL_STORAGE),
            photoPermissionsForSdk(32),
        )
        assertEquals(
            listOf(Manifest.permission.READ_EXTERNAL_STORAGE),
            photoPermissionsForSdk(26),
        )
    }
}
