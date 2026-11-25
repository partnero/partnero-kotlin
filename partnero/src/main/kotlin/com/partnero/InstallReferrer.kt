package com.partnero

import android.content.Context
import com.android.installreferrer.api.InstallReferrerClient
import com.android.installreferrer.api.InstallReferrerClient.InstallReferrerResponse
import com.android.installreferrer.api.InstallReferrerStateListener
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

internal sealed interface InstallReferrer {
    /** The referrer Google Play recorded for this install, such as `ref=CODE&utm_source=partner`. */
    class Found(val referrer: String) : InstallReferrer

    /** There is no referrer and never will be, for example when the app was not installed from Google Play. */
    object None : InstallReferrer

    /** Google Play could not be reached. Worth asking again on a later launch. */
    object Unavailable : InstallReferrer
}

internal fun interface InstallReferrerSource {
    suspend fun read(): InstallReferrer
}

internal class PlayInstallReferrerSource(private val context: Context) : InstallReferrerSource {
    override suspend fun read(): InstallReferrer = suspendCancellableCoroutine { continuation ->
        val client = InstallReferrerClient.newBuilder(context).build()
        fun finish(result: InstallReferrer) {
            runCatching { client.endConnection() }
            if (continuation.isActive) continuation.resume(result)
        }
        continuation.invokeOnCancellation { runCatching { client.endConnection() } }

        try {
            client.startConnection(object : InstallReferrerStateListener {
                override fun onInstallReferrerSetupFinished(responseCode: Int) {
                    finish(
                        when (responseCode) {
                            InstallReferrerResponse.OK -> runCatching { client.installReferrer.installReferrer }
                                .map { if (it.isNullOrBlank()) InstallReferrer.None else InstallReferrer.Found(it) }
                                .getOrDefault(InstallReferrer.Unavailable)
                            InstallReferrerResponse.FEATURE_NOT_SUPPORTED,
                            InstallReferrerResponse.DEVELOPER_ERROR,
                            InstallReferrerResponse.PERMISSION_ERROR -> InstallReferrer.None
                            else -> InstallReferrer.Unavailable
                        },
                    )
                }

                override fun onInstallReferrerServiceDisconnected() {
                    finish(InstallReferrer.Unavailable)
                }
            })
        } catch (_: RuntimeException) {
            finish(InstallReferrer.Unavailable)
        }
    }
}
