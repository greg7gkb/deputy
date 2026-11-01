package deputy.planner

import com.anthropic.core.JsonValue
import com.anthropic.models.messages.Tool
import deputy.broker.Action
import deputy.broker.ActionResult
import deputy.broker.GrantId
import deputy.broker.GrantRequest
import deputy.broker.GrantSummary
import deputy.broker.GrantTemplates
import deputy.broker.HoldId
import deputy.broker.Interval
import deputy.broker.Outcome
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Deputy's capabilities as Claude tools. Each tool maps one-to-one onto a typed broker call;
 * nothing here decides anything. Times cross this boundary as local date-times in the user's
 * zone, because that is how the request is phrased and it saves the model a conversion.
 */
object Tools {
    sealed interface Call {
        data object ListKeys : Call
        data class RequestKey(val request: GrantRequest) : Call
        data class Propose(val keyId: GrantId, val action: Action) : Call
    }

    val definitions: List<Tool> = listOf(
        tool("list_my_keys", "List the keys you currently hold: their ids, what each covers, uses left and expiry. Check this before asking for a new key.", emptyMap()),
        tool(
            "request_key",
            "Ask the user for a key. Deputy shows them exactly what it allows and nothing happens until they approve; this call waits for their decision. " +
                "kind 'calendar_scheduling' covers find_free_slots, place_hold and release_hold. kind 'send_invites' covers send_invite, only to the attendees you list.",
            mapOf(
                "kind" to mapOf("type" to "string", "enum" to listOf("calendar_scheduling", "send_invites")),
                "purpose" to mapOf("type" to "string", "description" to "One short line the user will see, e.g. 'Find a time with Dana next week'."),
                "invite_attendees" to mapOf("type" to "array", "items" to mapOf("type" to "string"), "description" to "Email addresses, for send_invites. Empty for calendar_scheduling."),
            ),
        ),
        tool(
            "find_free_slots",
            "Find open times in the user's calendar. Returns a few candidate slots, never what the user is busy with.",
            mapOf(
                "key_id" to str("A key id from list_my_keys or request_key."),
                "from_date" to str("First day to search, YYYY-MM-DD, local."),
                "to_date" to str("Last day to search, inclusive, YYYY-MM-DD, local."),
                "duration_minutes" to int("Length of the meeting, a multiple of 30."),
                "earliest_hour" to int("Earliest start hour, 0-23, local."),
                "latest_hour" to int("Latest end hour, 1-24, local."),
                "max_results" to int("How many options, 1-5."),
            ),
        ),
        tool(
            "place_hold",
            "Put a tentative hold on the user's calendar. The user can undo it.",
            mapOf(
                "key_id" to str("A key covering holds."),
                "start" to str("Local start, YYYY-MM-DDTHH:MM."),
                "duration_minutes" to int("Length in minutes."),
                "label" to str("Short label; the event is titled 'Hold: <label>'."),
            ),
        ),
        tool(
            "release_hold",
            "Remove a hold you placed earlier, by the hold_id that place_hold returned.",
            mapOf("key_id" to str("The key the hold was placed under."), "hold_id" to str("From place_hold.")),
        ),
        tool(
            "send_invite",
            "Create an event and invite people. This cannot be undone, so Deputy asks the user first; the call waits for their decision.",
            mapOf(
                "key_id" to str("A send_invites key naming these attendees."),
                "start" to str("Local start, YYYY-MM-DDTHH:MM."),
                "duration_minutes" to int("Length in minutes."),
                "title" to str("Event title the attendees will see."),
                "attendees" to mapOf("type" to "array", "items" to mapOf("type" to "string"), "description" to "Email addresses."),
            ),
        ),
    )

    /** Parses a tool call into a typed broker call, or throws IllegalArgumentException with a reason the model can act on. */
    fun parse(name: String, input: Map<String, Any?>, zone: TimeZone): Call {
        fun s(k: String) = (input[k] as? String)?.takeIf { it.isNotBlank() } ?: throw IllegalArgumentException("'$k' is required")
        fun i(k: String) = (input[k] as? Number)?.toInt() ?: throw IllegalArgumentException("'$k' must be a number")
        fun list(k: String) = (input[k] as? List<*>)?.map { it.toString() } ?: emptyList()
        fun at(k: String) = runCatching { LocalDateTime.parse(s(k)).toInstant(zone) }.getOrElse { throw IllegalArgumentException("'$k' must look like 2026-11-02T10:00") }
        fun date(k: String) = runCatching { LocalDate.parse(s(k)) }.getOrElse { throw IllegalArgumentException("'$k' must look like 2026-11-02") }
        val key = { GrantId(s("key_id")) }
        return when (name) {
            "list_my_keys" -> Call.ListKeys
            "request_key" -> {
                val purpose = s("purpose")
                Call.RequestKey(when (s("kind")) {
                    "calendar_scheduling" -> GrantTemplates.calendarForScheduling().copy(purpose = purpose)
                    "send_invites" -> {
                        val who = list("invite_attendees")
                        require(who.isNotEmpty()) { "send_invites needs invite_attendees" }
                        GrantTemplates.sendInvites(who).copy(title = "Send invites", purpose = purpose)
                    }
                    else -> throw IllegalArgumentException("unknown kind")
                })
            }
            "find_free_slots" -> Call.Propose(key(), Action.FindFreeSlots(date("from_date"), date("to_date"), i("duration_minutes"), i("earliest_hour"), i("latest_hour"), i("max_results")))
            "place_hold" -> Call.Propose(key(), Action.PlaceHold(at("start"), i("duration_minutes"), s("label")))
            "release_hold" -> Call.Propose(key(), Action.ReleaseHold(HoldId(s("hold_id"))))
            "send_invite" -> Call.Propose(key(), Action.SendInvite(at("start"), i("duration_minutes"), s("title"), list("attendees")))
            else -> throw IllegalArgumentException("there is no tool called $name")
        }
    }

    /** What the model reads back: the outcome as JSON, with times converted to the user's zone. */
    fun result(outcome: Outcome, zone: TimeZone): String = when (outcome) {
        is Outcome.Done -> buildJsonObject {
            put("status", "done")
            when (val r = outcome.result) {
                is ActionResult.FreeSlots -> put("slots", buildJsonArray { r.slots.forEach { add(slot(it, zone)) } })
                is ActionResult.HoldPlaced -> { put("hold_id", r.holdId.value); put("slot", slot(r.slot, zone)) }
                is ActionResult.HoldReleased -> put("hold_id", r.holdId.value)
                is ActionResult.InviteSent -> { put("slot", slot(r.slot, zone)); put("attendees", JsonArray(r.attendees.map(::JsonPrimitive))) }
            }
        }
        is Outcome.GrantIssued -> buildJsonObject { put("status", "key_issued"); put("key_id", outcome.grantId.value) }
        is Outcome.Refused -> buildJsonObject { put("status", "refused"); put("reason", outcome.reason.name.lowercase()); put("detail", outcome.detail) }
        is Outcome.Failed -> buildJsonObject { put("status", "failed"); put("detail", outcome.error) }
        is Outcome.AwaitingUser -> buildJsonObject { put("status", "waiting_for_user") }
        is Outcome.Ok -> buildJsonObject { put("status", "ok") }
    }.toString()

    fun keys(keys: List<GrantSummary>, zone: TimeZone): String = buildJsonArray {
        keys.forEach { k ->
            add(buildJsonObject {
                put("key_id", k.grantId.value)
                put("title", k.title)
                put("covers", JsonArray(k.capabilities.map { JsonPrimitive(it.value) }))
                put("status", k.status.name.lowercase())
                put("uses_left", k.usesLeft?.let(::JsonPrimitive) ?: JsonNull)
                put("expires", k.expiresAt?.let { JsonPrimitive(local(it, zone)) } ?: JsonNull)
            })
        }
    }.toString()

    private fun slot(i: Interval, zone: TimeZone): JsonElement = buildJsonObject {
        put("start", local(i.start, zone)); put("end", local(i.end, zone))
    }

    private fun local(at: kotlin.time.Instant, zone: TimeZone) = at.toLocalDateTime(zone).toString().take(16)

    private fun str(description: String) = mapOf("type" to "string", "description" to description)
    private fun int(description: String) = mapOf("type" to "integer", "description" to description)

    /** Strict tools: every property required, no extras, so inputs always match the schema. */
    private fun tool(name: String, description: String, properties: Map<String, Any>): Tool = Tool.builder()
        .name(name)
        .description(description)
        .strict(true)
        .inputSchema(
            Tool.InputSchema.builder()
                .properties(Tool.InputSchema.Properties.builder().apply {
                    properties.forEach { (k, v) -> putAdditionalProperty(k, JsonValue.from(v)) }
                }.build())
                .required(properties.keys.toList())
                .putAdditionalProperty("additionalProperties", JsonValue.from(false))
                .build(),
        )
        .build()
}
