package com.portalremote.net

import org.json.JSONObject

/**
 * One thing the assistant proposes doing to the PC.
 *
 * [summary] and [verb] are written by the PC, not here: it is the side that knows what
 * these actually press, and two implementations of "what will this do" is one too many
 * when the answer is what somebody approves.
 */
data class PlanAction(
    /** Position in the plan. **The unit of approval** — a plan can legitimately contain
     *  the same action twice, so approving by name would run a pair the user half-agreed
     *  to (`docs/phase7-assistant.md` §5). */
    val index: Int,
    val actionId: String,
    /** The full sentence — "Press ctrl + s", "Power: shutdown". */
    val summary: String,
    /** Two words at most, for a button face: "Mute", "Shut down". A one-action plan is
     *  approved by pressing the thing it does, not by pressing "Run". */
    val verb: String,
    /** Shutting down or restarting the PC. Gets a second confirmation (§7). */
    val destructive: Boolean = false,
)

/** What running one approved action did. */
data class PlanResult(val index: Int, val ok: Boolean, val detail: String)

/**
 * What the assistant proposed, attached to the reply it came with.
 *
 * A plan is **not** an execution. agent-platform only ever decides; nothing happens until
 * somebody approves a subset. That is why asking again is always safe, and why it has to
 * stay that way.
 *
 * [state] is what carries the card through its whole life in the transcript — proposed,
 * run, declined — instead of a dialog that appears and vanishes leaving no record.
 */
data class AiPlan(
    val thought: String,
    val state: String,
    val error: String?,
    val actions: List<PlanAction>,
    val results: List<PlanResult>,
) {
    /** Still a question. The only state that shows buttons. */
    val pending: Boolean get() = state == PENDING

    companion object {
        const val PENDING = "pending"
        const val RAN = "ran"
        const val CANCELLED = "cancelled"

        /** Held only in the PC's memory, and the PC restarted. Saying so beats a Run
         *  button that fails. */
        const val EXPIRED = "expired"

        /** The decision itself failed; [error] names the cause. */
        const val FAILED = "failed"

        fun fromJson(json: JSONObject): AiPlan {
            val actions = json.optJSONArray("actions")
            val results = json.optJSONArray("results")
            return AiPlan(
                thought = json.optString("thought"),
                state = json.optStringOrNull("state") ?: PENDING,
                error = json.optStringOrNull("error"),
                actions = (0 until (actions?.length() ?: 0)).mapNotNull { i ->
                    val action = actions?.optJSONObject(i) ?: return@mapNotNull null
                    PlanAction(
                        index = action.optInt("index", i),
                        actionId = action.optString("actionId"),
                        summary = action.optStringOrNull("summary") ?: action.optString("actionId"),
                        verb = action.optStringOrNull("verb") ?: "Run",
                        destructive = action.optBoolean("destructive"),
                    )
                },
                results = (0 until (results?.length() ?: 0)).mapNotNull { i ->
                    val result = results?.optJSONObject(i) ?: return@mapNotNull null
                    PlanResult(
                        index = result.optInt("index", i),
                        ok = result.optBoolean("ok"),
                        detail = result.optString("detail"),
                    )
                },
            )
        }
    }
}
