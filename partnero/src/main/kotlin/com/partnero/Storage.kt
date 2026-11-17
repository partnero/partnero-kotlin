package com.partnero

import android.content.SharedPreferences
import java.io.File
import java.io.IOException

internal interface Storage {
    fun getString(key: String): String?

    fun putString(key: String, value: String)
}

internal class SharedPreferencesStorage(private val preferences: SharedPreferences) : Storage {
    override fun getString(key: String): String? = preferences.getString(key, null)

    override fun putString(key: String, value: String) {
        preferences.edit().putString(key, value).apply()
    }
}

/** One file per key. Used in `noBackupFilesDir`, so a restored backup can't carry install state to a new install. */
internal class FileStorage(private val directory: File) : Storage {
    override fun getString(key: String): String? =
        try {
            File(directory, key).takeIf { it.isFile }?.readText()?.trim()?.ifEmpty { null }
        } catch (_: IOException) {
            null
        }

    override fun putString(key: String, value: String) {
        try {
            directory.mkdirs()
            File(directory, key).writeText(value)
        } catch (_: IOException) {
        }
    }
}
