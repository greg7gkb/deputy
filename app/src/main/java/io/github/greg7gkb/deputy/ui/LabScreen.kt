package io.github.greg7gkb.deputy.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import deputy.broker.BrokerSnapshot
import deputy.broker.Fact
import deputy.broker.present.FactsView
import deputy.broker.InboundAnswer
import deputy.broker.Provenance
import deputy.broker.present.Format
import io.github.greg7gkb.deputy.DeputyApp
import kotlinx.datetime.DayOfWeek
import java.time.LocalTime
import java.time.format.DateTimeFormatter

private data class Sim(val label: String, val explain: String, val run: () -> Unit)

/**
 * Phase 0 test bench. The askers here are simulated, but every question and key goes through
 * the real broker, so what it does shows up in Activity and the Keyring like anything else.
 */
@Composable
fun LabScreen(app: DeputyApp, snapshot: BrokerSnapshot, log: SnapshotStateList<String>, onShowAsk: () -> Unit) {
    val clock = DateTimeFormatter.ofPattern("HH:mm:ss")
    fun record(label: String, block: suspend () -> String) {
        app.act(block) { result ->
            val text = result.getOrElse { "failed: ${it.message ?: it::class.simpleName}" }
            log.add(0, "${LocalTime.now().format(clock)}  $label → $text")
        }
    }
    fun InboundAnswer.text() = name.lowercase().replace('_', ' ')
    fun zane(day: DayOfWeek, hour: Int, label: String) = record(label) { app.lab.zaneAsks(day, hour).text() }

    val calendar = listOf(
        Sim("Seed test calendar", "Adds made-up events to a local test calendar on this phone.") {
            record("Seed test calendar") { "added ${plural(app.lab.seedCalendar(), "event")}" }
        },
        Sim("Refresh calendar facts", "Reads busy times (only busy times) into what Deputy knows.") {
            record("Refresh calendar facts") { app.broker.user.refreshFacts().summary() }
        },
    )
    val inbound = listOf(
        Sim("Let Zane ask", "Gives Zane's Deputy a key to ask about weekday evenings.") {
            record("Let Zane ask") { app.lab.pairZane().summary() }
        },
        Sim("Zane asks: free Tue 7 pm?", "Inside his window, answered from facts.") { zane(DayOfWeek.TUESDAY, 19, "Zane asks: free Tue 7 pm?") },
        Sim("Zane asks: free Wed 7 pm?", "Inside his window, answered from facts.") { zane(DayOfWeek.WEDNESDAY, 19, "Zane asks: free Wed 7 pm?") },
        Sim("Zane asks: free Sat 2 pm?", "Outside his window, so the uniform decline.") { zane(DayOfWeek.SATURDAY, 14, "Zane asks: free Sat 2 pm?") },
        Sim("An unpaired Deputy asks", "No key at all. Same reply as every decline.") {
            record("An unpaired Deputy asks") { app.lab.strangerAsks().text() }
        },
        Sim("Zane asks 8 times this week", "Runs past his weekly question budget.") {
            record("Zane asks 8 times") { app.lab.zaneAsksAllWeek().joinToString { it.text() } }
        },
    )
    val planner = listOf(
        Sim("A script tries to break the rules", "Gets a 10-minute calendar key, then tries to misuse it and keys it doesn't hold. Watch it on Ask.") {
            record("Planner probe") { app.runProbe(); "finished, see Ask and Activity" }
            onShowAsk()
        },
    )

    ScreenColumn {
        item { ScreenHeader("Lab", "Simulations for Phase 0. The askers are pretend; the broker answering them is real.") }
        item { SimGroup("Calendar", calendar) }
        item { SimGroup("Someone else's Deputy asks", inbound) }
        item { SimGroup("Planner", planner) }
        if (log.isNotEmpty()) item { ResultLog(log) }
        item {
            Column {
                SectionLabel("What Deputy knows")
                Text("Kept on this phone. Each fact says where it came from and when.", style = Type.Sub.copy(fontSize = 12.5.sp))
            }
        }
        if (snapshot.facts.isEmpty()) item { DCard { Text("Nothing yet. Refresh calendar facts to read busy times.", style = Type.Sub) } }
        items(snapshot.facts, key = { it.key }) { FactCard(it, snapshot) }
    }
}

@Composable
private fun SimGroup(title: String, sims: List<Sim>) {
    Column {
        SectionLabel(title)
        VSpace(8.dp)
        DCard(padding = 0.dp) {
            sims.forEachIndexed { i, sim ->
                if (i > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(Palette.Line))
                Row(Modifier.padding(horizontal = 14.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(sim.label, style = Type.Body)
                        Text(sim.explain, style = Type.Sub.copy(fontSize = 12.5.sp))
                    }
                    Spacer(Modifier.width(10.dp))
                    SmallButton("Run", onClick = sim.run)
                }
            }
        }
    }
}

@Composable
private fun ResultLog(log: SnapshotStateList<String>) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionLabel("Results", Modifier.weight(1f))
            SmallButton("Clear", onClick = { log.clear() })
        }
        VSpace(8.dp)
        DCard(background = Palette.Note, border = Palette.Note, padding = 12.dp) {
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                log.take(20).forEach { Text(it, style = Type.Mono.copy(color = Palette.Ink)) }
            }
        }
    }
}

/** A fact as a person sees it, worded by FactsView. Busy intervals are summarised, never listed. */
@Composable
private fun FactCard(fact: Fact, snapshot: BrokerSnapshot) {
    val view = FactsView.describe(fact, snapshot.now, snapshot.zone)
    val style = when (fact.provenance) {
        Provenance.ATTESTED -> BadgeStyle.ATTESTED
        Provenance.OBSERVED -> BadgeStyle.OBSERVED
        Provenance.INFERRED -> BadgeStyle.INFERRED
        Provenance.ASSERTED -> BadgeStyle.PLAIN
    }
    DCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(view.title, style = Type.CardTitle, modifier = Modifier.weight(1f))
            Badge(view.provenance, style)
        }
        Text(view.detail, style = Type.Sub, modifier = Modifier.padding(top = 3.dp))
        Text(
            "${fact.key} · ${view.sensitivity} · ${view.freshness}",
            style = Type.Mono.copy(color = if (view.fresh) Palette.Muted else Palette.Red),
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}
