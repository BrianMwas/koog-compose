package io.github.koogcompose.demo

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.koogcompose.session.KoogDefinition

/**
 * Shared entry point for every platform's `App()` (Android/iOS/desktop),
 * replacing three copies of the same wiring. Asks for an OpenAI API key
 * before building the demo agent — [buildDemoContext] used to hardcode a
 * placeholder key that could never work on a fresh clone.
 *
 * This is intentionally minimal — a single hardcoded OpenAI provider. For
 * Ollama, on-device Gemma 4, and a proper provider picker, see `sample-app`.
 */
@Composable
fun DemoApp() {
    var apiKey by remember { mutableStateOf<String?>(null) }

    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            val key = apiKey
            if (key == null) {
                ApiKeyEntryScreen(onSubmit = { apiKey = it })
            } else {
                val viewModel = viewModel {
                    val agentDef: KoogDefinition<AppState> = buildDemoContext(apiKey = key)
                    DemoViewModel(agentDef)
                }
                DemoScreen(viewModel)
            }
        }
    }
}

@Composable
private fun ApiKeyEntryScreen(onSubmit: (String) -> Unit) {
    var key by remember { mutableStateOf("") }

    Scaffold { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(text = "koog-compose demo", style = MaterialTheme.typography.titleLarge)
            Text(
                text = "This minimal demo talks to OpenAI. Paste an API key to try it — " +
                    "it's kept in memory for this run only, never written to disk. " +
                    "For Ollama (no key needed) or on-device Gemma 4, see the sample-app module.",
                style = MaterialTheme.typography.bodyMedium,
            )
            OutlinedTextField(
                value = key,
                onValueChange = { key = it },
                label = { Text("OpenAI API key") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = { onSubmit(key) },
                enabled = key.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Start")
            }
        }
    }
}
