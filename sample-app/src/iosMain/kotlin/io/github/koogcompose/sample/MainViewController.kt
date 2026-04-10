package io.github.koogcompose.sample

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.ComposeUIViewController

/**
 * iOS placeholder — the koog-compose-ui module doesn't yet target iOS.
 * The full sample runs on Android and Desktop.
 */
fun MainViewController() = ComposeUIViewController {
    MaterialTheme {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "Sample app coming soon on iOS\n(koog-compose-ui targets Android/Desktop)",
                style = MaterialTheme.typography.bodyLarge,
            )
        }
    }
}
