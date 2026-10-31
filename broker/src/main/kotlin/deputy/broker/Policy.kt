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
    /** Who is asking. Holds can only be released by the principal whose key placed them. */
    val principal: Principal? = null,
    val holdOwner: (HoldRecord) -> Principal? = { null },
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
                ctx.activeHolds.values.count { ctx.holdOwner(it) == ctx.principal } >= permit.maxActiveHolds ->
                    "there are already ${permit.maxActiveHolds} holds"
                else -> null
            }

            permit is Permit.Holds && action is Action.ReleaseHold -> {
                val hold = ctx.activeHolds[action.holdId]
                when {
                    hold == null -> "that is not an active hold Deputy placed"
                    ctx.holdOwner(hold) != ctx.principal -> "that hold was placed for someone else"
                    else -> null
                }
            }

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
        request.permits.size > 4 -> "the request names too many permits"
        request.permits.any { it is Permit.InboundFreeBusy } && requester.kind != Principal.Kind.USER ->
            "only you can let someone else ask about you"
        request.permits.any { it is Permit.Invites && it.preapproved } && requester.kind != Principal.Kind.USER ->
            "only you can turn off asking first"
        request.maxUses != null && request.maxUses !in 1..1000 -> "the use limit is not reasonable"
        request.permits.firstNotNullOfOrNull { checkBounds(it) } != null -> request.permits.firstNotNullOf { checkBounds(it) }
        request.ttlMinutes == null && requester.kind != Principal.Kind.USER -> "an assistant's key must expire"
        (request.ttlMinutes ?: 0) > MAX_REQUEST_TTL_MINUTES && requester.kind != Principal.Kind.USER ->
            "an assistant's key may last at most 7 days"
        (request.ttlMinutes ?: 1) <= 0 -> "the expiry is not reasonable"
        !isCleanText(request.title, MAX_LABEL) -> "the title is too long or has line breaks"
        request.purpose != null && !isCleanText(request.purpose, MAX_TITLE) -> "the purpose is too long or has line breaks"
        else -> null
    }

    /**
     * Sane ranges for every permit field. Without these a requested permit could make the broker
     * loop or throw (a zero or negative slot size), or ask for far more than it reads like.
     */
    fun checkBounds(p: Permit): String? = when (p) {
        is Permit.FreeBusy -> when {
            p.maxDaysAhead !in 1..60 -> "free/busy can look at most 60 days ahead"
            p.granularityMinutes !in setOf(15, 30, 60) -> "slots must be 15, 30 or 60 minutes"
            !validHours(p.earliestHour, p.latestHour) -> "the hours are not a valid range"
            p.maxResults !in 1..10 -> "at most 10 options per question"
            else -> null
        }
        is Permit.Holds -> when {
            p.maxDaysAhead !in 1..60 -> "holds can be at most 60 days out"
            p.maxDurationMinutes !in 15..480 -> "a hold must be 15 minutes to 8 hours"
            p.maxActiveHolds !in 1..10 -> "at most 10 holds at once"
            else -> null
        }
        is Permit.Invites -> when {
            p.maxDaysAhead !in 1..90 -> "invites can be at most 90 days out"
            p.maxDurationMinutes !in 15..480 -> "an event must be 15 minutes to 8 hours"
            p.allowedAttendees != null && (p.allowedAttendees.size !in 1..20 || p.allowedAttendees.any { !isCleanText(it, 254) }) ->
                "the list of people is not valid"
            else -> null
        }
        is Permit.InboundFreeBusy -> when {
            p.weekdays.isEmpty() -> "no days allowed"
            !validHours(p.earliestHour, p.latestHour) -> "the hours are not a valid range"
            p.maxDaysAhead !in 1..60 -> "questions can look at most 60 days ahead"
            p.maxSlotMinutes !in 15..240 -> "a slot must be 15 minutes to 4 hours"
            p.questionsPerWeek !in 1..50 -> "at most 50 questions a week"
            else -> null
        }
    }

    private fun validHours(earliest: Int, latest: Int) = earliest in 0..23 && latest in 1..24 && earliest < latest

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
        /** Answered questions from this asker in the last 7 days, across all their keys. */
        answeredRecently: Int,
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
                    answeredRecently >= permit.questionsPerWeek ->
                        InboundVerdict.Deny(Refusal.INBOUND_BUDGET, "${asker.displayName} has used this week's ${permit.questionsPerWeek} questions")
                    else -> return InboundVerdict.Allow(record.grant)
                }
                firstDeny = firstDeny ?: deny
            }
        }
        return firstDeny ?: InboundVerdict.Deny(Refusal.NO_PERMIT, "the key does not cover this question")
    }

    private fun checkInbound(permit: Permit.InboundFreeBusy, question: Question, ctx: PolicyContext): String? = when (question) {
        is Question.FreeAt -> when {
            // Range checks first, so no calendar arithmetic ever runs on an extreme instant.
            question.durationMinutes !in 1..permit.maxSlotMinutes -> "the slot is longer than ${permit.maxSlotMinutes} minutes"
            question.start < ctx.now -> "the slot is in the past"
            question.start > ctx.now + permit.maxDaysAhead.days -> "the slot is more than ${permit.maxDaysAhead} days out"
            else -> {
                val start = question.start.toLocalDateTime(ctx.zone)
                val end = Interval.of(question.start, question.durationMinutes).end.toLocalDateTime(ctx.zone)
                val endMinutes = end.hour * 60 + end.minute
                when {
                start.date != end.date -> "the slot crosses midnight"
                start.dayOfWeek !in permit.weekdays -> "${start.dayOfWeek.name.lowercase()} is outside the allowed days"
                start.hour < permit.earliestHour || endMinutes > permit.latestHour * 60 ->
                    "the slot falls outside ${permit.earliestHour}:00 to ${permit.latestHour}:00"
                else -> null
                }
            }
        }
    }

    /** Non-blank, bounded, and free of control, format (bidi overrides) and separator characters. */
    fun isCleanText(text: String, max: Int) = text.isNotBlank() && text.length <= max && text.none {
        it.category == CharCategory.CONTROL || it.category == CharCategory.FORMAT ||
            it.category == CharCategory.LINE_SEPARATOR || it.category == CharCategory.PARAGRAPH_SEPARATOR
    }
}
