package deputy.broker

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/** Why the broker said no. Recorded in full for the user; never shown to an inbound asker. */
enum class Refusal {
    UNKNOWN_GRANT, NOT_HOLDER, REVOKED, EXPIRED, NOT_YET_VALID, EXHAUSTED,
    NO_PERMIT, OUT_OF_SCOPE, UNAVAILABLE, HUMAN_GESTURE_ONLY, FLOW_VIOLATION,
    DECLINED_BY_USER, NOT_PENDING, INTEGRITY, ADAPTER_FAILED,
    INBOUND_NO_GRANT, INBOUND_BUDGET, INBOUND_NO_FACTS,
}

@Serializable
enum class ApprovalKind { MINT_GRANT, CONFIRM_PERMANENT }

/**
 * Every state change in the broker is one of these, appended to the journal. Broker state is
 * a fold over the journal, so nothing can change without leaving a record (docs/decisions.md D2).
 */
@Serializable
sealed interface Event {
    val at: kotlin.time.Instant

    @Serializable @SerialName("grant_minted")
    data class GrantMinted(override val at: SInstant, val grant: Grant, val viaRequest: RequestId? = null) : Event

    @Serializable @SerialName("grant_revoked")
    data class GrantRevoked(override val at: SInstant, val grantId: GrantId, val by: Principal) : Event

    @Serializable @SerialName("kill_switch")
    data class KillSwitch(override val at: SInstant, val revoked: List<GrantId>, val cancelled: List<RequestId>) : Event

    @Serializable @SerialName("approval_requested")
    data class ApprovalRequested(
        override val at: SInstant,
        val requestId: RequestId,
        val principal: Principal,
        val kind: ApprovalKind,
        val grantId: GrantId? = null,
        val action: Action? = null,
        val grantRequest: GrantRequest? = null,
        val expiresAt: SInstant,
    ) : Event

    /** Requests nobody answered in time. Swept so pending never grows without bound. */
    @Serializable @SerialName("approval_expired")
    data class ApprovalExpired(override val at: SInstant, val requestIds: List<RequestId>) : Event

    @Serializable @SerialName("approval_resolved")
    data class ApprovalResolved(override val at: SInstant, val requestId: RequestId, val approved: Boolean, val by: Principal) : Event

    @Serializable @SerialName("action_executed")
    data class ActionExecuted(
        override val at: SInstant,
        val principal: Principal,
        val grantId: GrantId?,
        val action: Action,
        val result: ActionResult,
        val requestId: RequestId? = null,
        /** Adapter-internal handle (e.g. a calendar event id). Never returned to a proposer. */
        val adapterRef: String? = null,
        /** Journal seq of the action this one undid, when the user tapped Undo. */
        val undoOf: Long? = null,
        /** The id of what this action created, linked from the artifact back to here (D19). */
        val artifactId: String? = null,
        /** The user's side of a mail read: who sent each message and its subject. The proposer got only [result] (D25). */
        val mail: List<MailRecord> = emptyList(),
    ) : Event

    @Serializable @SerialName("action_refused")
    data class ActionRefused(
        override val at: SInstant,
        val principal: Principal,
        val grantId: GrantId?,
        val action: Action?,
        val reason: Refusal,
        val detail: String,
    ) : Event

    @Serializable @SerialName("action_failed")
    data class ActionFailed(
        override val at: SInstant,
        val principal: Principal,
        val grantId: GrantId?,
        val action: Action,
        val error: String,
    ) : Event

    /** Facts were re-read from a source. Values stay in the fact store, not the journal. */
    @Serializable @SerialName("facts_refreshed")
    data class FactsRefreshed(override val at: SInstant, val factKey: String, val source: String, val itemCount: Int) : Event

    @Serializable @SerialName("inbound_asked")
    data class InboundAsked(
        override val at: SInstant,
        val asker: Principal,
        val question: Question,
        val answer: InboundAnswer,
        val grantId: GrantId? = null,
        /** Why it was declined. For the user's eyes; the asker only ever sees NOT_AVAILABLE. */
        val declineReason: Refusal? = null,
        val factsObservedAt: SInstant? = null,
    ) : Event
}

data class JournalEntry(val seq: Long, val prevHash: String, val hash: String, val event: Event)

/**
 * Where journal lines live. One line per entry; appends only. The head (`seq<TAB>hash` of the
 * last entry) is kept separately, so dropping entries off the end is detected, not just edits.
 */
interface JournalStorage {
    fun readLines(): List<String>
    fun appendLine(line: String)
    fun readHead(): String?
    fun writeHead(head: String)
}

class InMemoryJournalStorage : JournalStorage {
    val lines = mutableListOf<String>()
    var head: String? = null
    override fun readLines() = lines.toList()
    override fun appendLine(line: String) { lines += line }
    override fun readHead() = head
    override fun writeHead(head: String) { this.head = head }
}

/** A plain text file, synced to disk on every append, with the head in a sibling file. */
class FileJournalStorage(private val file: File) : JournalStorage {
    private val headFile = File(file.parentFile, file.name + ".head")

    override fun readLines(): List<String> = if (file.exists()) file.readLines().filter { it.isNotBlank() } else emptyList()
    override fun appendLine(line: String) {
        file.parentFile?.mkdirs()
        FileOutputStream(file, true).use { out ->
            out.write((line + "\n").toByteArray(Charsets.UTF_8))
            out.fd.sync()
        }
    }
    override fun readHead(): String? = if (headFile.exists()) headFile.readText().trim() else null
    override fun writeHead(head: String) {
        val tmp = File(headFile.parentFile, headFile.name + ".tmp")
        tmp.writeText(head)
        check(tmp.renameTo(headFile)) { "could not replace ${headFile.name}" }
    }
}

/**
 * The append-only, hash-chained journal. Each line is `seq<TAB>prevHash<TAB>hash<TAB>eventJson`,
 * and `hash = sha256(seq|prevHash|eventJson)` over the exact stored text, so verification
 * never depends on re-encoding (docs/decisions.md D2).
 */
class Journal(private val storage: JournalStorage) {
    private val _entries = mutableListOf<JournalEntry>()
    val entries: List<JournalEntry> get() = _entries.toList()

    /** Non-null if the stored chain did not verify on load. The broker then fails closed. */
    var integrityError: String? = null
        private set

    init {
        var prev = GENESIS
        for ((i, line) in storage.readLines().withIndex()) {
            val parts = line.split('\t', limit = 4)
            if (parts.size != 4) { integrityError = "line ${i + 1} is malformed"; break }
            val (seqText, prevHash, hash, json) = parts
            val seq = seqText.toLongOrNull()
            if (seq != (i + 1).toLong()) { integrityError = "line ${i + 1} has seq $seqText"; break }
            if (prevHash != prev) { integrityError = "entry $seq does not chain to entry ${seq - 1}"; break }
            if (sha256("$seq|$prevHash|$json") != hash) { integrityError = "entry $seq has been altered"; break }
            val event = try { JSON.decodeFromString(Event.serializer(), json) } catch (e: Exception) {
                integrityError = "entry $seq cannot be read: ${e.message}"; break
            }
            _entries += JournalEntry(seq, prevHash, hash, event)
            prev = hash
        }
        if (integrityError == null) checkHead()
    }

    /**
     * The head must name an entry we have, with the same hash. The log may run one entry past
     * the head (a crash between the two writes); it may never stop short of it.
     */
    private fun checkHead() {
        val head = storage.readHead() ?: run {
            if (_entries.isNotEmpty()) integrityError = "the log's head record is missing"
            return
        }
        val (seqText, hash) = head.split('\t').let { if (it.size == 2) it else listOf("", "") }
        val seq = seqText.toLongOrNull()
        val entry = seq?.let { s -> _entries.firstOrNull { it.seq == s } }
        integrityError = when {
            seq == null -> "the log's head record is malformed"
            seq == 0L && hash == GENESIS -> null
            entry == null -> "the log ends at entry ${_entries.size} but should reach entry $seq: entries were removed"
            entry.hash != hash -> "entry $seq does not match the log's head record"
            else -> null
        }
    }

    fun append(event: Event): JournalEntry {
        check(integrityError == null) { "journal integrity failed; refusing to append" }
        val seq = (_entries.lastOrNull()?.seq ?: 0) + 1
        val prev = _entries.lastOrNull()?.hash ?: GENESIS
        val json = JSON.encodeToString(Event.serializer(), event)
        require('\n' !in json && '\t' !in json) { "event json must be a single line" }
        val hash = sha256("$seq|$prev|$json")
        storage.appendLine("$seq\t$prev\t$hash\t$json")
        storage.writeHead("$seq\t$hash")
        return JournalEntry(seq, prev, hash, event).also { _entries += it }
    }

    companion object {
        const val GENESIS = "genesis"

        val JSON = Json {
            encodeDefaults = true
            ignoreUnknownKeys = true
            classDiscriminator = "type"
        }

        // JVM-only. When the broker goes multiplatform this becomes an expect/actual.
        fun sha256(text: String): String =
            MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
    }
}
