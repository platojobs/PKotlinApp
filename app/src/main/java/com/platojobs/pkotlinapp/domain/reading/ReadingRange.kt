package com.platojobs.pkotlinapp.domain.reading

/**
 * 详情页可切换的查询区间与默认分桶。
 *
 * 分桶必须与服务端 UTC 网格一致（1m 整分钟 / 1h 整点 / 1d UTC 零点），
 * 且单个区间产生的桶数不得超过 [MAX_BUCKETS]（服务端硬上限 500）。
 */
enum class ReadingRange(val hours: Int, val bucket: String) {
    HOUR_1(1, "1m"),
    HOUR_24(24, "1h"),
    DAY_7(7 * 24, "1h"),
    DAY_30(30 * 24, "1d"),
    ;

    val windowMillis: Long get() = hours.toLong() * 60L * 60L * 1000L

    /** 区间可能产生的桶数（保守向上取整，含边界）。 */
    fun bucketCount(): Int = ReadingRange.bucketCount(windowMillis, bucket)

    /** 若默认桶会产生超过 500 桶，则自动退到更粗的粒度。 */
    fun effectiveBucket(): String = ReadingRange.chooseBucket(windowMillis, bucket)

    companion object {
        const val MAX_BUCKETS = 500
        val DEFAULT = HOUR_24

        val BUCKETS = listOf("1m", "1h", "1d")

        fun bucketMillis(bucket: String): Long = when (bucket) {
            "1m" -> 60_000L
            "1h" -> 3_600_000L
            "1d" -> 86_400_000L
            else -> 3_600_000L
        }

        fun bucketCount(spanMillis: Long, bucket: String): Int {
            if (spanMillis <= 0L) return 0
            val size = bucketMillis(bucket)
            return ((spanMillis + size - 1L) / size).toInt()
        }

        /**
         * 从首选粒度开始，逐级加粗直到桶数 ≤ [MAX_BUCKETS]。
         * `1m` → `1h` → `1h`（7 天仍为 168 桶）；`1d` 为最粗粒度。
         */
        fun chooseBucket(spanMillis: Long, preferred: String): String {
            val startIndex = BUCKETS.indexOf(preferred).takeIf { it >= 0 } ?: BUCKETS.lastIndex
            for (index in startIndex until BUCKETS.size) {
                if (bucketCount(spanMillis, BUCKETS[index]) <= MAX_BUCKETS) return BUCKETS[index]
            }
            return BUCKETS.last()
        }

        /** UTC 对齐的桶起点：epoch 毫秒向下取整到桶边界。 */
        fun bucketStart(recordedAt: Long, bucket: String): Long {
            val size = bucketMillis(bucket)
            if (recordedAt <= 0L) return 0L
            return recordedAt / size * size
        }
    }
}
