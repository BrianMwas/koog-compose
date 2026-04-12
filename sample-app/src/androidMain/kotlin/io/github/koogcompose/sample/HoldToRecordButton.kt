package io.github.koogcompose.sample

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import io.github.koogcompose.session.Attachment
import io.github.koogcompose.ui.voice.VoiceRecorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Hold-to-record button. Records audio while pressed, sends as an attachment
 * when released.
 *
 * @param onVoiceFile Callback with the recorded audio file URI when recording stops.
 * @param modifier Modifier for this composable.
 */
@Composable
fun HoldToRecordButton(
    onVoiceFile: (Attachment.Audio) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var isRecording by remember { mutableStateOf(false) }
    val recorder = remember { VoiceRecorder(context) }
    val pulseScale = remember { Animatable(1f) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            // Start recording after permission granted
            startRecording(context, recorder) { isRecording = true }
        }
    }

    LaunchedEffect(isRecording) {
        if (isRecording) {
            pulseScale.animateTo(1.2f, tween(durationMillis = 500))
            while (isRecording) {
                pulseScale.animateTo(1.0f, tween(durationMillis = 400))
                pulseScale.animateTo(1.15f, tween(durationMillis = 400))
            }
            pulseScale.animateTo(1f, tween(durationMillis = 200))
        }
    }

    val backgroundColor by animateColorAsState(
        if (isRecording) MaterialTheme.colorScheme.error
        else MaterialTheme.colorScheme.primary,
    )

    IconButton(
        modifier = modifier
            .size(48.dp)
            .scale(pulseScale.value)
            .background(backgroundColor, shape = CircleShape)
            .pointerInput(isRecording) {
                if (!isRecording) {
                    detectTapGestures(
                        onPress = {
                            val hasPermission = ContextCompat.checkSelfPermission(
                                context, Manifest.permission.RECORD_AUDIO
                            ) == PackageManager.PERMISSION_GRANTED

                            if (hasPermission) {
                                startRecording(context, recorder) { isRecording = true }
                                tryAwaitRelease()
                                stopRecording(recorder, context) { file ->
                                    isRecording = false
                                    if (file != null) {
                                        onVoiceFile(
                                            Attachment.Audio(
                                                uri = file.absolutePath,
                                                displayName = "Voice message",
                                            )
                                        )
                                    }
                                }
                            } else {
                                permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            }
                        }
                    )
                }
            },
        onClick = {},
    ) {
        Icon(
            imageVector = if (isRecording) Icons.Default.Stop else Icons.Default.Mic,
            contentDescription = if (isRecording) "Stop recording" else "Hold to record",
            tint = MaterialTheme.colorScheme.onPrimary,
        )
    }
}

private fun startRecording(
    context: Context,
    recorder: VoiceRecorder,
    onStarted: () -> Unit,
) {
    kotlinx.coroutines.GlobalScope.launch(Dispatchers.IO) {
        onStarted()
        recorder.recordToFile()
    }
}

private fun stopRecording(
    recorder: VoiceRecorder,
    context: Context,
    onFile: (java.io.File?) -> Unit,
) {
    recorder.stop()
    // The recordToFile coroutine will complete and return the file
    // We poll for the result since we launched it in GlobalScope
    kotlinx.coroutines.GlobalScope.launch(Dispatchers.IO) {
        kotlinx.coroutines.delay(200)
        val cacheDir = context.cacheDir
        val latestFile = cacheDir.listFiles { f ->
            f.name.startsWith("voice_") && f.name.endsWith(".m4a")
        }?.maxByOrNull { it.lastModified() }
        onFile(latestFile)
    }
}
