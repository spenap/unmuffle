package com.simonpena.unmuffle

import android.app.Application
import com.google.android.material.color.DynamicColors

class UnmuffleApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        DynamicColors.applyToActivitiesIfAvailable(this)
        AudioCopier.clear(AudioCopier.dir(this))
    }
}
