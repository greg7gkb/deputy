package deputy.broker

import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * Finds free slots from busy intervals. Returns derived values (a few slots), never the busy
 * map itself: the least information that answers the request (design.md section 10).
 */
object Slots {
    fun find(
        busy: List<Interval>,
        from: LocalDate,
        to: LocalDate,
        durationMinutes: Int,
        earliestHour: Int,
        latestHour: Int,
        granularityMinutes: Int,
        maxResults: Int,
        now: Instant,
        zone: TimeZone,
    ): List<Interval> {
        val perDay = mutableListOf<MutableList<Interval>>()
        var day = from
        while (day <= to) {
            val candidates = mutableListOf<Interval>()
            var start = LocalDateTime(day, LocalTime(earliestHour, 0)).toInstant(zone)
            val dayEnd = LocalDateTime(day, LocalTime(latestHour.coerceAtMost(23), if (latestHour >= 24) 59 else 0)).toInstant(zone)
            while (start + durationMinutes.minutes <= dayEnd) {
                val slot = Interval.of(start, durationMinutes)
                if (start >= now && busy.none { it.overlaps(slot) }) candidates += slot
                start += granularityMinutes.minutes
            }
            perDay += candidates
            day = day.plus(DatePeriod(days = 1))
        }

        // Spread the options: one per day first, then further options spaced apart on the same days.
        val chosen = mutableListOf<Interval>()
        var round = 0
        while (chosen.size < maxResults && perDay.any { it.isNotEmpty() }) {
            for (candidates in perDay) {
                if (chosen.size >= maxResults) break
                val pick = candidates.firstOrNull { c -> chosen.none { it.overlaps(Interval(c.start - 60.minutes, c.end + 60.minutes)) } }
                if (pick == null) { candidates.clear(); continue }
                chosen += pick
                candidates.removeAll { it.overlaps(pick) }
            }
            if (++round > 48) break
        }
        return chosen.sortedBy { it.start }
    }
}
