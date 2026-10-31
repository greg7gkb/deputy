package deputy.broker

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlin.jvm.JvmInline
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/** ISO-8601 serializer for [Instant], so the journal stays human-readable. */
object InstantIso : KSerializer<Instant> {
    override val descriptor = PrimitiveSerialDescriptor("deputy.Instant", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: Instant) = encoder.encodeString(value.toString())
    override fun deserialize(decoder: Decoder): Instant = Instant.parse(decoder.decodeString())
}

typealias SInstant = @Serializable(with = InstantIso::class) Instant

@Serializable @JvmInline value class GrantId(val value: String) { override fun toString() = value }
@Serializable @JvmInline value class RequestId(val value: String) { override fun toString() = value }
@Serializable @JvmInline value class HoldId(val value: String) { override fun toString() = value }
@Serializable @JvmInline value class CapabilityId(val value: String) { override fun toString() = value }

/**
 * Who is acting or asking. A principal is fixed by the host when it hands out a port; the
 * code holding the port cannot choose or change it (docs/decisions.md D4).
 */
@Serializable
data class Principal(val kind: Kind, val id: String, val displayName: String) {
    enum class Kind { USER, ASSISTANT, PEER }

    // Identity is kind + id. The display name is a label and can change without moving authority.
    override fun equals(other: Any?) = other is Principal && other.kind == kind && other.id == id
    override fun hashCode() = 31 * kind.hashCode() + id.hashCode()

    companion object {
        val User = Principal(Kind.USER, "user", "You")
        val Assistant = Principal(Kind.ASSISTANT, "assistant", "Your assistant")
        fun peer(id: String, displayName: String) = Principal(Kind.PEER, id, displayName)
    }
}

/** How undoable an action is. Decides whether Deputy asks first (CLAUDE.md, Action risk tiers). */
enum class RiskTier { REVERSIBLE, COMPENSABLE, PERMANENT }

/** The six adapter tiers, best to worst (CLAUDE.md, Adapters). */
enum class AdapterTier(val rank: Int, val label: String) {
    ON_DEVICE_API(1, "on-device API"),
    CLOUD_API(2, "cloud API"),
    CODE_TRIGGER(3, "code trigger"),
    HANDED_AFFORDANCE(4, "handed affordance"),
    UI_DRIVING(5, "UI driving"),
    GO_TO_APP(6, "go to the app"),
}

/** Outbound: what the user's agent may do to the world. Inbound: what the world may learn. */
enum class Direction { OUTBOUND, INBOUND }

/** Provenance labels, in descending trust. Ordinal order is the trust order. */
enum class Provenance { ATTESTED, OBSERVED, INFERRED, ASSERTED }

/**
 * Sensitivity labels for information flow. SECRET may never reach the planner, an outbound
 * message, or an inbound answer (CLAUDE.md hard rule).
 */
enum class Sensitivity { PUBLIC, PRIVATE, SECRET }

@Serializable
data class Interval(val start: SInstant, val end: SInstant) {
    init { require(end > start) { "interval must have positive length" } }
    fun overlaps(other: Interval) = start < other.end && other.start < end
    val minutes: Long get() = (end - start).inWholeMinutes

    companion object {
        fun of(start: Instant, durationMinutes: Int) = Interval(start, start + durationMinutes.minutes)
    }
}

/**
 * Everything Deputy creates outside itself (a hold, an invite, later an email or a message) gets
 * an artifact id before the adapter runs, and a link back to its entry in Activity. Where only
 * the user will see the artifact, the link is written into it; where others will see it (an
 * invite's description goes to every attendee), it is kept out (docs/decisions.md D19).
 */
object Artifacts {
    const val SCHEME = "deputy"
    const val HOST = "activity"
    /** Every note Deputy writes into an artifact starts with this; adapters use it to recognise their own. */
    const val NOTE_PREFIX = "Placed by Deputy."

    fun link(artifactId: String) = "$SCHEME://$HOST/$artifactId"
    fun note(artifactId: String) = "$NOTE_PREFIX See it, or undo it, in Deputy: ${link(artifactId)}"

    /** The artifact id in a Deputy link, or null if it isn't one. */
    fun parse(link: String): String? {
        val prefix = "$SCHEME://$HOST/"
        val id = link.removePrefix(prefix).takeIf { link.startsWith(prefix) } ?: return null
        return id.takeIf { it.isNotEmpty() && it.length <= 64 && it.all { c -> c.isLetterOrDigit() || c == '_' } }
    }
}
