package dev.kotonoha.collector.telemetry

import android.content.Context

internal object ImePreferences {
    fun isCollectionEnabled(context: Context): Boolean =
        preferences(context).getBoolean(KEY_COLLECTION_ENABLED, false)

    fun setCollectionEnabled(context: Context, enabled: Boolean) {
        preferences(context).edit().putBoolean(KEY_COLLECTION_ENABLED, enabled).apply()
    }

    fun isContextEnabled(context: Context): Boolean =
        preferences(context).getBoolean(KEY_CONTEXT_ENABLED, false)

    fun setContextEnabled(context: Context, enabled: Boolean) {
        preferences(context).edit().putBoolean(KEY_CONTEXT_ENABLED, enabled).apply()
    }

    private fun preferences(context: Context) =
        context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    private const val FILE_NAME = "collector_preferences"
    private const val KEY_COLLECTION_ENABLED = "collection_enabled"
    private const val KEY_CONTEXT_ENABLED = "context_enabled"
}
