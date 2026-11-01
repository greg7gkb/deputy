package deputy.broker

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlin.coroutines.ContinuationInterceptor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.security.SecureRandom
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * How a capability reaches the calendar. The broker is the only caller; adapters never see a
 * grant, a proposer, or anything but typed arguments (docs/decisions.md D6).
 */
interface CalendarAdapter {
    /** Shown as a fact's source, e.g. "Calendar on this phone". */
    val source: String
    /** Busy intervals only. Implementations must not read titles, attendees or locations. */
    suspend fun readBusy(window: Interval): List<Interval>
    /**
     * Returns an adapter-internal reference, kept by the broker and never shown to a proposer.
     * [note] is Deputy's provenance note with a link back to Activity; write it where the user sees it.
     */
    suspend fun placeHold(slot: Interval, title: String, note: String): String
    /** Must only remove an event whose note starts with [Artifacts.NOTE_PREFIX]. */
    suspend fun releaseHold(adapterRef: String)
    /** Attendees see this event, so there is no note; Deputy keeps the link on its own side. */
    suspend fun createEvent(slot: Interval, title: String, attendees: List<String>): String
    /**
     * Delete an event Deputy created, which tells its guests it's cancelled. Implementations must
     * check the event still matches [slot] before deleting, and must not read its title.
     */
    suspend fun cancelEvent(adapterRef: String, slot: Interval)
}

/** What a proposer gets back. Serializable, so it is also the wire format for a remote planner. */
@Serializable
sealed interface Outcome {
    @Serializable @SerialName("done")
    data class Done(val result: ActionResult, val seq: Long) : Outcome

    @Serializable @SerialName("grant_issued")
    data class GrantIssued(val grantId: GrantId, val seq: Long) : Outcome

    @Serializable @SerialName("refused")
    data class Refused(val reason: Refusal, val detail: String) : Outcome

    @Serializable @SerialName("awaiting_user")
    data class AwaitingUser(val requestId: RequestId, val kind: ApprovalKind) : Outcome

    @Serializable @SerialName("failed")
    data class Failed(val error: String) : Outcome

    /** A user-side operation (revoke, kill switch, refresh) was journaled at [seq]. */
    @Serializable @SerialName("ok")
    data class Ok(val seq: Long) : Outcome
}

/** What the assistant may know about its own keys: handles and limits, nothing else. */
@Serializable
data class GrantSummary(
    val grantId: GrantId,
    val title: String,
    val capabilities: List<CapabilityId>,
    val status: GrantRecord.Status,
    val usesLeft: Int?,
    val expiresAt: SInstant?,
)

/**
 * The only door an agent has. It is bound to one principal by the host; nothing on it can
 * approve, revoke or mint without the user (docs/decisions.md D4).
 */
interface AgentPort {
    val principal: Principal
    fun catalog(): List<CapabilitySpec>
    suspend fun myGrants(): List<GrantSummary>
    suspend fun requestGrant(request: GrantRequest): Outcome
    suspend fun propose(grantId: GrantId?, action: Action): Outcome
    /** Suspends until the user resolves [requestId], then returns the final outcome. Own requests only. */
    suspend fun await(requestId: RequestId): Outcome
}

/** The person's authority. Only the UI holds this. */
interface UserPort {
    suspend fun approve(requestId: RequestId): Outcome
    suspend fun decline(requestId: RequestId): Outcome
    suspend fun mintGrant(holder: Principal, direction: Direction, request: GrantRequest): Outcome
    suspend fun revoke(grantId: GrantId): Outcome
    suspend fun revokeAll(): Outcome
    suspend fun undo(seq: Long): Outcome
    suspend fun refreshFacts(): Outcome
}

/**
 * One other party asking typed questions. Bound to its asker by the host, like an AgentPort:
 * whatever authenticates the peer (pairing, in Phase 3) decides which port it gets.
 */
interface InboundPort {
    val asker: Principal
    suspend fun ask(question: Question): InboundAnswer
}

/** Everything the UI shows, as of [now]. */
data class BrokerSnapshot(
    val now: Instant,
    val zone: TimeZone,
    val grants: List<GrantRecord>,
    val pending: List<Event.ApprovalRequested>,
    val entries: List<JournalEntry>,
    val holds: List<HoldRecord>,
    val facts: List<Fact>,
    val undone: Set<Long>,
    val integrityError: String?,
    /** Messages Deputy has read, for showing a reply's recipient and subject. */
    val messages: Map<MessageId, MailRecord> = emptyMap(),
)

/** An invite Deputy sent and nobody has cancelled. */
data class InviteRecord(val artifactId: String, val slot: Interval, val attendees: List<String>, val adapterRef: String)

/** Broker state, derived by folding the journal. Never written any other way. */
data class BrokerState(
    val grants: Map<GrantId, GrantRecord> = emptyMap(),
    val pending: Map<RequestId, Event.ApprovalRequested> = emptyMap(),
    val holds: Map<HoldId, HoldRecord> = emptyMap(),
    /** Invites Deputy sent and nobody has cancelled, by artifact id. */
    val invites: Map<String, InviteRecord> = emptyMap(),
    /** Every message Deputy has read, by the handle the proposer was given (D25). */
    val messages: Map<MessageId, MailRecord> = emptyMap(),
    /** Replies that failed after possibly going out; no second attempt (D27). */
    val uncertainReplies: Set<MessageId> = emptySet(),
    /** (asker id, when) for every answered inbound question; the weekly budget counts these. */
    val inboundAnswered: List<Pair<String, Instant>> = emptyList(),
    val undone: Set<Long> = emptySet(),
    val lastEventAt: Instant? = null,
) {
    fun apply(entry: JournalEntry): BrokerState = when (val e = entry.event) {
        is Event.GrantMinted -> copy(grants = grants + (e.grant.id to GrantRecord(e.grant)))
        is Event.GrantRevoked -> copy(grants = grants.revoke(listOf(e.grantId), e.at))
        is Event.KillSwitch -> copy(grants = grants.revoke(e.revoked, e.at), pending = pending - e.cancelled.toSet())
        is Event.ApprovalRequested -> copy(pending = pending + (e.requestId to e))
        is Event.ApprovalResolved -> copy(pending = pending - e.requestId)
        is Event.ApprovalExpired -> copy(pending = pending - e.requestIds.toSet())
        is Event.ActionExecuted -> {
            var s = spend(e.grantId)
            when (val r = e.result) {
                is ActionResult.HoldPlaced -> s = s.copy(holds = s.holds + (r.holdId to HoldRecord(r.holdId, r.slot, e.adapterRef.orEmpty(), e.grantId)))
                is ActionResult.HoldReleased -> s = s.copy(holds = s.holds - r.holdId)
                is ActionResult.InviteSent -> if (e.artifactId != null) {
                    s = s.copy(invites = s.invites + (e.artifactId to InviteRecord(e.artifactId, r.slot, r.attendees, e.adapterRef.orEmpty())))
                }
                is ActionResult.InviteCancelled -> s = s.copy(invites = s.invites - r.artifactId)
                else -> Unit
            }
            if (e.mail.isNotEmpty()) s = s.copy(messages = s.messages + e.mail.associateBy { it.messageId })
            if (e.undoOf != null) s = s.copy(undone = s.undone + e.undoOf)
            s
        }
        // An answered question spends a use of the key, like an action does.
        is Event.InboundAsked ->
            if (e.answer != InboundAnswer.NOT_AVAILABLE && e.grantId != null) {
                spend(e.grantId).copy(inboundAnswered = inboundAnswered + (e.asker.id to e.at))
            } else this
        // A reply that failed may still have been sent (the call can finish after its timeout, D18).
        is Event.ActionFailed -> (e.action as? Action.SendReply)?.let { copy(uncertainReplies = uncertainReplies + it.messageId) } ?: this
        is Event.ActionRefused, is Event.FactsRefreshed -> this
    }.copy(lastEventAt = maxOf(lastEventAt ?: entry.event.at, entry.event.at))

    private fun spend(grantId: GrantId?): BrokerState {
        val record = grantId?.let { grants[it] } ?: return this
        return copy(grants = grants + (grantId to record.copy(usesConsumed = record.usesConsumed + 1)))
    }

    private fun Map<GrantId, GrantRecord>.revoke(ids: List<GrantId>, at: Instant) =
        this + ids.mapNotNull { id -> this[id]?.takeIf { it.revokedAt == null }?.let { id to it.copy(revokedAt = at) } }
}

/**
 * The reference monitor. Holds the adapters (the real authority), checks every proposal with
 * [Policy], journals every state change, and owns the kill switch. Plain deterministic code.
 */
class Broker(
    journalStorage: JournalStorage,
    private val facts: FactStore,
    private val calendar: CalendarAdapter,
    private val clock: Clock = Clock.System,
    val zone: TimeZone = TimeZone.currentSystemDefault(),
    private val newId: (prefix: String) -> String = ::randomId,
    /** Where links back to Activity point (D19); an https App Link base on a real phone. */
    private val linkBase: String = Artifacts.CUSTOM_BASE,
    /** Null until a mail account is connected; reading mail then fails, and says so. */
    private val mail: MailAdapter? = null,
    /** The quarantined reader (D25). The broker calls it, holds its output at arm's length, and bounds it. */
    private val mailReader: MailReader? = null,
) {
    private val mutex = Mutex()
    private val journal = Journal(journalStorage)
    private var state = journal.entries.fold(BrokerState()) { s, e -> s.apply(e) }
    private val waiters = mutableMapOf<RequestId, Pair<Principal, CompletableDeferred<Outcome>>>()
    private val _snapshot = MutableStateFlow(snapshot())
    val snapshots: StateFlow<BrokerSnapshot> = _snapshot.asStateFlow()

    /** Adapter calls run here, so a hung adapter can be abandoned and never holds the lock (F9). */
    private val adapterScope = CoroutineScope(SupervisorJob())

    val user: UserPort = UserPortImpl()
    fun agentPort(principal: Principal): AgentPort = AgentPortImpl(principal)
    fun inboundPort(asker: Principal): InboundPort = InboundPortImpl(asker)

    /** Expire stale approval requests and re-publish, so time-based states update in the UI. */
    suspend fun tick() = mutex.withLock {
        if (journal.integrityError == null) sweepExpired()
        _snapshot.value = snapshot()
    }

    // ---- agent side -------------------------------------------------------------------------

    private inner class AgentPortImpl(override val principal: Principal) : AgentPort {
        init { require(principal.kind != Principal.Kind.USER) { "the user does not act through an agent port" } }

        override fun catalog() = Capabilities.catalog.filter { it.direction == Direction.OUTBOUND }

        override suspend fun myGrants(): List<GrantSummary> = mutex.withLock {
            val now = now()
            state.grants.values.filter { it.grant.holder == principal }.map {
                GrantSummary(it.grant.id, it.grant.title, it.grant.permits.flatMap { p -> p.capabilities }.distinct(),
                    it.status(now), it.usesLeft, it.grant.expiresAt)
            }
        }

        override suspend fun requestGrant(request: GrantRequest): Outcome = mutex.withLock {
            failClosed()?.let { return it }
            sweepExpired()
            val now = now()
            oversized(Journal.JSON.encodeToString(GrantRequest.serializer(), request))?.let { detail ->
                record(Event.ActionRefused(now, principal, null, null, Refusal.OUT_OF_SCOPE, detail))
                return Outcome.Refused(Refusal.OUT_OF_SCOPE, detail)
            }
            Policy.validateGrantRequest(principal, request)?.let { detail ->
                record(Event.ActionRefused(now, principal, null, null, Refusal.OUT_OF_SCOPE, "key request refused: $detail"))
                return Outcome.Refused(Refusal.OUT_OF_SCOPE, detail)
            }
            requestApproval(principal, ApprovalKind.MINT_GRANT, grantRequest = request)
        }

        override suspend fun propose(grantId: GrantId?, action: Action): Outcome = mutex.withLock {
            failClosed()?.let { return it }
            sweepExpired()
            val now = now()
            // A proposal too large to be legitimate is logged by size, not content (F11).
            oversized(Journal.JSON.encodeToString(Action.serializer(), action))?.let { detail ->
                record(Event.ActionRefused(now, principal, grantId, null, Refusal.OUT_OF_SCOPE, detail))
                return Outcome.Refused(Refusal.OUT_OF_SCOPE, detail)
            }
            val record = grantId?.let { state.grants[it] }
            when (val verdict = Policy.authorize(principal, record, action, context(now, principal))) {
                is Verdict.Deny -> {
                    record(Event.ActionRefused(now, principal, grantId, action, verdict.reason, verdict.detail))
                    Outcome.Refused(verdict.reason, verdict.detail)
                }
                is Verdict.Allow ->
                    if (verdict.needsConfirmation) requestApproval(principal, ApprovalKind.CONFIRM_PERMANENT, grantId, action)
                    else execute(principal, grantId, action, verdict.permit)
            }
        }

        override suspend fun await(requestId: RequestId): Outcome {
            val waiter = mutex.withLock { waiters[requestId]?.takeIf { it.first == principal }?.second }
                ?: return Outcome.Refused(Refusal.NOT_PENDING, "nothing of yours is waiting on that request")
            return waiter.await()
        }
    }

    private fun requestApproval(
        principal: Principal,
        kind: ApprovalKind,
        grantId: GrantId? = null,
        action: Action? = null,
        grantRequest: GrantRequest? = null,
    ): Outcome {
        val now = now()
        if (state.pending.values.count { it.principal == principal } >= MAX_PENDING_PER_PRINCIPAL) {
            val detail = "$MAX_PENDING_PER_PRINCIPAL requests are already waiting for you"
            record(Event.ActionRefused(now, principal, grantId, action, Refusal.OUT_OF_SCOPE, detail))
            return Outcome.Refused(Refusal.OUT_OF_SCOPE, detail)
        }
        val id = RequestId(newId("r"))
        record(Event.ApprovalRequested(now, id, principal, kind, grantId, action, grantRequest, now + APPROVAL_TTL))
        waiters[id] = principal to CompletableDeferred()
        return Outcome.AwaitingUser(id, kind)
    }

    /** Journal the expiry of requests nobody answered in time. Caller holds the lock. */
    private fun sweepExpired() {
        val now = now()
        val expired = state.pending.values.filter { now >= it.expiresAt }.map { it.requestId }
        if (expired.isEmpty()) return
        record(Event.ApprovalExpired(now, expired))
        expired.forEach { waiters.remove(it)?.second?.complete(Outcome.Refused(Refusal.NOT_PENDING, "the request expired")) }
    }

    // ---- user side --------------------------------------------------------------------------

    private inner class UserPortImpl : UserPort {
        override suspend fun approve(requestId: RequestId): Outcome = mutex.withLock {
            failClosed()?.let { return it }
            sweepExpired()
            val now = now()
            val pending = state.pending[requestId] ?: return Outcome.Refused(Refusal.NOT_PENDING, "nothing is waiting on that request")
            record(Event.ApprovalResolved(now, requestId, approved = true, by = Principal.User))
            val outcome = when (pending.kind) {
                ApprovalKind.MINT_GRANT -> mint(pending.principal, Direction.OUTBOUND, pending.grantRequest!!, requestId)
                ApprovalKind.CONFIRM_PERMANENT -> {
                    // Check again: the key may have been revoked or used up while the person decided.
                    val action = pending.action!!
                    val record = pending.grantId?.let { state.grants[it] }
                    when (val verdict = Policy.authorize(pending.principal, record, action, context(now, pending.principal))) {
                        is Verdict.Deny -> {
                            record(Event.ActionRefused(now, pending.principal, pending.grantId, action, verdict.reason, verdict.detail))
                            Outcome.Refused(verdict.reason, verdict.detail)
                        }
                        is Verdict.Allow -> execute(pending.principal, pending.grantId, action, verdict.permit, requestId)
                    }
                }
            }
            waiters.remove(requestId)?.second?.complete(outcome)
            outcome
        }

        override suspend fun decline(requestId: RequestId): Outcome = mutex.withLock {
            failClosed()?.let { return it }
            sweepExpired()
            if (requestId !in state.pending) return Outcome.Refused(Refusal.NOT_PENDING, "nothing is waiting on that request")
            record(Event.ApprovalResolved(now(), requestId, approved = false, by = Principal.User))
            val outcome = Outcome.Refused(Refusal.DECLINED_BY_USER, "you declined")
            waiters.remove(requestId)?.second?.complete(outcome)
            outcome
        }

        override suspend fun mintGrant(holder: Principal, direction: Direction, request: GrantRequest): Outcome = mutex.withLock {
            failClosed()?.let { return it }
            Policy.validateGrantRequest(Principal.User, request)?.let { return Outcome.Refused(Refusal.OUT_OF_SCOPE, it) }
            val inboundOnly = request.permits.all { it is Permit.InboundFreeBusy }
            val outboundOnly = request.permits.none { it is Permit.InboundFreeBusy }
            if ((direction == Direction.INBOUND && !inboundOnly) || (direction == Direction.OUTBOUND && !outboundOnly)) {
                return Outcome.Refused(Refusal.OUT_OF_SCOPE, "a key is either for acting or for being asked, not both")
            }
            if (holder.kind == Principal.Kind.USER) return Outcome.Refused(Refusal.OUT_OF_SCOPE, "you don't need a key")
            mint(holder, direction, request, null)
        }

        override suspend fun revoke(grantId: GrantId): Outcome = mutex.withLock {
            failClosed()?.let { return it }
            val record = state.grants[grantId] ?: return Outcome.Refused(Refusal.UNKNOWN_GRANT, "no such key")
            if (record.revokedAt != null) return Outcome.Refused(Refusal.REVOKED, "already revoked")
            Outcome.Ok(record(Event.GrantRevoked(now(), grantId, Principal.User)).seq)
        }

        override suspend fun revokeAll(): Outcome = mutex.withLock {
            failClosed()?.let { return it }
            val active = state.grants.values.filter { it.revokedAt == null }.map { it.grant.id }
            val cancelled = state.pending.keys.toList()
            val entry = record(Event.KillSwitch(now(), active, cancelled))
            cancelled.forEach { waiters.remove(it)?.second?.complete(Outcome.Refused(Refusal.REVOKED, "everything was revoked")) }
            Outcome.Ok(entry.seq)
        }

        override suspend fun undo(seq: Long): Outcome = mutex.withLock {
            failClosed()?.let { return it }
            val entry = journal.entries.firstOrNull { it.seq == seq }
            val executed = entry?.event as? Event.ActionExecuted
            // The user's own authority: no key needed, but it is journaled like anything else.
            when (val result = executed?.result) {
                is ActionResult.HoldPlaced ->
                    if (seq in state.undone || result.holdId !in state.holds) Outcome.Refused(Refusal.NOT_PENDING, "already undone")
                    else execute(Principal.User, null, Action.ReleaseHold(result.holdId), permit = null, undoOf = seq)
                is ActionResult.InviteSent -> {
                    val id = executed.artifactId
                    if (id == null || seq in state.undone || id !in state.invites) Outcome.Refused(Refusal.NOT_PENDING, "already cancelled")
                    else execute(Principal.User, null, Action.CancelInvite(id), permit = null, undoOf = seq)
                }
                else -> Outcome.Refused(Refusal.OUT_OF_SCOPE, "that entry has no undo")
            }
        }

        override suspend fun refreshFacts(): Outcome = mutex.withLock {
            failClosed()?.let { return it }
            try {
                refreshBusy()
                Outcome.Ok(journal.entries.last().seq)
            } catch (e: Exception) {
                Outcome.Failed(e.message ?: e::class.simpleName.orEmpty())
            }
        }
    }

    private fun mint(holder: Principal, direction: Direction, request: GrantRequest, via: RequestId?): Outcome {
        val now = now()
        val grant = Grant(
            id = GrantId(newId("g")),
            holder = holder,
            direction = direction,
            title = request.title,
            permits = request.permits,
            maxUses = request.maxUses,
            issuedAt = now,
            expiresAt = GrantTemplates.ttlToExpiry(now, request.ttlMinutes),
            purpose = request.purpose,
        )
        val entry = record(Event.GrantMinted(now, grant, via))
        return Outcome.GrantIssued(grant.id, entry.seq)
    }

    // ---- inbound side -----------------------------------------------------------------------

    private inner class InboundPortImpl(override val asker: Principal) : InboundPort {
        override suspend fun ask(question: Question): InboundAnswer = mutex.withLock {
            if (journal.integrityError != null) return InboundAnswer.NOT_AVAILABLE
            val now = now()
            // Whatever goes wrong, the asker sees the same decline and the user sees why (F7).
            try {
                answer(question, now)
            } catch (e: Exception) {
                record(Event.InboundAsked(now, asker, question, InboundAnswer.NOT_AVAILABLE, declineReason = Refusal.OUT_OF_SCOPE))
                InboundAnswer.NOT_AVAILABLE
            }
        }

        private suspend fun answer(question: Question, now: Instant): InboundAnswer {
            val weekAgo = now - 7.days
            val answered = state.inboundAnswered.count { (who, at) -> who == asker.id && at > weekAgo }
            when (val verdict = Policy.authorizeInbound(asker, state.grants.values, question, context(now, asker), answered)) {
                is Policy.InboundVerdict.Deny -> {
                    record(Event.InboundAsked(now, asker, question, InboundAnswer.NOT_AVAILABLE, declineReason = verdict.reason))
                    return InboundAnswer.NOT_AVAILABLE
                }
                is Policy.InboundVerdict.Allow -> {
                    val q = question as Question.FreeAt
                    val slot = Interval.of(q.start, q.durationMinutes)
                    val fact = try { busyFactCovering(slot) } catch (e: Exception) { null }
                    if (fact == null || !FlowGuard.mayAnswerInbound(fact)) {
                        record(Event.InboundAsked(now, asker, question, InboundAnswer.NOT_AVAILABLE, verdict.grant.id, Refusal.INBOUND_NO_FACTS))
                        return InboundAnswer.NOT_AVAILABLE
                    }
                    val busy = (fact.value as FactValue.BusyIntervals).busy.any { it.overlaps(slot) }
                    val answer = if (busy) InboundAnswer.BUSY else InboundAnswer.FREE
                    record(Event.InboundAsked(now, asker, question, answer, verdict.grant.id, factsObservedAt = fact.observedAt))
                    return answer
                }
            }
        }
    }

    // ---- execution --------------------------------------------------------------------------

    private suspend fun execute(
        principal: Principal,
        grantId: GrantId?,
        action: Action,
        permit: Permit?,
        requestId: RequestId? = null,
        undoOf: Long? = null,
    ): Outcome {
        val now = now()
        var adapterRef: String? = null
        var artifactId: String? = null
        var mailRead: List<MailRecord> = emptyList()
        // Step 1: the side effect. Only a failure here is an ActionFailed.
        val result: ActionResult = try {
            when (action) {
                is Action.FindFreeSlots -> {
                    val fact = busyFactCovering(localWindow(action.from, action.to))
                    if (!FlowGuard.mayReachPlanner(fact)) {
                        record(Event.ActionRefused(now, principal, grantId, action, Refusal.FLOW_VIOLATION, "the data is labelled secret"))
                        return Outcome.Refused(Refusal.FLOW_VIOLATION, "the data is labelled secret")
                    }
                    val granularity = (permit as? Permit.FreeBusy)?.granularityMinutes ?: 30
                    val slots = Slots.find(
                        (fact.value as FactValue.BusyIntervals).busy, action.from, action.to, action.durationMinutes,
                        action.earliestHour, action.latestHour, granularity, action.maxResults, now, zone,
                    )
                    ActionResult.FreeSlots(slots, fact.observedAt)
                }
                is Action.PlaceHold -> {
                    val slot = Interval.of(action.start, action.durationMinutes)
                    val holdId = HoldId(newId("h"))
                    artifactId = holdId.value
                    adapterRef = adapterCall { calendar.placeHold(slot, "Hold: ${action.label}", Artifacts.note(holdId.value, linkBase)) }
                    ActionResult.HoldPlaced(holdId, slot)
                }
                is Action.ReleaseHold -> {
                    val hold = state.holds.getValue(action.holdId)
                    adapterCall { calendar.releaseHold(hold.adapterRef) }
                    adapterRef = hold.adapterRef
                    ActionResult.HoldReleased(action.holdId)
                }
                is Action.SendInvite -> {
                    val slot = Interval.of(action.start, action.durationMinutes)
                    artifactId = newId("a")
                    adapterRef = adapterCall { calendar.createEvent(slot, action.title, action.attendees) }
                    ActionResult.InviteSent(slot, action.attendees)
                }
                is Action.ReadInbox -> {
                    val source = mail ?: error("no mail account is connected")
                    val reader = mailReader ?: error("no mail reader is configured")
                    val raw = adapterCall("the mail account", ADAPTER_TIMEOUT * 2) { source.recent(now - action.sinceDays.days, action.maxResults) }.take(action.maxResults)
                    // One budget for the whole read, since it runs under the broker's lock (D25).
                    val started = kotlin.time.TimeSource.Monotonic.markNow()
                    mailRead = raw.map { m -> readOne(reader, m, now, principal, source.source, READ_BUDGET - started.elapsedNow()) }
                    ActionResult.InboxRead(mailRead.map { it.summary() })
                }
                is Action.SendReply -> {
                    val source = mail ?: error("no mail account is connected")
                    val message = state.messages.getValue(action.messageId)
                    // Policy checked replyTo is present; the adapter sends to it and nobody else (D27).
                    adapterRef = adapterCall("the mail account", ADAPTER_TIMEOUT * 2) { source.sendReply(message.adapterRef, message.replyTo!!, action.body) }
                    ActionResult.ReplySent(action.messageId)
                }
                is Action.CancelInvite -> {
                    val invite = state.invites.getValue(action.artifactId)
                    adapterCall { calendar.cancelEvent(invite.adapterRef, invite.slot) }
                    adapterRef = invite.adapterRef
                    artifactId = invite.artifactId
                    ActionResult.InviteCancelled(invite.artifactId, invite.slot, invite.attendees)
                }
            }
        } catch (e: Exception) {
            // The full error is for the user's log; the proposer gets a generic one, since adapter
            // errors can carry calendar contents or credentials (F10).
            record(Event.ActionFailed(now, principal, grantId, action, e.message ?: e::class.simpleName.orEmpty()))
            return Outcome.Failed("${Capabilities.spec(action.capability)?.title ?: "That"} didn't work. The details are in your Activity.")
        }
        // Step 2: the record. Once the world has changed it must be journaled, whatever else fails (F8).
        val entry = record(Event.ActionExecuted(now, principal, grantId, action, result, requestId, adapterRef, undoOf, artifactId, mailRead))
        // Step 3: housekeeping. Calendar writes make the cached busy view stale; best effort.
        if (action is Action.PlaceHold || action is Action.ReleaseHold || action is Action.SendInvite || action is Action.CancelInvite) runCatching { invalidateBusy() }
        return Outcome.Done(result, entry.seq)
    }

    /**
     * One message through the quarantined reader. Whatever the reader says is bounded by
     * [MailGuard]; if it fails or hangs, the message counts as unread ("other") rather than
     * failing the whole read. Only the cleaned sender and subject are kept, for the user.
     */
    private suspend fun readOne(reader: MailReader, m: RawMessage, now: Instant, by: Principal, via: String, left: kotlin.time.Duration): MailRecord {
        val reading = try {
            if (!left.isPositive()) error("the read ran out of time")
            MailGuard.bound(adapterCall("the mail reader", minOf(READER_TIMEOUT, left)) { reader.read(m, zone) }, now)
        } catch (e: Exception) {
            MailReading(MailKind.OTHER)
        }
        return MailRecord(
            MessageId(newId("m")), m.adapterRef, MailGuard.display(m.from, MailGuard.MAX_FROM),
            MailGuard.subject(m.subject), m.receivedAt, reading, reader.name, MailGuard.address(m.from), by, now, via,
        )
    }

    /**
     * Runs an adapter call off the caller's job with a timeout, so a hung adapter is abandoned
     * rather than holding the broker lock. Caveat: a blocking call that finishes after the timeout
     * still happens; the hold or event exists but is recorded as failed (decisions.md D18).
     */
    private suspend fun <T> adapterCall(what: String = "the calendar", timeout: kotlin.time.Duration = ADAPTER_TIMEOUT, block: suspend () -> T): T {
        // Detached from the caller's job, but on the caller's dispatcher (and so its clock).
        val dispatcher = currentCoroutineContext()[ContinuationInterceptor] ?: Dispatchers.Default
        val call = adapterScope.async(dispatcher) { block() }
        return try {
            withTimeout(timeout) { call.await() }
        } catch (e: TimeoutCancellationException) {
            call.cancel()
            throw IllegalStateException("$what did not answer within ${timeout.inWholeSeconds} s")
        }
    }

    /** The busy-intervals fact, re-read from the calendar if missing, stale, or too narrow. */
    private suspend fun busyFactCovering(window: Interval): Fact {
        val now = now()
        val fact = facts.get(FactKeys.CALENDAR_BUSY)
        val value = fact?.value as? FactValue.BusyIntervals
        val fresh = fact != null && now < fact.staleAfter && value != null &&
            value.window.start <= window.start && value.window.end >= window.end
        return if (fresh) fact!! else refreshBusy(window)
    }

    private suspend fun refreshBusy(atLeast: Interval? = null): Fact {
        val now = now()
        val today = now.toLocalDateTime(zone).date
        var window = localWindow(today, today.plus(DatePeriod(days = BUSY_HORIZON_DAYS)))
        if (atLeast != null) window = Interval(minOf(window.start, atLeast.start), maxOf(window.end, atLeast.end))
        val busy = adapterCall { calendar.readBusy(window) }.sortedBy { it.start }
        // Labels are sticky: a refresh never lowers a sensitivity someone raised (F4). Only an
        // explicit declassification could, and Phase 0 has none.
        val previous = facts.get(FactKeys.CALENDAR_BUSY)?.sensitivity ?: Sensitivity.PRIVATE
        val fact = Fact(
            key = FactKeys.CALENDAR_BUSY,
            value = FactValue.BusyIntervals(window, busy),
            provenance = Provenance.OBSERVED,
            sensitivity = maxOf(previous, Sensitivity.PRIVATE),
            source = calendar.source,
            observedAt = now,
            staleAfter = now + FACT_FRESHNESS,
        )
        facts.put(fact)
        record(Event.FactsRefreshed(now, fact.key, fact.source, busy.size))
        return fact
    }

    private fun invalidateBusy() {
        val fact = facts.get(FactKeys.CALENDAR_BUSY) ?: return
        facts.put(fact.copy(staleAfter = fact.observedAt))
    }

    private fun localWindow(from: kotlinx.datetime.LocalDate, to: kotlinx.datetime.LocalDate) = Interval(
        LocalDateTime(from, LocalTime(0, 0)).toInstant(zone),
        LocalDateTime(to.plus(DatePeriod(days = 1)), LocalTime(0, 0)).toInstant(zone),
    )

    // ---- plumbing ---------------------------------------------------------------------------

    /**
     * Time never runs backwards for the broker: winding the device clock back cannot revive an
     * expired key, because now is at least the time of the last journaled event.
     */
    private fun now(): Instant = state.lastEventAt?.let { maxOf(it, clock.now()) } ?: clock.now()

    private fun context(now: Instant, principal: Principal) = PolicyContext(
        now, zone, state.holds, principal,
        holdOwner = { hold -> hold.grantId?.let { state.grants[it]?.grant?.holder } },
        messages = state.messages,
        uncertainReplies = state.uncertainReplies,
    )

    /** If the journal did not verify, nothing happens until a person looks (fail closed). */
    private fun failClosed(): Outcome? = journal.integrityError?.let { Outcome.Refused(Refusal.INTEGRITY, "the activity log failed its integrity check: $it") }

    private fun oversized(json: String): String? =
        if (json.length > MAX_PROPOSAL_CHARS) "the proposal was too large to consider (${json.length} characters)" else null

    private fun record(event: Event): JournalEntry {
        val entry = journal.append(event)
        state = state.apply(entry)
        _snapshot.value = snapshot()
        return entry
    }

    private fun snapshot() = BrokerSnapshot(
        now = now(),
        zone = zone,
        grants = state.grants.values.toList(),
        pending = state.pending.values.toList(),
        entries = journal.entries,
        holds = state.holds.values.toList(),
        facts = facts.all(),
        undone = state.undone,
        integrityError = journal.integrityError,
        messages = state.messages,
    )

    companion object {
        val APPROVAL_TTL = 15.minutes
        val FACT_FRESHNESS = 15.minutes
        val ADAPTER_TIMEOUT = 10.seconds
        /** Per message. Generous, so an on-device model has room; the rule-based reader takes milliseconds. */
        val READER_TIMEOUT = 30.seconds
        /** For all the messages in one read together, so the lock (and the kill switch) never waits long. */
        val READ_BUDGET = 60.seconds
        const val BUSY_HORIZON_DAYS = 30
        const val MAX_PENDING_PER_PRINCIPAL = 5
        const val MAX_PROPOSAL_CHARS = 4_000
        private val random = SecureRandom()

        /** 128 random bits: an unguessable handle (D3). */
        fun randomId(prefix: String): String {
            val bytes = ByteArray(16).also { random.nextBytes(it) }
            return prefix + "_" + bytes.joinToString("") { "%02x".format(it) }
        }
    }
}

/** Information-flow rules on facts. Deterministic, label-driven. */
object FlowGuard {
    fun mayReachPlanner(fact: Fact) = fact.sensitivity != Sensitivity.SECRET
    fun mayAnswerInbound(fact: Fact) = fact.sensitivity != Sensitivity.SECRET
}
