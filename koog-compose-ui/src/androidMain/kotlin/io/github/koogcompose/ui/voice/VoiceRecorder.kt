package io.github.koogcompose.ui.voice

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * Records audio from the microphone to a temporary file.
 *
 * Usage:
 * ```kotlin
 * val recorder = VoiceRecorder(context)
 * val file = recorder.recordToFile()  // suspends until stopped
 * // Send file as Attachment.Audio(file.path)
 * ```
 */
public class VoiceRecorder(private val context: Context) {

    private var mediaRecorder: MediaRecorder? = null
    private var outputFile: File? = null
    private var isRecording = false

    /** True while audio is being captured. */
    public val recording: Boolean
        get() = isRecording

    /**
     * Starts recording and suspends until [stop] is called.
     * Returns the recorded audio file, or null if recording failed.
     */
    public suspend fun recordToFile(): File? = withContext(Dispatchers.IO) {
        val file = File(context.cacheDir, "voice_${UUID.randomUUID()}.m4a")
        outputFile = file

        try {
            mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioEncodingBitRate(128000)
                setAudioSamplingRate(44100)
                setOutputFile(file.absolutePath)
                prepare()
            }

            isRecording = true
            mediaRecorder?.start()

            // Suspend until stop() is called
            while (isRecording) {
                kotlinx.coroutines.delay(50)
            }

            mediaRecorder?.stop()
            mediaRecorder?.release()
            mediaRecorder = null

            if (file.exists() && file.length() > 0) file else null
        } catch (e: Exception) {
            mediaRecorder?.release()
            mediaRecorder = null
            isRecording = false
            null
        }
    }

    /**
     * Stops the current recording. The [recordToFile] suspend function
     * will return with the recorded file.
     */
    public fun stop() {
        isRecording = false
    }

    /**
     * Cancels recording without saving the file.
     */
    public fun cancel() {
        isRecording = false
        outputFile?.delete()
        try {
            mediaRecorder?.stop()
        } catch (_: Exception) {
            // ignore if already stopped
        }
        mediaRecorder?.release()
        mediaRecorder = null
    }
}
