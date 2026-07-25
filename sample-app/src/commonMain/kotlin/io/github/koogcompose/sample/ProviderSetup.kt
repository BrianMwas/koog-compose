package io.github.koogcompose.sample

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.github.koogcompose.provider.ProviderConfig

/**
 * Which family of provider a sample should run against. Kept separate from
 * [ProviderConfig] so the setup screen can hold half-entered fields (e.g. an
 * empty API key) without constructing an invalid config.
 */
enum class SampleProviderKind(val label: String, val blurb: String) {
    OpenAI(
        label = "OpenAI (cloud)",
        blurb = "Paste an API key below. Nothing is written to disk — the key " +
            "lives in memory for this run only.",
    ),
    Ollama(
        label = "Ollama (local)",
        blurb = "No API key needed, but Ollama must be running locally " +
            "(`ollama serve`) with the model pulled — e.g. `ollama pull llama3.2` " +
            "or `ollama pull gemma3:4b` for Gemma.",
    ),
    OnDeviceGemma(
        label = "On-device Gemma 4 (Android)",
        blurb = "Runs fully offline via LiteRT-LM. Requires the " +
            ":koog-compose-mediapipe module (already linked in this sample's " +
            "Android target) and a .litertlm model file pushed to the device — " +
            "see the README's \"On-device models\" section. Not wired up for " +
            "iOS/desktop in this sample.",
    ),
}

/**
 * In-memory, per-session provider selection for the sample app. Every field
 * the setup screen can edit lives here so the screen can be driven by a
 * single `remember`-ed instance.
 */
data class SampleProviderSelection(
    val kind: SampleProviderKind = SampleProviderKind.Ollama,
    val openAiKey: String = "",
    val openAiModel: String = "gpt-4o-mini",
    val ollamaModel: String = "llama3.2",
    val ollamaBaseUrl: String = "http://localhost:11434",
    val onDeviceModelPath: String = "/data/models/gemma-4-E2B.litertlm",
) {
    /** True once the current [kind] has enough information to build a [ProviderConfig]. */
    val isReady: Boolean
        get() = when (kind) {
            SampleProviderKind.OpenAI -> openAiKey.isNotBlank()
            SampleProviderKind.Ollama -> ollamaModel.isNotBlank()
            SampleProviderKind.OnDeviceGemma -> onDeviceModelPath.isNotBlank()
        }

    fun toProviderConfig(): ProviderConfig = when (kind) {
        SampleProviderKind.OpenAI -> ProviderConfig.OpenAI(apiKey = openAiKey, model = openAiModel)
        SampleProviderKind.Ollama -> ProviderConfig.Ollama(model = ollamaModel, baseUrl = ollamaBaseUrl)
        SampleProviderKind.OnDeviceGemma -> ProviderConfig.OnDevice(modelPath = onDeviceModelPath)
    }
}

/** Short human-readable summary shown once a provider is active, e.g. in a top bar. */
fun SampleProviderSelection.summary(): String = when (kind) {
    SampleProviderKind.OpenAI -> "OpenAI · $openAiModel"
    SampleProviderKind.Ollama -> "Ollama · $ollamaModel"
    SampleProviderKind.OnDeviceGemma -> "On-device · Gemma 4"
}

/**
 * Lets the user pick which LLM backend the samples talk to before entering
 * any sample. Exists because every sample used to hardcode its own provider
 * (mostly Ollama, one with a placeholder API key), so a fresh clone couldn't
 * actually run anything without editing source. Selection is in-memory only
 * — nothing is persisted across app restarts.
 */
@Composable
fun ProviderSetupScreen(
    initial: SampleProviderSelection,
    onContinue: (SampleProviderSelection) -> Unit,
    modifier: Modifier = Modifier,
) {
    var selection by remember { mutableStateOf(initial) }

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .statusBarsPadding(),
        topBar = {
            Text(
                text = "Choose a provider",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "Every sample below runs against whichever provider you pick here. " +
                    "You can come back and change it at any time.",
                style = MaterialTheme.typography.bodyMedium,
            )

            SampleProviderKind.entries.forEach { kind ->
                ProviderOptionCard(
                    kind = kind,
                    selected = selection.kind == kind,
                    onSelect = { selection = selection.copy(kind = kind) },
                ) {
                    when (kind) {
                        SampleProviderKind.OpenAI -> OpenAIFields(
                            selection = selection,
                            onChange = { selection = it },
                        )
                        SampleProviderKind.Ollama -> OllamaFields(
                            selection = selection,
                            onChange = { selection = it },
                        )
                        SampleProviderKind.OnDeviceGemma -> Unit
                    }
                }
            }

            Button(
                onClick = { onContinue(selection) },
                enabled = selection.isReady,
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            ) {
                Text("Continue")
            }
        }
    }
}

@Composable
private fun ProviderOptionCard(
    kind: SampleProviderKind,
    selected: Boolean,
    onSelect: () -> Unit,
    content: @Composable () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Column {
                ProviderKindRow(kind = kind, selected = selected, onSelect = onSelect)
                Text(
                    text = kind.blurb,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(start = 40.dp, top = 2.dp),
                )
            }
            if (selected) {
                Column(modifier = Modifier.padding(start = 40.dp, top = 8.dp)) {
                    content()
                }
            }
        }
    }
}

@Composable
private fun ProviderKindRow(kind: SampleProviderKind, selected: Boolean, onSelect: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Text(text = kind.label, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun OpenAIFields(
    selection: SampleProviderSelection,
    onChange: (SampleProviderSelection) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = selection.openAiKey,
            onValueChange = { onChange(selection.copy(openAiKey = it)) },
            label = { Text("OpenAI API key") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = selection.openAiModel,
            onValueChange = { onChange(selection.copy(openAiModel = it)) },
            label = { Text("Model") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun OllamaFields(
    selection: SampleProviderSelection,
    onChange: (SampleProviderSelection) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = selection.ollamaModel,
            onValueChange = { onChange(selection.copy(ollamaModel = it)) },
            label = { Text("Model (e.g. llama3.2, gemma3:4b)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = selection.ollamaBaseUrl,
            onValueChange = { onChange(selection.copy(ollamaBaseUrl = it)) },
            label = { Text("Base URL") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
