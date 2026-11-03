package com.simonpena.unmuffle

import android.os.Bundle
import android.os.ParcelFileDescriptor
import androidx.test.runner.AndroidJUnitRunner

/**
 * Keeps the phone awake and unlocked for the whole run, then restores the
 * setting. A PIN or pattern lock can't be dismissed: unlock the phone first.
 */
class AwakeRunner : AndroidJUnitRunner() {
    private var stayOn = "0"

    override fun onStart() {
        stayOn = shell("settings get global stay_on_while_plugged_in").trim().ifEmpty { "0" }
        shell("settings put global stay_on_while_plugged_in 15") // AC, USB, wireless, dock
        shell("input keyevent KEYCODE_WAKEUP")
        shell("wm dismiss-keyguard")
        super.onStart()
    }

    override fun finish(resultCode: Int, results: Bundle?) {
        shell("settings put global stay_on_while_plugged_in $stayOn")
        super.finish(resultCode, results)
    }

    /** Runs [command] as the shell user and waits for it. */
    private fun shell(command: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(uiAutomation.executeShellCommand(command))
            .use { it.readBytes().decodeToString() }
}
