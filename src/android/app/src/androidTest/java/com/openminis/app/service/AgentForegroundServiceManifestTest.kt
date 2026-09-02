package com.openminis.app.service

import android.Manifest
import android.content.ComponentName
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 34)
class AgentForegroundServiceManifestTest {

    @Test
    fun agentServiceUsesSpecialUseAndNotMediaOrDataSync() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val pm = context.packageManager
        val component = ComponentName(context, AgentForegroundService::class.java)
        @Suppress("DEPRECATION")
        val info = pm.getServiceInfo(component, 0)

        assertTrue(
            info.foregroundServiceType and ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE != 0,
        )
        assertEquals(
            0,
            info.foregroundServiceType and ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
        )
        assertEquals(
            0,
            info.foregroundServiceType and ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )

        assertEquals(
            PackageManager.PERMISSION_GRANTED,
            pm.checkPermission(Manifest.permission.FOREGROUND_SERVICE_SPECIAL_USE, context.packageName),
        )

        val subtype = pm.getProperty(
            "android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE",
            component,
        ).string
        assertFalse(subtype.isNullOrBlank())
        assertTrue(subtype!!.contains("agent", ignoreCase = true))
    }
}
