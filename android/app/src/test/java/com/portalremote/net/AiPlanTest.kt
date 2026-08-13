package com.portalremote.net

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reading a plan and what running it did — `docs/phase7-assistant.md` §7c.
 *
 * The parsing matters more here than anywhere else in the app: what comes out of this
 * drives a card with buttons on it, and pressing one presses keys on a PC in another room.
 * A summary read wrong is somebody approving the wrong thing.
 */
class AiPlanTest {
    private fun plan(json: String) = AiPlan.fromJson(JSONObject(json))

    @Test
    fun readsActionsInOrder() {
        val plan = plan(
            """
            {"thought":"Pause it, then lock.","state":"pending","actions":[
              {"index":0,"actionId":"media_control","summary":"Media key: play_pause","verb":"Play/pause","destructive":false},
              {"index":1,"actionId":"power","summary":"Power: shutdown","verb":"Shut down","destructive":true}]}
            """.trimIndent(),
        )

        assertNull(plan.error)
        assertTrue(plan.pending)
        assertEquals(2, plan.actions.size)
        assertEquals("Media key: play_pause", plan.actions[0].summary)
        // The button face and the sentence are separate strings, both written by the PC.
        assertEquals("Play/pause", plan.actions[0].verb)
        // The one that costs unsaved work is flagged by the PC, not guessed at here.
        assertTrue(plan.actions[1].destructive)
    }

    @Test
    fun aFailedDecisionIsNotAnEmptyPlan() {
        val plan = plan("""{"state":"failed","error":"Error during decision: no provider"}""")

        assertEquals("Error during decision: no provider", plan.error)
        assertFalse(plan.pending)
        assertTrue(plan.actions.isEmpty())
    }

    @Test
    fun onlyAPendingPlanIsStillAQuestion() {
        // Everything else is a record of what already happened, and a card that still
        // offered a Run button would be offering to do it twice.
        assertTrue(plan("""{"state":"pending"}""").pending)
        assertFalse(plan("""{"state":"ran"}""").pending)
        assertFalse(plan("""{"state":"cancelled"}""").pending)
        assertFalse(plan("""{"state":"expired"}""").pending)
    }

    @Test
    fun missingStateReadsAsPendingRatherThanAsNothing() {
        assertEquals(AiPlan.PENDING, plan("""{"thought":"x"}""").state)
    }

    @Test
    fun fallsBackToTheActionIdWhenThereIsNoSummary() {
        val plan = plan("""{"state":"pending","actions":[{"index":0,"actionId":"type_text"}]}""")

        assertEquals("type_text", plan.actions[0].summary)
        // A button with no label is worse than a generic one.
        assertEquals("Run", plan.actions[0].verb)
    }

    @Test
    fun resultsSayWhichActionFailedAndWhichDidNot() {
        val plan = plan(
            """
            {"state":"ran","results":[
              {"index":0,"ok":true,"detail":"Media key: play_pause"},
              {"index":1,"ok":false,"detail":"no cast receiver is attached"}]}
            """.trimIndent(),
        )

        assertEquals(2, plan.results.size)
        assertTrue(plan.results[0].ok)
        assertFalse(plan.results[1].ok)
        assertEquals("no cast receiver is attached", plan.results[1].detail)
    }

    @Test
    fun aRefusedPlanCarriesTheRefusal() {
        // The PC refuses to run a plan twice, and that answer has to reach the card rather
        // than looking like an empty run (§4.4).
        val plan = plan("""{"state":"failed","error":"Already run."}""")

        assertEquals("Already run.", plan.error)
        assertTrue(plan.results.isEmpty())
    }
}
