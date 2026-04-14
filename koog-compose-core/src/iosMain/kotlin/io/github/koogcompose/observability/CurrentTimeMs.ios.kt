package io.github.koogcompose.observability

import platform.Foundation.NSDate

internal actual fun currentTimeMs(): Long =
    (NSDate.date().timeIntervalSince1970 * 1000).toLong()
