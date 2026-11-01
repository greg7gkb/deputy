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
    val holderPrincipal: Principal,
    val direction: Direction,
    val status: GrantRecord.Status,
    val active: Boolean,
)

/** CONTAINED: untrusted input that tried to steer the assistant, and didn't reach it (D25). */
enum class Tone { DONE, REFUSED, ASKED, INBOUND, SYSTEM, FAILED, CONTAINED }

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
    /** What the undo button says ("Undo", "Cancel invite"). */
    val undoLabel: String = "Undo",
    /** Non-null if undoing tells other people; the UI asks for a second tap and shows this. */
    val undoConfirm: String? = null,
    /** Who did or asked this. Null means Deputy itself (reading the calendar, expiring requests). */
    val actor: Principal? = null,
    /** What this entry created outside Deputy, which links back here (deputy://activity/<id>). */
    val artifactId: String? = null,
    /** Where that artifact lives, so the app can open it, e.g. the event in the calendar. */
    val external: ExternalRef? = null,
)

data class ExternalRef(val kind: Kind, val ref: String) {
    enum class Kind { CALENDAR_EVENT }
}

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
        return KeyView(g.id, g.title, sentence(g), chips, g.holder.displayName, g.holder, g.direction, status, status == GrantRecord.Status.ACTIVE)
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
        if (p.any { it is Permit.MailReply }) parts += "May reply to messages Deputy has read, only to whoever sent them. Asks you first each time."
        if (p.any { it is Permit.MailRead }) parts += "May read recent mail as summaries: what kind each message is and any times it proposes. Never who sent it or what it says."
        p.filterIsInstance<Permit.InboundFreeBusy>().forEach {
            parts += "${g.holder.displayName} may ask if you're free ${days(it.weekdays)}, ${it.earliestHour}:00 to ${it.latestHour}:00. Nothing else."
        }
        return parts.joinToString(" ")
    }

    fun permitChips(p: Permit): List<String> = when (p) {
        is Permit.FreeBusy -> listOf("free/busy only", "next ${p.maxDaysAhead} days", "${p.earliestHour}–${p.latestHour}h",
            "${p.granularityMinutes}-min slots", "≤ ${p.maxResults} options")
        is Permit.Holds -> listOf("up to ${p.maxActiveHolds} holds", "≤ ${Format.duration(p.maxDurationMinutes)} each", "≤ ${p.maxDaysAhead} days out")
        is Permit.Invites -> listOf(if (p.preapproved) "no asking" else "asks first", "≤ ${Format.duration(p.maxDurationMinutes)}") +
            (p.allowedAttendees?.let { listOf("only ${it.joinToString()}") } ?: listOf("anyone"))
        is Permit.InboundFreeBusy -> listOf("free/busy only", days(p.weekdays), "${p.earliestHour}–${p.latestHour}h", "${p.questionsPerWeek} questions/week")
        is Permit.MailRead -> listOf("summaries only", "≤ ${p.maxMessages} messages", "last ${p.maxAgeDays} days")
        is Permit.MailReply -> listOf("asks first", "sender only", "≤ ${p.maxBodyChars} characters")
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
        expiresAt - now < 1.days -> "ends in ${((expiresAt - now).inWholeMinutes + 59) / 60} h"
        expiresAt - now < 7.days -> "ends ${Format.day(expiresAt.toLocalDateTime(zone).dayOfWeek)}"
        else -> "ends ${Format.date(expiresAt, zone)}"
    }
}

data class FactView(val title: String, val detail: String, val provenance: String, val sensitivity: String, val freshness: String, val fresh: Boolean)

object FactsView {
    fun describe(fact: deputy.broker.Fact, now: Instant, zone: TimeZone): FactView {
        val (title, detail) = when (val v = fact.value) {
            is deputy.broker.FactValue.BusyIntervals -> "Busy times" to
                "${v.busy.size} busy ${if (v.busy.size == 1) "time" else "times"}, ${Format.date(v.window.start, zone)} to ${Format.date(v.window.end, zone)}. No titles."
            is deputy.broker.FactValue.Text -> fact.key to v.text
            is deputy.broker.FactValue.Flag -> fact.key to if (v.value) "yes" else "no"
        }
        val fresh = now < fact.staleAfter
        return FactView(title, detail, fact.provenance.name.lowercase(), fact.sensitivity.name.lowercase(),
            "${fact.source}, read ${Format.time(fact.observedAt, zone, now)}" + if (fresh) "" else " (stale; re-read when needed)", fresh)
    }
}

object Narrator {
    /** The activity log, newest first. */
    fun narrate(snapshot: BrokerSnapshot): List<ActivityItem> {
        val grants = snapshot.grants.associateBy { it.grant.id }
        val requests = snapshot.entries.mapNotNull { it.event as? Event.ApprovalRequested }.associateBy { it.requestId }
        val activeHolds = snapshot.holds.map { it.holdId }.toSet()
        val activeInvites = snapshot.entries.mapNotNull { (it.event as? Event.ActionExecuted)?.takeIf { e -> e.result is ActionResult.InviteSent }?.artifactId }.toSet() -
            snapshot.entries.mapNotNull { ((it.event as? Event.ActionExecuted)?.result as? ActionResult.InviteCancelled)?.artifactId }.toSet()
        val zone = snapshot.zone
        return snapshot.entries.map { entry ->
            val e = entry.event
            val time = Format.time(e.at, zone, snapshot.now)
            fun key(id: GrantId?) = id?.let { grants[it]?.grant?.title }?.let { "key: $it" }
            val item = when (e) {
                is Event.GrantMinted -> ActivityItem(entry.seq, e.at, time, "New key: ${e.grant.title}", Keyring.sentence(e.grant),
                    if (e.viaRequest != null) "asked for by ${e.grant.holder.displayName.lowercase()} · you approved" else "for ${e.grant.holder.displayName}", Tone.SYSTEM)
                is Event.GrantRevoked -> ActivityItem(entry.seq, e.at, time, "Revoked: ${grants[e.grantId]?.grant?.title ?: "a key"}",
                    "It stopped working immediately.", "by you", Tone.SYSTEM)
                is Event.KillSwitch -> ActivityItem(entry.seq, e.at, time, "Revoked everything",
                    "${plural(e.revoked.size, "key")} revoked, ${plural(e.cancelled.size, "pending request")} cancelled.", "kill switch", Tone.SYSTEM)
                is Event.ApprovalExpired -> ActivityItem(entry.seq, e.at, time, "Nobody answered in time",
                    "${plural(e.requestIds.size, "request")} expired after ${deputy.broker.Broker.APPROVAL_TTL.inWholeMinutes} minutes. Nothing was done.", "expired", Tone.SYSTEM)
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
                        if (e.approved) "approved" else "nothing happened", Tone.SYSTEM)
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
            item.copy(actor = actorOf(e), artifactId = (e as? Event.ActionExecuted)?.artifactId, external = externalOf(e, activeHolds, activeInvites))
        }.reversed()
    }

    /** Who did it. The person for their own decisions, the proposer or asker otherwise, null for Deputy. */
    fun actorOf(e: Event): Principal? = when (e) {
        is Event.GrantMinted, is Event.GrantRevoked, is Event.KillSwitch, is Event.ApprovalResolved -> Principal.User
        is Event.ApprovalRequested -> e.principal
        is Event.ActionExecuted -> e.principal
        is Event.ActionRefused -> e.principal
        is Event.ActionFailed -> e.principal
        is Event.InboundAsked -> e.asker
        is Event.ApprovalExpired, is Event.FactsRefreshed -> null
    }

    private fun externalOf(e: Event, activeHolds: Set<deputy.broker.HoldId>, activeInvites: Set<String>): ExternalRef? {
        val executed = e as? Event.ActionExecuted ?: return null
        val ref = executed.adapterRef ?: return null
        return when (val r = executed.result) {
            is ActionResult.HoldPlaced -> if (r.holdId in activeHolds) ExternalRef(ExternalRef.Kind.CALENDAR_EVENT, ref) else null
            is ActionResult.InviteSent -> if (executed.artifactId != null && executed.artifactId in activeInvites) ExternalRef(ExternalRef.Kind.CALENDAR_EVENT, ref) else null
            else -> null
        }
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
            is ActionResult.InviteSent -> {
                val active = e.artifactId != null && seq !in s.undone && s.entries.none {
                    ((it.event as? Event.ActionExecuted)?.result as? ActionResult.InviteCancelled)?.artifactId == e.artifactId
                }
                ActivityItem(seq, e.at, time, "Sent an invite",
                    "${Format.slot(r.slot, zone, s.now)} to ${r.attendees.joinToString()}." + if (active) " You can cancel it; they'll be told." else "",
                    "asked first: permanent" + (key?.let { " · $it" } ?: ""), Tone.DONE,
                    undoSeq = if (active) seq else null, undoLabel = "Cancel invite",
                    undoConfirm = "Tap again: cancel and notify ${r.attendees.size} ${if (r.attendees.size == 1) "person" else "people"}")
            }
            is ActionResult.InviteCancelled -> ActivityItem(seq, e.at, time, "You cancelled an invite",
                "${Format.slot(r.slot, zone, s.now)} with ${r.attendees.joinToString()}. They were sent a cancellation.", footer, Tone.DONE)
            is ActionResult.InboxRead -> narrateMail(seq, e, time, footer, zone, s.now)
            is ActionResult.ReplySent -> {
                val m = s.messages[r.messageId]
                ActivityItem(seq, e.at, time, "Sent a reply",
                    "To ${m?.replyTo ?: "the sender"}, re \"${m?.subject ?: "a message"}\":\n${(e.action as Action.SendReply).body}",
                    "asked first: permanent" + if (footer.isNotEmpty()) " · $footer" else "", Tone.DONE)
            }
        }
    }

    /**
     * A mail read, from the user's side: each message as they'd recognise it, what the reader made
     * of it, and the line that matters, that the proposer got none of the words (D25).
     */
    private fun narrateMail(seq: Long, e: Event.ActionExecuted, time: String, footer: String, zone: TimeZone, now: Instant): ActivityItem {
        val flagged = e.mail.count { it.reading.addressesAssistant }
        val lines = e.mail.map { m -> "• ${m.from}: \"${m.subject}\"\n   → ${mailLine(m.reading, zone, now)}" }
        val body = buildString {
            if (lines.isEmpty()) append("Nothing new.") else append(lines.joinToString("\n"))
            append("\n\n${e.principal.displayName} was given only the kind of each message and any times in it. ")
            append("No senders, subjects or text: those stayed with Deputy's reader.")
            if (flagged > 0) append(" ${if (flagged == 1) "One message was" else "$flagged messages were"} written to instruct an AI assistant; it never saw ${if (flagged == 1) "that text" else "them"}.")
        }
        return ActivityItem(seq, e.at, time, "Read ${plural(e.mail.size, "email")} for ${e.principal.displayName.lowercase()}", body,
            "untrusted input · read by ${e.mail.firstOrNull()?.reader ?: "the reader"}" + if (footer.isNotEmpty()) " · $footer" else "",
            if (flagged > 0) Tone.CONTAINED else Tone.DONE)
    }

    fun mailLine(r: deputy.broker.MailReading, zone: TimeZone, now: Instant): String = buildString {
        append(when (r.kind) {
            deputy.broker.MailKind.MEETING_REQUEST -> "meeting request"
            deputy.broker.MailKind.SECURITY_CODE -> "security code (held back; the code itself isn't kept)"
            deputy.broker.MailKind.OTHER -> "other"
        })
        if (r.proposedTimes.isNotEmpty()) append(", proposes " + r.proposedTimes.joinToString(", ") { Format.slot(it, zone, now) })
        if (r.addressesAssistant) append(". ⚠ Addressed to an AI assistant")
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

    fun approval(
        req: Event.ApprovalRequested,
        zone: TimeZone,
        now: Instant,
        messages: Map<deputy.broker.MessageId, deputy.broker.MailRecord> = emptyMap(),
    ): ApprovalView = when (req.kind) {
        ApprovalKind.MINT_GRANT -> {
            val r = req.grantRequest!!
            val preview = Grant(GrantId("preview"), req.principal, Direction.OUTBOUND, r.title, r.permits, r.maxUses, now,
                deputy.broker.GrantTemplates.ttlToExpiry(now, r.ttlMinutes))
            ApprovalView(req.requestId, req.kind, r.title,
                "${req.principal.displayName} is asking for this key. Nothing happens until you approve it.",
                buildList {
                    add("Can" to Keyring.sentence(preview))
                    add("Limits" to r.permits.flatMap { Keyring.permitChips(it) }.joinToString(" · "))
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
                    // The recipient comes from the message, not the proposer; the person sees exactly who (D27).
                    is Action.SendReply -> {
                        val m = messages[a.messageId]
                        buildList {
                            add("To" to (m?.replyTo ?: "unknown"))
                            add("Replying to" to (m?.let { "\"${it.subject}\" from ${it.from}" } ?: "a message"))
                            add("Message" to a.body)
                            if (m?.reading?.addressesAssistant == true) add("Careful" to "Deputy's reader flagged that message as written to instruct an AI assistant.")
                            add("Undo" to "Not possible: email can't be unsent")
                        }
                    }
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
        is Action.CancelInvite -> "cancel an invite"
        is Action.SendReply -> "send a reply"
        is Action.ReadInbox -> "read up to ${a.maxResults} emails from the last ${plural(a.sinceDays, "day")}"
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
