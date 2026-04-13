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
import io.github.koogcompose.demo.AppState
import io.github.koogcompose.session.UnifiedAgentResult
import io.github.koogcompose.session.createExecutor

@Composable
fun App() {
    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            val agentResult: UnifiedAgentResult<AppState> = remember { buildDemoContext() }
            val executor = remember(agentResult) { agentResult.createExecutor() }
            val viewModel = viewModel {
                DemoViewModel(agentResult, executor)
            }
            DemoScreen(viewModel)
        }
    }
}
