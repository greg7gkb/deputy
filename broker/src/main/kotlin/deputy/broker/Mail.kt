package deputy.broker

import kotlinx.datetime.TimeZone
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.jvm.JvmInline
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

/** A broker-minted handle for one message. The only string about a message a proposer ever gets. */
@Serializable @JvmInline value class MessageId(val value: String) { override fun toString() = value }

/**
 * One message as the mail adapter returns it. Every field is untrusted: other people wrote it,
 * and any of it may be written to steer an assistant. It goes to the reader and nowhere else;
 * the body is never journaled (docs/decisions.md D25).
 */
data class RawMessage(
    /** Adapter-internal id (a Gmail message id). Never shown to a proposer. */
    val adapterRef: String,
    val from: String,
    val subject: String,
    val body: String,
    val receivedAt: Instant,
)

/**
 * How mail reaches the broker. The broker is the only caller. Implementations read only the
 * mail the user set aside for Deputy (a Gmail label), and only what [RawMessage] carries (D9, D25).
 */
interface MailAdapter {
    /** Shown in Activity, e.g. "Gmail, label Deputy". */
    val source: String
    /** Newest first, at most [max], received at or after [since]. */
    suspend fun recent(since: Instant, max: Int): List<RawMessage>

    /**
     * Reply to the message [adapterRef], in its thread, to exactly [to] and nobody else; the
     * subject is "Re: " plus the original's. [to] is the address the user saw and approved, so
     * implementations must not substitute a Reply-To or add recipients (D27). Returns a reference
     * to the sent message.
     */
    suspend fun sendReply(adapterRef: String, to: String, body: String): String
}

/** What kind of message the reader thinks it is. A closed set: there is no "other, described as ...". */
@Serializable
enum class MailKind { MEETING_REQUEST, SECURITY_CODE, OTHER }

/**
 * Everything the quarantined reader may say about a message: typed slots, no free text, so a
 * reader that has been fooled can produce a wrong kind or a wrong time but cannot pass words on.
 */
@Serializable
data class MailReading(
    val kind: MailKind,
    /** Times the message proposes, if it proposes any. */
    val proposedTimes: List<Interval> = emptyList(),
    /** Meeting length the message states, if it states one. */
    val durationMinutes: Int? = null,
    /**
     * The reader thinks the text is written to an AI assistant ("ignore your instructions...").
     * Shown to the user and passed on as one bit. Nothing enforces on it: containment doesn't
     * depend on spotting the attack.
     */
    val addressesAssistant: Boolean = false,
)

/**
 * The quarantined side of the dual-model split (design.md section 4). It reads untrusted
 * content and holds no authority: no port, no adapter, nothing to call. The broker calls it and
 * bounds whatever it returns ([MailGuard.bound]), because a reader can be fooled too.
 */
interface MailReader {
    /** Shown in Activity, e.g. "rule-based reader" or "Gemma 3n on this phone". */
    val name: String
    suspend fun read(message: RawMessage, zone: TimeZone): MailReading
}

/**
 * The user's side of one message Deputy read: enough to recognise it in Activity. Journaled,
 * so message handles resolve the same way after a restart (D2). Never the body.
 */
@Serializable
data class MailRecord(
    val messageId: MessageId,
    val adapterRef: String,
    /** Display only, cleaned and shortened. */
    val from: String,
    /** Display only, cleaned and shortened; digit runs masked when it carries a code. */
    val subject: String,
    val receivedAt: SInstant,
    val reading: MailReading,
    val reader: String,
    /** The bare address in From, if it is one. The only address a reply can go to (D27). */
    val replyTo: String? = null,
) {
    /** The proposer's side: the same message with every untrusted string removed. */
    fun summary() = MailSummary(messageId, receivedAt, reading.kind, reading.proposedTimes, reading.durationMinutes, reading.addressesAssistant)
}

/**
 * What a proposer learns about one message. Apart from the broker-minted [messageId], no field
 * can hold text, so nothing the sender wrote can reach the planner (D25).
 */
@Serializable
data class MailSummary(
    val messageId: MessageId,
    val receivedAt: SInstant,
    val kind: MailKind,
    val proposedTimes: List<Interval>,
    val durationMinutes: Int?,
    val flagged: Boolean,
)

/** Deterministic rules for what a reading may say and how a message is shown. */
object MailGuard {
    const val MAX_TIMES = 5
    const val MAX_FROM = 80
    const val MAX_SUBJECT = 100

    /**
     * Bounds a reading before anything uses it. The reader is untrusted, so the broker, not the
     * reader, decides what a reading may contain: a few plausible future times, a sane length,
     * and nothing at all for a message carrying a code.
     */
    fun bound(r: MailReading, now: Instant): MailReading {
        if (r.kind == MailKind.SECURITY_CODE) return MailReading(MailKind.SECURITY_CODE, addressesAssistant = r.addressesAssistant)
        val duration = r.durationMinutes?.takeIf { it in 15..480 && it % 15 == 0 }
        val times = r.proposedTimes
            .filter { it.start >= now - 1.days && it.start <= now + 90.days && it.minutes in 15..480 }
            .distinct()
            .sortedBy { it.start }
            .take(MAX_TIMES)
        return MailReading(r.kind, if (r.kind == MailKind.MEETING_REQUEST) times else emptyList(),
            if (r.kind == MailKind.MEETING_REQUEST) duration else null, r.addressesAssistant)
    }

    /** One line for a person to read: control and format characters out, whitespace collapsed, shortened. */
    fun display(text: String, max: Int): String {
        val clean = text.map { c ->
            if (c.category == CharCategory.CONTROL || c.category == CharCategory.FORMAT ||
                c.category == CharCategory.LINE_SEPARATOR || c.category == CharCategory.PARAGRAPH_SEPARATOR) ' ' else c
        }.joinToString("").replace(Regex("\\s+"), " ").trim()
        return if (clean.length <= max) clean.ifEmpty { "(none)" } else clean.take(max - 1).trimEnd() + "…"
    }

    /** The bare address in a From header ("Dana <dana@example.com>"), or null if there isn't exactly one plausible one. */
    fun address(from: String): String? {
        // One sender, one address. A From naming several is either broken or an attempt to add recipients.
        if (from.count { it == '@' } != 1) return null
        val candidate = Regex("<([^<>]+)>").findAll(from).lastOrNull()?.groupValues?.get(1) ?: from
        return candidate.trim().lowercase().takeIf { EMAIL.matches(it) }
    }

    private val EMAIL = Regex("[a-z0-9._%+-]{1,64}@[a-z0-9-]{1,63}(\\.[a-z0-9-]{1,63}){1,8}")

    /** A code message's subject keeps its shape but not its digits, so the journal never holds the code. */
    fun subject(subject: String, kind: MailKind): String {
        val shown = display(subject, MAX_SUBJECT)
        return if (kind == MailKind.SECURITY_CODE) shown.replace(Regex("\\d{3,}")) { "•".repeat(it.value.length) } else shown
    }
}
