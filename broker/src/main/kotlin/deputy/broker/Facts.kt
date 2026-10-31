package deputy.broker

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import java.io.File

/**
 * One thing Deputy knows about the user. Written only by the broker; every fact says where it
 * came from ([provenance], [source]) and when ([observedAt], [staleAfter]).
 */
@Serializable
data class Fact(
    val key: String,
    val value: FactValue,
    val provenance: Provenance,
    val sensitivity: Sensitivity,
    val source: String,
    val observedAt: SInstant,
    val staleAfter: SInstant,
)

@Serializable
sealed interface FactValue {
    /** Busy intervals within [window]. No titles, attendees or locations, by construction. */
    @Serializable @SerialName("busy_intervals")
    data class BusyIntervals(val window: Interval, val busy: List<Interval>) : FactValue

    @Serializable @SerialName("text")
    data class Text(val text: String) : FactValue

    @Serializable @SerialName("flag")
    data class Flag(val value: Boolean) : FactValue
}

object FactKeys {
    const val CALENDAR_BUSY = "calendar.busy"
}

/** Current facts, one per key; a new observation supersedes the old one. History lives in the journal. */
interface FactStore {
    fun get(key: String): Fact?
    fun put(fact: Fact)
    fun all(): List<Fact>
}

class InMemoryFactStore : FactStore {
    private val facts = linkedMapOf<String, Fact>()
    override fun get(key: String) = facts[key]
    override fun put(fact: Fact) { facts[fact.key] = fact }
    override fun all() = facts.values.toList()
}

/** A JSON file, replaced atomically on every write. */
class FileFactStore(private val file: File) : FactStore {
    private val facts = linkedMapOf<String, Fact>()
    private val serializer = ListSerializer(Fact.serializer())

    init {
        if (file.exists()) {
            runCatching { Journal.JSON.decodeFromString(serializer, file.readText()) }
                .getOrDefault(emptyList())
                .forEach { facts[it.key] = it }
        }
    }

    override fun get(key: String) = facts[key]
    override fun all() = facts.values.toList()
    override fun put(fact: Fact) {
        facts[fact.key] = fact
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(Journal.JSON.encodeToString(serializer, facts.values.toList()))
        check(tmp.renameTo(file)) { "could not replace ${file.name}" }
    }
}

/** The fixed inbound question vocabulary. There is no free-text question (CLAUDE.md hard rule). */
@Serializable
sealed interface Question {
    @Serializable @SerialName("free_at")
    data class FreeAt(val start: SInstant, val durationMinutes: Int) : Question
}

/** Every decline, whatever the reason, is the same NOT_AVAILABLE, so a refusal carries no signal. */
@Serializable
enum class InboundAnswer { FREE, BUSY, NOT_AVAILABLE }
