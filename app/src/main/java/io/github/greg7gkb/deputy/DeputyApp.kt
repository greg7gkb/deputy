package io.github.greg7gkb.deputy

import android.app.Application
import android.util.Log
import deputy.broker.Artifacts
import deputy.broker.Broker
import deputy.broker.FileFactStore
import deputy.broker.FileJournalStorage
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import deputy.broker.Direction
import deputy.broker.GrantTemplates
import deputy.broker.Principal
import deputy.planner.ClaudePlanner
import deputy.planner.Planner
import deputy.planner.Transcript
import io.github.greg7gkb.deputy.agent.StubPlanner
import io.github.greg7gkb.deputy.calendar.AndroidCalendarAdapter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

/**
 * Hosts the broker for the app's lifetime. The broker's files live in app-private storage,
 * which the Android sandbox keeps from every other app (design.md section 4).
 *
 * The UI gets [broker]'s UserPort. The planner gets an AgentPort and nothing else.
 */
class DeputyApp : Application() {
    /**
     * Runs the rule-breaking script. You (through the Lab) first give it a short-lived calendar key,
     * so it can also try to misuse a key it really holds, then it tries everything it shouldn't.
     */
    suspend fun runProbe() {
        broker.user.mintGrant(PROBE, Direction.OUTBOUND, GrantTemplates.calendarForScheduling().copy(title = "Key for the scripted test", ttlMinutes = 10))
        probe.probeLimitsWithLeakedHandle(lab.zaneGrantId())
    }

    /**
     * Every Claude request and response, as JSON, to logcat under [LLM_TAG] (D21). Debug builds
     * only; logcat lines are capped near 4 KB, so long messages are split and numbered.
     */
    private fun logWire(message: String) {
        val parts = message.chunked(3_500)
        parts.forEachIndexed { i, part -> Log.d(LLM_TAG, if (parts.size == 1) part else "[${i + 1}/${parts.size}] $part") }
    }

    companion object {
        const val LLM_TAG = "DeputyLLM"

        /** The rule-breaking script's identity. Never the assistant's. */
        val PROBE = Principal(Principal.Kind.ASSISTANT, "scripted-test", "Scripted test")

        val WEB_LINK_BASE = "https://${BuildConfig.LINK_HOST}${BuildConfig.LINK_PATH}"
        /** Links Deputy accepts: the https App Link form it writes, and the custom-scheme form. */
        val LINK_BASES = listOf(WEB_LINK_BASE, Artifacts.CUSTOM_BASE)
    }

    lateinit var broker: Broker
        private set
    /** Claude when an API key is configured (D20); the keyword script otherwise. */
    lateinit var planner: Planner
        private set
    /** A deliberately misbehaving script, run from the Lab. Its own principal, so its refusals are never Claude's. */
    lateinit var probe: StubPlanner
        private set
    val transcript = Transcript()
    val calendarAdapter by lazy { AndroidCalendarAdapter(this, BuildConfig.WRITE_CALENDAR) }
    lateinit var lab: Lab
        private set

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        val dir = File(filesDir, "broker")
        broker = Broker(
            journalStorage = FileJournalStorage(File(dir, "journal.tsv")),
            facts = FileFactStore(File(dir, "facts.json")),
            calendar = calendarAdapter,
            linkBase = WEB_LINK_BASE,
        )
        val assistant = broker.agentPort(Principal.Assistant)
        planner = if (BuildConfig.ANTHROPIC_API_KEY.isNotEmpty()) {
            ClaudePlanner(
                assistant, AnthropicOkHttpClient.builder().apiKey(BuildConfig.ANTHROPIC_API_KEY).build(), broker.zone, transcript,
                wireLog = if (BuildConfig.DEBUG) ::logWire else null,
            )
        } else {
            StubPlanner(assistant, broker.zone, transcript)
        }
        probe = StubPlanner(broker.agentPort(PROBE), broker.zone, transcript, name = "scripted test")
        lab = Lab(this, broker)
        // Keys expire with time, not events; refresh what the UI shows twice a minute.
        scope.launch { while (true) { delay(30_000); broker.tick() } }
    }
}
