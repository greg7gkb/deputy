package io.github.greg7gkb.deputy

import android.content.Context
import deputy.broker.Broker
import deputy.broker.Direction
import deputy.broker.GrantId
import deputy.broker.GrantRecord
import deputy.broker.GrantTemplates
import deputy.broker.InboundAnswer
import deputy.broker.Outcome
import deputy.broker.Principal
import deputy.broker.Question
import io.github.greg7gkb.deputy.calendar.TestCalendar
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock

/**
 * Phase 0 stand-ins for things that don't exist yet: a test calendar, a paired friend's Deputy
 * asking typed questions (Phase 3), and an unpaired stranger. Inbound questions go through the
 * real broker; only the asker is simulated.
 */
class Lab(private val context: Context, private val broker: Broker) {
    val zane = Principal.peer("zane", "Zane")
    val stranger = Principal.peer("unpaired-7f3a", "An unpaired Deputy")

    suspend fun seedCalendar(): Int = TestCalendar.seed(context)

    /** You let Zane's Deputy ask whether you're free on weekday evenings. */
    suspend fun pairZane(): Outcome = broker.user.mintGrant(zane, Direction.INBOUND, GrantTemplates.inboundFreeBusy())

    fun zaneHasKey(): Boolean = zaneGrantId() != null

    /** For the leaked-handle test: the id of Zane's key, as if it had leaked to the planner. */
    fun zaneGrantId(): GrantId? {
        val now = Clock.System.now()
        return broker.snapshots.value.grants.firstOrNull { it.grant.holder == zane && it.status(now) == GrantRecord.Status.ACTIVE }?.grant?.id
    }

    suspend fun zaneAsks(day: DayOfWeek, hour: Int, minutes: Int = 60): InboundAnswer =
        broker.inbound.ask(zane, Question.FreeAt(next(day, hour), minutes))

    suspend fun strangerAsks(): InboundAnswer =
        broker.inbound.ask(stranger, Question.FreeAt(next(DayOfWeek.WEDNESDAY, 19), 60))

    /** Asks until the weekly budget runs out, to show the budget and the uniform decline. */
    suspend fun zaneAsksAllWeek(): List<InboundAnswer> = (0 until 8).map { i ->
        val day = listOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY)[i % 4]
        zaneAsks(day, 18 + (i / 4))
    }

    /** The next [day] strictly after today, at [hour]:00 local time. */
    private fun next(day: DayOfWeek, hour: Int): kotlin.time.Instant {
        val zone = broker.zone
        val today = Clock.System.now().toLocalDateTime(zone).date
        var ahead = (day.isoDayNumber - today.dayOfWeek.isoDayNumber + 7) % 7
        if (ahead == 0) ahead = 7
        return LocalDateTime(today.plus(DatePeriod(days = ahead)), LocalTime(hour, 0)).toInstant(zone)
    }
}
