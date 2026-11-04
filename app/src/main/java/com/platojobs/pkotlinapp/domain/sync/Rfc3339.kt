package com.platojobs.pkotlinapp.domain.sync

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * 服务端契约统一使用 RFC 3339（UTC）时间字符串；本地 Room 使用 epoch millis。
 *
 * 服务端 Go 的 `time.Time` 可能输出 0-9 位小数秒，这里统一截断/补齐到毫秒，
 * 避免 Android 24/25 上 java.time 不可用的问题。
 */
object Rfc3339 {
    private val PATTERN = Regex(
        """^(\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2})(\.\d+)?(Z|[+-]\d{2}:\d{2})$""",
    )

    fun format(epochMillis: Long): String {
        val formatter = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
        formatter.timeZone = TimeZone.getTimeZone("UTC")
        return formatter.format(Date(epochMillis))
    }

    fun parse(raw: String?): Long? {
        val value = raw?.trim().orEmpty()
        if (value.isEmpty()) return null
        val match = PATTERN.matchEntire(value) ?: return null
        val base = match.groupValues[1]
        val fraction = match.groupValues[2]
        val zone = match.groupValues[3]

        val millis = if (fraction.isEmpty()) {
            ""
        } else {
            "." + fraction.removePrefix(".").padEnd(3, '0').take(3)
        }
        // 'Z' 按 UTC 字面量解析；带偏移的 RFC 3339 用 ISO 8601 的 XXX 模式。
        val zonePattern = if (zone == "Z") "'Z'" else "XXX"
        // 输入统一成 3 位小数秒，但 pattern 必须使用 SSS 才会被解析为毫秒。
        val fractionPattern = if (millis.isEmpty()) "" else ".SSS"
        val normalized = "$base$millis$zone"
        val formatter = SimpleDateFormat(
            "yyyy-MM-dd'T'HH:mm:ss$fractionPattern$zonePattern",
            Locale.US,
        ).apply {
            isLenient = false
            timeZone = TimeZone.getTimeZone("UTC")
        }
        return runCatching { formatter.parse(normalized)?.time }.getOrNull()
    }
}
