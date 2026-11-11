package com.adbpair.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.adbpair.core.AdbPairService

/**
 * BOOT_COMPLETED → start the pairing service → resume attempt with the stored
 * identity. The connectedDevice foreground type is exempt from the
 * background-start restriction, which is what makes this legal at boot.
 * If the start is ever refused, the service is started on the next app open
 * instead — the first-run checklist tells the user to open the app once
 * after install.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val serviceIntent = AdbPairService.resumeIntent(context)
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        }
    }
}
