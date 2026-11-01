package io.github.greg7gkb.deputy.mail

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.ClearTokenRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Task
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Thrown when Gmail needs the user's consent first. Its message is written for a person. */
class GmailNotConnected : IllegalStateException("Gmail isn't connected: connect it in Lab.")

/**
 * Gmail authorization through Play Services' Authorization API (D25). Play Services holds the
 * grant and refreshes access tokens; Deputy asks for one when it reads and never writes one to
 * disk. The only scope is gmail.readonly, so the adapter cannot send, change or delete mail.
 */
object GmailAuth {
    const val SCOPE = "https://www.googleapis.com/auth/gmail.readonly"

    /** What [connect] found: already connected, or the consent screen the Activity must launch. */
    sealed interface Start {
        data object Connected : Start
        class NeedsConsent(val pendingIntent: PendingIntent) : Start
    }

    /** Last outcome seen this process. Memory only: a hint for Lab, not proof (see [status]). */
    @Volatile var connectedHint: Boolean = false
        private set

    /** Starts connecting. Shows nothing itself: if consent is needed, the caller launches the intent. */
    suspend fun connect(context: Context): Start {
        val result = authorize(context)
        return if (result.hasResolution()) Start.NeedsConsent(checkNotNull(result.pendingIntent))
        else Start.Connected
    }

    /**
     * Completes [connect] from the consent screen's result intent. True if Gmail is now connected;
     * false if the user backed out or Play Services refused.
     */
    fun finish(context: Context, data: Intent?): Boolean {
        val result = try {
            Identity.getAuthorizationClient(context).getAuthorizationResultFromIntent(data)
        } catch (_: ApiException) {
            null
        }
        connectedHint = result?.accessToken != null
        return connectedHint
    }

    /** "Connected" or not, checked silently against Play Services. Never shows UI. */
    suspend fun status(context: Context): Boolean = try {
        !authorize(context).hasResolution()
    } catch (_: Exception) {
        false
    }

    /** A fresh access token, silently. Throws [GmailNotConnected] if the user must consent first. */
    internal suspend fun accessToken(context: Context): String {
        val result = authorize(context)
        if (result.hasResolution()) throw GmailNotConnected()
        return result.accessToken ?: throw GmailNotConnected()
    }

    /** Drops a token Gmail rejected, so the next [accessToken] call gets a new one. */
    internal suspend fun clear(context: Context, token: String) {
        runCatching { Identity.getAuthorizationClient(context).clearToken(ClearTokenRequest.builder().setToken(token).build()).await() }
    }

    private suspend fun authorize(context: Context): AuthorizationResult {
        val request = AuthorizationRequest.builder().setRequestedScopes(listOf(Scope(SCOPE))).build()
        val result = Identity.getAuthorizationClient(context).authorize(request).await()
        connectedHint = !result.hasResolution() && result.accessToken != null
        return result
    }
}

/** Task.await without adding kotlinx-coroutines-play-services for one function. */
private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
    addOnSuccessListener { cont.resume(it) }
    addOnFailureListener { cont.resumeWithException(it) }
    addOnCanceledListener { cont.cancel() }
}
