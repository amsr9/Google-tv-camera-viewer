package com.home.nvrviewer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** After the TV starts (e.g. after a power cut): start monitoring and open the camera wall. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != "android.intent.action.QUICKBOOT_POWERON"
        ) return
        MonitorService.start(context)
        MonitorService.openCameraWallIfWanted(context)
    }
}
