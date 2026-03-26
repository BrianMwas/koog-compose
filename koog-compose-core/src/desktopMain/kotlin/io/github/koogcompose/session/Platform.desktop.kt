package io.github.koogcompose.session

import java.util.UUID

internal actual fun currentTimeMs(): Long = System.currentTimeMillis()
internal actual fun randomId(): String = UUID.randomUUID().toString()