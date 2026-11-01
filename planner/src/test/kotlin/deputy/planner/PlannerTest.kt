package deputy.planner

import com.anthropic.client.okhttp.AnthropicOkHttpClient
import deputy.broker.Action
import deputy.broker.ActionResult
import deputy.broker.Broker
import deputy.broker.CalendarAdapter
import deputy.broker.Event
import deputy.broker.GrantId
import deputy.broker.InMemoryFactStore
import deputy.broker.InMemoryJournalStorage
import deputy.broker.Interval
import deputy.broker.MailKind
import deputy.broker.MailSummary
import deputy.broker.MessageId
import deputy.broker.Outcome
import deputy.broker.Principal
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

private val ZONE = TimeZone.of("America/Los_Angeles")

class ToolsTest {
    @Test fun `tool inputs parse into typed broker calls`() {
        val call = Tools.parse("place_hold", mapOf("key_id" to "g_1", "start" to "2026-11-02T10:00", "duration_minutes" to 60, "label" to "Dana"), ZONE)
        val propose = assertIs<Tools.Call.Propose>(call)
        assertEquals(GrantId("g_1"), propose.keyId)
        val hold = assertIs<Action.PlaceHold>(propose.action)
        assertEquals("2026-11-02T18:00:00Z", hold.start.toString())

        val find = assertIs<Tools.Call.Propose>(Tools.parse("find_free_slots", mapOf(
            "key_id" to "g_1", "from_date" to "2026-11-02", "to_date" to "2026-11-06", "duration_minutes" to 60,
            "earliest_hour" to 9, "latest_hour" to 17, "max_results" to 3), ZONE)).action
        assertEquals(LocalDate(2026, 11, 2), assertIs<Action.FindFreeSlots>(find).from)
    }

    @Test fun `an inbox result is built only from typed fields`() {
        val read = assertIs<Action.ReadInbox>(assertIs<Tools.Call.Propose>(
            Tools.parse("read_inbox", mapOf("key_id" to "g_1", "max_results" to 5, "since_days" to 3), ZONE)).action)
        assertEquals(Action.ReadInbox(5, 3), read)
        val start = kotlin.time.Instant.parse("2026-11-03T22:00:00Z")
        val json = Tools.result(Outcome.Done(ActionResult.InboxRead(listOf(
            MailSummary(MessageId("m_1"), start, MailKind.MEETING_REQUEST, listOf(Interval.of(start, 30)), 30, flagged = true))), 1), ZONE)
        assertEquals("""{"status":"done","messages":[{"message_id":"m_1","received":"2026-11-03T14:00","kind":"meeting_request",""" +
            """"proposed_times":[{"start":"2026-11-03T14:00","end":"2026-11-03T14:30"}],"duration_minutes":30,"flagged":true}]}""", json)
    }

    @Test fun `bad inputs are explained, not guessed`() {
        assertFailsWith<IllegalArgumentException> { Tools.parse("place_hold", mapOf("key_id" to "g_1", "start" to "next tuesday", "duration_minutes" to 60, "label" to "x"), ZONE) }
        assertFailsWith<IllegalArgumentException> { Tools.parse("open_garage", emptyMap(), ZONE) }
        assertFailsWith<IllegalArgumentException> { Tools.parse("request_key", mapOf("kind" to "send_invites", "purpose" to "x", "invite_attendees" to emptyList<String>()), ZONE) }
    }

    @Test fun `every tool is strict and its schema names every property as required`() {
        assertEquals(8, Tools.definitions.size)
        Tools.definitions.forEach { assertEquals(true, it.strict().orElse(false), it.name()) }
    }
}

/** A calendar with one busy morning, for the live test. */
private class FakeCalendar : CalendarAdapter {
    override val source = "fake calendar"
    val holds = mutableMapOf<String, Interval>()
    var busy = listOf<Interval>()
    override suspend fun readBusy(window: Interval) = (busy + holds.values).filter { it.overlaps(window) }
    override suspend fun placeHold(slot: Interval, title: String, note: String) = "e${holds.size + 1}".also { holds[it] = slot }
    override suspend fun releaseHold(adapterRef: String) { holds.remove(adapterRef) }
    override suspend fun createEvent(slot: Interval, title: String, attendees: List<String>) = "invite"
    override suspend fun cancelEvent(adapterRef: String, slot: Interval) {}
}

/**
 * Talks to the real Claude API. Runs only with ./gradlew :planner:test -PliveApi, which reads the
 * key from local.properties. A coroutine stands in for the user tapping Approve.
 */
class LiveClaudePlannerTest {
    @Test fun `Claude finds a time and holds it, entirely through the broker`() = runBlocking {
        if (System.getProperty("deputy.liveApi") != "true") return@runBlocking
        val calendar = FakeCalendar()
        val broker = Broker(InMemoryJournalStorage(), InMemoryFactStore(), calendar, zone = ZONE)
        val transcript = Transcript()
        val planner = ClaudePlanner(broker.agentPort(Principal.Assistant), AnthropicOkHttpClient.fromEnv(), ZONE, transcript, readableLog = { println("LOG| $it") })

        // The "user": approves whatever the broker asks, as a tap would.
        val approver = launch {
            while (true) {
                broker.snapshots.value.pending.forEach { broker.user.approve(it.requestId) }
                delay(200)
            }
        }
        planner.handle("Find an hour with Dana next week and hold the first good time.")
        approver.cancel()

        transcript.steps.value.forEach { println("${it.kind}: ${it.text}") }
        val events = broker.snapshots.value.entries.map { it.event }
        assertTrue(events.any { it is Event.GrantMinted }, "a key was requested and approved")
        val holds = events.filterIsInstance<Event.ActionExecuted>().mapNotNull { it.result as? ActionResult.HoldPlaced }
        assertEquals(1, holds.size, "exactly one hold")
        assertEquals(1, calendar.holds.size)
    }
}
