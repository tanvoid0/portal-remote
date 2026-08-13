package com.portalremote.net

import okio.Buffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ScreenApiTest {
    /** Two parts back to back — the second must parse, or the mirror shows one frame. */
    @Test
    fun readsEveryPartInTheStream() {
        val api = ScreenApi()
        val stream = Buffer().apply {
            repeat(2) {
                writeUtf8("--portalframe\r\nContent-Type: image/jpeg\r\nContent-Length: 3\r\n\r\n")
                writeUtf8("abc\r\n")
            }
        }

        assertEquals(3, api.readPartLength(stream))
        assertEquals("abc", stream.readUtf8(3))
        assertEquals(3, api.readPartLength(stream))
        assertEquals("abc", stream.readUtf8(3))
        assertNull(api.readPartLength(stream))
    }
}
