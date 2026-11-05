package com.platojobs.pkotlinapp.domain.account

/**
 * 配对码输入约束，与服务端字符集一致：
 * `ABCDEFGHJKLMNPQRSTUVWXYZ23456789`（剔除易混的 0/O/1/I），固定 8 位。
 */
object PairingCodeFormat {

    const val LENGTH = 8

    /** 可用于输入框过滤的允许字符集（供 InputFilter 使用）。 */
    const val ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"

    /**
     * 规范化用户输入：去空白、转大写、只保留允许字符，并截断到 8 位。
     * 过滤而非报错，保证粘贴「k7m2-qx9p」这类带分隔符的码也能直接使用。
     */
    fun sanitize(raw: String?): String {
        val normalized = raw.orEmpty()
            .uppercase()
            .filter { it in ALPHABET }
        return normalized.take(LENGTH)
    }

    fun isValid(code: String?): Boolean {
        val value = code.orEmpty()
        return value.length == LENGTH && value.all { it in ALPHABET }
    }
}
