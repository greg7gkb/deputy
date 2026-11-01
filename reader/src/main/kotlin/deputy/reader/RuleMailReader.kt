package deputy.reader

import deputy.broker.Interval
import deputy.broker.MailKind
import deputy.broker.MailReader
import deputy.broker.MailReading
import deputy.broker.RawMessage
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/**
 * The first quarantined reader: plain rules, no model (docs/decisions.md D26). It stands where an
 * on-device model will, with the same contract: untrusted text in, a [MailReading] out, no
 * authority. It is deliberately simple, and fooling it is allowed by design: the worst a fooled
 * reader can do is report a wrong kind or a wrong time, because a reading has no room for words.
 */
class RuleMailReader : MailReader {
    override val name = "rule-based reader"
    override suspend fun read(message: RawMessage, zone: TimeZone): MailReading =
        MailRules.read(message.subject, message.body, message.receivedAt, zone)
}

object MailRules {
    fun read(subject: String, body: String, receivedAt: Instant, zone: TimeZone): MailReading {
        val text = "$subject\n$body".lowercase()
        val addressesAssistant = ASSISTANT.any { it.containsMatchIn(text) }
        if (CODE.any { it.containsMatchIn(text) }) return MailReading(MailKind.SECURITY_CODE, addressesAssistant = addressesAssistant)
        val duration = duration(text)
        val times = times(text, receivedAt.toLocalDateTime(zone).date, zone, duration ?: DEFAULT_MINUTES)
        return if (MEETING.containsMatchIn(text) && times.isNotEmpty()) {
            MailReading(MailKind.MEETING_REQUEST, times, duration, addressesAssistant)
        } else {
            MailReading(MailKind.OTHER, addressesAssistant = addressesAssistant)
        }
    }

    const val DEFAULT_MINUTES = 30

    /** A code word near a 4 to 8 digit number, either way round. */
    private val CODE = listOf(
        Regex("""\b(code|passcode|otp|pin|verification)\b\D{0,30}\b\d{4,8}\b"""),
        Regex("""\b\d{4,8}\b\D{0,30}\b(is your|code|passcode)\b"""),
        Regex("""\b\d{3} \d{3}\b\D{0,30}\b(is your|code)\b"""),
    )

    private val MEETING = Regex(
        """\b(meet|meeting|call|catch up|catch-up|coffee|lunch|chat|sync|get together|available|availability|free for|free at|time to talk)\b""",
    )

    /** Text written to a machine reader rather than a person. A heuristic flag, never a defence. */
    private val ASSISTANT = listOf(
        Regex("""\bignore (all |any |your |the )?(previous|prior|above|earlier|other|existing) (instructions|directions|rules|guidance)"""),
        Regex("""\b(ai|virtual|digital) (assistant|agent)\b"""),
        Regex("""\b(language model|llm|system prompt|new instructions|you are now)\b"""),
        Regex("""\b(note to|attention|dear|hey|instructions for) (the )?(assistant|agent|ai|claude|gemini|chatgpt|copilot)\b"""),
        Regex("""\bdo not (tell|inform|mention|notify|alert) (this to )?the user\b"""),
        Regex("""\b(claude|chatgpt|gemini|copilot)\b"""),
        Regex("""(^|\n|>)\s*(assistant|ai|agent)\s*[:,]"""),
    )

    private val DAY = Regex(
        """\b(today|tomorrow|monday|mon|tuesday|tues|tue|wednesday|wed|thursday|thurs|thu|friday|fri|saturday|sat|sunday|sun)\b""",
    )
    private val TIME_12H = Regex("""\b(\d{1,2})(?::([0-5]\d))?\s*(am|pm|a\.m\.|p\.m\.)""")
    private val TIME_24H = Regex("""\b([01]?\d|2[0-3]):([0-5]\d)\b(?!\s*(am|pm|a\.m\.|p\.m\.))""")
    private val NOON = Regex("""\bnoon\b""")

    /** Days and times paired within a sentence: each time takes the nearest day before it, else after. */
    fun times(text: String, received: LocalDate, zone: TimeZone, minutes: Int): List<Interval> {
        val out = mutableListOf<Interval>()
        for (sentence in text.split(Regex("""[.!?\n;]+"""))) {
            val days = DAY.findAll(sentence).map { it.range.first to resolveDay(it.value, received) }.toList()
            if (days.isEmpty()) continue
            for ((pos, time) in clockTimes(sentence)) {
                val day = days.lastOrNull { it.first < pos }?.second ?: days.first().second
                val start = LocalDateTime(day, time).toInstant(zone)
                out += Interval.of(start, minutes)
            }
        }
        return out.distinct().sortedBy { it.start }
    }

    private fun clockTimes(s: String): List<Pair<Int, LocalTime>> {
        val found = mutableListOf<Pair<Int, LocalTime>>()
        TIME_12H.findAll(s).forEach { m ->
            val h = m.groupValues[1].toInt()
            val min = m.groupValues[2].ifEmpty { "0" }.toInt()
            if (h in 1..12) {
                val pm = m.groupValues[3].startsWith("p")
                found += m.range.first to LocalTime((h % 12) + if (pm) 12 else 0, min)
            }
        }
        TIME_24H.findAll(s).forEach { m ->
            if (found.none { it.first == m.range.first }) found += m.range.first to LocalTime(m.groupValues[1].toInt(), m.groupValues[2].toInt())
        }
        NOON.findAll(s).forEach { found += it.range.first to LocalTime(12, 0) }
        return found.sortedBy { it.first }
    }

    /** "today", "tomorrow", or the next such weekday after the day the message arrived. */
    private fun resolveDay(word: String, received: LocalDate): LocalDate {
        if (word == "today") return received
        if (word == "tomorrow") return received.plus(DatePeriod(days = 1))
        val target = DayOfWeek.entries.first { it.name.lowercase().startsWith(word.take(3)) }
        var ahead = (target.isoDayNumber - received.dayOfWeek.isoDayNumber + 7) % 7
        if (ahead == 0) ahead = 7
        return received.plus(DatePeriod(days = ahead))
    }

    fun duration(text: String): Int? {
        Regex("""\b(\d{2,3})\s*-?\s*(min|mins|minutes|minute)\b""").find(text)?.let { return it.groupValues[1].toInt() }
        if (Regex("""\b(half an hour|half-hour|half hour)\b""").containsMatchIn(text)) return 30
        Regex("""\b([1-8])\s*-?\s*(hours|hour|hr|hrs)\b""").find(text)?.let { return it.groupValues[1].toInt() * 60 }
        if (Regex("""\b(an|one) hour\b|\bhour-long\b""").containsMatchIn(text)) return 60
        return null
    }
}
