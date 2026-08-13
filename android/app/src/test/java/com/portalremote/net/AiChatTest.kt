package com.portalremote.net

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AiChatTest {
    private fun delta(content: String) =
        """data: {"choices":[{"delta":{"content":"$content"}}]}"""

    @Test
    fun `reads the text out of a delta frame`() {
        assertEquals(ChatEvent.Delta("Hello"), AiChat.parse(delta("Hello")))
    }

    @Test
    fun `the terminator is what makes a reply whole`() {
        // Nothing else distinguishes a finished stream from a dropped one — both are
        // just a socket that stopped producing lines.
        assertEquals(ChatEvent.Done, AiChat.parse("data: [DONE]"))
    }

    @Test
    fun `blank lines and comments carry nothing`() {
        // SSE separates events with a blank line and keeps connections alive with `:`
        // comments. Treating either as an empty delta would append nothing forever.
        assertEquals(ChatEvent.Ignore, AiChat.parse(""))
        assertEquals(ChatEvent.Ignore, AiChat.parse(": keep-alive"))
        assertEquals(ChatEvent.Ignore, AiChat.parse("event: message"))
    }

    @Test
    fun `the role-only opening chunk is not text`() {
        // OpenAI's first chunk announces the role and carries no content. Rendering it
        // as an empty delta is harmless; treating a missing `content` as a parse failure
        // would not be.
        assertEquals(
            ChatEvent.Ignore,
            AiChat.parse("""data: {"choices":[{"delta":{"role":"assistant"}}]}"""),
        )
    }

    @Test
    fun `an unparseable frame is skipped, not fatal`() {
        // One bad frame should not kill a reply — the next is usually fine, and a stream
        // that has genuinely failed reports it by ending without [DONE].
        assertEquals(ChatEvent.Ignore, AiChat.parse("data: {not json"))
        assertEquals(ChatEvent.Ignore, AiChat.parse("""data: {"choices":[]}"""))
    }

    @Test
    fun `whitespace after the colon is not part of the text`() {
        assertEquals(ChatEvent.Delta("hi"), AiChat.parse("""data:{"choices":[{"delta":{"content":"hi"}}]}"""))
    }

    @Test
    fun `a failure prefers the PC's own sentence over the status code`() {
        // A 503 from our server is the availability model talking, and its `detail` was
        // written for exactly this moment.
        assertEquals(
            "http://127.0.0.1:18410/health did not answer within a second",
            AiChat.describe(503, """{"t":"ai_state","state":"unavailable","detail":"http://127.0.0.1:18410/health did not answer within a second"}"""),
        )
        assertEquals("no messages", AiChat.describe(400, """{"error":"no messages"}"""))
    }

    @Test
    fun `a failure with no body still says something`() {
        assertTrue(AiChat.describe(502, null).contains("502"))
        assertTrue(AiChat.describe(500, "<html>oops</html>").contains("500"))
    }
}
