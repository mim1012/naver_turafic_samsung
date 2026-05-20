package com.navertraffic.samsung.data

interface CaptchaChallengeClient {
    suspend fun submitChallenge(request: CaptchaChallengeRequest): String
    suspend fun pollAnswer(challengeId: String): CaptchaChallengeAnswer?
    suspend fun completeChallenge(challengeId: String, success: Boolean)
}

data class CaptchaChallengeRequest(
    val deviceName: String,
    val leaseId: String?,
    val accountAlias: String,
    val screenshotBase64: String,
    val signalType: String,
    val pageUrl: String?,
)

data class CaptchaChallengeAnswer(
    val challengeId: String,
    val status: String, // "pending", "answered", "expired"
    val answer: String?,
)
