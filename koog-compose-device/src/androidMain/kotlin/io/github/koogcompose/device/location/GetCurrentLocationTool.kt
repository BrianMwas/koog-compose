package io.github.koogcompose.device.location

import android.annotation.SuppressLint
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import io.github.koogcompose.tool.PermissionLevel
import io.github.koogcompose.tool.SecureTool
import io.github.koogcompose.tool.ToolResult
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.coroutines.resume

public class GetCurrentLocationTool(
    context: Context
) : SecureTool {
    private val appContext = context.applicationContext
    private val fusedLocationClient = LocationServices.getFusedLocationProviderClient(appContext)

    override val name: String = "get_current_location"
    override val description: String =
        "Reads the device's current latitude and longitude coordinates."
    override val permissionLevel: PermissionLevel = PermissionLevel.SENSITIVE

    @SuppressLint("MissingPermission")
    override suspend fun execute(args: JsonObject): ToolResult {
        return suspendCancellableCoroutine { continuation ->
            val cancellationTokenSource = CancellationTokenSource()
            continuation.invokeOnCancellation { cancellationTokenSource.cancel() }
            val hasFineLocation = ContextCompat.checkSelfPermission(
                appContext,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
            val hasCoarseLocation = ContextCompat.checkSelfPermission(
                appContext,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED

            if (!hasFineLocation && !hasCoarseLocation) {
                continuation.resume(
                    ToolResult.Failure(
                        "Location permission not granted. Request ACCESS_FINE_LOCATION or ACCESS_COARSE_LOCATION first."
                    )
                )
                return@suspendCancellableCoroutine
            }

            try {
                fusedLocationClient
                    .getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, cancellationTokenSource.token)
                    .addOnSuccessListener { location ->
                        val result = if (location == null) {
                            ToolResult.Failure("Current location unavailable")
                        } else {
                            ToolResult.Success(
                                locationToolJson.encodeToString(
                                    JsonObject.serializer(),
                                    buildJsonObject {
                                        put("latitude", location.latitude)
                                        put("longitude", location.longitude)
                                        put("accuracyMeters", location.accuracy.toDouble())
                                        put("provider", location.provider ?: "unknown")
                                        put("timestampMs", location.time)
                                    }
                                )
                            )
                        }
                        continuation.resume(result)
                    }
                    .addOnFailureListener { error ->
                        continuation.resume(
                            ToolResult.Failure(error.message ?: "Failed to read current location")
                        )
                    }
            } catch (error: SecurityException) {
                continuation.resume(
                    ToolResult.Failure(error.message ?: "Location permission was revoked")
                )
            }
        }
    }

    override fun confirmationMessage(args: JsonObject): String =
        "Allow the assistant to access this device's current location?"

}

private val locationToolJson = Json { encodeDefaults = true }
