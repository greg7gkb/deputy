package deputy.reader

import deputy.broker.MailKind
import deputy.broker.MailReading
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RuleMailReaderTest {
    private val zone = TimeZone.of("America/Los_Angeles")
    /** Monday 2 Nov 2026, 08:00. */
    private val now = LocalDateTime(LocalDate(2026, 11, 2), LocalTime(8, 0)).toInstant(zone)
    private fun at(day: Int, hour: Int) = LocalDateTime(LocalDate(2026, 11, day), LocalTime(hour, 0)).toInstant(zone)

    private suspend fun readings(): Map<String, MailReading> {
        val reader = RuleMailReader()
        return SampleMail.inbox(now).associate { it.adapterRef to reader.read(it, zone) }
    }

    @Test fun `a plain meeting request gives its times and length`() = runTest {
        val dana = readings().getValue("sample-dana")
        assertEquals(MailKind.MEETING_REQUEST, dana.kind)
        assertEquals(listOf(at(3, 14), at(4, 10)), dana.proposedTimes.map { it.start })
        assertEquals(30, dana.durationMinutes)
        assertFalse(dana.addressesAssistant)
    }

    @Test fun `a meeting request with a hidden instruction is still a meeting request, flagged`() = runTest {
        val sam = readings().getValue("sample-sam")
        assertEquals(MailKind.MEETING_REQUEST, sam.kind)
        assertEquals(listOf(at(5, 15)), sam.proposedTimes.map { it.start })
        assertTrue(sam.addressesAssistant)
    }

    @Test fun `a code message is a code and nothing else`() = runTest {
        val code = readings().getValue("sample-code")
        assertEquals(MailReading(MailKind.SECURITY_CODE), code)
    }

    @Test fun `the attack fools the reader into a meeting time, and that is all it can do`() = runTest {
        // The attack is flagged, and the reader reports the time it names (tomorrow 9am). That is
        // the whole of what fooling the reader buys: a wrong time in a typed slot, never words.
        val attack = readings().getValue("sample-attack")
        assertTrue(attack.addressesAssistant)
        assertEquals(listOf(at(3, 9)), attack.proposedTimes.map { it.start })
    }

    @Test fun `times without a meeting are not a meeting request`() = runTest {
        assertEquals(MailReading(MailKind.OTHER), readings().getValue("sample-news"))
    }

    @Test fun `durations and clock times`() {
        assertEquals(45, MailRules.duration("a 45-minute call"))
        assertEquals(60, MailRules.duration("an hour or so"))
        assertEquals(120, MailRules.duration("block 2 hours"))
        val received = LocalDate(2026, 11, 2)
        assertEquals(listOf(at(6, 12), at(6, 16)),
            MailRules.times("friday at noon or 16:00", received, zone, 30).map { it.start })
        // A weekday named on that same weekday means next week.
        assertEquals(listOf(at(9, 11)), MailRules.times("monday 11am?", received, zone, 30).map { it.start })
        assertTrue(MailRules.times("call me at 3pm", received, zone, 30).isEmpty(), "no day, no time")
    }
}
