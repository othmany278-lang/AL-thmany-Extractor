package com.althmany.extractor.profile

import android.content.Context

data class ProfileAccessibilitySnapshot(
    val profileKey: String, val localServiceConnected: Boolean, val heartbeatAgeMs: Long?,
    val lastEventPackage: String?, val lastEventAgeMs: Long?, val rootAvailable: Boolean,
    val rootPackage: String?, val rootAgeMs: Long?
)

object ProfileAccessibilityRuntime {
    private const val PREFS = "profile_accessibility_runtime_v215"
    private const val HEARTBEAT_WRITE_MIN_MS = 1_500L
    @Volatile private var lastHeartbeatWriteWall = 0L

    fun recordServiceConnected(context: Context) {
        val now=System.currentTimeMillis(); val profile=RuntimeProfileDetector.detect(context)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("profile", profile.profileKey).putBoolean("connected", true).putLong("heartbeat", now).apply()
        lastHeartbeatWriteWall=now
    }
    fun heartbeat(context: Context, force: Boolean=false) {
        val now=System.currentTimeMillis(); if(!force && now-lastHeartbeatWriteWall<HEARTBEAT_WRITE_MIN_MS) return
        val profile=RuntimeProfileDetector.detect(context)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("profile", profile.profileKey).putBoolean("connected", true).putLong("heartbeat", now).apply(); lastHeartbeatWriteWall=now
    }
    fun recordEvent(context: Context, pkg: String) { if(pkg.isBlank()) return; heartbeat(context); context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("event_pkg",pkg).putLong("event_wall",System.currentTimeMillis()).apply() }
    fun recordRoot(context: Context, available: Boolean, pkg: String?) {
        heartbeat(context); context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("root_available",available).putLong("root_wall",System.currentTimeMillis()).apply { if(pkg.isNullOrBlank()) remove("root_pkg") else putString("root_pkg",pkg) }.apply()
    }
    fun markDisconnected(context: Context) { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("connected",false).apply() }
    fun snapshot(context: Context): ProfileAccessibilitySnapshot {
        val now=System.currentTimeMillis(); val profile=RuntimeProfileDetector.detect(context); val p=context.getSharedPreferences(PREFS,Context.MODE_PRIVATE)
        val hb=p.getLong("heartbeat",0L).takeIf{it>0}; val hba=hb?.let{(now-it).coerceAtLeast(0)}
        val connected=p.getBoolean("connected",false)&&p.getString("profile",null)==profile.profileKey&&hba!=null&&hba<=ProfileControlPolicy.SERVICE_HEARTBEAT_FRESH_MS
        val ew=p.getLong("event_wall",0L).takeIf{it>0}; val rw=p.getLong("root_wall",0L).takeIf{it>0}
        return ProfileAccessibilitySnapshot(profile.profileKey, connected, hba, p.getString("event_pkg",null), ew?.let{(now-it).coerceAtLeast(0)}, p.getBoolean("root_available",false), p.getString("root_pkg",null), rw?.let{(now-it).coerceAtLeast(0)})
    }
}
