package io.github.greg7gkb.deputy.agent

import deputy.broker.Action
import deputy.broker.ActionResult
import deputy.broker.AgentPort
import deputy.broker.Capabilities
import deputy.broker.CapabilityId
import deputy.broker.GrantId
import deputy.broker.GrantRecord
import deputy.broker.GrantRequest
import deputy.broker.GrantTemplates
import deputy.broker.HoldId
import deputy.broker.Journal
import deputy.broker.Outcome
import deputy.broker.Permit
import deputy.broker.present.Format
import deputy.planner.Planner
import deputy.planner.Transcript
import deputy.planner.Transcript.Step
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock

/**
 * Stands in for the cloud planner in Phase 0. It is scripted, not a model, and it holds only an
 * [AgentPort]: exactly the access a real planner would get. Swapping in an LLM later changes
 * what proposes, not what is allowed (docs/decisions.md D5).
 */
class StubPlanner(
    private val port: AgentPort,
    private val zone: TimeZone,
    override val transcript: Transcript,
    override val name: String = "scripted",
    private val clock: Clock = Clock.System,
) : Planner {
    private val _busy = MutableStateFlow(false)
    override val busy: StateFlow<Boolean> = _busy.asStateFlow()
    private val running = Mutex()

    override val suggestions = listOf(
        "Find a time with Dana next week",
        "Send Dana an invite",
    )

    /** Routes a typed request by keyword. Used when no API key is configured. */
    override suspend fun handle(request: String) = run(request) {
        val text = request.lowercase()
        val who = Regex("""with (\w+)""").find(text)?.groupValues?.get(1)
            ?: Regex("""send (\w+)""").find(text)?.groupValues?.get(1)?.takeIf { it != "an" && it != "a" }
            ?: "dana"
        val name = who.replaceFirstChar { it.uppercase() }
        when {
            "break" in text || "rules" in text || "probe" in text -> probeLimits(null)
            "invite" in text -> sendInvite(name)
            listOf("time", "meet", "find", "hold", "free").any { it in text } -> findTime(name)
            else -> say("Phase 0 has no language model, so I only understand a few requests. Try one of the suggestions.")
        }
    }

    suspend fun probeLimitsWithLeakedHandle(leaked: GrantId?) = run("Try to break the rules") { probeLimits(leaked) }

    private suspend fun run(request: String, block: suspend () -> Unit) {
        running.withLock {
            _busy.value = true
            log(Step.Kind.USER, request)
            try { block() } catch (e: Exception) { say("Something went wrong: ${e.message}") } finally { _busy.value = false }
        }
    }

    // ---- scenarios --------------------------------------------------------------------------

    private suspend fun findTime(name: String) {
        say("I'll look for an hour with $name next week and hold the first good time.")
        val key = ensureKey(setOf(Capabilities.FIND_FREE_SLOTS, Capabilities.PLACE_HOLD),
            GrantTemplates.calendarForScheduling().copy(purpose = "Find a time with $name next week")) ?: return
        val (from, to) = nextWeek()
        val found = call(key, Action.FindFreeSlots(from, to, durationMinutes = 60, earliestHour = 9, latestHour = 17, maxResults = 3))
        val slots = ((found as? Outcome.Done)?.result as? ActionResult.FreeSlots)?.slots ?: return
        if (slots.isEmpty()) return say("Nothing fits next week.")
        say("Options: " + slots.joinToString(", ") { Format.slot(it, zone, clock.now()) } + ". I saw only these times, never what's in your calendar.")
        val held = call(key, Action.PlaceHold(slots.first().start, 60, name))
        if (held is Outcome.Done) say("Held the first one. You can undo it from Activity.")
    }

    private suspend fun sendInvite(name: String) {
        val email = "${name.lowercase()}@example.com"
        say("I'll find a time, then ask before sending $name an invite, since an invite can't be taken back.")
        val calendarKey = ensureKey(setOf(Capabilities.FIND_FREE_SLOTS, Capabilities.PLACE_HOLD),
            GrantTemplates.calendarForScheduling().copy(purpose = "Find a time with $name")) ?: return
        val (from, to) = nextWeek()
        val found = call(calendarKey, Action.FindFreeSlots(from, to, durationMinutes = 60, maxResults = 1))
        val slot = ((found as? Outcome.Done)?.result as? ActionResult.FreeSlots)?.slots?.firstOrNull() ?: return say("Nothing fits next week.")
        val inviteKey = ensureKey(setOf(Capabilities.SEND_INVITE),
            GrantTemplates.sendInvites(listOf(email)).copy(title = "Invite $name", purpose = "Invite $name to catch up")) ?: return
        val sent = call(inviteKey, Action.SendInvite(slot.start, 60, "Catch up: $name", listOf(email)))
        if (sent is Outcome.Done) say("Sent. $name's invite is for ${Format.slot(slot, zone, clock.now())}.")
    }

    /**
     * Behaves like a compromised or confused planner: tries things outside its keys. Every
     * attempt should be refused by the broker, and each refusal lands in Activity.
     */
    private suspend fun probeLimits(leaked: GrantId?) {
        say("Pretending to be a planner that's been tricked. Each of these should be refused.")
        val today = clock.now().toLocalDateTime(zone).date
        val key = activeKeyCovering(setOf(Capabilities.FIND_FREE_SLOTS))
        var tried = 0
        var refused = 0
        suspend fun attempt(label: String, block: suspend () -> Outcome) {
            say(label)
            tried++
            if (block() is Outcome.Refused) refused++
        }
        attempt("Use no key at all.") { call(null, Action.FindFreeSlots(today, today.plus(DatePeriod(days = 3)), 60)) }
        attempt("Guess a key handle.") { call(GrantId("g_" + "0".repeat(32)), Action.FindFreeSlots(today, today, 30)) }
        if (leaked != null) attempt("Use a key that belongs to someone else (leaked handle).") { call(leaked, Action.FindFreeSlots(today, today, 30)) }
        if (key != null) {
            attempt("Look 60 days ahead.") { call(key, Action.FindFreeSlots(today, today.plus(DatePeriod(days = 60)), 60)) }
            attempt("Look at the middle of the night.") { call(key, Action.FindFreeSlots(today, today.plus(DatePeriod(days = 2)), 60, earliestHour = 0, latestHour = 23)) }
            attempt("Delete an event Deputy didn't create.") { call(key, Action.ReleaseHold(HoldId("h_someone_elses_event"))) }
        } else {
            say("(No calendar key yet, so the in-scope-key tests are skipped. Run \"Find a time\" first.)")
        }
        attempt("Ask for a key that lets others query you.") { request(GrantRequest("Let anyone ask", listOf(Permit.InboundFreeBusy()))) }
        attempt("Ask for a key that never expires.") { request(GrantRequest("Calendar forever", listOf(Permit.FreeBusy()), ttlMinutes = null)) }
        say("There's no action type for opening the garage or reading event titles. A planner can't even express them.")
        say("$refused of $tried attempts refused by policy.")
    }

    // ---- helpers ----------------------------------------------------------------------------

    private suspend fun ensureKey(caps: Set<CapabilityId>, request: GrantRequest): GrantId? {
        activeKeyCovering(caps)?.let { return it }
        say("I need a key for this. Asking you for \"${request.title}\".")
        val outcome = request(request)
        return (outcome as? Outcome.GrantIssued)?.grantId.also { if (it == null) say("No key, so I'll stop here.") }
    }

    private suspend fun activeKeyCovering(caps: Set<CapabilityId>): GrantId? =
        port.myGrants().firstOrNull { it.status == GrantRecord.Status.ACTIVE && it.capabilities.containsAll(caps) && (it.usesLeft ?: 1) > 0 }?.grantId

    private suspend fun request(request: GrantRequest): Outcome {
        log(Step.Kind.PROPOSES, "request key \"${request.title}\"", Journal.JSON.encodeToString(GrantRequest.serializer(), request))
        return settle(port.requestGrant(request))
    }

    private suspend fun call(grantId: GrantId?, action: Action): Outcome {
        log(Step.Kind.PROPOSES, action.capability.value, Journal.JSON.encodeToString(Action.serializer(), action) + "\nkey=" + (grantId?.value?.take(10) ?: "none"))
        return settle(port.propose(grantId, action))
    }

    private suspend fun settle(first: Outcome): Outcome {
        var outcome = first
        if (outcome is Outcome.AwaitingUser) {
            log(Step.Kind.BROKER, "waiting for you to decide")
            outcome = port.await(outcome.requestId)
        }
        log(Step.Kind.BROKER, describe(outcome))
        return outcome
    }

    private fun describe(o: Outcome): String = when (o) {
        is Outcome.Done -> when (val r = o.result) {
            is ActionResult.FreeSlots -> "done: ${r.slots.size} slots"
            is ActionResult.HoldPlaced -> "done: hold placed"
            is ActionResult.HoldReleased -> "done: hold released"
            is ActionResult.InviteSent -> "done: invite sent"
        }
        is Outcome.GrantIssued -> "key issued"
        is Outcome.Refused -> "refused (${o.reason.name.lowercase()}): ${o.detail}"
        is Outcome.AwaitingUser -> "waiting for you"
        is Outcome.Failed -> "failed: ${o.error}"
        is Outcome.Ok -> "ok"
    }

    private fun nextWeek(): Pair<LocalDate, LocalDate> {
        val today = clock.now().toLocalDateTime(zone).date
        val monday = today.plus(DatePeriod(days = 8 - today.dayOfWeek.isoDayNumber))
        return monday to monday.plus(DatePeriod(days = 4))
    }

    private fun say(text: String) = log(Step.Kind.SAYS, text)
    private fun log(kind: Step.Kind, text: String, detail: String? = null) = transcript.log(Step(kind, text, detail, source = name))
}
