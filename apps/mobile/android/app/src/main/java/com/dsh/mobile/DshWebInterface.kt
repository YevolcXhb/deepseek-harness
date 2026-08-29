package com.dsh.mobile

import android.content.Context
import android.content.Intent
import android.webkit.JavascriptInterface

/**
 * JavaScript ↔ Native bridge.
 * dsh Web UI can call native functions via window.DshNative.
 */
class DshWebInterface(private val context: Context) {

    @JavascriptInterface
    fun getKeepAliveStatus(): String {
        val active = DshKeepAliveBridge.isActive()
        val level = DshKeepAliveBridge.getLevel()
        val remaining = DshKeepAliveBridge.getRemainingSeconds()
        return """{"active":$active,"level":"$level","remainingSeconds":$remaining}"""
    }

    @JavascriptInterface
    fun activateKeepAlive(level: String, durationSeconds: Int) {
        Intent(context, DshKeepAliveService::class.java).apply {
            action = "com.dsh.mobile.ACTIVATE"
            putExtra("level", level)
            putExtra("duration", durationSeconds * 1000L)
            putExtra("pid", DshKeepAliveBridge.getMonitoredPid())
        }.also { context.startService(it) }
    }

    @JavascriptInterface
    fun releaseKeepAlive() {
        Intent(context, DshKeepAliveService::class.java).apply {
            action = "com.dsh.mobile.RELEASE"
        }.also { context.startService(it) }
    }

    @JavascriptInterface
    fun getContainerStatus(): String {
        val bridge = DshSandboxBridge()
        val alive = bridge.isAlive()
        val port = DshKeepAliveBridge.getPort()
        val pid = DshKeepAliveBridge.getMonitoredPid()
        return """{"alive":$alive,"port":$port,"pid":$pid}"""
    }

    @JavascriptInterface
    fun getApiKey(): String {
        return getSharedPrefs().getString(DshApplication.PREF_API_KEY, "") ?: ""
    }

    @JavascriptInterface
    fun setApiKey(key: String) {
        getSharedPrefs().edit().putString(DshApplication.PREF_API_KEY, key).apply()
    }

    private fun getSharedPrefs() =
        context.getSharedPreferences(DshApplication.PREFS_NAME, Context.MODE_PRIVATE)
}
