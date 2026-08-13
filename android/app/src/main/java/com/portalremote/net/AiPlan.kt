package com.portalremote.net

import org.json.JSONObject

/**
 * One thing the assistant proposes doing to the PC.
 *
 * [summary] is written by the PC, not here: it is the side that knows what these actions
 * actually press, and two implementations of "what will this do" is one too many.
 */
data class PlanAction(
    /** Position in the plan. **The unit of approval** — a plan can legitimately contain
     *  the same action twice, so approving by name would run a pair the user half-agreed
     *  to (`docs/phase7-assistant.md` §5). */
    val index: Int,
    val actionId: String,
    val summary: String,
    /** Shutting down or restarting the PC. Gets a second confirmation (§7). */
    val destructive: Boolean = false,
)

/**
 * What the assistant proposes — step 7c of `docs/phase7-assistant.md`.
 *
 * A plan is **not** an execution. agent-platform only ever decides; nothing happens until
 * the user approves a subset and this phone sends it back. That is why asking again is
 * always safe, and why it has to stay that way.
 */
data class AiPlan(
    val id: String,
    val thought: String,
    val actions: List<PlanAction>,
    /** The decision never happened. Distinct from an empty plan, which is the assistant
     *  saying there is nothing to do. */
    val error: String? = null,
) {
    companion object {
        /** `{"t":"ai_plan","id":…,"thought":…,"actions":[…]}`, or the `error` form. */
        fun fromPush(json: JSONObject): AiPlan {
            val actions = json.optJSONArray("actions")
            return AiPlan(
                id = json.optString("id"),
                thought = json.optString("thought"),
                error = json.optString("error").ifBlank { null },
                actions = (0 until (actions?.length() ?: 0)).mapNotNull { i ->
                    val action = actions?.optJSONObject(i) ?: return@mapNotNull null
                    PlanAction(
                        index = action.optInt("index", i),
                        actionId = action.optString("action_id"),
                        summary = action.optString("summary").ifBlank { action.optString("action_id") },
                        destructive = action.optBoolean("destructive"),
                    )
                },
            )
        }

        /**
         * `{"t":"ai_result","id":…,"results":[…]}` as one line for the transcript.
         *
         * Failures are named individually and successes are counted: "it worked" needs no
         * detail, and which one didn't is the only part worth reading.
         */
        fun describeResult(json: JSONObject): String {
            json.optString("error").ifBlank { null }?.let { return it }

            val results = json.optJSONArray("results")
            val count = results?.length() ?: 0
            if (count == 0) return "Nothing was run."

            val done = mutableListOf<String>()
            val failed = mutableListOf<String>()
            for (i in 0 until count) {
                val result = results?.optJSONObject(i) ?: continue
                val detail = result.optString("detail").ifBlank { result.optString("action_id") }
                if (result.optBoolean("ok")) done += detail else failed += detail
            }

            return when {
                failed.isEmpty() -> "Done — ${done.joinToString("; ")}"
                done.isEmpty() -> "Nothing worked — ${failed.joinToString("; ")}"
                else -> "Done — ${done.joinToString("; ")}. Failed — ${failed.joinToString("; ")}"
            }
        }
    }
}
