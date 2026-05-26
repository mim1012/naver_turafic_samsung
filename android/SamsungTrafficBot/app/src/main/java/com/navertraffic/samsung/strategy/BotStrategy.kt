package com.navertraffic.samsung.strategy

sealed class BotStrategy {
    class A(val strategy: SamsungBrowserStrategyA) : BotStrategy()
    class G(val strategy: SamsungBrowserStrategyG) : BotStrategy()
    class Multi(
        val strategyA: SamsungBrowserStrategyA,
        val strategyG: SamsungBrowserStrategyG,
    ) : BotStrategy()

    val strategyName: String get() = when (this) {
        is A -> "A"
        is G -> "G"
        is Multi -> "G"
    }
}
