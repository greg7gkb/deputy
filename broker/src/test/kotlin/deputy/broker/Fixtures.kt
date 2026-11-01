package deputy.broker

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Instant

class TestClock(var now: Instant) : Clock {
    override fun now() = now
    fun advance(d: Duration) { now += d }
}

/** A calendar that records what the broker asked of it. */
class FakeCalendar : CalendarAdapter {
    override val source = "fake calendar"
    val busy = mutableListOf<Interval>()
    val events = linkedMapOf<String, Pair<Interval, String>>()
    val notes = mutableMapOf<String, String>()
    var reads = 0
    var failNext = false
    private var nextId = 100

    override suspend fun readBusy(window: Interval): List<Interval> {
        reads++
        return (busy + events.values.map { it.first }).filter { it.overlaps(window) }
    }

    override suspend fun placeHold(slot: Interval, title: String, note: String): String {
        if (failNext) { failNext = false; error("calendar unavailable") }
        return (nextId++).toString().also { events[it] = slot to title; notes[it] = note }
    }

    override suspend fun releaseHold(adapterRef: String) {
        checkNotNull(events.remove(adapterRef)) { "no such event" }
    }

    override suspend fun createEvent(slot: Interval, title: String, attendees: List<String>): String =
        (nextId++).toString().also { events[it] = slot to "$title -> ${attendees.joinToString()}" }

    override suspend fun cancelEvent(adapterRef: String, slot: Interval) {
        check(events[adapterRef]?.first == slot) { "that event has changed" }
        events.remove(adapterRef)
    }
}

val ZONE = TimeZone.of("America/Los_Angeles")

/** Monday 2 Nov 2026. */
val MONDAY: LocalDate = LocalDate(2026, 11, 2)

fun at(date: LocalDate, hour: Int, minute: Int = 0): Instant = LocalDateTime(date, LocalTime(hour, minute)).toInstant(ZONE)

fun LocalDate.plusDays(n: Int): LocalDate = LocalDate.fromEpochDays(toEpochDays() + n)

/** A mailbox holding whatever the test puts in it. */
class FakeMail(val messages: MutableList<RawMessage> = mutableListOf()) : MailAdapter {
    override val source = "fake mail"
    var reads = 0
    override suspend fun recent(since: kotlin.time.Instant, max: Int): List<RawMessage> {
        reads++
        return messages.filter { it.receivedAt >= since }.sortedByDescending { it.receivedAt }.take(max)
    }

    /** (replying to, to, body) for every reply sent. */
    val sent = mutableListOf<Triple<String, String, String>>()
    override suspend fun sendReply(adapterRef: String, to: String, body: String): String {
        sent += Triple(adapterRef, to, body)
        return "sent-${sent.size}"
    }
}

/** A broker on a fake calendar at Monday 08:00, with deterministic ids. */
class Rig(
    val storage: JournalStorage = InMemoryJournalStorage(),
    val facts: FactStore = InMemoryFactStore(),
    val calendar: FakeCalendar = FakeCalendar(),
    val clock: TestClock = TestClock(at(MONDAY, 8)),
    val mail: FakeMail = FakeMail(),
    val reader: MailReader = deputy.reader.RuleMailReader(),
) {
    private var counter = 0
    val broker = Broker(storage, facts, calendar, clock, ZONE, newId = { prefix -> "${prefix}_${++counter}" }, mail = mail, mailReader = reader)
    val agent = broker.agentPort(Principal.Assistant)
    val user = broker.user

    suspend fun calendarKey(request: GrantRequest = GrantTemplates.calendarForScheduling()): GrantId =
        (user.mintGrant(Principal.Assistant, Direction.OUTBOUND, request) as Outcome.GrantIssued).grantId

    fun events() = broker.snapshots.value.entries.map { it.event }
}
