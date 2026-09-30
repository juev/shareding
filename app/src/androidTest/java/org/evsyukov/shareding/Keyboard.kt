package org.evsyukov.shareding

import android.app.Activity
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ActivityScenario

/**
 * Hides the soft keyboard and waits until it is gone. The keyboard resizes the screen
 * through `imePadding`, so taps and visibility checks made while it animates are flaky.
 */
internal fun <A : Activity> ComposeTestRule.hideKeyboard(scenario: ActivityScenario<A>) {
    scenario.onActivity { activity ->
        WindowCompat.getInsetsController(activity.window, activity.window.decorView)
            .hide(WindowInsetsCompat.Type.ime())
    }
    waitUntil(5_000) {
        var visible = true
        scenario.onActivity { activity ->
            visible = ViewCompat.getRootWindowInsets(activity.window.decorView)
                ?.isVisible(WindowInsetsCompat.Type.ime()) == true
        }
        !visible
    }
    waitForIdle()
}
