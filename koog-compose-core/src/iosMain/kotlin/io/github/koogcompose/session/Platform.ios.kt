package io.github.koogcompose.session

import platform.Foundation.NSDate
import platform.Foundation.NSUUID
import platform.Foundation.timeIntervalSince1970

internal actual fun currentTimeMs(): Long =
    (NSDate().timeIntervalSince1970 * 1000).toLong()

internal actual fun randomId(): String =
    NSUUID().UUIDString()