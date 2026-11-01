package deputy.broker

import kotlinx.datetime.LocalDate
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * What a capability is, independent of any grant: its risk tier, how it reaches the world,
 * and how it reads to a person. The catalog is what a planner would see (docs/decisions.md D7).
 */
data class CapabilitySpec(
    val id: CapabilityId,
    val group: String,
    val title: String,
    val risk: RiskTier,
    val adapterTier: AdapterTier,
    val adapterLabel: String,
    val direction: Direction = Direction.OUTBOUND,
    /** Only a real human gesture can complete it (the garage). Deputy may surface, never fire. */
    val requiresHumanGesture: Boolean = false,
    /** False for capabilities designed but not yet built; shown in "Can do" as not connected. */
    val available: Boolean = true,
)

object Capabilities {
    val FIND_FREE_SLOTS = CapabilityId("calendar.find_free_slots")
    val PLACE_HOLD = CapabilityId("calendar.place_hold")
    val RELEASE_HOLD = CapabilityId("calendar.release_hold")
    val SEND_INVITE = CapabilityId("calendar.send_invite")
    val CANCEL_INVITE = CapabilityId("calendar.cancel_invite")
    val ANSWER_FREE_BUSY = CapabilityId("inbound.free_busy")

    val GARAGE_SHOW = CapabilityId("garage.show_button")
    val GARAGE_OPEN = CapabilityId("garage.open")
    val EMAIL_SUMMARIZE = CapabilityId("email.summarize")
    val EMAIL_SEND = CapabilityId("email.send_reply")

    val catalog: List<CapabilitySpec> = listOf(
        CapabilitySpec(FIND_FREE_SLOTS, "Calendar", "Find free time", RiskTier.REVERSIBLE, AdapterTier.ON_DEVICE_API, "CalendarContract"),
        CapabilitySpec(PLACE_HOLD, "Calendar", "Place a hold", RiskTier.REVERSIBLE, AdapterTier.ON_DEVICE_API, "CalendarContract"),
        CapabilitySpec(RELEASE_HOLD, "Calendar", "Release a hold", RiskTier.COMPENSABLE, AdapterTier.ON_DEVICE_API, "CalendarContract"),
        CapabilitySpec(SEND_INVITE, "Calendar", "Send an invite", RiskTier.PERMANENT, AdapterTier.ON_DEVICE_API, "CalendarContract"),
        // The invite can't be unsent, but it can be cancelled: guests get a second message. Yours only, from Activity.
        CapabilitySpec(CANCEL_INVITE, "Calendar", "Cancel an invite (you only)", RiskTier.COMPENSABLE, AdapterTier.ON_DEVICE_API, "CalendarContract"),
        CapabilitySpec(ANSWER_FREE_BUSY, "Calendar", "Answer free/busy for someone", RiskTier.REVERSIBLE, AdapterTier.ON_DEVICE_API, "fact store", Direction.INBOUND),
        CapabilitySpec(GARAGE_SHOW, "Garage", "Show the button near home", RiskTier.REVERSIBLE, AdapterTier.HANDED_AFFORDANCE, "Door widget", available = false),
        CapabilitySpec(GARAGE_OPEN, "Garage", "Open the gate", RiskTier.PERMANENT, AdapterTier.HANDED_AFFORDANCE, "Door widget", requiresHumanGesture = true, available = false),
        CapabilitySpec(EMAIL_SUMMARIZE, "Email", "Summarize new mail", RiskTier.REVERSIBLE, AdapterTier.CLOUD_API, "Gmail API", available = false),
        CapabilitySpec(EMAIL_SEND, "Email", "Send a reply", RiskTier.PERMANENT, AdapterTier.CLOUD_API, "Gmail API", available = false),
    )

    fun spec(id: CapabilityId): CapabilitySpec? = catalog.firstOrNull { it.id == id }
}

/**
 * A typed proposal. The serial name is the capability id, so a serialized action is exactly
 * what a planner's tool call would carry. There is no free-text action.
 */
@Serializable
sealed interface Action {
    val capability: CapabilityId

    /** Find up to [maxResults] free slots of [durationMinutes] between [from] and [to] (local dates, inclusive). */
    @Serializable @SerialName("calendar.find_free_slots")
    data class FindFreeSlots(
        val from: LocalDate,
        val to: LocalDate,
        val durationMinutes: Int,
        val earliestHour: Int = 9,
        val latestHour: Int = 17,
        val maxResults: Int = 3,
    ) : Action { override val capability get() = Capabilities.FIND_FREE_SLOTS }

    /** Put a tentative hold on the user's calendar. [label] is shown as "Hold: label". */
    @Serializable @SerialName("calendar.place_hold")
    data class PlaceHold(val start: SInstant, val durationMinutes: Int, val label: String) : Action {
        override val capability get() = Capabilities.PLACE_HOLD
    }

    /** Remove a hold Deputy placed. Only holds Deputy created can be named. */
    @Serializable @SerialName("calendar.release_hold")
    data class ReleaseHold(val holdId: HoldId) : Action {
        override val capability get() = Capabilities.RELEASE_HOLD
    }

    /** Create an event and invite people. Others are notified, so this is permanent. */
    @Serializable @SerialName("calendar.send_invite")
    data class SendInvite(
        val start: SInstant,
        val durationMinutes: Int,
        val title: String,
        val attendees: List<String>,
    ) : Action { override val capability get() = Capabilities.SEND_INVITE }

    /** Cancel an invite Deputy sent; guests are notified. No permit covers it: only the user can, from Activity. */
    @Serializable @SerialName("calendar.cancel_invite")
    data class CancelInvite(val artifactId: String) : Action {
        override val capability get() = Capabilities.CANCEL_INVITE
    }
}

/** What an executed action returned. Only these typed values ever flow back to a proposer. */
@Serializable
sealed interface ActionResult {
    @Serializable @SerialName("free_slots")
    data class FreeSlots(val slots: List<Interval>, val factsObservedAt: SInstant) : ActionResult

    @Serializable @SerialName("hold_placed")
    data class HoldPlaced(val holdId: HoldId, val slot: Interval) : ActionResult

    @Serializable @SerialName("hold_released")
    data class HoldReleased(val holdId: HoldId) : ActionResult

    @Serializable @SerialName("invite_sent")
    data class InviteSent(val slot: Interval, val attendees: List<String>) : ActionResult

    @Serializable @SerialName("invite_cancelled")
    data class InviteCancelled(val artifactId: String, val slot: Interval, val attendees: List<String>) : ActionResult
}
