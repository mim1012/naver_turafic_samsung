package com.navertraffic.samsung.strategy

import com.navertraffic.samsung.data.ProtectionSignal
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtectionDetectorTest {
    private val detector = ProtectionDetector()

    @Test
    fun ignoresGenericPhoneTextOnNormalPages() {
        val signals = detector.detectFromText("고객센터 휴대전화 문의 및 일반 상품 안내")

        assertFalse(signals.contains(ProtectionSignal.PHONE_VERIFICATION_REQUIRED))
    }

    @Test
    fun detectsPhoneVerificationContext() {
        val signals = detector.detectFromText("안전한 로그인을 위해 휴대전화 인증을 진행해 주세요")

        assertTrue(signals.contains(ProtectionSignal.PHONE_VERIFICATION_REQUIRED))
    }
}
