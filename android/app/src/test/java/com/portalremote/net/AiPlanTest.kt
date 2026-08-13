package com.portalremote.net

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reading a plan and its result — step 7c of `docs/phase7-assistant.md`.
 *
 * The parsing matters more here than anywhere else in the app: what comes out of this
 * drives a confirmation sheet, and what the user ticks on that sheet presses keys on a
 * PC in another room. A summary read wrong is somebody approving the wrong thing.
 */
class AiPlanTest {
    @Test
    fun readsActionsInOrder() {
        val plan = AiPlan.fromPush(
            JSONObject(
                """
                {"t":"ai_plan","id":"goal-1","thought":"Pause it, then lock.",
                 "actions":[
                   {"index":0,"action_id":"media_control","summary":"Media key: play_pause","destructive":false},
                   {"index":1,"action_id":"power","summary":"Power: shutdown","destructive":true}]}
                """.trimIndent(),
            ),
        )

        assertNull(plan.error)
        assertEquals("goal-1", plan.id)
        assertEquals(2, plan.actions.size)
        assertEquals("Media key: play_pause", plan.actions[0].summary)
        // The one that costs unsaved work is flagged by the PC, not guessed at here.
        assertTrue(plan.actions[1].destructive)
    }

    @Test
    fun errorIsNotAnEmptyPlan() {
        val plan = AiPlan.fromPush(
            JSONObject("""{"t":"ai_plan","id":"goal-2","error":"Error during decision: no provider"}"""),
        )

        assertEquals("Error during decision: no provider", plan.error)
        assertTrue(plan.actions.isEmpty())
    }

    @Test
    fun emptyPlanHasNoError() {
        // The assistant deciding there is nothing to do is an answer, not a failure —
        // and the difference is what stops an empty confirmation sheet appearing.
        val plan = AiPlan.fromPush(
            JSONObject("""{"t":"ai_plan","id":"goal-3","thought":"Nothing to do.","actions":[]}"""),
        )

        assertNull(plan.error)
        assertTrue(plan.actions.isEmpty())
        assertEquals("Nothing to do.", plan.thought)
    }

    @Test
    fun fallsBackToTheActionIdWhenThereIsNoSummary() {
        val plan = AiPlan.fromPush(
            JSONObject("""{"t":"ai_plan","id":"g","actions":[{"index":0,"action_id":"type_text"}]}"""),
        )

        assertEquals("type_text", plan.actions[0].summary)
    }

    @Test
    fun resultNamesWhatFailedAndCountsWhatDidNot() {
        val line = AiPlan.describeResult(
            JSONObject(
                """
                {"t":"ai_result","id":"g","results":[
                  {"index":0,"action_id":"media_control","ok":true,"detail":"Media key: play_pause"},
                  {"index":1,"action_id":"player_transport","ok":false,"detail":"no cast receiver is attached"}]}
                """.trimIndent(),
            ),
        )

        assertTrue(line.contains("Media key: play_pause"))
        assertTrue(line.contains("no cast receiver is attached"))
    }

    @Test
    fun aRefusedPlanReportsTheRefusal() {
        // The PC refuses to run a plan twice, and that answer has to reach the transcript
        // rather than looking like an empty run (§4.4).
        assertEquals(
            "Already run.",
            AiPlan.describeResult(JSONObject("""{"t":"ai_result","id":"g","error":"Already run."}""")),
        )
    }
}
