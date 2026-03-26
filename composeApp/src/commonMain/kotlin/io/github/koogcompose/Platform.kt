package io.github.koogcompose

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform