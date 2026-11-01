package deputy.planner

import com.anthropic.client.AnthropicClient
import com.anthropic.core.JsonValue
import com.anthropic.core.jsonMapper
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.errors.AnthropicIoException
import com.anthropic.models.messages.CacheControlEphemeral
import com.anthropic.models.messages.ContentBlockParam
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.MessageParam
import com.anthropic.models.messages.OutputConfig
import com.anthropic.models.messages.StopReason
import com.anthropic.models.messages.ToolResultBlockParam
import com.anthropic.models.messages.ToolUseBlock
import com.fasterxml.jackson.core.type.TypeReference
import deputy.broker.AgentPort
import deputy.broker.Journal
import deputy.broker.Outcome
import deputy.planner.Transcript.Step
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock

/**
 * The cloud planner: Claude through the Messages API with tool use (docs/decisions.md D20).
 *
 * It sees the request, the tool list, its own keys and typed results. Every tool call goes
 * through [port], so the broker checks it, may ask the user, and is the only thing that acts.
 * The loop is hand-written rather than the SDK's tool runner because a call can wait minutes for
 * the user's tap, and because nothing should execute except through the broker.
 */
class ClaudePlanner(
    private val port: AgentPort,
    private val client: AnthropicClient,
    private val zone: TimeZone,
    override val transcript: Transcript,
    private val clock: Clock = Clock.System,
    val model: String = DEFAULT_MODEL,
    private val maxTurns: Int = 12,
    /** Plain-text record of what crosses the wire: messages, tool calls, results (D21). */
    private val readableLog: ((String) -> Unit)? = null,
    /** Every request and response as the exact JSON sent and received (D21). Never sees the API key. */
    private val rawLog: ((String) -> Unit)? = null,
) : Planner {
    override val name = "Claude"
    override val suggestions = listOf(
        "Find a time with Dana next week",
        "Hold an hour for a dentist appointment Thursday afternoon",
        "Send Dana an invite for a catch-up next week",
    )

    private val _busy = MutableStateFlow(false)
    override val busy: StateFlow<Boolean> = _busy.asStateFlow()
    private val running = Mutex()

    /**
     * The conversation so far, so a follow-up ("Monday, please") has context. Append-only, as
     * preserved thinking requires; it starts over on [reset], after [IDLE_RESET] without a
     * request, or after anything leaves it half-finished.
     */
    private val history = mutableListOf<MessageParam>()
    private var lastRequestAt: kotlin.time.Instant? = null
    private var exchange = 0

    override fun reset() { history.clear(); lastRequestAt = null }
    override val inConversation get() = history.isNotEmpty()

    override suspend fun handle(request: String) = running.withLock {
        _busy.value = true
        try {
            val now = clock.now()
            if (lastRequestAt?.let { now - it > IDLE_RESET } == true) reset()
            if (history.isEmpty()) transcript.newConversation()
            log(Step.Kind.USER, request)
            lastRequestAt = now
            if (!converse(request)) reset()
        } catch (e: AnthropicServiceException) {
            reset()
            log(Step.Kind.INFO, "Claude API error ${e.statusCode()}: ${e.message?.take(200)}. Starting a fresh conversation.")
        } catch (e: AnthropicIoException) {
            reset()
            log(Step.Kind.INFO, "Couldn't reach the Claude API: ${e.message?.take(200)}. Starting a fresh conversation.")
        } finally {
            _busy.value = false
        }
    }

    /** Returns true if the conversation ended cleanly and can be continued. */
    private suspend fun converse(request: String): Boolean {
        // The date line goes in the user turn, not the system prompt, so the system prompt and
        // tools stay byte-identical across requests and cache.
        val now = clock.now().toLocalDateTime(zone)
        val messages = history
        messages += MessageParam.ofUser("Now: ${now.dayOfWeek.name.lowercase()} ${now.toString().take(16)} in $zone.\n\n$request")
        exchange++
        readableLog?.invoke("── exchange $exchange ${if (messages.size > 1) "(continuing)" else "(new conversation)"} ──")
        readableLog?.invoke("You → Claude: $request")
        var inputTokens = 0L
        var outputTokens = 0L
        var cachedTokens = 0L
        repeat(maxTurns) {
            val params = MessageCreateParams.builder()
                .model(model)
                .maxTokens(16_000L)
                .system(SYSTEM_PROMPT)
                .apply { Tools.definitions.forEach { addTool(it) } }
                .messages(messages)
                // Cache the stable prefix (tools and system prompt) across the loop's rounds.
                .cacheControl(CacheControlEphemeral.builder().build())
                .outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.MEDIUM).build())
                // Server-side refusal fallback: if a safety classifier declines, the API retries on
                // the model Anthropic recommends for that category instead of returning a refusal.
                .putAdditionalHeader("anthropic-beta", "server-side-fallback-2026-07-01")
                .putAdditionalBodyProperty("fallbacks", JsonValue.from("default"))
                .build()
            rawLog?.invoke("→ exchange $exchange, round ${it + 1}, request: " + jsonMapper().writeValueAsString(params._body()))
            val response = withContext(Dispatchers.IO) { client.messages().create(params) }
            rawLog?.invoke("← exchange $exchange, round ${it + 1}, response: " + jsonMapper().writeValueAsString(response))
            readableLog?.let { logReadable(it, response) }
            inputTokens += response.usage().inputTokens() + response.usage().cacheReadInputTokens().orElse(0L) +
                response.usage().cacheCreationInputTokens().orElse(0L)
            cachedTokens += response.usage().cacheReadInputTokens().orElse(0L)
            outputTokens += response.usage().outputTokens()
            // Append the whole response (thinking blocks included) unchanged: the history is
            // append-only, which preserved thinking requires.
            messages += response.toParam()
            response.content().forEach { block -> block.text().ifPresent { if (it.text().isNotBlank()) log(Step.Kind.SAYS, it.text().trim()) } }

            when (response.stopReason().orElse(null)) {
                StopReason.TOOL_USE -> {
                    val results = response.content().mapNotNull { it.toolUse().orElse(null) }.map { call ->
                        execute(call).also { result ->
                            readableLog?.invoke("Deputy → Claude (${call.name()}): " + (result.toolResult().orElse(null)?.content()?.orElse(null)?.string()?.orElse(null) ?: "?"))
                        }
                    }
                    messages += MessageParam.ofUserOfContentBlockParams(results)
                }
                StopReason.REFUSAL -> { finish("Claude declined this request.", inputTokens, outputTokens, cachedTokens); return false }
                StopReason.MAX_TOKENS -> { finish("Claude ran out of room before finishing.", inputTokens, outputTokens, cachedTokens); return false }
                else -> { finish(null, inputTokens, outputTokens, cachedTokens); return true }
            }
        }
        finish("Stopped after $maxTurns rounds without finishing.", inputTokens, outputTokens, cachedTokens)
        return false
    }

    private fun logReadable(log: (String) -> Unit, response: com.anthropic.models.messages.Message) {
        response.content().forEach { block ->
            block.text().ifPresent { if (it.text().isNotBlank()) log("Claude: ${it.text().trim()}") }
            block.toolUse().ifPresent { log("Claude → Deputy: ${it.name()} ${jsonMapper().writeValueAsString(it._input())}") }
            block.thinking().ifPresent { if (it.thinking().isNotBlank()) log("Claude (thinking): ${it.thinking().trim()}") }
        }
        val u = response.usage()
        log("   [stop: ${response.stopReason().map { it.toString() }.orElse("?")} · ${u.inputTokens() + u.cacheReadInputTokens().orElse(0L) + u.cacheCreationInputTokens().orElse(0L)} tokens in, ${u.outputTokens()} out]")
    }

    /** One tool call, through the broker. Waits for the user when the broker asks them. */
    private suspend fun execute(call: ToolUseBlock): ContentBlockParam {
        val input: Map<String, Any?> = runCatching { call._input().convert(object : TypeReference<Map<String, Any?>>() {}) }.getOrNull() ?: emptyMap()
        val parsed = try {
            Tools.parse(call.name(), input, zone)
        } catch (e: IllegalArgumentException) {
            log(Step.Kind.PROPOSES, call.name(), input.toString())
            log(Step.Kind.BROKER, "not understood: ${e.message}")
            return toolResult(call.id(), """{"status":"invalid_input","detail":"${e.message}"}""", isError = true)
        }
        val content = when (parsed) {
            Tools.Call.ListKeys -> {
                log(Step.Kind.PROPOSES, "list_my_keys")
                Tools.keys(port.myGrants(), zone).also { log(Step.Kind.BROKER, "listed keys") }
            }
            is Tools.Call.RequestKey -> {
                log(Step.Kind.PROPOSES, "request key \"${parsed.request.title}\"", Journal.JSON.encodeToString(deputy.broker.GrantRequest.serializer(), parsed.request))
                Tools.result(settle(port.requestGrant(parsed.request)), zone)
            }
            is Tools.Call.Propose -> {
                log(Step.Kind.PROPOSES, parsed.action.capability.value, Journal.JSON.encodeToString(deputy.broker.Action.serializer(), parsed.action))
                Tools.result(settle(port.propose(parsed.keyId, parsed.action)), zone)
            }
        }
        return toolResult(call.id(), content)
    }

    private suspend fun settle(first: Outcome): Outcome {
        var outcome = first
        if (outcome is Outcome.AwaitingUser) {
            log(Step.Kind.BROKER, "waiting for you to decide")
            outcome = port.await(outcome.requestId)
        }
        log(Step.Kind.BROKER, summary(outcome))
        return outcome
    }

    private fun summary(o: Outcome): String = when (o) {
        is Outcome.Done -> "done"
        is Outcome.GrantIssued -> "key issued"
        is Outcome.Refused -> "refused (${o.reason.name.lowercase()}): ${o.detail}"
        is Outcome.Failed -> "failed: ${o.error}"
        is Outcome.AwaitingUser -> "waiting for you"
        is Outcome.Ok -> "ok"
    }

    private fun finish(note: String?, inputTokens: Long, outputTokens: Long, cachedTokens: Long = 0) {
        note?.let { log(Step.Kind.INFO, it) }
        log(Step.Kind.INFO, "$model · $inputTokens tokens in ($cachedTokens from cache), $outputTokens out")
    }

    private fun toolResult(id: String, content: String, isError: Boolean = false) = ContentBlockParam.ofToolResult(
        ToolResultBlockParam.builder().toolUseId(id).content(content).isError(isError).build(),
    )

    private fun log(kind: Step.Kind, text: String, detail: String? = null) = transcript.log(Step(kind, text, detail, source = name))

    companion object {
        const val DEFAULT_MODEL = "claude-sonnet-5-5"
        val IDLE_RESET = kotlin.time.Duration.parse("30m")

        val SYSTEM_PROMPT = """
            You are the planning half of Deputy, an app on the user's Android phone. You turn the user's request into calls to the tools you have. You cannot act on your own: every tool call goes to Deputy's broker, plain code that checks it against a key the user approved, asks the user first when an action can't be undone, and refuses anything outside a key's limits. The user sees every call and every refusal.

            Keys: each tool except list_my_keys and request_key needs a key_id. Check list_my_keys first and reuse an active key that covers the call. If none does, use request_key; the user sees exactly what it allows and decides. If they decline, stop and say so briefly.

            When the broker refuses a call, read the detail and either adjust within the key's limits (for example a narrower date range) or tell the user plainly what the limit was. Don't try to get around a limit, and don't ask for a broader key than the request needs.

            You only ever see free times, never what the user is busy with, and that is by design. Times you send and receive are local to the user, as YYYY-MM-DDTHH:MM. Working hours mean 9:00 to 17:00 unless the user says otherwise.

            The user can reply to you, so if you need a choice from them, ask for it and stop. When you're done, reply in one or two short sentences: what you did, or what stopped you. Plain sentences, no lists or markdown, because the reply is shown as plain text on a phone.
        """.trimIndent()
    }
}
