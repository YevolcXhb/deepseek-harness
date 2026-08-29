package com.dsh.mobile

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Boot receiver: auto-start keep-alive service after device boot.
 */
class DshBootReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "DshBootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                Log.i(TAG, "Boot completed, starting keep-alive service")
                val serviceIntent = Intent(context, DshKeepAliveService::class.java)
                context.startForegroundService(serviceIntent)
            }
        }
    }
}
