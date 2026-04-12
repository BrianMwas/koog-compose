package io.github.koogcompose.sample

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProgressIndicatorDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import io.github.koogcompose.litertlm.LiteRtLmProvider
import io.github.koogcompose.session.Attachment
import io.github.koogcompose.ui.components.ChatInputBar
import io.github.koogcompose.ui.components.ChatMessageList
import io.github.koogcompose.ui.state.ChatState
import io.github.koogcompose.ui.state.rememberChatState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Math Tutor sample app — fully offline using LiteRT-LM + Gemma 3.
 *
 * Features:
 * - On-device AI (no internet needed after model download)
 * - Photo capture → explain math problems
 * - Voice recording → ask math questions
 * - Step-by-step explanations with KaTeX-style formatting
 */
@Composable
fun MathTutorApp(
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    var modelPath by remember { mutableStateOf<String?>(null) }
    var downloadProgress by remember { mutableStateOf<Float?>(null) }

    // 1. Ensure the LiteRT-LM model is downloaded
    LaunchedEffect(Unit) {
        val path = ensureModelDownloaded(context) { progress ->
            downloadProgress = progress
        }
        modelPath = path
    }

    // Show download progress
    downloadProgress?.let { progress ->
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator(
                progress = { progress },
                modifier = Modifier,
                color = ProgressIndicatorDefaults.circularColor,
                strokeWidth = ProgressIndicatorDefaults.CircularStrokeWidth,
                trackColor = ProgressIndicatorDefaults.circularIndeterminateTrackColor,
                strokeCap = ProgressIndicatorDefaults.CircularDeterminateStrokeCap,
            )
            Text("Downloading model: ${(progress * 100).toInt()}%")
        }
        return
    }

    // Model not ready
    if (modelPath == null) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            Text("Loading model...", style = MaterialTheme.typography.bodyLarge)
        }
        return
    }

    // 2. Build the AI provider
    val provider = remember(modelPath) {
        LiteRtLmProvider(
            context = context,
            modelPath = modelPath!!,
        )
    }

    // 3. Build the chat state
    val chatState = rememberChatState(
        provider = provider,
        context = io.github.koogcompose.session.KoogComposeContext<Unit> {
            provider {
                // Not used by LiteRtLmProvider — placeholder for DSL compatibility
                ollama(model = "gemma3-1b-it")
            }
            phases {
                phase("math_tutor", initial = true) {
                    instructions {
                        """
                        You are a patient, encouraging math tutor for students.
                        When shown a photo of a math problem, explain the solution step by step.
                        Use clear, simple language. Never just give the answer — teach the method.
                        Use KaTeX notation for math: $$x^2 + 2x + 1 = 0$$
                        When the user asks via voice, respond with clear explanations.
                        If the user doesn't understand, re-explain differently.
                        """.trimIndent()
                    }
                }
            }
        },
    )

    // 4. Camera permission + launcher
    val cameraLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicturePreview()
    ) { bitmap ->
        if (bitmap != null) {
            val file = saveBitmapToCache(context, bitmap)
            chatState.addAttachment(
                Attachment.Image(
                    uri = file.absolutePath,
                    displayName = "Math problem photo",
                )
            )
        }
    }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            cameraLauncher.launch(null)
        }
    }

    // 5. Main UI
    MathTutorScreen(
        chatState = chatState,
        onCameraClick = {
            val hasPermission = ContextCompat.checkSelfPermission(
                context, Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED

            if (hasPermission) {
                cameraLauncher.launch(null)
            } else {
                cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
            }
        },
        snackbarHostState = snackbarHostState,
        modifier = modifier,
    )
}

/**
 * Ensures the LiteRT-LM model is downloaded to the app's cache.
 * Downloads from HuggingFace on first run, then reuses the cached file.
 *
 * Model: Gemma3-1B-IT from https://huggingface.co/litert-community/Gemma3-1B-IT
 */
private suspend fun ensureModelDownloaded(
    context: Context,
    onProgress: (Float) -> Unit,
): String? = withContext(Dispatchers.IO) {
    val cacheDir = File(context.cacheDir, "litertlm_models")
    cacheDir.mkdirs()
    val destFile = File(cacheDir, "gemma3-1b-it.litertlm")

    if (destFile.exists() && destFile.length() > 0) {
        return@withContext destFile.absolutePath
    }

    // Download from HuggingFace
    val modelUrl = "https://huggingface.co/litert-community/Gemma3-1B-IT/resolve/main/model.litertlm"
    try {
        val connection = URL(modelUrl).openConnection() as HttpURLConnection
        connection.connectTimeout = 30_000
        connection.readTimeout = 60_000
        connection.connect()

        val contentLength = connection.contentLengthLong
        if (contentLength <= 0) {
            return@withContext null
        }

        connection.inputStream.use { input ->
            FileOutputStream(destFile).use { output ->
                val buffer = ByteArray(8192)
                var totalRead = 0L
                var read: Int

                while (input.read(buffer).also { read = it } != -1) {
                    output.write(buffer, 0, read)
                    totalRead += read
                    onProgress(totalRead.toFloat() / contentLength)
                }
            }
        }

        destFile.absolutePath
    } catch (e: Exception) {
        destFile.delete()
        null
    }
}

/**
 * Saves a bitmap to the app's cache directory and returns the file.
 */
private fun saveBitmapToCache(context: Context, bitmap: android.graphics.Bitmap): File {
    val cacheDir = File(context.cacheDir, "photos")
    cacheDir.mkdirs()
    val file = File(cacheDir, "math_problem_${System.currentTimeMillis()}.jpg")
    FileOutputStream(file).use { stream ->
        bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, stream)
    }
    return file
}

@Composable
private fun MathTutorScreen(
    chatState: ChatState,
    onCameraClick: () -> Unit,
    snackbarHostState: androidx.compose.material3.SnackbarHostState,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .statusBarsPadding(),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            androidx.compose.foundation.layout.Column(
                modifier = androidx.compose.foundation.layout.fillMaxWidth()
            ) {
                Text(
                    text = "Math Tutor (Offline AI)",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = androidx.compose.foundation.layout.padding(
                        horizontal = 16.dp, vertical = 12.dp
                    ),
                )
                Text(
                    text = "Powered by Gemma 3 — no internet needed",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = androidx.compose.foundation.layout.padding(
                        start = 16.dp, bottom = 4.dp
                    ),
                )
            }
        },
        bottomBar = {
            ChatInputBar(
                chatState = chatState,
                placeholder = "Ask a math question or show me a problem...",
                leadingActions = {
                    // Camera button
                    androidx.compose.material3.IconButton(onClick = onCameraClick) {
                        androidx.compose.material3.Icon(
                            androidx.compose.material.icons.Icons.Default.PhotoCamera,
                            contentDescription = "Take photo of math problem",
                        )
                    }
                },
                trailingActions = {
                    HoldToRecordButton(
                        onVoiceFile = { audio ->
                            chatState.addAttachment(audio)
                            chatState.send()
                        }
                    )
                }
            )
        }
    ) { innerPadding ->
        ChatMessageList(
            chatState = chatState,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            showSystemMessages = false,
            showToolCallMessages = true,
        )
    }
}

@androidx.compose.ui.tooling.preview.Preview(showBackground = true)
@Composable
private fun PreviewMathTutorApp() {
    MaterialTheme {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            Text("Math Tutor — requires LiteRT-LM model to run")
        }
    }
}
