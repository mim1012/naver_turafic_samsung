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
    fun protectionSignalIsNotRecoverableTaskFailure() {
        val result = StrategyAResult(
            success = false,
            signals = listOf(ProtectionSignal.LOGIN_STILL_REQUIRED),
            message = "protection signal detected",
        )

        assertFalse(result.isRecoverableTaskFailure())
    }
}
