package deputy.broker

import deputy.broker.present.Narrator
import deputy.reader.SampleMail
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

/** Mail is the first untrusted input (D25). These tests are the containment claim, in code. */
class MailTest {
    private fun refusal(o: Outcome) = assertIs<Outcome.Refused>(o).reason

    private suspend fun Rig.mailKey(request: GrantRequest = GrantTemplates.mailReading()): GrantId =
        (user.mintGrant(Principal.Assistant, Direction.OUTBOUND, request) as Outcome.GrantIssued).grantId

    private fun Rig.withSampleInbox() = apply { mail.messages += SampleMail.inbox(clock.now) }

    private fun summaries(o: Outcome) = assertIs<ActionResult.InboxRead>(assertIs<Outcome.Done>(o).result).messages

    @Test fun `no mail key, no mail read`() = runTest {
        val rig = Rig().withSampleInbox()
        assertEquals(Refusal.UNKNOWN_GRANT, refusal(rig.agent.propose(null, Action.ReadInbox())))
        assertEquals(Refusal.NO_PERMIT, refusal(rig.agent.propose(rig.calendarKey(), Action.ReadInbox())))
        assertEquals(0, rig.mail.reads)
    }

    @Test fun `nothing the senders wrote reaches the proposer`() = runTest {
        val rig = Rig().withSampleInbox()
        val key = rig.mailKey()
        val outcome = rig.agent.propose(key, Action.ReadInbox())
        assertEquals(5, summaries(outcome).size)
        // Everything the proposer can get back through its port, as it would cross the wire.
        val seen = Journal.JSON.encodeToString(Outcome.serializer(), outcome) +
            Journal.JSON.encodeToString(kotlinx.serialization.builtins.ListSerializer(GrantSummary.serializer()), rig.agent.myGrants())
        for (canary in SampleMail.CANARIES) assertFalse(canary.lowercase() in seen.lowercase(), "\"$canary\" reached the proposer: $seen")
    }

    @Test fun `the summaries carry kinds, times and a flag`() = runTest {
        val rig = Rig().withSampleInbox()
        val byKind = summaries(rig.agent.propose(rig.mailKey(), Action.ReadInbox())).groupBy { it.kind }
        assertEquals(3, byKind.getValue(MailKind.MEETING_REQUEST).size, "Dana, Sam, and the attack's fake invite")
        assertEquals(1, byKind.getValue(MailKind.SECURITY_CODE).size)
        assertTrue(byKind.getValue(MailKind.SECURITY_CODE).single().proposedTimes.isEmpty())
        assertEquals(2, byKind.values.flatten().count { it.flagged }, "Sam's hidden instruction and the attack")
        val dana = byKind.getValue(MailKind.MEETING_REQUEST).first { !it.flagged }
        assertEquals(listOf(at(MONDAY.plusDays(1), 14), at(MONDAY.plusDays(2), 10)), dana.proposedTimes.map { it.start })
    }

    @Test fun `the journal keeps who and what for the user, never the body or the code`() = runTest {
        val rig = Rig().withSampleInbox()
        rig.agent.propose(rig.mailKey(), Action.ReadInbox())
        val journal = rig.storage.let { it as InMemoryJournalStorage }.lines.joinToString("\n")
        assertTrue("Coffee next week?" in journal, "the user can recognise the message")
        assertTrue(SampleMail.DANA in journal)
        assertFalse(SampleMail.CODE in journal, "the code is masked out of the subject")
        assertFalse("ignore your previous instructions" in journal.lowercase(), "bodies are never journaled")
        assertFalse("display:none" in journal)
    }

    @Test fun `Activity says what was withheld and flags the attempt`() = runTest {
        val rig = Rig().withSampleInbox()
        rig.agent.propose(rig.mailKey(), Action.ReadInbox())
        val item = Narrator.narrate(rig.broker.snapshots.value).first()
        assertEquals("Read 5 emails for your assistant", item.title)
        assertTrue("Coffee next week?" in item.body)
        assertTrue("No senders, subjects or text" in item.body)
        assertTrue("2 messages were written to instruct an AI assistant" in item.body, item.body)
        assertFalse(SampleMail.CODE in item.body)
        assertEquals(deputy.broker.present.Tone.CONTAINED, item.tone)
    }

    @Test fun `the broker bounds whatever a reader says`() = runTest {
        // A reader that has been thoroughly fooled: dozens of times, absurd lengths, times in the
        // past and far future, and times on a code message.
        val now = at(MONDAY, 8)
        val liar = object : MailReader {
            override val name = "fooled reader"
            override suspend fun read(message: RawMessage, zone: TimeZone) = MailReading(
                if (message.adapterRef == "sample-code") MailKind.SECURITY_CODE else MailKind.MEETING_REQUEST,
                proposedTimes = (0 until 40).map { Interval.of(now + (it * 6).hours, 60) } +
                    Interval.of(now - 30.days, 60) + Interval.of(now + 400.days, 60) + Interval.of(now + 1.days, 6000),
                durationMinutes = 10_000,
            )
        }
        val rig = Rig(reader = liar).withSampleInbox()
        val read = summaries(rig.agent.propose(rig.mailKey(), Action.ReadInbox()))
        for (m in read) {
            assertTrue(m.proposedTimes.size <= MailGuard.MAX_TIMES)
            assertTrue(m.proposedTimes.all { it.start >= now - 1.days && it.start <= now + 90.days && it.minutes <= 480 })
            assertEquals(null, m.durationMinutes)
        }
        assertTrue(read.single { it.kind == MailKind.SECURITY_CODE }.proposedTimes.isEmpty())
    }

    @Test fun `a reader that fails or hangs costs one message, not the read`() = runTest {
        val rig = Rig(reader = object : MailReader {
            override val name = "broken reader"
            override suspend fun read(message: RawMessage, zone: TimeZone): MailReading {
                if (message.adapterRef == "sample-dana") delay(5.minutes)
                if (message.adapterRef == "sample-sam") error("model crashed")
                return MailReading(MailKind.OTHER)
            }
        }).withSampleInbox()
        val read = summaries(rig.agent.propose(rig.mailKey(), Action.ReadInbox()))
        assertEquals(5, read.size)
        assertTrue(read.all { it.kind == MailKind.OTHER })
    }

    @Test fun `mail keys have limits`() = runTest {
        val rig = Rig().withSampleInbox()
        val key = rig.mailKey()
        assertEquals(Refusal.OUT_OF_SCOPE, refusal(rig.agent.propose(key, Action.ReadInbox(maxResults = 50))))
        assertEquals(Refusal.OUT_OF_SCOPE, refusal(rig.agent.propose(key, Action.ReadInbox(sinceDays = 30))))
        assertEquals(0, rig.mail.reads)
        assertNotNull(Policy.validateGrantRequest(Principal.Assistant, GrantRequest("All mail", listOf(Permit.MailRead(maxMessages = 500)))))
        assertNotNull(Policy.validateGrantRequest(Principal.Assistant, GrantRequest("Old mail", listOf(Permit.MailRead(maxAgeDays = 365)))))
    }

    @Test fun `what the attack asks for is refused even from a planner that obeys it`() = runTest {
        // Suppose the planner did read the attack and tried to do what it says. It holds a mail key,
        // a calendar key, and an invite key for Dana only.
        val rig = Rig().withSampleInbox()
        rig.mailKey()
        val calendar = rig.calendarKey()
        val invites = rig.calendarKey(GrantTemplates.sendInvites(listOf(SampleMail.DANA)))
        val tomorrow9 = at(MONDAY.plusDays(1), 9)
        assertEquals(Refusal.NO_PERMIT, refusal(rig.agent.propose(calendar, Action.SendInvite(tomorrow9, 30, "Sync", SampleMail.EXFIL.take(1)))))
        assertEquals(Refusal.OUT_OF_SCOPE, refusal(rig.agent.propose(invites, Action.SendInvite(tomorrow9, 30, "Sync", SampleMail.EXFIL.take(1)))))
        // It can't skip the confirmation either: only the user can mint a preapproved invite key.
        assertNotNull(Policy.validateGrantRequest(Principal.Assistant,
            GrantRequest("Invites", listOf(Permit.Invites(SampleMail.EXFIL, preapproved = true)))))
        // And there is no action that forwards mail, reads a message's text, or reveals a code.
        assertTrue(Capabilities.catalog.none { "forward" in it.id.value || "body" in it.id.value || "code" in it.id.value })
        assertTrue(rig.calendar.events.isEmpty())
    }

    @Test fun `message handles survive a restart`() = runTest {
        val rig = Rig().withSampleInbox()
        val ids = summaries(rig.agent.propose(rig.mailKey(), Action.ReadInbox())).map { it.messageId }
        val reloaded = Rig(storage = rig.storage)
        val executed = reloaded.events().filterIsInstance<Event.ActionExecuted>().single { it.mail.isNotEmpty() }
        assertEquals(ids, executed.mail.map { it.messageId })
    }
}
