package com.navertraffic.samsung.strategy

data class StrategyATask(
    val keyword: String,
    val secondKeyword: String,
    val linkUrl: String,
    val mid: String? = null,
    val productTitle: String? = null,
) {
    fun validate(): String? {
        if (keyword.isBlank()) return "keyword is required"
        if (secondKeyword.isBlank()) return "secondKeyword is required for Strategy A"
        if (linkUrl.isBlank()) return "linkUrl is required"
        return null
    }
}
