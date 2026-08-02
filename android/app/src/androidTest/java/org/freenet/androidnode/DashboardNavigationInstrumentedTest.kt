package org.freenet.androidnode

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DashboardNavigationInstrumentedTest {
    @Test
    fun userClickedHostedAppsAndExternalLinksOpenInBrowser() {
        assertTrue(
            shouldOpenInExternalBrowser(
                Uri.parse("http://127.0.0.1:7509/v1/contract/web/example/"),
                isForMainFrame = true,
                hasGesture = true,
            ),
        )
        assertTrue(
            shouldOpenInExternalBrowser(
                Uri.parse("https://freenet.org/quickstart"),
                isForMainFrame = true,
                hasGesture = true,
            ),
        )
    }

    @Test
    fun dashboardRoutesSubresourcesAndRedirectsStaySandboxed() {
        assertFalse(
            shouldOpenInExternalBrowser(
                Uri.parse("http://127.0.0.1:7509/peer/example"),
                isForMainFrame = true,
                hasGesture = true,
            ),
        )
        assertFalse(
            shouldOpenInExternalBrowser(
                Uri.parse("http://127.0.0.1:7509/v1/contract/web/example/app.js"),
                isForMainFrame = false,
                hasGesture = false,
            ),
        )
        assertFalse(
            shouldOpenInExternalBrowser(
                Uri.parse("https://unexpected.example/redirect"),
                isForMainFrame = true,
                hasGesture = false,
            ),
        )
    }
}
