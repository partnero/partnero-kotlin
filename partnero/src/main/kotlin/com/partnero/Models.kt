package com.partnero

/** What a code is matched against. [AUTO] tries partner link keys and promotion codes. */
public enum class CodeType(internal val value: String) {
    AUTO("auto"),
    LINK("link"),
    PROMOTION_CODE("promotion_code"),
}

public data class ClaimResult(val status: Status, val appUserId: String) {
    public enum class Status(internal val value: String) {
        /** The user is now attributed to the code's partner. */
        CLAIMED("claimed"),

        /** The user was already attributed, possibly to a different partner. Nothing changed. */
        ALREADY_CLAIMED("already_claimed"),
    }
}

/**
 * A claim Partnero refused, or one that was never sent.
 *
 * Network failures are thrown as [java.io.IOException] instead, and cancellation as
 * [kotlin.coroutines.cancellation.CancellationException].
 */
public sealed class PartneroException(message: String) : Exception(message) {
    public enum class Field(internal val label: String, internal val serverName: String?) {
        PROGRAM_ID("Program ID", null),
        REFERRAL_PARAMETER("referral parameter", null),
        CODE("referral code", "code"),
        APP_USER_ID("app user ID", "app_user_id"),
        CODE_TYPE("code type", "code_type"),
        FIRST_SEEN_AT("first launch date", "first_seen_at"),
        PLATFORM("platform", "platform"),

        /** Partnero rejected the request without naming a field. */
        REQUEST("request", null),
        ;

        internal companion object {
            fun fromServerName(name: String): Field? = entries.firstOrNull { it.serverName == name }
        }
    }

    /** [Partnero.configure] has not been called. */
    public class NotConfigured : PartneroException("Partnero.configure must be called before claiming a code.")

    /** A value was blank or malformed. Checked before any request, and again by Partnero. */
    public class InvalidInput(public val field: Field) : PartneroException("The ${field.label} is blank or not valid.")

    /** No active code matches, or mobile claims are turned off for the program. */
    public class CodeNotFound : PartneroException("The referral code was not found.")

    /** The code matches more than one partner (pass a [CodeType]), or the program's claim window has closed. */
    public class CodeConflict(message: String) : PartneroException(message)

    /** Too many claims from this network for the program. Try again in a minute. */
    public class RateLimited : PartneroException("Too many referral claims. Try again in a minute.")

    public class Server(public val status: Int, message: String?) :
        PartneroException(message ?: "Partnero responded with HTTP $status.")
}
