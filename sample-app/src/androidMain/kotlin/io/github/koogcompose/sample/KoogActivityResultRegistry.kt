package io.github.koogcompose.sample

import android.content.Context
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.suspendCancellableCoroutine

class KoogActivityResultRegistry(
    private val context: Context,
) {

    private var pendingPhotoUri: Uri? = null

    private val capturePhotoChannel = Channel<Uri?>(Channel.RENDEZVOUS)
    private val pickFileChannel = Channel<Uri?>(Channel.RENDEZVOUS)
    private val pickMultipleFilesChannel = Channel<List<Uri>>(Channel.RENDEZVOUS)
    private val permissionChannel = Channel<Boolean>(Channel.RENDEZVOUS)
    private val multiplePermissionsChannel = Channel<Map<String, Boolean>>(Channel.RENDEZVOUS)

    private lateinit var capturePhotoLauncher: ActivityResultLauncher<Uri>
    private lateinit var pickFileLauncher: ActivityResultLauncher<String>
    private lateinit var pickMultipleFilesLauncher: ActivityResultLauncher<Array<String>>
    private lateinit var permissionLauncher: ActivityResultLauncher<String>
    private lateinit var multiplePermissionsLauncher: ActivityResultLauncher<Array<String>>

    fun register(activity: ComponentActivity) {
        capturePhotoLauncher = activity.registerForActivityResult(
            ActivityResultContracts.TakePicture()
        ) { success ->
            val uri = pendingPhotoUri
            pendingPhotoUri = null
            capturePhotoChannel.trySend(if (success) uri else null)
        }

        pickFileLauncher = activity.registerForActivityResult(
            ActivityResultContracts.GetContent()
        ) { uri ->
            pickFileChannel.trySend(uri)
        }

        pickMultipleFilesLauncher = activity.registerForActivityResult(
            ActivityResultContracts.OpenMultipleDocuments()
        ) { uris ->
            pickMultipleFilesChannel.trySend(uris ?: emptyList())
        }

        permissionLauncher = activity.registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { granted ->
            permissionChannel.trySend(granted)
        }

        multiplePermissionsLauncher = activity.registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { result ->
            multiplePermissionsChannel.trySend(result)
        }
    }

    suspend fun capturePhoto(outputUri: Uri? = null): Uri? {
        val uri = outputUri ?: context.createTempImageUri()
        pendingPhotoUri = uri
        return suspendCancellableCoroutine { cont ->
            cont.invokeOnCancellation {
                pendingPhotoUri = null
                capturePhotoChannel.trySend(null)
            }
            capturePhotoLauncher.launch(uri)
        }
    }

    suspend fun pickFile(mimeType: String = "*/*"): Uri? {
        return suspendCancellableCoroutine { cont ->
            cont.invokeOnCancellation { pickFileChannel.trySend(null) }
            pickFileLauncher.launch(mimeType)
        }
    }

    suspend fun pickMultipleFiles(vararg mimeTypes: String): List<Uri> {
        return suspendCancellableCoroutine { cont ->
            cont.invokeOnCancellation { pickMultipleFilesChannel.trySend(emptyList()) }
            pickMultipleFilesLauncher.launch(mimeTypes.asList().toTypedArray())
        }
    }

    suspend fun requestPermission(permission: String): Boolean {
        return suspendCancellableCoroutine { cont ->
            cont.invokeOnCancellation { permissionChannel.trySend(false) }
            permissionLauncher.launch(permission)
        }
    }

    suspend fun requestPermissions(permissions: List<String>): Map<String, Boolean> {
        return suspendCancellableCoroutine { cont ->
            cont.invokeOnCancellation { multiplePermissionsChannel.trySend(emptyMap()) }
            multiplePermissionsLauncher.launch(permissions.toTypedArray())
        }
    }
}

private fun Context.createTempImageUri(): Uri {
    val file = java.io.File.createTempFile(
        "koog_photo_",
        ".jpg",
        cacheDir
    )
    return androidx.core.content.FileProvider.getUriForFile(
        this,
        "$packageName.fileprovider",
        file
    )
}
