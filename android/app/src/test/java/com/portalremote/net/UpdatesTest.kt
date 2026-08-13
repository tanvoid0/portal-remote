package com.portalremote.net

import org.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UpdatesTest {

    @Test
    fun `compares numerically, not as strings`() {
        assertTrue(Updates.isNewer("0.10.0", "0.9.0"))
        assertTrue(Updates.isNewer("1.0.0", "0.99.9"))
        assertFalse(Updates.isNewer("0.1.0", "0.1.0"))
        assertFalse(Updates.isNewer("0.1.0", "0.2.0"))
    }

    @Test
    fun `a pre-release does not beat the release it precedes`() {
        assertFalse(Updates.isNewer("0.2.0-rc1", "0.2.0"))
        assertTrue(Updates.isNewer("0.2.0-rc1", "0.1.0"))
    }

    @Test
    fun `picks the apk asset off the release`() {
        val release = Updates.parse(
            JSONObject(
                """
                {"tag_name":"v0.2.0","body":"notes","assets":[
                  {"name":"PortalRemote.exe","browser_download_url":"https://x/exe"},
                  {"name":"PortalRemote-0.2.0.apk","browser_download_url":"https://x/apk"}
                ]}
                """.trimIndent(),
            ),
        )
        assertEquals("0.2.0", release?.version)
        assertEquals("https://x/apk", release?.apkUrl)
    }

    @Test
    fun `a release with no apk is not an update`() {
        assertNull(Updates.parse(JSONObject("""{"tag_name":"v0.2.0","assets":[]}""")))
    }
}
