package io.github.greg7gkb.deputy.mail

import android.content.Context
import deputy.broker.MailAdapter
import deputy.broker.RawMessage
import deputy.reader.SampleMail
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * A made-up inbox for the emulator and for demos (the mail counterpart of the test calendar):
 * [SampleMail], received relative to when it was seeded. Remembers only the seed time.
 */
class TestInbox(context: Context) : MailAdapter {
    private val prefs = context.getSharedPreferences("test_inbox", Context.MODE_PRIVATE)
    override val source = "Test inbox on this phone"

    val seededAt: Instant? get() = prefs.getLong(KEY, 0L).takeIf { it > 0 }?.let(Instant::fromEpochMilliseconds)
    fun seed(): Int {
        prefs.edit().putLong(KEY, Clock.System.now().toEpochMilliseconds()).apply()
        return SampleMail.inbox(Clock.System.now()).size
    }

    override suspend fun recent(since: Instant, max: Int): List<RawMessage> {
        val at = seededAt ?: error("The test inbox is empty. Seed it in Lab.")
        return SampleMail.inbox(at).filter { it.receivedAt >= since }.sortedByDescending { it.receivedAt }.take(max)
    }

    /** Nothing leaves the phone: the reply is counted, and Activity shows what would have gone. */
    override suspend fun sendReply(adapterRef: String, to: String, body: String): String {
        val n = prefs.getInt(SENT, 0) + 1
        prefs.edit().putInt(SENT, n).apply()
        return "test-reply-$n"
    }

    private companion object {
        const val KEY = "seeded_at"
        const val SENT = "replies_sent"
    }
}

/**
 * Which mailbox the broker reads: the test inbox (the default) or Gmail. The person picks it in
 * Lab; the broker sees one [MailAdapter] whose [source] names the current one in Activity.
 */
class MailSwitch(context: Context, val test: TestInbox, val gmail: GmailAdapter) : MailAdapter {
    private val prefs = context.getSharedPreferences("mail_source", Context.MODE_PRIVATE)
    var useGmail: Boolean
        get() = prefs.getBoolean("gmail", false)
        set(value) { prefs.edit().putBoolean("gmail", value).apply() }

    private val current get() = if (useGmail) gmail else test
    override val source get() = current.source
    override suspend fun recent(since: Instant, max: Int) = current.recent(since, max)
    // A reply goes back through the mailbox the message came from: test refs never reach Gmail.
    override suspend fun sendReply(adapterRef: String, to: String, body: String) =
        (if (adapterRef.startsWith("sample-")) test else gmail).sendReply(adapterRef, to, body)
}
