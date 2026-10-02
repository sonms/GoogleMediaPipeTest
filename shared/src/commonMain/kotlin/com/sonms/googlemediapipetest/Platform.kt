package com.sonms.googlemediapipetest

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform