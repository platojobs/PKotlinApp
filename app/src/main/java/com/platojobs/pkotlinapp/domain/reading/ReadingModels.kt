package com.platojobs.pkotlinapp.domain.reading

import java.util.UUID
import kotlin.math.pow

/** 服务端与本地统一的指标白名单；未知指标统一落到 [METRIC_OTHER] 并保留原始快照。 */
object ReadingMetrics {
    const val TEMPERATURE = "temperature"
    const val HUMIDITY = "humidity"
    const val BATTERY = "battery"
    const val HEART_RATE = "heart_rate"
    const val CO2 = "co2"
    const val PRESSURE = "pressure"
    const val OTHER = "other"

    val ALL = listOf(TEMPERATURE, HUMIDITY, BATTERY, HEART_RATE, CO2, PRESSURE, OTHER)

    /** 宽松归一化：兼容 `heart-rate`、`heartrate` 等常见写法，未知值落到 other。 */
    fun normalize(raw: String): String {
        val key = raw.trim().lowercase().replace('-', '_').replace(' ', '_')
        return when (key) {
            TEMPERATURE, "temp" -> TEMPERATURE
            HUMIDITY, "hum" -> HUMIDITY
            BATTERY, "batt" -> BATTERY
            HEART_RATE, "heartrate", "hr" -> HEART_RATE
            CO2, "co_2" -> CO2
            PRESSURE, "press" -> PRESSURE
            else -> OTHER
        }.takeIf { it in ALL } ?: OTHER
    }

    fun unit(metric: String): String = when (metric) {
        TEMPERATURE -> "°C"
        HUMIDITY -> "%"
        BATTERY -> "%"
        HEART_RATE -> "bpm"
        CO2 -> "ppm"
        PRESSURE -> "hPa"
        else -> ""
    }
}

/** 支持的 notify 特征 UUID（小写）。 */
object ReadingUuids {
    const val HEART_RATE = "00002a37-0000-1000-8000-00805f9b34fb"
    const val BATTERY = "00002a19-0000-1000-8000-00805f9b34fb"
    const val TEMPERATURE = "00002a6e-0000-1000-8000-00805f9b34fb"
    const val NUS_TX = "6e400003-b5a3-f393-e0a9-e50e24dcca9e"

    val supported = setOf(HEART_RATE, BATTERY, TEMPERATURE, NUS_TX)

    fun normalize(uuid: String): String = uuid.trim().lowercase()
}

/** 一条待落库/上报的读数（尚未分配本地 id）。 */
data class ReadingSample(
    val metric: String,
    val value: Double,
    val unit: String,
    val recordedAt: Long,
    val rawSnapshot: String? = null,
)

/** 本地读数记录。 */
data class Reading(
    val id: String,
    val deviceId: String,
    val metric: String,
    val value: Double,
    val unit: String,
    val recordedAt: Long,
    val syncState: String = SYNC_STATE_PENDING,
    val rawSnapshot: String? = null,
) {
    companion object {
        const val SYNC_STATE_PENDING = "pending"
        const val SYNC_STATE_SYNCED = "synced"
        const val RETENTION_DAYS = 7L
        const val LOCAL_RETENTION_MILLIS = RETENTION_DAYS * 24 * 60 * 60 * 1000L

        fun newId(): String = UUID.randomUUID().toString()
    }
}

/** 24h（或指定窗口）汇总；无数据时 [count] 为 0、其余为 null。 */
data class ReadingSummary(
    val metric: String,
    val unit: String,
    val count: Int,
    val min: Double? = null,
    val max: Double? = null,
    val avg: Double? = null,
    val last: Double? = null,
    val windowStart: Long = 0L,
    val windowEnd: Long = 0L,
)

/**
 * GATT notify 解析器。所有异常路径返回 null，由调用方静默丢弃并记录日志，
 * 绝不向 Room 写入非法值。
 */
object GattReadingParser {

    fun parse(characteristicUuid: String, value: ByteArray, recordedAt: Long): ReadingSample? =
        when (ReadingUuids.normalize(characteristicUuid)) {
            ReadingUuids.HEART_RATE -> parseHeartRate(value, recordedAt)
            ReadingUuids.BATTERY -> parseBattery(value, recordedAt)
            ReadingUuids.TEMPERATURE -> parseTemperature(value, recordedAt)
            ReadingUuids.NUS_TX -> parseNus(value, recordedAt)
            else -> null
        }

    /** 心率测量：flags 首字节，bit0=0 时第 2 字节 uint8，否则第 2-3 字节 uint16。 */
    fun parseHeartRate(value: ByteArray, recordedAt: Long): ReadingSample? {
        if (value.size < 2) return null
        val flags = value[0].toInt() and 0xFF
        val bpm = if (flags and 0x01 == 0) {
            value[1].toInt() and 0xFF
        } else {
            if (value.size < 3) return null
            (value[1].toInt() and 0xFF) or ((value[2].toInt() and 0xFF) shl 8)
        }
        if (bpm <= 0 || bpm > 300) return null
        return ReadingSample(
            metric = ReadingMetrics.HEART_RATE,
            value = bpm.toDouble(),
            unit = ReadingMetrics.unit(ReadingMetrics.HEART_RATE),
            recordedAt = recordedAt,
        )
    }

    /** 电池电量：单字节百分比，0..100。 */
    fun parseBattery(value: ByteArray, recordedAt: Long): ReadingSample? {
        if (value.isEmpty()) return null
        val percent = value[0].toInt() and 0xFF
        if (percent > 100) return null
        return ReadingSample(
            metric = ReadingMetrics.BATTERY,
            value = percent.toDouble(),
            unit = ReadingMetrics.unit(ReadingMetrics.BATTERY),
            recordedAt = recordedAt,
        )
    }

    /**
     * 温度：兼容两种实际固件格式：
     * - 0x2A6E 规范定义的 sint16 / 0.01℃ 放大值；
     * - 部分固件使用的 IEEE-754 半精度（2 字节 little-endian）。
     * 放大值落在 -40..125℃ 时优先按规范解释；否则回退半精度，两者都不合理则丢弃。
     */
    fun parseTemperature(value: ByteArray, recordedAt: Long): ReadingSample? {
        if (value.size < 2) return null
        val raw = (value[0].toInt() and 0xFF) or ((value[1].toInt() and 0xFF) shl 8)
        val half = halfToFloat(raw)
        val scaled = signed16(value) / 100.0
        val halfPlausible = half.isFinite() && half in MIN_TEMPERATURE..MAX_TEMPERATURE
        val scaledPlausible = scaled in MIN_TEMPERATURE..MAX_TEMPERATURE
        val celsius = when {
            scaledPlausible -> scaled
            halfPlausible -> half.toDouble()
            else -> return null
        }
        return ReadingSample(
            metric = ReadingMetrics.TEMPERATURE,
            value = celsius,
            unit = ReadingMetrics.unit(ReadingMetrics.TEMPERATURE),
            recordedAt = recordedAt,
        )
    }

    /**
     * Nordic UART TX 文本行：
     * - `metric=value`（metric 不在白名单时记 other 并保留原文）；
     * - 纯数字（记 other，单位为「」，保留原文）。
     * 超长、非数字、NaN/Infinity 都丢弃。
     */
    fun parseNus(value: ByteArray, recordedAt: Long): ReadingSample? {
        if (value.isEmpty() || value.size > MAX_NUS_BYTES) return null
        val text = String(value, Charsets.UTF_8).trim()
        if (text.isEmpty()) return null
        val line = text.lineSequence().firstOrNull { it.isNotBlank() }?.trim() ?: return null
        if (line.length > MAX_NUS_CHARS) return null

        val separator = line.indexOf('=')
        if (separator > 0) {
            val rawMetric = line.substring(0, separator).trim()
            if (rawMetric.isEmpty()) return null
            val amount = line.substring(separator + 1).trim().toDoubleOrNull() ?: return null
            if (!amount.isFinite()) return null
            val metric = ReadingMetrics.normalize(rawMetric)
            return ReadingSample(
                metric = metric,
                value = amount,
                unit = ReadingMetrics.unit(metric),
                recordedAt = recordedAt,
                rawSnapshot = if (metric == ReadingMetrics.OTHER) line.take(MAX_SNAPSHOT_CHARS) else null,
            )
        }

        val amount = line.toDoubleOrNull() ?: return null
        if (!amount.isFinite()) return null
        return ReadingSample(
            metric = ReadingMetrics.OTHER,
            value = amount,
            unit = "",
            recordedAt = recordedAt,
            rawSnapshot = line.take(MAX_SNAPSHOT_CHARS),
        )
    }

    /** 未知指标的原始字节摘要（大写十六进制，截断避免撑爆数据库）。 */
    fun byteSnapshot(value: ByteArray, maxChars: Int = MAX_SNAPSHOT_CHARS): String =
        value.joinToString(":") { byte ->
            (byte.toInt() and 0xFF).toString(16).uppercase().padStart(2, '0')
        }.take(maxChars)

    private fun signed16(value: ByteArray): Int {
        val raw = (value[0].toInt() and 0xFF) or ((value[1].toInt() and 0xFF) shl 8)
        return if (raw >= 0x8000) raw - 0x10000 else raw
    }

    private const val MIN_TEMPERATURE = -40.0
    private const val MAX_TEMPERATURE = 125.0
    private const val MAX_NUS_BYTES = 256
    private const val MAX_NUS_CHARS = 256
    private const val MAX_SNAPSHOT_CHARS = 64
}

/** IEEE-754 half -> float（不使用平台 API，便于 JVM 单测）。 */
internal fun halfToFloat(bits: Int): Float {
    val sign = bits and 0x8000
    val exponent = (bits shr 10) and 0x1F
    val mantissa = bits and 0x03FF
    val magnitude = when {
        exponent == 0 -> mantissa.toFloat() / 1024f * 2f.pow(-14)
        exponent == 0x1F -> if (mantissa == 0) Float.POSITIVE_INFINITY else Float.NaN
        else -> (1f + mantissa.toFloat() / 1024f) * 2f.pow(exponent - 15)
    }
    return if (sign != 0) -magnitude else magnitude
}
