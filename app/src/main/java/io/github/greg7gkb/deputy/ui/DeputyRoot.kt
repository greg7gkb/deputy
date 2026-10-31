package io.github.greg7gkb.deputy.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import deputy.broker.present.Narrator
import io.github.greg7gkb.deputy.DeputyApp

enum class Tab(val label: String) { ASK("Ask"), CAN_DO("Can do"), KEYRING("Keyring"), ACTIVITY("Activity"), LAB("Lab") }

/**
 * The whole app: five tabs over one broker snapshot, plus the approval sheet, which sits above
 * every tab because the broker can ask at any time (the planner suspends until you answer).
 */
@Composable
fun DeputyRoot(app: DeputyApp, calendarGranted: Boolean, onRequestCalendar: () -> Unit, focusArtifact: String? = null, onFocusDone: () -> Unit = {}) {
    val snapshot by app.broker.snapshots.collectAsStateWithLifecycle()
    val items = remember(snapshot) { Narrator.narrate(snapshot) }
    var tab by rememberSaveable { mutableStateOf(Tab.ASK) }
    val labLog = remember { mutableStateListOf<String>() }
    // A deputy://activity link opens Activity at that entry.
    LaunchedEffect(focusArtifact) { if (focusArtifact != null) tab = Tab.ACTIVITY }

    Box(Modifier.fillMaxSize().background(Palette.Background)) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))) {
            Box(Modifier.weight(1f)) {
                when (tab) {
                    Tab.ASK -> AskScreen(app, snapshot, items, calendarGranted, onRequestCalendar, onSeeActivity = { tab = Tab.ACTIVITY })
                    Tab.CAN_DO -> CanDoScreen()
                    Tab.KEYRING -> KeyringScreen(app, snapshot)
                    Tab.ACTIVITY -> ActivityScreen(app, snapshot, items, focusArtifact)
                    Tab.LAB -> LabScreen(app, snapshot, labLog, onShowAsk = { tab = Tab.ASK })
                }
            }
            TabBar(tab) { tab = it; onFocusDone() }
        }
        ApprovalSheet(app, snapshot)
    }
}

@Composable
private fun TabBar(selected: Tab, onSelect: (Tab) -> Unit) {
    Column(Modifier.fillMaxWidth().background(Palette.Background).navigationBarsPadding()) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(Palette.Line))
        Row(Modifier.fillMaxWidth()) {
            Tab.entries.forEach { t ->
                val on = t == selected
                Box(
                    Modifier.weight(1f).clickable { onSelect(t) }.padding(top = 14.dp, bottom = 16.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        t.label,
                        fontSize = 14.sp,
                        fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
                        color = if (on) Palette.Ink else Palette.Muted,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}
