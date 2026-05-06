package com.navertraffic.samsung.strategy

import com.navertraffic.samsung.data.ProtectionSignal

class ProtectionDetector {
    fun detectFromText(text: String): List<ProtectionSignal> {
        val signals = mutableListOf<ProtectionSignal>()
        val normalized = text.replace("\\s+".toRegex(), " ")

        if (
            normalized.contains("보호조치") ||
            normalized.contains("비정상적인 활동") ||
            normalized.contains("본인 확인") ||
            normalized.contains("본인확인")
        ) {
            signals += ProtectionSignal.PROTECTION_TEXT
        }

        if (
            normalized.contains("자동입력 방지") ||
            normalized.contains("보안문자") ||
            normalized.contains("캡차") ||
            normalized.contains("captcha", ignoreCase = true)
        ) {
            signals += ProtectionSignal.CAPTCHA_OR_SECURITY_PAGE
        }

        if (
            normalized.contains("휴대전화 인증") ||
            normalized.contains("휴대폰 인증") ||
            normalized.contains("휴대전화로 인증") ||
            normalized.contains("휴대폰으로 인증") ||
            normalized.contains("휴대전화 본인확인") ||
            normalized.contains("휴대폰 본인확인")
        ) {
            signals += ProtectionSignal.PHONE_VERIFICATION_REQUIRED
        }

        if (normalized.contains("이메일 인증")) {
            signals += ProtectionSignal.EMAIL_VERIFICATION_REQUIRED
        }

        if (normalized.contains("다시 로그인") || normalized.contains("비밀번호를 다시")) {
            signals += ProtectionSignal.PASSWORD_RETRY_REQUIRED
        }

        return signals.distinct()
    }
}
