package deputy.broker

import deputy.broker.present.Keyring
import deputy.broker.present.Narrator
import deputy.broker.present.Tone
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.toLocalDateTime
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JournalTest {
    @Test fun `state survives a restart because it is folded from the journal`() = runTest {
        val dir = createTempDirectory("deputy").toFile()
        val clock = TestClock(at(MONDAY, 8))
        val calendar = FakeCalendar()
        val first = Rig(FileJournalStorage(File(dir, "journal.tsv")), FileFactStore(File(dir, "facts.json")), calendar, clock)
        val key = first.calendarKey(GrantTemplates.calendarForScheduling().copy(maxUses = 2))
        val held = assertIs<Outcome.Done>(first.agent.propose(key, Action.PlaceHold(at(MONDAY, 14), 60, "Dana")))
        first.user.refreshFacts()

        val second = Rig(FileJournalStorage(File(dir, "journal.tsv")), FileFactStore(File(dir, "facts.json")), calendar, clock)
        val record = second.broker.snapshots.value.grants.single()
        assertEquals(1, record.usesConsumed)
        assertEquals(1, second.broker.snapshots.value.holds.size)
        assertIs<Outcome.Done>(second.user.undo(held.seq))
        assertTrue(calendar.events.isEmpty())
        assertNotNull(second.facts.get(FactKeys.CALENDAR_BUSY))
    }

    @Test fun `an edited entry is detected and the broker fails closed`() = runTest {
        val storage = InMemoryJournalStorage()
        val rig = Rig(storage)
        val key = rig.calendarKey()
        rig.user.revoke(key)
        // Someone edits the log to un-revoke the key.
        storage.lines[1] = storage.lines[1].replace("grant_revoked", "grant_revoked_x")

        val reopened = Rig(storage)
        assertNotNull(reopened.broker.snapshots.value.integrityError)
        assertEquals(Refusal.INTEGRITY, assertIs<Outcome.Refused>(reopened.agent.propose(key, Action.FindFreeSlots(MONDAY, MONDAY, 30))).reason)
        assertEquals(Refusal.INTEGRITY, assertIs<Outcome.Refused>(reopened.user.revokeAll()).reason)
        assertEquals(InboundAnswer.NOT_AVAILABLE, reopened.broker.inboundPort(Principal.peer("z", "Z")).ask(Question.FreeAt(at(MONDAY, 19), 60)))
    }

    @Test fun `a deleted entry breaks the chain`() {
        val storage = InMemoryJournalStorage()
        val journal = Journal(storage)
        repeat(3) { journal.append(Event.FactsRefreshed(at(MONDAY, 8), "k", "s", it)) }
        storage.lines.removeAt(1)
        assertNotNull(Journal(storage).integrityError)
    }

    @Test fun `a clean journal verifies`() {
        val storage = InMemoryJournalStorage()
        val journal = Journal(storage)
        repeat(3) { journal.append(Event.FactsRefreshed(at(MONDAY, 8), "k", "s", it)) }
        val reopened = Journal(storage)
        assertNull(reopened.integrityError)
        assertEquals(3, reopened.entries.size)
    }
}

class InboundTest {
    private val zane = Principal.peer("zane", "Zane")
    private val tuesday = MONDAY.plusDays(1)

    private suspend fun Rig.pairZane() = assertIs<Outcome.GrantIssued>(user.mintGrant(zane, Direction.INBOUND, GrantTemplates.inboundFreeBusy())).grantId
    private suspend fun Rig.ask(start: kotlin.time.Instant, minutes: Int = 60, who: Principal = zane) = broker.inboundPort(who).ask(Question.FreeAt(start, minutes))
    private fun Rig.lastInbound() = events().filterIsInstance<Event.InboundAsked>().last()

    @Test fun `no key means not available, with the reason kept for the user only`() = runTest {
        val rig = Rig()
        assertEquals(InboundAnswer.NOT_AVAILABLE, rig.ask(at(tuesday, 19)))
        assertEquals(Refusal.INBOUND_NO_GRANT, rig.lastInbound().declineReason)
        assertEquals(0, rig.calendar.reads)
    }

    @Test fun `answers come from the fact store and match the calendar`() = runTest {
        val rig = Rig()
        rig.calendar.busy += Interval(at(tuesday, 19), at(tuesday, 20))
        val grant = rig.pairZane()
        assertEquals(InboundAnswer.BUSY, rig.ask(at(tuesday, 19)))
        assertEquals(InboundAnswer.FREE, rig.ask(at(MONDAY.plusDays(2), 19)))
        assertEquals(grant, rig.lastInbound().grantId)
        assertEquals(1, rig.calendar.reads)
    }

    @Test fun `questions outside the window get the same decline`() = runTest {
        val rig = Rig()
        rig.pairZane()
        val outside = listOf(
            at(MONDAY.plusDays(5), 14),        // Saturday
            at(tuesday, 14),                   // afternoon
            at(tuesday, 20, 30),               // runs past 21:00
            at(MONDAY.plusDays(30), 19),       // too far ahead
            at(MONDAY.plusDays(-1), 19),       // the past
        )
        for (t in outside) assertEquals(InboundAnswer.NOT_AVAILABLE, rig.ask(t), t.toString())
        assertEquals(InboundAnswer.NOT_AVAILABLE, rig.ask(at(tuesday, 18), minutes = 240))
    }

    @Test fun `the weekly information budget runs out, and declines do not spend it`() = runTest {
        val rig = Rig()
        rig.pairZane()
        rig.ask(at(MONDAY.plusDays(5), 14)) // declined, free
        val answers = (0 until 8).map { i -> rig.ask(at(MONDAY.plusDays(1 + i % 4), 18 + i / 4)) }
        assertEquals(6, answers.count { it != InboundAnswer.NOT_AVAILABLE })
        assertEquals(Refusal.INBOUND_BUDGET, rig.lastInbound().declineReason)
        rig.clock.advance(kotlin.time.Duration.parse("7d"))
        assertEquals(InboundAnswer.FREE, rig.ask(at(MONDAY.plusDays(8), 19)))
    }

    @Test fun `revoking an inbound key stops answers, and the key cannot act`() = runTest {
        val rig = Rig()
        val grant = rig.pairZane()
        assertEquals(InboundAnswer.FREE, rig.ask(at(tuesday, 19)))
        val asZane = rig.broker.agentPort(zane)
        assertEquals(Refusal.NO_PERMIT, assertIs<Outcome.Refused>(asZane.propose(grant, Action.FindFreeSlots(MONDAY, MONDAY, 30))).reason)
        rig.user.revoke(grant)
        assertEquals(InboundAnswer.NOT_AVAILABLE, rig.ask(at(tuesday, 19)))
        assertEquals(InboundAnswer.NOT_AVAILABLE, rig.ask(at(tuesday, 19), who = Principal.peer("mallory", "Mallory")))
    }
}

class PresentTest {
    @Test fun `keys read as one sentence and the log reads as plain words`() = runTest {
        val rig = Rig()
        val key = rig.calendarKey()
        rig.agent.propose(key, Action.FindFreeSlots(MONDAY.plusDays(7), MONDAY.plusDays(11), 60))
        rig.agent.propose(null, Action.FindFreeSlots(MONDAY, MONDAY, 30))
        val snapshot = rig.broker.snapshots.value
        val view = Keyring.describe(snapshot.grants.single(), snapshot.now, ZONE)
        assertTrue("busy, not what" in view.sentence, view.sentence)
        assertTrue("ends in 2 h" in view.chips, view.chips.toString())

        val items = Narrator.narrate(snapshot)
        assertEquals(snapshot.entries.size, items.size)
        assertEquals(Tone.REFUSED, items.first().tone)
        assertTrue(items.any { it.title.startsWith("Found 3 open times") })
    }
}

class SlotsTest {
    @Test fun `slots respect hours, busy time, and spread across days`() {
        val busy = listOf(Interval(at(MONDAY, 9), at(MONDAY, 17)))
        val slots = Slots.find(busy, MONDAY, MONDAY.plusDays(2), 60, 9, 17, 30, 4, at(MONDAY, 8), ZONE)
        assertEquals(4, slots.size)
        assertTrue(slots.none { s -> busy.any { it.overlaps(s) } })
        assertEquals(setOf(MONDAY.plusDays(1), MONDAY.plusDays(2)), slots.map { it.start.toLocalDateTime(ZONE).date }.toSet())
    }
}
