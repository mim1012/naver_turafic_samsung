package com.navertraffic.samsung.soldier

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

class IpRotationManager {
    suspend fun rotate(log: (String) -> Unit) = withContext(Dispatchers.IO) {
        log("IP rotation: data off")
        Runtime.getRuntime().exec(arrayOf("su", "-c", "svc data disable")).waitFor()
        delay(5_000)
        log("IP rotation: data on")
        Runtime.getRuntime().exec(arrayOf("su", "-c", "svc data enable")).waitFor()
        delay(6_000)
    }
}
