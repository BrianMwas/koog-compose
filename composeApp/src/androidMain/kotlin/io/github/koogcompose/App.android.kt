package io.github.koogcompose

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.koogcompose.demo.buildDemoContext
import io.github.koogcompose.demo.DemoScreen
import io.github.koogcompose.demo.DemoViewModel
import io.github.koogcompose.session.KoogDefinition
import io.github.koogcompose.demo.AppState

@Composable
fun App() {
    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            val agentDef: KoogDefinition<AppState> = remember { buildDemoContext() }
            val executor = remember(agentDef) { agentDef.createExecutor() }
            val viewModel = viewModel {
                DemoViewModel(agentDef, executor)
            }
            DemoScreen(viewModel)
        }
    }
}
