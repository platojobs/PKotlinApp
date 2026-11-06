package com.platojobs.pkotlinapp.domain.reading

/**
 * 本地兜底分桶：网络不可用时把 Room 里的读数按与服务端完全相同的 UTC 网格聚合，
 * 保证离线图表与在线图表的时间定位口径一致。
 */
object ReadingSeriesBucketing {

    fun aggregate(readings: List<Reading>, bucket: String): List<ReadingBucket> =
        aggregateRows(
            rows = readings,
            bucket = bucket,
            value = { it.value },
            recordedAt = { it.recordedAt },
            id = { it.id },
        )

    /** 服务端原始读数的客户端聚合：24 小时视图的 30 分钟桶走这条路径。 */
    fun aggregateRemote(readings: List<RemoteReading>, bucket: String): List<ReadingBucket> =
        aggregateRows(
            rows = readings,
            bucket = bucket,
            value = { it.value },
            recordedAt = { it.recordedAt },
            id = { it.id },
        )

    private fun <T> aggregateRows(
        rows: List<T>,
        bucket: String,
        value: (T) -> Double,
        recordedAt: (T) -> Long,
        id: (T) -> String,
    ): List<ReadingBucket> {
        val size = ReadingRange.bucketMillis(bucket)
        val valid = rows.filter { value(it).isFinite() && recordedAt(it) > 0L }
        val groups = valid.groupBy { ReadingRange.bucketStart(recordedAt(it), bucket) }

        return groups.entries
            .sortedBy { it.key }
            .map { (start, group) ->
                val values = group.map(value)
                val lastRow = group.maxWith(compareBy<T>(recordedAt).thenBy(id))
                ReadingBucket(
                    start = start,
                    end = start + size,
                    count = group.size.toLong(),
                    min = values.min(),
                    max = values.max(),
                    avg = values.average(),
                    last = value(lastRow),
                )
            }
    }
}
