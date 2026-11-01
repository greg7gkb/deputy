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
class GmailNotConnected(message: String = "Gmail isn't connected: connect it in Lab.") : IllegalStateException(message)

/**
 * Gmail authorization through Play Services' Authorization API (D25). Play Services holds the
 * grant and refreshes access tokens; Deputy asks for one when it reads and never writes one to
 * disk. Two scopes: gmail.readonly to read and gmail.send to send replies (D27). Neither can
 * change or delete mail. Each call asks only for the scope it needs.
 *
 * The Authorization API does allow asking for scopes incrementally, but sending runs inside the
 * broker with no Activity to show a consent screen. So [connect], the one place with an Activity,
 * asks for both; reading and sending then authorize silently with their own scope.
 */
object GmailAuth {
    const val READ = "https://www.googleapis.com/auth/gmail.readonly"
    const val SEND = "https://www.googleapis.com/auth/gmail.send"

    /** What [connect] found: already connected, or the consent screen the Activity must launch. */
    sealed interface Start {
        data object Connected : Start
        class NeedsConsent(val pendingIntent: PendingIntent) : Start
    }

    /** Last outcome seen this process. Memory only: a hint for Lab, not proof (see [status]). */
    @Volatile var connectedHint: Boolean = false
        private set

    /**
     * Starts connecting (or reconnecting, to add sending). Shows nothing itself: if consent is
     * needed, the caller launches the intent.
     */
    suspend fun connect(context: Context): Start {
        val result = authorize(context, READ, SEND)
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
        val ok = result?.accessToken != null
        if (ok) connectedHint = true
        return ok
    }

    /** Reading connected or not, checked silently against Play Services. Never shows UI. */
    suspend fun status(context: Context): Boolean = silently(context, READ)

    /** Sending connected or not (a Gmail connected before replies existed lacks it). Never shows UI. */
    suspend fun sendStatus(context: Context): Boolean = silently(context, SEND)

    private suspend fun silently(context: Context, scope: String) = try {
        !authorize(context, scope).hasResolution()
    } catch (_: Exception) {
        false
    }

    /** A fresh token for reading, silently. Throws [GmailNotConnected] if the user must consent first. */
    internal suspend fun readToken(context: Context): String = token(context, READ, GmailNotConnected())

    /** A fresh token for sending, silently. Throws [GmailNotConnected] if sending was never granted. */
    internal suspend fun sendToken(context: Context): String =
        token(context, SEND, GmailNotConnected("Gmail sending isn't connected: reconnect Gmail in Lab."))

    private suspend fun token(context: Context, scope: String, missing: GmailNotConnected): String {
        val result = authorize(context, scope)
        if (result.hasResolution()) throw missing
        return result.accessToken ?: throw missing
    }

    /** Drops a token Gmail rejected, so the next [accessToken] call gets a new one. */
    internal suspend fun clear(context: Context, token: String) {
        runCatching { Identity.getAuthorizationClient(context).clearToken(ClearTokenRequest.builder().setToken(token).build()).await() }
    }

    private suspend fun authorize(context: Context, vararg scopes: String): AuthorizationResult {
        val request = AuthorizationRequest.builder().setRequestedScopes(scopes.map(::Scope)).build()
        val result = Identity.getAuthorizationClient(context).authorize(request).await()
        // The hint tracks reading, which is what "connected" means in Lab.
        if (READ in scopes && (SEND !in scopes || !result.hasResolution())) {
            connectedHint = !result.hasResolution() && result.accessToken != null
        }
        return result
    }
}

/** Task.await without adding kotlinx-coroutines-play-services for one function. */
private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
    addOnSuccessListener { cont.resume(it) }
    addOnFailureListener { cont.resumeWithException(it) }
    addOnCanceledListener { cont.cancel() }
}
