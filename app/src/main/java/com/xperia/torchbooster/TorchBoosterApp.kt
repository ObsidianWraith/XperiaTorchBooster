package com.xperia.torchbooster
import android.app.Application
import com.topjohnwu.superuser.Shell
class TorchBoosterApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Normal root namespace suffices for sysfs. Do not request mount-master.
        Shell.setDefaultBuilder(Shell.Builder.create().setTimeout(10))
    }
}
