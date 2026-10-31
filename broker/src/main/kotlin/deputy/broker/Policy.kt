package deputy.broker

import kotlinx.datetime.DatePeriod
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

/** A hold Deputy placed and has not released. */
data class HoldRecord(val holdId: HoldId, val slot: Interval, val adapterRef: String, val grantId: GrantId?)

data class PolicyContext(
    val now: Instant,
    val zone: TimeZone,
    val activeHolds: Map<HoldId, HoldRecord>,
)

sealed interface Verdict {
    data class Allow(val permit: Permit, val needsConfirmation: Boolean) : Verdict
    data class Deny(val reason: Refusal, val detail: String) : Verdict
}

/**
 * The reference monitor's rules. Pure functions of (who, which grant, what action, when):
 * no I/O, no model, no randomness. If a decision is not explained by this file, it is a bug.
 */
object Policy {
    const val MAX_LABEL = 60
    const val MAX_TITLE = 120
    const val MAX_ATTENDEES = 10
    const val MAX_REQUEST_TTL_MINUTES = 7 * 24 * 60

    fun authorize(principal: Principal, record: GrantRecord?, action: Action, ctx: PolicyContext): Verdict {
        val spec = Capabilities.spec(action.capability)
            ?: return Verdict.Deny(Refusal.UNAVAILABLE, "unknown capability ${action.capability}")
        if (!spec.available) return Verdict.Deny(Refusal.UNAVAILABLE, "${spec.title} is not connected yet")
        if (spec.requiresHumanGesture) return Verdict.Deny(Refusal.HUMAN_GESTURE_ONLY, "${spec.title} needs your own tap")
        if (spec.direction != Direction.OUTBOUND) return Verdict.Deny(Refusal.NO_PERMIT, "${spec.title} is not something an agent can do")

        if (record == null) return Verdict.Deny(Refusal.UNKNOWN_GRANT, "no such key")
        val grant = record.grant
        // Non-delegable: the handle is worthless to anyone but its holder (D3).
        if (grant.holder != principal) return Verdict.Deny(Refusal.NOT_HOLDER, "this key belongs to ${grant.holder.displayName}")
        when (record.status(ctx.now)) {
            GrantRecord.Status.ACTIVE -> Unit
            GrantRecord.Status.REVOKED -> return Verdict.Deny(Refusal.REVOKED, "the key was revoked")
            GrantRecord.Status.EXPIRED -> return Verdict.Deny(Refusal.EXPIRED, "the key has expired")
            GrantRecord.Status.NOT_YET_VALID -> return Verdict.Deny(Refusal.NOT_YET_VALID, "the key is not valid yet")
            GrantRecord.Status.EXHAUSTED -> return Verdict.Deny(Refusal.EXHAUSTED, "the key has no uses left")
        }
        if (grant.direction != Direction.OUTBOUND) return Verdict.Deny(Refusal.NO_PERMIT, "an inbound key cannot act")

        val covering = grant.permits.filter { action.capability in it.capabilities }
        if (covering.isEmpty()) return Verdict.Deny(Refusal.NO_PERMIT, "the key does not cover ${spec.title.lowercase()}")
        var firstViolation: String? = null
        for (permit in covering) {
            val violation = checkPermit(permit, action, ctx)
            if (violation == null) {
                val preapproved = permit is Permit.Invites && permit.preapproved
                return Verdict.Allow(permit, needsConfirmation = spec.risk == RiskTier.PERMANENT && !preapproved)
            }
            firstViolation = firstViolation ?: violation
        }
        return Verdict.Deny(Refusal.OUT_OF_SCOPE, firstViolation!!)
    }

    /** Null if [action] is within [permit]; otherwise a sentence saying which limit it crossed. */
    fun checkPermit(permit: Permit, action: Action, ctx: PolicyContext): String? {
        val today = ctx.now.toLocalDateTime(ctx.zone).date
        return when {
            permit is Permit.FreeBusy && action is Action.FindFreeSlots -> when {
                action.to < action.from -> "the date range is backwards"
                action.from < today -> "the range starts in the past"
                action.to > today.plus(DatePeriod(days = permit.maxDaysAhead)) ->
                    "it looks further ahead than ${permit.maxDaysAhead} days"
                action.durationMinutes <= 0 || action.durationMinutes > 8 * 60 -> "the duration is not reasonable"
                action.durationMinutes % permit.granularityMinutes != 0 ->
                    "the duration is not a multiple of ${permit.granularityMinutes} minutes"
                action.earliestHour < permit.earliestHour || action.latestHour > permit.latestHour ||
                    action.earliestHour >= action.latestHour ->
                    "the hours fall outside ${permit.earliestHour}:00 to ${permit.latestHour}:00"
                action.maxResults !in 1..permit.maxResults -> "it asks for more than ${permit.maxResults} options"
                else -> null
            }

            permit is Permit.Holds && action is Action.PlaceHold -> when {
                action.durationMinutes <= 0 -> "the duration is not reasonable"
                action.durationMinutes > permit.maxDurationMinutes -> "the hold is longer than ${permit.maxDurationMinutes} minutes"
                action.start < ctx.now -> "the hold starts in the past"
                action.start > ctx.now + permit.maxDaysAhead.days -> "the hold is more than ${permit.maxDaysAhead} days out"
                !isCleanText(action.label, MAX_LABEL) -> "the label is too long or has line breaks"
                ctx.activeHolds.size >= permit.maxActiveHolds -> "there are already ${ctx.activeHolds.size} holds"
                else -> null
            }

            permit is Permit.Holds && action is Action.ReleaseHold ->
                if (action.holdId in ctx.activeHolds) null else "that is not an active hold Deputy placed"

            permit is Permit.Invites && action is Action.SendInvite -> when {
                action.attendees.isEmpty() -> "there is nobody to invite"
                action.attendees.size > MAX_ATTENDEES -> "it invites more than $MAX_ATTENDEES people"
                permit.allowedAttendees != null && !permit.allowedAttendees.containsAll(action.attendees) ->
                    "it invites someone this key does not name"
                action.durationMinutes <= 0 || action.durationMinutes > permit.maxDurationMinutes ->
                    "the event is longer than ${permit.maxDurationMinutes} minutes"
                action.start < ctx.now -> "the event starts in the past"
                action.start > ctx.now + permit.maxDaysAhead.days -> "the event is more than ${permit.maxDaysAhead} days out"
                !isCleanText(action.title, MAX_TITLE) -> "the title is too long or has line breaks"
                else -> null
            }

            else -> "the key does not cover this action"
        }
    }

    /** What a requester may even ask the user for. The user still sees and approves it. */
    fun validateGrantRequest(requester: Principal, request: GrantRequest): String? = when {
        request.permits.isEmpty() -> "the request names no authority"
        request.permits.any { it is Permit.InboundFreeBusy } && requester.kind != Principal.Kind.USER ->
            "only you can let someone else ask about you"
        request.ttlMinutes == null && requester.kind != Principal.Kind.USER -> "an assistant's key must expire"
        (request.ttlMinutes ?: 0) > MAX_REQUEST_TTL_MINUTES && requester.kind != Principal.Kind.USER ->
            "an assistant's key may last at most 7 days"
        (request.ttlMinutes ?: 1) <= 0 -> "the expiry is not reasonable"
        !isCleanText(request.title, MAX_LABEL) -> "the title is too long or has line breaks"
        request.purpose != null && !isCleanText(request.purpose, MAX_TITLE) -> "the purpose is too long or has line breaks"
        else -> null
    }

    sealed interface InboundVerdict {
        data class Allow(val grant: Grant) : InboundVerdict
        data class Deny(val reason: Refusal, val detail: String) : InboundVerdict
    }

    /**
     * Inbound questions use the same grants and the same style of check as outbound actions.
     * [answeredRecently] counts answered (not declined) questions per grant in the last 7 days.
     */
    fun authorizeInbound(
        asker: Principal,
        grants: Collection<GrantRecord>,
        question: Question,
        ctx: PolicyContext,
        answeredRecently: (GrantId) -> Int,
    ): InboundVerdict {
        val candidates = grants.filter {
            it.grant.holder == asker && it.grant.direction == Direction.INBOUND && it.status(ctx.now) == GrantRecord.Status.ACTIVE
        }
        if (candidates.isEmpty()) return InboundVerdict.Deny(Refusal.INBOUND_NO_GRANT, "${asker.displayName} has no active key to ask")
        var firstDeny: InboundVerdict.Deny? = null
        for (record in candidates) {
            for (permit in record.grant.permits.filterIsInstance<Permit.InboundFreeBusy>()) {
                val violation = checkInbound(permit, question, ctx)
                val deny = when {
                    violation != null -> InboundVerdict.Deny(Refusal.OUT_OF_SCOPE, violation)
                    answeredRecently(record.grant.id) >= permit.questionsPerWeek ->
                        InboundVerdict.Deny(Refusal.INBOUND_BUDGET, "${asker.displayName} has used this week's ${permit.questionsPerWeek} questions")
                    else -> return InboundVerdict.Allow(record.grant)
                }
                firstDeny = firstDeny ?: deny
            }
        }
        return firstDeny ?: InboundVerdict.Deny(Refusal.NO_PERMIT, "the key does not cover this question")
    }

    private fun checkInbound(permit: Permit.InboundFreeBusy, question: Question, ctx: PolicyContext): String? = when (question) {
        is Question.FreeAt -> {
            val start = question.start.toLocalDateTime(ctx.zone)
            val slot = Interval.of(question.start, question.durationMinutes.coerceAtLeast(1))
            val end = slot.end.toLocalDateTime(ctx.zone)
            val endMinutes = end.hour * 60 + end.minute
            when {
                question.durationMinutes !in 1..permit.maxSlotMinutes -> "the slot is longer than ${permit.maxSlotMinutes} minutes"
                question.start < ctx.now -> "the slot is in the past"
                question.start > ctx.now + permit.maxDaysAhead.days -> "the slot is more than ${permit.maxDaysAhead} days out"
                start.date != end.date -> "the slot crosses midnight"
                start.dayOfWeek !in permit.weekdays -> "${start.dayOfWeek.name.lowercase()} is outside the allowed days"
                start.hour < permit.earliestHour || endMinutes > permit.latestHour * 60 ->
                    "the slot falls outside ${permit.earliestHour}:00 to ${permit.latestHour}:00"
                else -> null
            }
        }
    }

    private fun isCleanText(text: String, max: Int) = text.isNotBlank() && text.length <= max && text.none { it == '\n' || it == '\r' || it == '\t' }
}
