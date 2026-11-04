package com.simonpena.unmuffle

import android.app.Activity
import android.content.Intent
import android.view.View
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

/** With the model in place, Home shows how to share. Run with scripts/device-test.sh. */
@RunWith(AndroidJUnit4::class)
class HomeTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test
    fun readyOnceTheModelIsThere() {
        val previous = SettingsRepository.getModel(context)
        SettingsRepository.setModel(context, WhisperModel.TINY)
        val monitor = instrumentation.addMonitor(MainActivity::class.java.name, null, false)
        context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val activity: Activity? = instrumentation.waitForMonitorWithTimeout(monitor, 15_000)
        try {
            assertNotNull("Home didn't start within 15 s", activity)
            instrumentation.runOnMainSync {
                assertEquals(View.VISIBLE, activity!!.findViewById<View>(R.id.ready_group).visibility)
                assertEquals(View.GONE, activity.findViewById<View>(R.id.download_group).visibility)
            }
        } finally {
            activity?.finish()
            SettingsRepository.setModel(context, previous)
        }
    }
}
