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
    var reads = 0
    var failNext = false
    private var nextId = 100

    override suspend fun readBusy(window: Interval): List<Interval> {
        reads++
        return (busy + events.values.map { it.first }).filter { it.overlaps(window) }
    }

    override suspend fun placeHold(slot: Interval, title: String): String {
        if (failNext) { failNext = false; error("calendar unavailable") }
        return (nextId++).toString().also { events[it] = slot to title }
    }

    override suspend fun releaseHold(adapterRef: String) {
        checkNotNull(events.remove(adapterRef)) { "no such event" }
    }

    override suspend fun createEvent(slot: Interval, title: String, attendees: List<String>): String =
        (nextId++).toString().also { events[it] = slot to "$title -> ${attendees.joinToString()}" }
}

val ZONE = TimeZone.of("America/Los_Angeles")

/** Monday 2 Nov 2026. */
val MONDAY: LocalDate = LocalDate(2026, 11, 2)

fun at(date: LocalDate, hour: Int, minute: Int = 0): Instant = LocalDateTime(date, LocalTime(hour, minute)).toInstant(ZONE)

fun LocalDate.plusDays(n: Int): LocalDate = LocalDate.fromEpochDays(toEpochDays() + n)

/** A broker on a fake calendar at Monday 08:00, with deterministic ids. */
class Rig(
    val storage: JournalStorage = InMemoryJournalStorage(),
    val facts: FactStore = InMemoryFactStore(),
    val calendar: FakeCalendar = FakeCalendar(),
    val clock: TestClock = TestClock(at(MONDAY, 8)),
) {
    private var counter = 0
    val broker = Broker(storage, facts, calendar, clock, ZONE) { prefix -> "${prefix}_${++counter}" }
    val agent = broker.agentPort(Principal.Assistant)
    val user = broker.user

    suspend fun calendarKey(request: GrantRequest = GrantTemplates.calendarForScheduling()): GrantId =
        (user.mintGrant(Principal.Assistant, Direction.OUTBOUND, request) as Outcome.GrantIssued).grantId

    fun events() = broker.snapshots.value.entries.map { it.event }
}
