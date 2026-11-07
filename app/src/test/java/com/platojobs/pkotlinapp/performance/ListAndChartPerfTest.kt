package com.platojobs.pkotlinapp.performance

import com.platojobs.pkotlinapp.domain.model.Device
import com.platojobs.pkotlinapp.domain.model.Room
import com.platojobs.pkotlinapp.domain.reading.Reading
import com.platojobs.pkotlinapp.domain.reading.ReadingSeriesBucketing
import com.platojobs.pkotlinapp.ui.chart.ChartBucket
import com.platojobs.pkotlinapp.ui.chart.ReadingChartGeometry
import com.platojobs.pkotlinapp.ui.home.DeviceSection
import com.platojobs.pkotlinapp.ui.home.HomeListBuilder
import com.platojobs.pkotlinapp.ui.home.HomeOverview
import com.platojobs.pkotlinapp.ui.home.HomeUiState
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 大列表压测（纯 JVM，不含 Robolectric/真机）：
 * 1000 台设备构建首页条目 + 单设备 10000 条读数做分桶与图表几何。
 *
 * 注意：这里的数字是 JVM 字节码执行时间，**不是真机帧时间**；真机还叠加布局、
 * 测量与绘制开销。预算取实测中位数的数倍，仅用于拦截数量级回退。
 */
class ListAndChartPerfTest {

    @Test
    fun `1000 devices list build stays within budget`() {
        val state = largeHomeState(deviceCount = DEVICE_COUNT, rooms = ROOM_COUNT)
        // 预热 JIT，再取 10 次的算术平均。
        repeat(3) { HomeListBuilder.build(state, null, UNASSIGNED_LABEL) }

        val runs = 10
        val startNanos = System.nanoTime()
        var totalItems = 0
        repeat(runs) {
            totalItems = HomeListBuilder.build(state, null, UNASSIGNED_LABEL).size
        }
        val averageMs = (System.nanoTime() - startNanos) / 1_000_000.0 / runs
        println(
            "[perf] 首页列表构建：$DEVICE_COUNT 台设备 → $totalItems 条，JVM 平均 ${"%.2f".format(averageMs)} ms",
        )
        assertTrue("列表构建平均 ${"%.2f".format(averageMs)} ms 超出预算 $LIST_BUDGET_MS ms", averageMs < LIST_BUDGET_MS)
    }

    @Test
    fun `10000 readings chart geometry stays within budget`() {
        val readings = largeReadingSet(READING_COUNT)
        repeat(2) {
            ReadingChartGeometry.plot(
                bucketize(readings),
                readings.first().recordedAt,
                readings.last().recordedAt,
                LEFT,
                TOP,
                RIGHT,
                BOTTOM,
            )
        }

        val runs = 5
        val startNanos = System.nanoTime()
        var bucketCount = 0
        repeat(runs) {
            val buckets = bucketize(readings)
            bucketCount = buckets.size
            ReadingChartGeometry.plot(
                buckets,
                readings.first().recordedAt,
                readings.last().recordedAt,
                LEFT,
                TOP,
                RIGHT,
                BOTTOM,
            )
        }
        val averageMs = (System.nanoTime() - startNanos) / 1_000_000.0 / runs
        println(
            "[perf] 图表几何：$READING_COUNT 条读数 → $bucketCount 桶，JVM 平均 ${"%.2f".format(averageMs)} ms",
        )
        assertTrue("图表几何平均 ${"%.2f".format(averageMs)} ms 超出预算 $CHART_BUDGET_MS ms", averageMs < CHART_BUDGET_MS)
    }

    private fun bucketize(readings: List<Reading>): List<ChartBucket> =
        ReadingSeriesBucketing.aggregate(readings, "1h").map { bucket ->
            ChartBucket(
                start = bucket.start,
                end = bucket.end,
                min = bucket.min,
                max = bucket.max,
                avg = bucket.avg,
                count = bucket.count,
                last = bucket.last,
            )
        }

    private fun largeHomeState(deviceCount: Int, rooms: Int): HomeUiState {
        val now = 1_700_000_000_000L
        val roomList = (1L..rooms.toLong()).map { Room(id = it, name = "Room $it", sortOrder = it.toInt()) }
        val devices = (1..deviceCount).map { index ->
            Device(
                mac = "AA:BB:CC:00:%02X:%02X".format(index / 256, index % 256),
                lastRssi = -60,
                lastSeenTimestamp = now - index.toLong() * 1_000L,
                roomId = (index % rooms + 1).toLong(),
                householdId = 1L,
            )
        }
        val sections = roomList.map { room ->
            DeviceSection(room = room, devices = devices.filter { it.roomId == room.id })
        }
        val readings = devices.associate { device ->
            device.mac to Reading(
                id = "r-${device.mac}",
                deviceId = device.mac,
                metric = "temperature",
                value = 20.0 + (device.mac.hashCode() % 10),
                unit = "°C",
                recordedAt = device.lastSeenTimestamp,
            )
        }
        return HomeUiState(
            devices = devices,
            sections = sections,
            rooms = roomList,
            latestReadings = readings,
            overview = HomeOverview(
                todayReadings = deviceCount,
                localConnectedCount = deviceCount,
            ),
            groups = listOf("Living", "Bedroom"),
            householdSynced = true,
            loading = false,
        )
    }

    private fun largeReadingSet(count: Int): List<Reading> {
        val start = 1_700_000_000_000L
        return (0 until count).map { index ->
            Reading(
                id = "r-$index",
                deviceId = "device-1",
                metric = "temperature",
                value = 20.0 + (index % 50) / 10.0,
                unit = "°C",
                recordedAt = start + index.toLong() * 60_000L,
            )
        }
    }

    private companion object {
        const val DEVICE_COUNT = 1000
        const val ROOM_COUNT = 20
        const val READING_COUNT = 10_000
        const val UNASSIGNED_LABEL = "Unassigned"
        const val LEFT = 40f
        const val TOP = 0f
        const val RIGHT = 1080f
        const val BOTTOM = 400f

        /**
         * JVM 预算：本机实测列表约 0.2 ms、图表约 1.8 ms；
         * 预算保留 ~100x / ~35x 余量以吸收 CI 机器差异，只拦截数量级回退。
         */
        const val LIST_BUDGET_MS = 20.0
        const val CHART_BUDGET_MS = 60.0
    }
}
