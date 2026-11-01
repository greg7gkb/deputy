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
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.Charset
import java.util.Base64
import kotlin.time.Instant

/**
 * Tier 2: Gmail through its REST API: reads one label (D25) and sends replies in it (D27).
 *
 * Minimisation by construction (D9). Google can't scope a grant to a label: gmail.readonly can
 * read the whole mailbox. So the restriction is this class's: it lists only messages carrying
 * the one label the user set aside for Deputy ([labelName]), checks each fetched message still
 * carries it, and keeps only From, Subject, the received time and the plain-text body. No other
 * headers, no To or Cc, no attachments. Everything it returns is untrusted and goes only to the
 * broker's reader.
 *
 * Sending is just as narrow: [sendReply] only answers a message that still carries the label,
 * to exactly the address the broker passes (never the original's Reply-To), with no Cc or Bcc.
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
        withToken({ GmailAuth.readToken(context) }) { read(it, since, max) }
    }

    override suspend fun sendReply(adapterRef: String, to: String, body: String): String = withContext(Dispatchers.IO) {
        val recipient = checkRecipient(to)
        // Read the original with the read scope, and only the headers threading needs.
        val original = withToken({ GmailAuth.readToken(context) }) { token ->
            val label = labelId ?: resolveLabel(token).also { labelId = it }
            val headers = listOf("Message-ID", "Subject", "References").joinToString("") { "&metadataHeaders=$it" }
            val fields = URLEncoder.encode("id,threadId,labelIds,payload/headers", "UTF-8")
            val m = request("GET", "$API/messages/${URLEncoder.encode(adapterRef, "UTF-8")}?format=metadata$headers&fields=$fields", token)
            val labels = (m["labelIds"] as? JsonArray).orEmpty().map { it.jsonPrimitive.content }
            check(label in labels) { "That message is no longer under the Gmail label \"$labelName\", so Deputy won't reply to it." }
            m
        }
        val raw = Base64.getUrlEncoder().encodeToString(reply(original, recipient, body).toByteArray(Charsets.US_ASCII))
        val payload = buildJsonObject {
            put("raw", raw)
            original.str("threadId").takeIf { it.isNotEmpty() }?.let { put("threadId", it) }
        }.toString()
        val sent = withToken({ GmailAuth.sendToken(context) }) { request("POST", "$API/messages/send", it, payload) }
        sent.str("id").ifEmpty { error("Gmail didn't say which message it sent") }
    }

    /**
     * The address exactly as given, or a refusal. Anything that could end the header or add a
     * second recipient is refused rather than cleaned, since a cleaned address isn't the approved one.
     */
    private fun checkRecipient(to: String): String {
        val t = to.trim()
        require(t.isNotEmpty() && '@' in t) { "\"$to\" isn't an email address" }
        require(t.none { it == ',' || it == ';' || it.isISOControl() }) { "Deputy replies to one address only, and \"${t.filterNot { it.isISOControl() }}\" isn't one" }
        // The message is sent as plain ASCII; an address outside it would arrive altered.
        require(t.all { it.code in 0x20..0x7e }) { "Deputy can't yet reply to an address with non-ASCII characters" }
        return t
    }

    /** The reply as an RFC 2822 message, all ASCII: the subject is RFC 2047 encoded if needed, the body base64. */
    private fun reply(original: JsonObject, to: String, body: String): String {
        val headers = (original["payload"]?.jsonObject?.get("headers") as? JsonArray).orEmpty().map { it.jsonObject }
        // Every value here was written by the sender, so line breaks are taken out before reuse.
        fun header(name: String) = headers.firstOrNull { it.str("name").equals(name, ignoreCase = true) }
            ?.str("value").orEmpty().replace(Regex("[\\r\\n]+"), " ").filterNot { it.isISOControl() }.trim()
        val subject = header("Subject").let { if (it.startsWith("Re:", ignoreCase = true)) it else "Re: $it" }
        val messageId = header("Message-ID")
        val references = listOf(header("References"), messageId).filter { it.isNotEmpty() }.joinToString(" ")
        val lines = buildList {
            add("To: $to")
            add("Subject: ${encodeHeader(subject)}")
            if (messageId.isNotEmpty()) add("In-Reply-To: $messageId")
            if (references.isNotEmpty()) add("References: $references")
            add("MIME-Version: 1.0")
            add("Content-Type: text/plain; charset=UTF-8")
            add("Content-Transfer-Encoding: base64")
        }
        val text = body.replace(Regex("\\r?\\n"), "\r\n").toByteArray(Charsets.UTF_8)
        return lines.joinToString("\r\n") + "\r\n\r\n" + Base64.getMimeEncoder().encodeToString(text) + "\r\n"
    }

    private fun encodeHeader(value: String): String =
        if (value.all { it.code in 0x20..0x7e }) value
        else "=?UTF-8?B?" + Base64.getEncoder().encodeToString(value.toByteArray(Charsets.UTF_8)) + "?="

    /** Runs [block] with a token; on a 401, drops the token (Play Services can hand back an expired one) and retries once. */
    private suspend fun <T> withToken(token: suspend () -> String, block: (String) -> T): T {
        val t = token()
        return try {
            block(t)
        } catch (_: Unauthorized) {
            GmailAuth.clear(context, t)
            block(token())
        }
    }

    private fun read(token: String, since: Instant, max: Int): List<RawMessage> {
        val label = labelId ?: resolveLabel(token).also { labelId = it }
        // after: takes epoch seconds and is exclusive, so ask from a second earlier and filter here.
        val q = URLEncoder.encode("after:${since.epochSeconds - 1}", "UTF-8")
        val list = request("GET", "$API/messages?labelIds=$label&maxResults=${max.coerceIn(1, 50)}&q=$q", token)
        val ids = (list["messages"] as? JsonArray).orEmpty().map { it.jsonObject.str("id") }
        return ids.mapNotNull { fetch(it, label, token) }
            .filter { it.receivedAt >= since }
            .sortedByDescending { it.receivedAt }
            .take(max)
    }

    private fun resolveLabel(token: String): String {
        val labels = (request("GET", "$API/labels", token)["labels"] as? JsonArray).orEmpty().map { it.jsonObject }
        // Gmail label names are unique ignoring case.
        return labels.firstOrNull { it.str("name").equals(labelName, ignoreCase = true) }?.str("id")
            ?: error("There's no Gmail label named \"$labelName\". Create it in Gmail and put the mail Deputy may read under it.")
    }

    /** One message, reduced to [RawMessage]. Null if it no longer carries Deputy's label. */
    private fun fetch(id: String, label: String, token: String): RawMessage? {
        // fields= asks Google to send only what's used here, plus the labels for the check below.
        val fields = URLEncoder.encode("id,labelIds,internalDate,payload(mimeType,headers,body/data,parts)", "UTF-8")
        val m = request("GET", "$API/messages/${URLEncoder.encode(id, "UTF-8")}?format=full&fields=$fields", token)
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

    /** One request. Error messages carry Google's reason, never the token. */
    private fun request(method: String, url: String, token: String, json: String? = null): JsonObject {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = TIMEOUT_MS
        c.readTimeout = TIMEOUT_MS
        c.setRequestProperty("Authorization", "Bearer $token")
        c.setRequestProperty("Accept", "application/json")
        c.requestMethod = method
        if (json != null) {
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            c.outputStream.use { it.write(json.toByteArray(Charsets.UTF_8)) }
        }
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
