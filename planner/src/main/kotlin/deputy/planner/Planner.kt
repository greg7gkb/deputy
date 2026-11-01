package deputy.planner

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * What a planner wrote, step by step, for the Ask screen. Shared by every planner so their steps
 * interleave in one history; each step says which planner wrote it.
 */
class Transcript {
    /** [conversation] groups steps: a request, any back-and-forth, and what came of it. */
    data class Step(val kind: Kind, val text: String, val detail: String? = null, val source: String = "", val conversation: Int = 0) {
        enum class Kind { USER, SAYS, PROPOSES, BROKER, INFO }
    }

    private val _steps = MutableStateFlow<List<Step>>(emptyList())
    val steps: StateFlow<List<Step>> = _steps.asStateFlow()
    private var current = 0

    /** Steps logged from now on belong to a new conversation. */
    fun newConversation() { current++ }
    fun log(step: Step) { _steps.value = _steps.value + step.copy(conversation = current) }
    fun clear() { _steps.value = emptyList(); current++ }
}

/**
 * Something that turns a request into proposals. It holds only an AgentPort, so swapping one
 * planner for another changes what proposes, never what is allowed (docs/decisions.md D5, D20).
 */
interface Planner {
    /** Shown on the Ask screen, e.g. "Claude" or "scripted". */
    val name: String
    val transcript: Transcript
    val busy: StateFlow<Boolean>
    val suggestions: List<String>
    /** Continues the current conversation if there is one, else starts a new one. */
    suspend fun handle(request: String)
    /** Forget the conversation so the next request starts fresh. */
    fun reset() {}
    /** True while a follow-up would continue a conversation (the UI offers a reply box). */
    val inConversation: Boolean get() = false
}
