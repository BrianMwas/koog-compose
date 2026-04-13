package io.github.koogcompose.demo

import io.github.koogcompose.session.SessionMessage
import kotlinx.serialization.Serializable

/**
 * Shared app state for the koog-compose demo.
 * Demonstrates typed state management across tools and UI.
 */
@Serializable
data class AppState(
    val userName: String? = null,
    val greetingShown: Boolean = false,
    val lastTopic: String? = null
)
