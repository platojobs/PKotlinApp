package com.platojobs.pkotlinapp.domain.reading

/**
 * 本地兜底分桶：网络不可用时把 Room 里的读数按与服务端完全相同的 UTC 网格聚合，
 * 保证离线图表与在线图表的时间定位口径一致。
 */
object ReadingSeriesBucketing {

    fun aggregate(readings: List<Reading>, bucket: String): List<ReadingBucket> {
        val size = ReadingRange.bucketMillis(bucket)
        val groups = readings
            .filter { it.value.isFinite() && it.recordedAt > 0L }
            .groupBy { ReadingRange.bucketStart(it.recordedAt, bucket) }

        return groups.entries
            .sortedBy { it.key }
            .map { (start, rows) ->
                val values = rows.map { it.value }
                val lastRow = rows.maxWith(
                    compareBy<Reading> { it.recordedAt }.thenBy { it.id },
                )
                ReadingBucket(
                    start = start,
                    end = start + size,
                    count = rows.size.toLong(),
                    min = values.min(),
                    max = values.max(),
                    avg = values.average(),
                    last = lastRow.value,
                )
            }
    }
}
