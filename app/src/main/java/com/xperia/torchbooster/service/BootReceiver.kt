package com.xperia.torchbooster.service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.xperia.torchbooster.data.PrefsManager
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val prefs = PrefsManager(context)
        if (prefs.enabled && prefs.acknowledged && prefs.flag("boot")) {
            try { TorchBoostService.start(context) }
            catch (e: RuntimeException) {
                prefs.store.edit().putString("last_status", "Boot start blocked: ${e.message}. Open the app to start.").apply()
            }
        }
    }
}
