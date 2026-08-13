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
    fun `standing separates up to date from unreleased and behind`() {
        // The three things Settings says differently. A build made after a release but
        // before the next tag is newer than anything published, and offering to "update"
        // that one would install an older APK over a newer one.
        assertEquals(Updates.Standing.Same, Updates.standing("0.3.1", "0.3.1"))
        assertEquals(Updates.Standing.Same, Updates.standing("v0.3.1", "0.3.1"))
        assertEquals(Updates.Standing.Behind, Updates.standing("0.2.0", "0.3.1"))
        assertEquals(Updates.Standing.Unreleased, Updates.standing("0.4.0", "0.3.1"))
        assertEquals(Updates.Standing.Unreleased, Updates.standing("0.4.0-rc1", "0.3.1"))
    }

    @Test
    fun `a dev build is never behind`() {
        // The build.gradle default trails whatever has been tagged since it was last
        // touched, so comparing its digits would call a build of today's source "behind"
        // and offer to install an older release over it.
        assertEquals(Updates.Standing.Unreleased, Updates.standing("0.1.0-dev", "0.3.1"))
        assertEquals(Updates.Standing.Unreleased, Updates.standing("0.1.0-dev", "0.1.0"))
        assertFalse(Updates.isDevBuild("0.1.0"))
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
