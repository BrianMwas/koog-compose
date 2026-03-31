package io.github.koogcompose.session.room

import kotlinx.datetime.Clock

internal fun currentTimeMillis(): Long =
    Clock.System.now().toEpochMilliseconds()
