package com.partnero

import android.content.Context
import android.net.Uri

/**
 * Claims Partnero referral codes from an Android app.
 *
 * Only the program's Program ID is used. Never put your Partnero API key in an app.
 */
public object Partnero {
    public const val VERSION: String = BuildConfig.VERSION_NAME

    /** The RevenueCat subscriber attribute Partnero reads when a purchase has no claimed customer. */
    public const val REVENUECAT_CODE_ATTRIBUTE: String = "partnero_code"

    private const val DEFAULT_BASE_URL = "https://app.partnero.com"
    private const val PREFERENCES_NAME = "com.partnero"

    @Volatile
    private var client: Client? = null

    /**
     * Call once at launch, before any claim, usually from `Application.onCreate`.
     * Records the first launch date the first time it runs.
     *
     * @param programId The program's Program ID, shown on its Integration page in Partnero.
     * @param baseUrl Overrides `https://app.partnero.com`, for example for staging.
     * @param referralParameter The query parameter that holds the code in your referral links, such as `aff`.
     */
    @JvmStatic
    @JvmOverloads
    public fun configure(
        context: Context,
        programId: String,
        baseUrl: String = DEFAULT_BASE_URL,
        referralParameter: String = "ref",
    ) {
        val appContext = context.applicationContext ?: context
        client = Client(
            programId = programId.trim(),
            baseUrl = baseUrl,
            referralParameter = referralParameter.trim(),
            storage = SharedPreferencesStorage(appContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)),
            installStorage = FileStorage(appContext.noBackupFilesDir),
            transport = HttpUrlConnectionTransport,
            installReferrer = PlayInstallReferrerSource(appContext),
        )
    }

    /**
     * Attributes [appUserId] to the partner who owns [code], a partner's referral link key or promotion code.
     *
     * Use the same ID your purchases are reported under, such as `Purchases.sharedInstance.appUserID`
     * once the user is identified. Anonymous RevenueCat IDs are rejected, because they change on reinstall.
     *
     * @throws PartneroException when Partnero refuses the claim or the input is not valid.
     * @throws java.io.IOException when Partnero could not be reached.
     */
    public suspend fun claim(code: String, appUserId: String, codeType: CodeType = CodeType.AUTO): ClaimResult =
        requireClient().claim(code, appUserId, codeType)

    /** Claims the code in a referral or deep link. Returns `null` when the link has no code. */
    public suspend fun claim(uri: Uri, appUserId: String): ClaimResult? =
        requireClient().claimQuery(uri.encodedQuery, appUserId)

    /**
     * Claims the code from the Google Play install referrer, for users who installed through a partner's
     * Play Store link. Runs once per install; later calls return `null` without a request.
     *
     * Call it after sign-in, once [appUserId] is known. A rate limit, server error or network failure
     * leaves it to try again on the next call. Any other refusal, such as an unknown code, is final.
     */
    public suspend fun claimFromInstallReferrer(appUserId: String): ClaimResult? =
        requireClient().claimFromInstallReferrer(appUserId)

    /** The code in a referral or deep link, or `null` when it has none. */
    @JvmStatic
    public fun code(uri: Uri): String? = requireClient().code(uri.encodedQuery)

    /**
     * Subscriber attributes for RevenueCat, so a purchase is still attributed if the claim never reached Partnero.
     *
     * ```kotlin
     * Purchases.sharedInstance.setAttributes(Partnero.revenueCatAttributes(code))
     * ```
     */
    @JvmStatic
    public fun revenueCatAttributes(code: String): Map<String, String> {
        val trimmed = code.trim()
        return if (trimmed.isEmpty()) emptyMap() else mapOf(REVENUECAT_CODE_ATTRIBUTE to trimmed)
    }

    private fun requireClient(): Client = client ?: throw PartneroException.NotConfigured()
}
