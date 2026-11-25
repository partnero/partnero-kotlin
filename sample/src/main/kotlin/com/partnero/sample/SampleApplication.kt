package com.partnero.sample

import android.app.Application
import com.partnero.Partnero

class SampleApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        Partnero.configure(
            this,
            programId = BuildConfig.PARTNERO_PROGRAM_ID,
            baseUrl = BuildConfig.PARTNERO_BASE_URL,
            referralParameter = BuildConfig.PARTNERO_REFERRAL_PARAMETER,
        )
    }
}
