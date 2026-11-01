package io.github.greg7gkb.deputy

import android.app.Application
import deputy.broker.Artifacts
import deputy.broker.Broker
import deputy.broker.FileFactStore
import deputy.broker.FileJournalStorage
import deputy.broker.Principal
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
    companion object {
        val WEB_LINK_BASE = "https://${BuildConfig.LINK_HOST}${BuildConfig.LINK_PATH}"
        /** Links Deputy accepts: the https App Link form it writes, and the custom-scheme form. */
        val LINK_BASES = listOf(WEB_LINK_BASE, Artifacts.CUSTOM_BASE)
    }

    lateinit var broker: Broker
        private set
    lateinit var planner: StubPlanner
        private set
    lateinit var lab: Lab
        private set

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        val dir = File(filesDir, "broker")
        broker = Broker(
            journalStorage = FileJournalStorage(File(dir, "journal.tsv")),
            facts = FileFactStore(File(dir, "facts.json")),
            calendar = AndroidCalendarAdapter(this),
            linkBase = WEB_LINK_BASE,
        )
        planner = StubPlanner(broker.agentPort(Principal.Assistant), broker.zone)
        lab = Lab(this, broker)
        // Keys expire with time, not events; refresh what the UI shows twice a minute.
        scope.launch { while (true) { delay(30_000); broker.tick() } }
    }
}
