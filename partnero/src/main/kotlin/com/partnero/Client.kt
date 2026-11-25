package com.partnero

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject
import java.net.URLDecoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

internal class Client(
    private val programId: String,
    baseUrl: String,
    private val referralParameter: String,
    private val storage: Storage,
    private val installStorage: Storage,
    private val transport: Transport,
    private val installReferrer: InstallReferrerSource,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val baseUrl = baseUrl.trimEnd('/')

    init {
        if (storage.getString(FIRST_SEEN_AT_KEY) == null) {
            storage.putString(FIRST_SEEN_AT_KEY, formatTimestamp(clock()))
        }
    }

    suspend fun claim(code: String?, appUserId: String, codeType: CodeType, clickId: String? = null): ClaimResult {
        val body = requestBody(code, clickId, appUserId, codeType)
        val response = transport.post("$baseUrl/api/pub/v1/mobile/$programId/claims", body)
        return result(response)
    }

    suspend fun claimQuery(query: String?, appUserId: String): ClaimResult? {
        val code = code(query)
        val clickId = clickId(query)
        if (code == null && clickId == null) return null

        return claim(code, appUserId, CodeType.AUTO, clickId)
    }

    suspend fun claimFromInstallReferrer(appUserId: String): ClaimResult? {
        requireValidProgramId()
        requireValidAppUserId(appUserId.trim())

        return installReferrerLock.withLock {
            if (withContext(Dispatchers.IO) { installStorage.getString(INSTALL_REFERRER_KEY) } != null) {
                return@withLock null
            }

            val referrer = when (val found = installReferrer.read()) {
                InstallReferrer.Unavailable -> return@withLock null
                InstallReferrer.None -> null
                is InstallReferrer.Found -> found.referrer
            }
            val code = code(referrer)
            val clickId = clickId(referrer)
            if (code == null && clickId == null) {
                markInstallReferrerClaimed()
                return@withLock null
            }

            val result = try {
                claim(code, appUserId, CodeType.AUTO, clickId)
            } catch (e: PartneroException) {
                if (!e.isRetryable) markInstallReferrerClaimed()
                throw e
            }
            markInstallReferrerClaimed()
            result
        }
    }

    /**
     * `partnero_code` from a Partnero app link, otherwise the value of [referralParameter], in a URL query or
     * install referrer. Names are matched case-insensitively.
     */
    fun code(query: String?): String? {
        if (referralParameter.isBlank()) {
            throw PartneroException.InvalidInput(PartneroException.Field.REFERRAL_PARAMETER)
        }

        return queryValue(query, CODE_PARAMETER) ?: queryValue(query, referralParameter)
    }

    /** `partnero_click` from a Partnero app link. A malformed click ID is ignored, since the code alone still claims. */
    fun clickId(query: String?): String? = queryValue(query, CLICK_ID_PARAMETER)?.takeIf(::isValidClickId)

    private fun queryValue(query: String?, name: String): String? {
        if (query.isNullOrEmpty()) return null

        return query.split('&').firstNotNullOfOrNull { pair ->
            if (pair.contains('=') && decode(pair.substringBefore('=')).equals(name, ignoreCase = true)) {
                decode(pair.substringAfter('=')).trim().ifEmpty { null }
            } else {
                null
            }
        }
    }

    private fun requestBody(code: String?, clickId: String?, appUserId: String, codeType: CodeType): String {
        val code = code?.trim()?.ifEmpty { null }
        val clickId = clickId?.trim()?.ifEmpty { null }
        val appUserId = appUserId.trim()

        requireValidProgramId()
        if (code != null && (code.length > MAX_CODE_LENGTH || !code.all(::isIdentifierCharacter))) {
            throw PartneroException.InvalidInput(PartneroException.Field.CODE)
        }
        if (clickId != null && !isValidClickId(clickId)) {
            throw PartneroException.InvalidInput(PartneroException.Field.CLICK_ID)
        }
        if (code == null && clickId == null) {
            throw PartneroException.InvalidInput(PartneroException.Field.CODE)
        }
        requireValidAppUserId(appUserId)

        val body = JSONObject()
            .put("app_user_id", appUserId)
            .put("platform", PLATFORM)
        if (code != null) {
            body.put("code", code).put("code_type", codeType.value)
        }
        clickId?.let { body.put("click_id", it) }
        storage.getString(FIRST_SEEN_AT_KEY)?.let { body.put("first_seen_at", it) }

        return body.toString()
    }

    private fun result(response: HttpResponse): ClaimResult {
        val json = try {
            JSONObject(response.body)
        } catch (_: JSONException) {
            null
        }
        val message = json?.optString("message")?.ifEmpty { null }

        when (response.status) {
            in 200..299 -> {
                val data = json?.optJSONObject("data")
                val status = ClaimResult.Status.entries.firstOrNull { it.value == data?.optString("status") }
                val appUserId = (data?.opt("app_user_id") as? String)?.ifEmpty { null }
                if (status == null || appUserId == null) {
                    throw PartneroException.Server(response.status, "Unexpected response from Partnero.")
                }
                return ClaimResult(status, appUserId)
            }
            400, 422 -> {
                val field = json?.optJSONObject("errors")?.keys()?.asSequence()?.sorted()?.firstOrNull()
                    ?.let(PartneroException.Field::fromServerName)
                throw PartneroException.InvalidInput(field ?: PartneroException.Field.REQUEST)
            }
            404 -> throw PartneroException.CodeNotFound()
            409 -> throw PartneroException.CodeConflict(message ?: "The code matches more than one partner.")
            429 -> throw PartneroException.RateLimited()
            else -> throw PartneroException.Server(response.status, message)
        }
    }

    private fun requireValidProgramId() {
        if (programId.isEmpty() || !programId.all(::isIdentifierCharacter)) {
            throw PartneroException.InvalidInput(PartneroException.Field.PROGRAM_ID)
        }
    }

    private fun requireValidAppUserId(appUserId: String) {
        if (!isValidAppUserId(appUserId)) throw PartneroException.InvalidInput(PartneroException.Field.APP_USER_ID)
    }

    private suspend fun markInstallReferrerClaimed() = withContext(Dispatchers.IO) {
        installStorage.putString(INSTALL_REFERRER_KEY, formatTimestamp(clock()))
    }

    private fun decode(value: String): String =
        try {
            URLDecoder.decode(value, "UTF-8")
        } catch (_: IllegalArgumentException) {
            value
        }

    private fun formatTimestamp(millis: Long): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }
            .format(Date(millis))

    private val PartneroException.isRetryable: Boolean
        get() = this is PartneroException.RateLimited || (this is PartneroException.Server && status >= 500)

    companion object {
        /** Shared by every client, so calling `configure` again can't start a second claim for the same install. */
        private val installReferrerLock = Mutex()

        const val FIRST_SEEN_AT_KEY = "com.partnero.first_seen_at"
        const val INSTALL_REFERRER_KEY = "com.partnero.install_referrer_claimed_at"
        const val PLATFORM = "android"

        /** Mirrors the claim endpoint's validation, so bad input fails without a request. */
        const val MAX_CODE_LENGTH = 100
        const val MAX_CLICK_ID_LENGTH = 32
        const val MAX_APP_USER_ID_LENGTH = 255

        /** Set by Partnero app links on deep links and on the Google Play install referrer. */
        const val CODE_PARAMETER = "partnero_code"
        const val CLICK_ID_PARAMETER = "partnero_click"

        fun isValidClickId(value: String): Boolean =
            value.isNotEmpty() && value.length <= MAX_CLICK_ID_LENGTH &&
                value.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' }

        fun isIdentifierCharacter(character: Char): Boolean =
            character in 'a'..'z' || character in 'A'..'Z' || character in '0'..'9' ||
                character == '-' || character == '_' || character == '.'

        /** Partnero customer keys are an email address, or letters, numbers, `-`, `_` and `.`. */
        fun isValidAppUserId(value: String): Boolean {
            if (value.isEmpty() || value.toByteArray(Charsets.UTF_8).size > MAX_APP_USER_ID_LENGTH) return false
            if (value.all(::isIdentifierCharacter)) return true

            val parts = value.split('@')
            return parts.size == 2 &&
                parts[0].isNotEmpty() &&
                parts[1].contains('.') &&
                value.none { it.isWhitespace() || it in "{}()<>" }
        }
    }
}
