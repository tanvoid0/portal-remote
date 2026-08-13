package com.portalremote.net

import org.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Reading the conversation the PC pushes.
 *
 * This is the whole of the phone's chat state machine now that the streaming happens on the
 * other machine: a snapshot, an upsert and a delta. Getting one wrong puts a token on the
 * wrong turn or loses a reply, and neither shows up as an exception.
 */
class AiChatTest {
    private fun turn(json: String) = ChatTurn.fromJson(JSONObject(json))

    @Test
    fun `a snapshot is the whole conversation, in order`() {
        val turns = AiTranscript.snapshot(
            JSONObject(
                """
                {"t":"ai_chat","turns":[
                  {"id":"t1","role":"user","text":"hello","at":1000},
                  {"id":"t2","role":"assistant","text":"hi","at":1001}]}
                """.trimIndent(),
            ),
        )

        assertEquals(2, turns.size)
        assertTrue(turns[0].fromUser)
        assertEquals("hi", turns[1].text)
    }

    @Test
    fun `a missing turns array is an empty conversation, not a crash`() {
        assertTrue(AiTranscript.snapshot(JSONObject("""{"t":"ai_chat"}""")).isEmpty())
    }

    @Test
    fun `an upsert replaces the turn with the same id rather than appending it`() {
        // The same turn arrives several times: empty and streaming, then grown, then
        // finished, then carrying a plan. Appending each one would repeat the reply.
        val first = turn("""{"id":"t2","role":"assistant","text":"","streaming":true}""")
        val done = turn("""{"id":"t2","role":"assistant","text":"all done"}""")

        val turns = AiTranscript.upsert(AiTranscript.upsert(emptyList(), first), done)

        assertEquals(1, turns.size)
        assertEquals("all done", turns[0].text)
        assertEquals(false, turns[0].streaming)
    }

    @Test
    fun `a delta appends to the turn it names, wherever that turn is`() {
        val turns = listOf(
            turn("""{"id":"t1","role":"user","text":"hello"}"""),
            turn("""{"id":"t2","role":"assistant","text":"Wor"}"""),
        )

        val grown = AiTranscript.delta(turns, "t2", "king on it")

        assertEquals("hello", grown[0].text)
        assertEquals("Working on it", grown[1].text)
    }

    @Test
    fun `a delta for a turn we do not have is dropped, not appended somewhere else`() {
        // A trimmed transcript, or a message that outran its turn. Appending it to the
        // last turn instead would put someone else's words in the wrong bubble.
        val turns = listOf(turn("""{"id":"t1","role":"user","text":"hello"}"""))

        assertEquals(turns, AiTranscript.delta(turns, "t9", "stray"))
    }

    @Test
    fun `a turn carries why it stopped as well as what it said`() {
        val cut = turn("""{"id":"t2","role":"assistant","text":"half an","incomplete":true}""")
        assertTrue(cut.incomplete)
        assertNull(cut.error)

        val failed = turn("""{"id":"t3","role":"assistant","text":"","error":"agent-platform answered 401."}""")
        assertEquals("agent-platform answered 401.", failed.error)

        // Blank is not an error — the PC omits the field, and an empty string here would
        // put an error line under every healthy reply.
        assertNull(turn("""{"id":"t4","role":"assistant","text":"fine","error":""}""").error)
    }

    @Test
    fun `deciding is its own state, distinct from having no plan`() {
        // "Still working out what to do" and "there was nothing to do" look identical
        // without this, and the first one lasts tens of seconds on a local model.
        val thinking = turn("""{"id":"t2","role":"assistant","text":"ok","deciding":true}""")
        assertTrue(thinking.deciding)
        assertNull(thinking.plan)
    }
}
