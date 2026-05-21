package com.navertraffic.samsung.strategy

import com.navertraffic.samsung.data.ProtectionSignal
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StrategyAResultTest {
    @Test
    fun midNotFoundIsRecoverableTaskFailure() {
        val result = StrategyAResult(
            success = false,
            message = "MID product not found after timeout: 123",
        )

        assertTrue(result.isRecoverableTaskFailure())
    }

    @Test
    fun invalidTaskPayloadIsRecoverableTaskFailure() {
        val result = StrategyAResult(
            success = false,
            message = "mid is required for Strategy G",
        )

        assertTrue(result.isRecoverableTaskFailure())
    }

    @Test
    fun detailDomAndRateLimitFailuresAreRecoverableTaskFailures() {
        assertTrue(
            StrategyAResult(
                success = false,
                message = "detail_dom_not_confirmed:not_detail",
            ).isRecoverableTaskFailure(),
        )
        assertTrue(
            StrategyAResult(
                success = false,
                message = "rate_limited_429",
            ).isRecoverableTaskFailure(),
        )
    }

    @Test
    fun protectionSignalIsNotRecoverableTaskFailure() {
        val result = StrategyAResult(
            success = false,
            signals = listOf(ProtectionSignal.LOGIN_STILL_REQUIRED),
            message = "protection signal detected",
        )

        assertFalse(result.isRecoverableTaskFailure())
    }
}
