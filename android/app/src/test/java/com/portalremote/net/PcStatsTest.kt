package com.portalremote.net

import org.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The `sys` push, as the Stats dashboard reads it. Kept in step with the payload the
 * server's `SystemStats.Sample()` builds (Metrics/SystemStats.cs).
 *
 * Two things are worth a test here. One is the shape: the dashboard draws seven
 * different readings out of one message, and a renamed key produces a screen of zeroes
 * rather than an error. The other is [formatBytes], which is the only arithmetic on this
 * side — every number on the screen goes through it, and the rounding is the kind of
 * thing that looks fine until a 999MB reading renders as "1000 MB" next to a card
 * showing GB.
 */
class PcStatsTest {

    /** `receivedAt` is passed explicitly: the default reads `SystemClock`, which is a
     *  stub that throws on the JVM. */
    private fun parse(json: String) = PcStats.fromPush(JSONObject(json), receivedAt = 0)

    @Test
    fun `a sample carries everything the dashboard draws`() {
        val stats = parse(
            """
            {"t":"sys","cpu":23.4,"cores":[10.0,40.5,0.0,80.0],
             "cpuName":"AMD Ryzen 9 5900X","os":"Windows 11 Pro","uptimeMs":93600000,
             "mem":{"used":17179869184,"total":34359738368},
             "net":{"up":2048.0,"down":1048576.0},
             "disks":[{"name":"C:","label":"System","used":500000000000,"total":1000000000000}],
             "top":[{"name":"chrome","cpu":12.5,"mem":734003200}]}
            """.trimIndent(),
        )

        assertEquals(23.4f, stats.cpu)
        assertEquals(listOf(10.0f, 40.5f, 0.0f, 80.0f), stats.cores)
        assertEquals("AMD Ryzen 9 5900X", stats.cpuName)
        assertEquals(0.5f, stats.memFraction)
        assertEquals(1_048_576L, stats.netDown)
        assertEquals(2_048L, stats.netUp)
        assertEquals("System", stats.disks.single().label)
        assertEquals(0.5f, stats.disks.single().fraction)
        assertEquals("chrome", stats.top.single().name)
        assertEquals(12.5f, stats.top.single().cpu)
    }

    @Test
    fun `a machine that reported nothing draws as empty rather than crashing`() {
        // An older server, or one that failed every read this second. Every list is
        // empty and every gauge sits at zero — the screen has cards that hide
        // themselves when the list behind them is empty, and none of this may throw.
        val stats = parse("""{"t":"sys"}""")

        assertEquals(0f, stats.cpu)
        assertTrue(stats.cores.isEmpty())
        assertTrue(stats.disks.isEmpty())
        assertTrue(stats.top.isEmpty())
        // The divide-by-zero guard: a machine that didn't report its RAM has no
        // fraction, and NaN would draw an arc of undefined length.
        assertEquals(0f, stats.memFraction)
    }

    @Test
    fun `a drive with no volume label is unlabelled, not called null`() {
        val stats = parse("""{"t":"sys","disks":[{"name":"D:","label":null,"used":1,"total":2}]}""")
        assertEquals(null, stats.disks.single().label)
    }

    @Test
    fun `bytes read the way the PC's own dialogs write them`() {
        assertEquals("512 B", formatBytes(512))
        assertEquals("1.0 KB", formatBytes(1024))
        assertEquals("9.8 KB", formatBytes(10_000))
        // Past ten the decimal goes: "247 GB", not "247.3 GB".
        assertEquals("977 KB", formatBytes(1_000_000))
        assertEquals("16 GB", formatBytes(17_179_869_184))
    }

    @Test
    fun `an idle link shows a dash rather than a column of zeroes`() {
        assertEquals("—", formatRate(0))
        assertEquals("1.0 MB/s", formatRate(1_048_576))
    }

    @Test
    fun `uptime carries one step of precision`() {
        assertEquals("3m", formatUptime(3 * 60_000L))
        assertEquals("4h 12m", formatUptime((4 * 60 + 12) * 60_000L))
        assertEquals("6d 4h", formatUptime((6 * 24 * 60 + 4 * 60) * 60_000L))
    }
}
