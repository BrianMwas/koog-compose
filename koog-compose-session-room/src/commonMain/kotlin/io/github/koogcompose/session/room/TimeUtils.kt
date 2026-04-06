package io.github.koogcompose.session.room

import kotlin.time.Clock

internal fun currentTimeMillis(): Long =
    Clock.System.now().toEpochMilliseconds()
