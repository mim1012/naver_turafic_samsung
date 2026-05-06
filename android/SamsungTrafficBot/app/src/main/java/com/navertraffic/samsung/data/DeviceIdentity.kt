package com.navertraffic.samsung.data

data class DeviceIdentity(
    val rawName: String,
    val role: Role,
    val groupId: String,
    val index: Int?,
) {
    enum class Role { BOSS, SOLDIER }

    val isBoss: Boolean get() = role == Role.BOSS
    val isSoldier: Boolean get() = role == Role.SOLDIER

    companion object {
        fun parse(name: String): DeviceIdentity? {
            val trimmed = name.trim()
            Regex("""^(z\d+)$""", RegexOption.IGNORE_CASE).find(trimmed)?.let {
                return DeviceIdentity(trimmed, Role.BOSS, it.groupValues[1].lowercase(), null)
            }
            Regex("""^(z\d+)-(\d+)$""", RegexOption.IGNORE_CASE).find(trimmed)?.let {
                return DeviceIdentity(
                    rawName = trimmed,
                    role = Role.SOLDIER,
                    groupId = it.groupValues[1].lowercase(),
                    index = it.groupValues[2].toInt(),
                )
            }
            return null
        }

        fun validateInput(name: String): String? {
            if (name.isBlank()) return "장비명을 입력하세요"
            if (parse(name) == null) return "형식 오류: z1 또는 z1-1"
            return null
        }
    }
}
