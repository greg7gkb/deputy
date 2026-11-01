package io.github.greg7gkb.deputy

import android.Manifest
import android.accounts.AccountManager
import android.content.Intent
import android.content.pm.PackageManager
import deputy.broker.Artifacts
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import io.github.greg7gkb.deputy.mail.GmailAuth
import kotlinx.coroutines.launch
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import io.github.greg7gkb.deputy.ui.DeputyRoot
import io.github.greg7gkb.deputy.ui.DeputyTheme

class MainActivity : ComponentActivity() {
    private val calendarPermissions = arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
    private var calendarGranted by mutableStateOf(false)
    /** The artifact a deputy://activity/<id> link asked to see, until the person moves on. */
    private var focusArtifact by mutableStateOf<String?>(null)
    private val askForCalendar = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { checkCalendar() }
    /** Android's own account chooser; picking an account makes it visible to Deputy (for sync requests only). */
    private val chooseAccount = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { accountTick++ }
    /** Bumped after the chooser returns, so the UI re-checks account visibility. */
    private var accountTick by mutableStateOf(0)
    /** What the last Gmail connect attempt said, for Lab (D25). */
    private var gmailStatus by mutableStateOf<String?>(null)
    /** Google's consent screen for gmail.readonly. Deputy never sees the password or keeps the token. */
    private val gmailConsent = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { res ->
        gmailStatus = if (GmailAuth.finish(this, res.data)) "connected" else "not connected (consent was not given)"
    }

    private fun connectGmail() = lifecycleScope.launch {
        gmailStatus = try {
            when (val s = GmailAuth.connect(this@MainActivity)) {
                GmailAuth.Start.Connected -> "connected"
                is GmailAuth.Start.NeedsConsent -> {
                    gmailConsent.launch(IntentSenderRequest.Builder(s.pendingIntent.intentSender).build())
                    "waiting for Google's consent screen"
                }
            }
        } catch (e: Exception) {
            // ApiException 10 (DEVELOPER_ERROR) means no OAuth client matches this package and signing key.
            "couldn't connect: ${e.message ?: e::class.simpleName}. See docs/gmail-setup.md."
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val bars = SystemBarStyle.light(BACKGROUND, BACKGROUND)
        enableEdgeToEdge(statusBarStyle = bars, navigationBarStyle = bars)
        super.onCreate(savedInstanceState)
        val app = application as DeputyApp
        focusArtifact = linkedArtifact(intent)
        checkCalendar()
        if (!calendarGranted && savedInstanceState == null) askForCalendar.launch(calendarPermissions)
        setContent {
            DeputyTheme {
                DeputyRoot(
                    app, calendarGranted,
                    onRequestCalendar = { askForCalendar.launch(calendarPermissions) },
                    focusArtifact = focusArtifact,
                    onFocusDone = { focusArtifact = null },
                    accountTick = accountTick,
                    gmailStatus = gmailStatus,
                    onConnectGmail = { connectGmail() },
                    onChooseAccount = {
                        val target = app.calendarAdapter.writableCalendar()?.account
                        chooseAccount.launch(AccountManager.newChooseAccountIntent(
                            target, null, arrayOf(target?.type ?: "com.google"), null, null, null, null,
                        ))
                    },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        linkedArtifact(intent)?.let { focusArtifact = it }
    }

    /** Only well-formed Deputy links are accepted; anything else is ignored. */
    private fun linkedArtifact(intent: Intent?): String? = intent?.dataString?.let { Artifacts.parse(it, DeputyApp.LINK_BASES) }

    /** The person can grant or revoke in Settings while we're away, so re-check on every resume. */
    override fun onResume() {
        super.onResume()
        checkCalendar()
    }

    private fun checkCalendar() {
        calendarGranted = calendarPermissions.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }
    }

    private companion object {
        const val BACKGROUND = 0xFFF6F5F2.toInt()
    }
}
