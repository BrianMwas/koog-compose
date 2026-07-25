package io.github.koogcompose.sample

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application

/**
 * Desktop entry point for the cross-platform samples.
 *
 * This target exists mainly so the `commonMain` samples stay honest: they compile and
 * run without an Android SDK, which is the cheapest way to keep them from rotting.
 * Android-only samples (Home Tutor with Room persistence, Run Coach, Math Tutor) are
 * reachable from the Android selector instead — see `AndroidSample` in MainActivity.kt.
 */
fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "koog-compose Samples",
    ) {
        MaterialTheme {
            SampleSelectorApp(modifier = Modifier.fillMaxSize())
        }
    }
}
