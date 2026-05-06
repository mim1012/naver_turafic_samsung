package com.navertraffic.samsung.data

data class AccountLease(
    val leaseId: String,
    val accountAlias: String,
    val loginId: String,
    val password: String,
    val expiresAt: String,
)

enum class AccountLeaseResult {
    SUCCESS,
    FAILED,
    PROTECTED,
    MANUAL_CHECK_REQUIRED,
    SESSION_EXPIRED,
}

enum class ProtectionSignal {
    PROTECTION_TEXT,
    CAPTCHA_OR_SECURITY_PAGE,
    PHONE_VERIFICATION_REQUIRED,
    EMAIL_VERIFICATION_REQUIRED,
    PASSWORD_RETRY_REQUIRED,
    LOGIN_STILL_REQUIRED,
    SESSION_EXPIRED,
    UNEXPECTED_LOGOUT,
    SAMSUNG_INTERNET_MISSING,
}

data class AccountLeaseReport(
    val leaseId: String?,
    val deviceName: String,
    val result: AccountLeaseResult,
    val signals: List<ProtectionSignal> = emptyList(),
    val lastUrl: String? = null,
    val message: String? = null,
)
