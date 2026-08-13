package com.althmany.extractor.engine

import android.content.Context

class ExtractionStateStore(context: Context) {
    private val prefs = context.getSharedPreferences("extraction_state", Context.MODE_PRIVATE)

    var active: Boolean
        get() = prefs.getBoolean("active", false)
        set(value) = prefs.edit().putBoolean("active", value).apply()

    var paused: Boolean
        get() = prefs.getBoolean("paused", false)
        set(value) = prefs.edit().putBoolean("paused", value).apply()

    var currentGroupName: String?
        get() = prefs.getString("current_group_name", null)
        set(value) = prefs.edit().putString("current_group_name", value).apply()

    var currentGroupId: Long
        get() = prefs.getLong("current_group_id", -1L)
        set(value) = prefs.edit().putLong("current_group_id", value).apply()

    var currentRetry: Int
        get() = prefs.getInt("current_retry", 0)
        set(value) = prefs.edit().putInt("current_retry", value).apply()

    var currentIteration: Int
        get() = prefs.getInt("current_iteration", 0)
        set(value) = prefs.edit().putInt("current_iteration", value).apply()

    fun clearRuntime() {
        prefs.edit()
            .remove("active").remove("paused").remove("current_group_name")
            .remove("current_group_id").remove("current_retry").remove("current_iteration").apply()
    }

    fun clear() = prefs.edit().clear().apply()
}
