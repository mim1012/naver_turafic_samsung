package com.navertraffic.samsung.strategy

data class StrategyGTask(
    val keyword: String,
    val keywordName: String,
    val linkUrl: String,
    val mid: String,
    val productTitle: String? = null,
) {
    fun validate(): String? {
        if (keyword.isBlank()) return "keyword is required"
        if (keywordName.isBlank()) return "keywordName is required for Strategy G"
        if (linkUrl.isBlank()) return "linkUrl is required"
        if (mid.isBlank()) return "mid is required for Strategy G"
        return null
    }
}
