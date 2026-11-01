package io.github.greg7gkb.deputy.mail

import android.content.Context
import deputy.broker.MailAdapter
import deputy.broker.RawMessage
import io.github.greg7gkb.deputy.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.Charset
import java.util.Base64
import kotlin.time.Instant

/**
 * Tier 2: Gmail through its REST API, read-only (D25).
 *
 * Minimisation by construction (D9). Google can't scope a grant to a label: gmail.readonly can
 * read the whole mailbox. So the restriction is this class's: it lists only messages carrying
 * the one label the user set aside for Deputy ([labelName]), checks each fetched message still
 * carries it, and keeps only From, Subject, the received time and the plain-text body. No other
 * headers, no To or Cc, no attachments. Everything it returns is untrusted and goes only to the
 * broker's reader.
 */
class GmailAdapter(
    private val context: Context,
    private val labelName: String = BuildConfig.MAIL_LABEL,
) : MailAdapter {
    override val source = "Gmail, label \"$labelName\""

    /** Resolved once per process; the label's id doesn't change when it's renamed, its name does. */
    @Volatile private var labelId: String? = null

    /** Cheap, no network: what Play Services said last time this process asked. */
    fun isConnectedHint(): Boolean = GmailAuth.connectedHint

    override suspend fun recent(since: Instant, max: Int): List<RawMessage> = withContext(Dispatchers.IO) {
        val token = GmailAuth.accessToken(context)
        try {
            read(token, since, max)
        } catch (_: Unauthorized) {
            // Play Services can hand back a token Gmail has already expired. Drop it and retry once.
            GmailAuth.clear(context, token)
            read(GmailAuth.accessToken(context), since, max)
        }
    }

    private fun read(token: String, since: Instant, max: Int): List<RawMessage> {
        val label = labelId ?: resolveLabel(token).also { labelId = it }
        // after: takes epoch seconds and is exclusive, so ask from a second earlier and filter here.
        val q = URLEncoder.encode("after:${since.epochSeconds - 1}", "UTF-8")
        val list = get("$API/messages?labelIds=$label&maxResults=${max.coerceIn(1, 50)}&q=$q", token)
        val ids = (list["messages"] as? JsonArray).orEmpty().map { it.jsonObject.str("id") }
        return ids.mapNotNull { fetch(it, label, token) }
            .filter { it.receivedAt >= since }
            .sortedByDescending { it.receivedAt }
            .take(max)
    }

    private fun resolveLabel(token: String): String {
        val labels = (get("$API/labels", token)["labels"] as? JsonArray).orEmpty().map { it.jsonObject }
        // Gmail label names are unique ignoring case.
        return labels.firstOrNull { it.str("name").equals(labelName, ignoreCase = true) }?.str("id")
            ?: error("There's no Gmail label named \"$labelName\". Create it in Gmail and put the mail Deputy may read under it.")
    }

    /** One message, reduced to [RawMessage]. Null if it no longer carries Deputy's label. */
    private fun fetch(id: String, label: String, token: String): RawMessage? {
        // fields= asks Google to send only what's used here, plus the labels for the check below.
        val fields = URLEncoder.encode("id,labelIds,internalDate,payload(mimeType,headers,body/data,parts)", "UTF-8")
        val m = get("$API/messages/${URLEncoder.encode(id, "UTF-8")}?format=full&fields=$fields", token)
        val labels = (m["labelIds"] as? JsonArray).orEmpty().map { it.jsonPrimitive.content }
        if (label !in labels) return null
        val payload = m["payload"]?.jsonObject ?: return null
        val headers = (payload["headers"] as? JsonArray).orEmpty().map { it.jsonObject }
        fun header(name: String) = headers.firstOrNull { it.str("name").equals(name, ignoreCase = true) }?.str("value").orEmpty()
        val body = part(payload, "text/plain") ?: part(payload, "text/html")?.let(::stripHtml) ?: ""
        return RawMessage(
            adapterRef = m.str("id"),
            from = header("From"),
            subject = header("Subject"),
            body = body.trim().take(MAX_BODY),
            receivedAt = Instant.fromEpochMilliseconds(m.str("internalDate").toLong()),
        )
    }

    /** First inline part of [mime], depth first, decoded. Attachments carry no inline data, so they're never read. */
    private fun part(p: JsonObject, mime: String): String? {
        val type = p.str("mimeType").lowercase()
        if (type == mime) {
            val data = p["body"]?.jsonObject?.get("data")?.jsonPrimitive?.content
            if (!data.isNullOrEmpty()) return String(Base64.getUrlDecoder().decode(data), charset(p))
        }
        if (type.startsWith("multipart/")) {
            for (child in (p["parts"] as? JsonArray).orEmpty()) part(child.jsonObject, mime)?.let { return it }
        }
        return null
    }

    /** The part's declared charset, or UTF-8. Reads the part's Content-Type only. */
    private fun charset(p: JsonObject): Charset {
        val contentType = (p["headers"] as? JsonArray).orEmpty().map { it.jsonObject }
            .firstOrNull { it.str("name").equals("Content-Type", ignoreCase = true) }?.str("value").orEmpty()
        val name = Regex("charset=\"?([^\";\\s]+)", RegexOption.IGNORE_CASE).find(contentType)?.groupValues?.get(1)
        return runCatching { Charset.forName(name) }.getOrDefault(Charsets.UTF_8)
    }

    /** Crude on purpose: the reader wants words, not layout. */
    private fun stripHtml(html: String): String = html
        .replace(Regex("(?is)<(style|script|head)[^>]*>.*?</\\1>"), " ")
        .replace(Regex("(?i)<br\\s*/?>|</(p|div|tr|li|h\\d)>"), "\n")
        .replace(Regex("<[^>]*>"), " ")
        .replace("&nbsp;", " ").replace("&lt;", "<").replace("&gt;", ">")
        .replace("&quot;", "\"").replace("&#39;", "'").replace("&amp;", "&")
        .replace(Regex("[ \\t]+"), " ")
        .replace(Regex("\\s*\\n\\s*"), "\n")

    private class Unauthorized : Exception()

    /** One GET. Error messages carry Google's reason, never the token. */
    private fun get(url: String, token: String): JsonObject {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = TIMEOUT_MS
        c.readTimeout = TIMEOUT_MS
        c.setRequestProperty("Authorization", "Bearer $token")
        c.setRequestProperty("Accept", "application/json")
        try {
            val code = c.responseCode
            if (code == 401) throw Unauthorized()
            if (code !in 200..299) {
                val reason = runCatching {
                    Json.parseToJsonElement(c.errorStream.bufferedReader().use { it.readText() })
                        .jsonObject["error"]?.jsonObject?.str("message")
                }.getOrNull().orEmpty().take(200)
                error("Gmail refused the request ($code)${if (reason.isEmpty()) "" else ": $reason"}")
            }
            return Json.parseToJsonElement(c.inputStream.bufferedReader().use { it.readText() }).jsonObject
        } finally {
            c.disconnect()
        }
    }

    private fun JsonObject.str(key: String): String = this[key]?.jsonPrimitive?.content.orEmpty()

    companion object {
        private const val API = "https://gmail.googleapis.com/gmail/v1/users/me"
        private const val TIMEOUT_MS = 8_000
        private const val MAX_BODY = 8_000
    }
}
