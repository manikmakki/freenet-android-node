package org.freenet.androidnode

import android.os.Bundle
import androidx.test.runner.AndroidJUnitRunner

class FreenetAndroidTestRunner : AndroidJUnitRunner() {
    private var disclaimerWasAccepted = false

    override fun onStart() {
        disclaimerWasAccepted = AlphaDisclaimerAcceptance.isAccepted(targetContext)
        AlphaDisclaimerAcceptance.accept(targetContext)
        super.onStart()
    }

    override fun finish(resultCode: Int, results: Bundle?) {
        if (!disclaimerWasAccepted) {
            AlphaDisclaimerAcceptance.clear(targetContext)
        }
        super.finish(resultCode, results)
    }
}
