package deputy.broker

import kotlinx.datetime.DayOfWeek
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * One typed piece of authority inside a grant. Permits are plain data; the checks that read
 * them live in one place, [Policy], so the enforcement logic can be read top to bottom.
 */
@Serializable
sealed interface Permit {
    val capabilities: Set<CapabilityId>

    /** See when the user is busy, never what. Results are slots, never the busy map. */
    @Serializable @SerialName("free_busy")
    data class FreeBusy(
        val maxDaysAhead: Int = 14,
        val granularityMinutes: Int = 30,
        val earliestHour: Int = 8,
        val latestHour: Int = 20,
        val maxResults: Int = 5,
    ) : Permit {
        override val capabilities get() = setOf(Capabilities.FIND_FREE_SLOTS)
    }

    /** Place and release tentative holds. Only Deputy's own holds can be released. */
    @Serializable @SerialName("holds")
    data class Holds(
        val maxDaysAhead: Int = 14,
        val maxDurationMinutes: Int = 120,
        val maxActiveHolds: Int = 3,
    ) : Permit {
        override val capabilities get() = setOf(Capabilities.PLACE_HOLD, Capabilities.RELEASE_HOLD)
    }

    /** Send invites. Permanent, so each one asks first unless [preapproved] (e.g. "auto for contacts"). */
    @Serializable @SerialName("invites")
    data class Invites(
        val allowedAttendees: List<String>? = null,
        val maxDurationMinutes: Int = 120,
        val maxDaysAhead: Int = 30,
        val preapproved: Boolean = false,
    ) : Permit {
        override val capabilities get() = setOf(Capabilities.SEND_INVITE)
    }

    /**
     * Read recent mail through the quarantined reader. What comes back is typed summaries
     * (kind, proposed times, a flag), never who sent it or what it says (D25).
     */
    @Serializable @SerialName("mail_read")
    data class MailRead(
        val maxMessages: Int = 10,
        val maxAgeDays: Int = 7,
    ) : Permit {
        override val capabilities get() = setOf(Capabilities.EMAIL_READ)
    }

    /**
     * Reply to messages Deputy has read, to their senders only. Permanent: every reply asks
     * first, and there is no preapproved form (D27).
     */
    @Serializable @SerialName("mail_reply")
    data class MailReply(val maxBodyChars: Int = 1000) : Permit {
        override val capabilities get() = setOf(Capabilities.EMAIL_SEND)
    }

    /** Inbound: a peer may ask whether a slot is free, within these windows and this budget. */
    @Serializable @SerialName("inbound_free_busy")
    data class InboundFreeBusy(
        val weekdays: Set<DayOfWeek> = setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY),
        val earliestHour: Int = 18,
        val latestHour: Int = 21,
        val maxDaysAhead: Int = 14,
        val maxSlotMinutes: Int = 120,
        /** Information budget: answered questions per rolling 7 days. Declines do not count. */
        val questionsPerWeek: Int = 6,
    ) : Permit {
        override val capabilities get() = setOf(Capabilities.ANSWER_FREE_BUSY)
    }
}

/**
 * A capability token, held by the broker. The holder only ever sees [id], an unguessable
 * handle; the authority itself never leaves the broker (docs/decisions.md D3).
 */
@Serializable
data class Grant(
    val id: GrantId,
    val holder: Principal,
    val direction: Direction,
    /** Short name for the Keyring, e.g. "Calendar for scheduling". */
    val title: String,
    val permits: List<Permit>,
    val maxUses: Int? = null,
    val issuedAt: SInstant,
    val expiresAt: SInstant? = null,
    /** Non-delegable by default: only [holder] may present it. Phase 0 never mints delegable grants. */
    val delegable: Boolean = false,
    /** Why it was asked for, as the requester put it. Shown labelled, never trusted. */
    val purpose: String? = null,
    val issuedBy: Principal = Principal.User,
)

/** A grant plus what the journal says has happened to it. Derived, never stored directly. */
data class GrantRecord(
    val grant: Grant,
    val usesConsumed: Int = 0,
    val revokedAt: Instant? = null,
) {
    enum class Status { ACTIVE, NOT_YET_VALID, EXPIRED, REVOKED, EXHAUSTED }

    fun status(now: Instant): Status = when {
        revokedAt != null -> Status.REVOKED
        now < grant.issuedAt -> Status.NOT_YET_VALID
        grant.expiresAt != null && now >= grant.expiresAt -> Status.EXPIRED
        grant.maxUses != null && usesConsumed >= grant.maxUses -> Status.EXHAUSTED
        else -> Status.ACTIVE
    }

    val usesLeft: Int? get() = grant.maxUses?.let { (it - usesConsumed).coerceAtLeast(0) }
}

/** What a requester (the assistant) asks for. The user sees exactly this before it exists. */
@Serializable
data class GrantRequest(
    val title: String,
    val permits: List<Permit>,
    val maxUses: Int? = null,
    val ttlMinutes: Int? = 120,
    val purpose: String? = null,
)

/** The keys a person can add from the Keyring without being asked by the assistant. */
object GrantTemplates {
    fun calendarForScheduling() = GrantRequest(
        title = "Calendar for scheduling",
        permits = listOf(Permit.FreeBusy(), Permit.Holds()),
        // A use cap bounds how much of the calendar narrow queries can map out (decisions.md D18).
        maxUses = 20,
        ttlMinutes = 2.hours.inWholeMinutes.toInt(),
    )

    fun sendInvites(attendees: List<String>? = null) = GrantRequest(
        title = "Send invites",
        permits = listOf(Permit.Invites(allowedAttendees = attendees)),
        maxUses = 3,
        ttlMinutes = 1.days.inWholeMinutes.toInt(),
    )

    fun mailReading() = GrantRequest(
        title = "Read mail as summaries",
        permits = listOf(Permit.MailRead()),
        maxUses = 10,
        ttlMinutes = 2.hours.inWholeMinutes.toInt(),
    )

    fun mailReplies() = GrantRequest(
        title = "Reply to mail",
        permits = listOf(Permit.MailReply()),
        maxUses = 3,
        ttlMinutes = 2.hours.inWholeMinutes.toInt(),
    )

    fun inboundFreeBusy() = GrantRequest(
        title = "Free/busy, weekday evenings",
        permits = listOf(Permit.InboundFreeBusy()),
        ttlMinutes = 30.days.inWholeMinutes.toInt(),
    )

    fun ttlToExpiry(issuedAt: Instant, ttlMinutes: Int?): Instant? = ttlMinutes?.let { issuedAt + it.minutes }
}
