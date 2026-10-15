package com.siliconleap.app

import android.app.Application
import com.siliconleap.app.runtime.AddonManager
import com.siliconleap.app.runtime.CrashRecorder
import com.siliconleap.app.runtime.RuntimeManager
import com.siliconleap.app.runtime.SubsystemManager

class SiliconLeapApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashRecorder.install(applicationContext)
        RuntimeManager.attach(applicationContext)
        SubsystemManager.attach(applicationContext)
        AddonManager.attach(applicationContext)
    }
}
