package deputy.broker.present

import deputy.broker.Action
import deputy.broker.ActionResult
import deputy.broker.ApprovalKind
import deputy.broker.BrokerSnapshot
import deputy.broker.Capabilities
import deputy.broker.Direction
import deputy.broker.Event
import deputy.broker.Grant
import deputy.broker.GrantId
import deputy.broker.GrantRecord
import deputy.broker.InboundAnswer
import deputy.broker.Interval
import deputy.broker.Permit
import deputy.broker.Principal
import deputy.broker.Question
import deputy.broker.Refusal
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

/**
 * Renders broker state for a person. Deterministic and pure, so what the Keyring and Activity
 * say is testable, and the UI layer only lays it out (docs/decisions.md D8).
 */

data class KeyView(
    val grantId: GrantId,
    val title: String,
    val sentence: String,
    val chips: List<String>,
    val holder: String,
    val direction: Direction,
    val status: GrantRecord.Status,
    val active: Boolean,
)

enum class Tone { DONE, REFUSED, ASKED, INBOUND, SYSTEM, FAILED }

data class ActivityItem(
    val seq: Long,
    val at: Instant,
    val time: String,
    val title: String,
    val body: String,
    val footer: String,
    val tone: Tone,
    /** Non-null if the person can undo this entry; pass to UserPort.undo. */
    val undoSeq: Long? = null,
)

data class ApprovalView(
    val requestId: deputy.broker.RequestId,
    val kind: ApprovalKind,
    val headline: String,
    val explanation: String,
    val rows: List<Pair<String, String>>,
    /** The exact typed request, for people who want to see what the code sees. */
    val machineLine: String,
    val approveLabel: String,
)

object Keyring {
    fun describe(record: GrantRecord, now: Instant, zone: TimeZone): KeyView {
        val g = record.grant
        val status = record.status(now)
        val chips = buildList {
            g.permits.forEach { addAll(permitChips(it)) }
            record.usesLeft?.let { add(if (it == 1) "1 use left" else "$it uses left") }
            add(expiryChip(g.expiresAt, now, zone))
            if (!g.delegable) add("no re-sharing")
            if (status != GrantRecord.Status.ACTIVE) add(status.name.lowercase().replace('_', ' '))
        }
        return KeyView(g.id, g.title, sentence(g), chips, g.holder.displayName, g.direction, status, status == GrantRecord.Status.ACTIVE)
    }

    fun sentence(g: Grant): String {
        val p = g.permits
        val parts = mutableListOf<String>()
        if (p.any { it is Permit.FreeBusy }) parts += "Can see when you're busy, not what."
        if (p.any { it is Permit.Holds }) parts += "May place holds, and release its own."
        p.filterIsInstance<Permit.Invites>().forEach {
            val who = it.allowedAttendees?.joinToString() ?: "anyone"
            parts += if (it.preapproved) "May send invites to $who without asking." else "May send invites to $who. Asks you first each time."
        }
        p.filterIsInstance<Permit.InboundFreeBusy>().forEach {
            parts += "${g.holder.displayName} may ask if you're free ${days(it.weekdays)}, ${it.earliestHour}:00 to ${it.latestHour}:00. Nothing else."
        }
        return parts.joinToString(" ")
    }

    private fun permitChips(p: Permit): List<String> = when (p) {
        is Permit.FreeBusy -> listOf("free/busy only", "next ${p.maxDaysAhead} days", "${p.granularityMinutes}-min slots")
        is Permit.Holds -> listOf("up to ${p.maxActiveHolds} holds", "≤ ${p.maxDurationMinutes / 60.0}".removeSuffix(".0") + " h each")
        is Permit.Invites -> listOf(if (p.preapproved) "no asking" else "asks first")
        is Permit.InboundFreeBusy -> listOf("free/busy only", days(p.weekdays), "${p.earliestHour}–${p.latestHour}h", "${p.questionsPerWeek} questions/week")
    }

    private fun days(set: Set<DayOfWeek>): String {
        val weekdays = setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY)
        return when (set) {
            weekdays -> "weekdays"
            DayOfWeek.entries.toSet() -> "any day"
            else -> set.sortedBy { it.ordinal }.joinToString("/") { Format.day(it) }
        }
    }

    private fun expiryChip(expiresAt: Instant?, now: Instant, zone: TimeZone): String = when {
        expiresAt == null -> "ongoing"
        expiresAt <= now -> "ended"
        expiresAt - now < 1.hours -> "ends in ${(expiresAt - now).inWholeMinutes} min"
        expiresAt - now < 1.days -> "ends in ${(expiresAt - now).inWholeHours} h"
        expiresAt - now < 7.days -> "ends ${Format.day(expiresAt.toLocalDateTime(zone).dayOfWeek)}"
        else -> "ends ${Format.date(expiresAt, zone)}"
    }
}

object Narrator {
    /** The activity log, newest first. */
    fun narrate(snapshot: BrokerSnapshot): List<ActivityItem> {
        val grants = snapshot.grants.associateBy { it.grant.id }
        val requests = snapshot.entries.mapNotNull { it.event as? Event.ApprovalRequested }.associateBy { it.requestId }
        val activeHolds = snapshot.holds.map { it.holdId }.toSet()
        val zone = snapshot.zone
        return snapshot.entries.map { entry ->
            val e = entry.event
            val time = Format.time(e.at, zone, snapshot.now)
            fun key(id: GrantId?) = id?.let { grants[it]?.grant?.title }?.let { "key: $it" }
            when (e) {
                is Event.GrantMinted -> ActivityItem(entry.seq, e.at, time, "New key: ${e.grant.title}", Keyring.sentence(e.grant),
                    if (e.viaRequest != null) "asked for by ${e.grant.holder.displayName.lowercase()} · you approved" else "for ${e.grant.holder.displayName}", Tone.SYSTEM)
                is Event.GrantRevoked -> ActivityItem(entry.seq, e.at, time, "Revoked: ${grants[e.grantId]?.grant?.title ?: "a key"}",
                    "It stopped working immediately.", "by you", Tone.SYSTEM)
                is Event.KillSwitch -> ActivityItem(entry.seq, e.at, time, "Revoked everything",
                    "${plural(e.revoked.size, "key")} revoked, ${plural(e.cancelled.size, "pending request")} cancelled.", "kill switch", Tone.REFUSED)
                is Event.ApprovalRequested -> ActivityItem(entry.seq, e.at, time,
                    if (e.kind == ApprovalKind.MINT_GRANT) "${e.principal.displayName} asked for a key" else "Asked you first",
                    if (e.kind == ApprovalKind.MINT_GRANT) "\"${e.grantRequest?.title}\"" + (e.grantRequest?.purpose?.let { ", for: $it" } ?: "")
                    else describe(e.action!!, zone, snapshot.now),
                    if (e.kind == ApprovalKind.CONFIRM_PERMANENT) "permanent · ${key(e.grantId) ?: ""}".trimEnd(' ', '·') else "new key", Tone.ASKED)
                is Event.ApprovalResolved -> {
                    val req = requests[e.requestId]
                    val what = when {
                        req == null -> "a request"
                        req.kind == ApprovalKind.MINT_GRANT -> "key \"${req.grantRequest?.title}\""
                        else -> describe(req.action!!, zone, snapshot.now)
                    }
                    ActivityItem(entry.seq, e.at, time, if (e.approved) "You approved" else "You declined", what.replaceFirstChar { it.uppercase() },
                        if (e.approved) "approved" else "nothing happened", if (e.approved) Tone.DONE else Tone.SYSTEM)
                }
                is Event.ActionExecuted -> narrateExecuted(entry.seq, e, time, key(e.grantId), zone, snapshot, activeHolds)
                is Event.ActionRefused -> ActivityItem(entry.seq, e.at, time,
                    "Blocked: " + (e.action?.let { shortWhat(it) } ?: "a key request"),
                    e.detail.replaceFirstChar { it.uppercase() } + ". Nothing was done.",
                    "policy said no, not the AI · ${e.reason.name.lowercase()}", Tone.REFUSED)
                is Event.ActionFailed -> ActivityItem(entry.seq, e.at, time, "Couldn't ${shortWhat(e.action)}", e.error,
                    key(e.grantId) ?: "adapter error", Tone.FAILED)
                is Event.FactsRefreshed -> ActivityItem(entry.seq, e.at, time, "Calendar read",
                    "${plural(e.itemCount, "busy time")} for the next 30 days, read on this phone. No titles were read.",
                    "observed · ${e.source}", Tone.SYSTEM)
                is Event.InboundAsked -> narrateInbound(entry.seq, e, time, grants, zone, snapshot.now)
            }
        }.reversed()
    }

    private fun narrateExecuted(seq: Long, e: Event.ActionExecuted, time: String, key: String?, zone: TimeZone, s: BrokerSnapshot, activeHolds: Set<deputy.broker.HoldId>): ActivityItem {
        val footer = key ?: if (e.principal == Principal.User) "by you" else ""
        return when (val r = e.result) {
            is ActionResult.FreeSlots -> ActivityItem(seq, e.at, time, "Found ${plural(r.slots.size, "open time")}",
                (if (r.slots.isEmpty()) "Nothing fit." else r.slots.joinToString(", ") { Format.slot(it, zone, s.now) } + ".") + " Event names stayed private.",
                footer, Tone.DONE)
            is ActionResult.HoldPlaced -> {
                val undoable = r.holdId in activeHolds && seq !in s.undone
                ActivityItem(seq, e.at, time, "Placed a hold",
                    "${Format.slot(r.slot, zone, s.now)}, \"Hold: ${(e.action as Action.PlaceHold).label}\"." + if (undoable) " You can undo this." else "",
                    footer, Tone.DONE, undoSeq = if (undoable) seq else null)
            }
            is ActionResult.HoldReleased -> ActivityItem(seq, e.at, time,
                if (e.undoOf != null) "You undid a hold" else "Released a hold", "The calendar is back as it was.", footer, Tone.DONE)
            is ActionResult.InviteSent -> ActivityItem(seq, e.at, time, "Sent an invite",
                "${Format.slot(r.slot, zone, s.now)} to ${r.attendees.joinToString()}.", "asked first: permanent" + (key?.let { " · $it" } ?: ""), Tone.DONE)
        }
    }

    private fun narrateInbound(seq: Long, e: Event.InboundAsked, time: String, grants: Map<GrantId, GrantRecord>, zone: TimeZone, now: Instant): ActivityItem {
        val q = when (val q = e.question) { is Question.FreeAt -> "free ${Format.slot(Interval.of(q.start, q.durationMinutes), zone, now)}?" }
        val body = when (e.answer) {
            InboundAnswer.FREE -> "Answered \"free\". Nothing else about your week was shared."
            InboundAnswer.BUSY -> "Answered \"busy\". Not why, or what."
            InboundAnswer.NOT_AVAILABLE -> "Declined with the same reply as every decline: \"not available\"."
        }
        val footer = when {
            e.declineReason != null -> "only you see why: ${reasonText(e.declineReason)}"
            else -> "inbound · key: ${e.grantId?.let { grants[it]?.grant?.title } ?: "?"}"
        }
        return ActivityItem(seq, e.at, time, "${e.asker.displayName} asked: $q", body, footer, Tone.INBOUND)
    }

    fun approval(req: Event.ApprovalRequested, zone: TimeZone, now: Instant): ApprovalView = when (req.kind) {
        ApprovalKind.MINT_GRANT -> {
            val r = req.grantRequest!!
            val preview = Grant(GrantId("preview"), req.principal, Direction.OUTBOUND, r.title, r.permits, r.maxUses, now,
                deputy.broker.GrantTemplates.ttlToExpiry(now, r.ttlMinutes))
            ApprovalView(req.requestId, req.kind, r.title,
                "${req.principal.displayName} is asking for this key. Nothing happens until you approve it.",
                buildList {
                    add("Can" to Keyring.sentence(preview))
                    r.purpose?.let { add("Says it's for" to it) }
                    add("Uses" to (r.maxUses?.toString() ?: "no limit"))
                    add("Expires" to (r.ttlMinutes?.let { Format.duration(it) } ?: "never"))
                    add("Re-sharing" to "Not allowed")
                },
                deputy.broker.Journal.JSON.encodeToString(deputy.broker.GrantRequest.serializer(), r),
                "Approve key")
        }
        ApprovalKind.CONFIRM_PERMANENT -> {
            val a = req.action!!
            ApprovalView(req.requestId, req.kind, describe(a, zone, now).replaceFirstChar { it.uppercase() },
                "This can't be undone, so Deputy asks first. ${req.principal.displayName} proposed it.",
                when (a) {
                    is Action.SendInvite -> listOf("When" to Format.slot(Interval.of(a.start, a.durationMinutes), zone, now),
                        "Title" to a.title, "Invites" to a.attendees.joinToString(), "Undo" to "Not possible: they'll be notified")
                    else -> listOf("Action" to describe(a, zone, now))
                },
                deputy.broker.Journal.JSON.encodeToString(Action.serializer(), a),
                "Do it")
        }
    }

    fun describe(a: Action, zone: TimeZone, now: Instant): String = when (a) {
        is Action.FindFreeSlots -> "find ${a.durationMinutes}-minute openings, ${Format.date(a.from)} to ${Format.date(a.to)}"
        is Action.PlaceHold -> "place a hold ${Format.slot(Interval.of(a.start, a.durationMinutes), zone, now)}, \"${a.label}\""
        is Action.ReleaseHold -> "release a hold"
        is Action.SendInvite -> "send \"${a.title}\" ${Format.slot(Interval.of(a.start, a.durationMinutes), zone, now)} to ${a.attendees.joinToString()}"
    }

    private fun shortWhat(a: Action) = Capabilities.spec(a.capability)?.title?.lowercase() ?: a.capability.value

    fun reasonText(r: Refusal): String = when (r) {
        Refusal.INBOUND_NO_GRANT -> "they have no key to ask"
        Refusal.INBOUND_BUDGET -> "they used up this week's questions"
        Refusal.INBOUND_NO_FACTS -> "the calendar couldn't be read"
        Refusal.OUT_OF_SCOPE -> "outside what their key allows"
        else -> r.name.lowercase().replace('_', ' ')
    }

    private fun plural(n: Int, word: String) = "$n $word" + if (n == 1) "" else "s"
}

object Format {
    fun day(d: DayOfWeek) = d.name.take(3).lowercase().replaceFirstChar { it.uppercase() }

    fun time(at: Instant, zone: TimeZone, now: Instant): String {
        val t = at.toLocalDateTime(zone)
        val n = now.toLocalDateTime(zone)
        val hm = "%d:%02d".format(t.hour, t.minute)
        return if (t.date == n.date) hm else "${day(t.dayOfWeek)} $hm"
    }

    fun slot(i: Interval, zone: TimeZone, now: Instant): String {
        val s = i.start.toLocalDateTime(zone)
        val e = i.end.toLocalDateTime(zone)
        val today = now.toLocalDateTime(zone).date
        val dayLabel = if (s.date == today) "today" else day(s.dayOfWeek)
        return "$dayLabel %d:%02d–%d:%02d".format(s.hour, s.minute, e.hour, e.minute)
    }

    fun date(d: kotlinx.datetime.LocalDate) = "${day(d.dayOfWeek)} ${d.month.name.take(3).lowercase().replaceFirstChar { it.uppercase() }} ${d.day}"
    fun date(at: Instant, zone: TimeZone) = date(at.toLocalDateTime(zone).date)

    fun duration(minutes: Int): String = when {
        minutes % (24 * 60) == 0 -> "${minutes / (24 * 60)} day" + if (minutes / (24 * 60) == 1) "" else "s"
        minutes % 60 == 0 -> "${minutes / 60} h"
        else -> "$minutes min"
    }
}
