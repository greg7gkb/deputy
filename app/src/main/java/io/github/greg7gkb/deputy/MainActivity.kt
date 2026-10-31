package io.github.greg7gkb.deputy

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import io.github.greg7gkb.deputy.ui.DeputyRoot
import io.github.greg7gkb.deputy.ui.DeputyTheme

class MainActivity : ComponentActivity() {
    private val calendarPermissions = arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
    private var calendarGranted by mutableStateOf(false)
    private val askForCalendar = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { checkCalendar() }

    override fun onCreate(savedInstanceState: Bundle?) {
        val bars = SystemBarStyle.light(BACKGROUND, BACKGROUND)
        enableEdgeToEdge(statusBarStyle = bars, navigationBarStyle = bars)
        super.onCreate(savedInstanceState)
        val app = application as DeputyApp
        checkCalendar()
        if (!calendarGranted && savedInstanceState == null) askForCalendar.launch(calendarPermissions)
        setContent {
            DeputyTheme {
                DeputyRoot(app, calendarGranted, onRequestCalendar = { askForCalendar.launch(calendarPermissions) })
            }
        }
    }

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
