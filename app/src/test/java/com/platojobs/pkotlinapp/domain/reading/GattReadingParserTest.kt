package com.platojobs.pkotlinapp.domain.reading

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 黄金字节表：同一输入必须产出唯一输出，与 iOS 口径保持一致。
 * 所有非法路径都返回空，不崩溃、不产生脏数据。
 */
class GattReadingParserTest {

    private val at = 1_760_000_000_000L

    private fun hex(vararg bytes: Int): ByteArray =
        bytes.map { it.toByte() }.toByteArray()

    @Test
    fun `heart rate golden cases`() {
        val normal = GattReadingParser.parseHeartRate(hex(0x00, 0x48), at)
        assertNotNull(normal)
        assertEquals(ReadingMetrics.HEART_RATE, normal!!.metric)
        assertEquals(72.0, normal.value, 0.0)
        assertEquals("bpm", normal.unit)

        // 0 不是合法心率。
        assertNull(GattReadingParser.parseHeartRate(hex(0x00, 0x00), at))

        // flags bit0=1：uint16 小端，0x012C = 300。
        val wide = GattReadingParser.parseHeartRate(hex(0x01, 0x2C, 0x01), at)
        assertEquals(300.0, wide!!.value, 0.0)

        // 0x012D = 301，超范围。
        assertNull(GattReadingParser.parseHeartRate(hex(0x01, 0x2D, 0x01), at))
    }

    @Test
    fun `heart rate rejects truncated payloads`() {
        assertNull(GattReadingParser.parseHeartRate(byteArrayOf(), at))
        assertNull(GattReadingParser.parseHeartRate(hex(0x00), at))
        assertNull(GattReadingParser.parseHeartRate(hex(0x01, 0x2C), at))
    }

    @Test
    fun `battery accepts 0 and 100 golden cases`() {
        val full = GattReadingParser.parseBattery(hex(0x64), at)
        assertEquals(100.0, full!!.value, 0.0)
        assertEquals(ReadingMetrics.BATTERY, full.metric)
        assertEquals("%", full.unit)

        val empty = GattReadingParser.parseBattery(hex(0x00), at)
        assertEquals(0.0, empty!!.value, 0.0)

        assertNull(GattReadingParser.parseBattery(hex(0x65), at))
        assertNull(GattReadingParser.parseBattery(byteArrayOf(), at))
    }

    @Test
    fun `temperature golden cases`() {
        // 2A 08 -> 0x082A = 2090 -> 20.90℃（sint16 × 0.01 优先）。
        val scaled = GattReadingParser.parseTemperature(hex(0x2A, 0x08), at)
        assertEquals(20.90, scaled!!.value, 0.001)
        assertEquals(ReadingMetrics.TEMPERATURE, scaled.metric)
        assertEquals("°C", scaled.unit)

        // 70 FE -> 0xFE70 = -400 -> -4.00℃。
        val negative = GattReadingParser.parseTemperature(hex(0x70, 0xFE), at)
        assertEquals(-4.00, negative!!.value, 0.001)

        // FF FF -> sint16 = -1 -> -0.01℃；按优先口径必须保留，不能当 half NaN 丢弃。
        val minusOneHundredth = GattReadingParser.parseTemperature(hex(0xFF, 0xFF), at)
        assertEquals(-0.01, minusOneHundredth!!.value, 0.0001)

        // 00 00 C0 3F -> float32 little-endian = 1.5℃。
        val float32 = GattReadingParser.parseTemperature(hex(0x00, 0x00, 0xC0, 0x3F), at)
        assertEquals(1.5, float32!!.value, 0.0)
    }

    @Test
    fun `temperature half fallback when scaled is implausible`() {
        // 需求表的 `00 4A` 实际 IEEE-754 half 为 12.0℃（20.0℃ 的正确编码是 `00 4D`）。
        // 这里按真实 half 解码固化，避免把错误期望写进生产逻辑。
        val halfFromTable = GattReadingParser.parseTemperature(hex(0x00, 0x4A), at)
        assertEquals(12.0, halfFromTable!!.value, 0.001)

        val twenty = GattReadingParser.parseTemperature(hex(0x00, 0x4D), at)
        assertEquals(20.0, twenty!!.value, 0.001)

        // 36.5 的 half 编码 0x5090：放大值 206.24℃ 不合理，回退 half。
        val thirtySix = GattReadingParser.parseTemperature(hex(0x90, 0x50), at)
        assertEquals(36.5, thirtySix!!.value, 0.01)
    }

    @Test
    fun `temperature rejects out of range and non finite`() {
        assertNull(GattReadingParser.parseTemperature(byteArrayOf(), at))
        assertNull(GattReadingParser.parseTemperature(hex(0x01), at))
        // 0x7FFF：放大值 327.67℃，half 为 NaN，均不可用。
        assertNull(GattReadingParser.parseTemperature(hex(0xFF, 0x7F), at))
        // float32 NaN。
        assertNull(GattReadingParser.parseTemperature(hex(0x00, 0x00, 0xC0, 0x7F), at))
        // float32 200℃ 超范围。
        assertNull(GattReadingParser.parseTemperature(hex(0x00, 0x00, 0x48, 0x43), at))
    }

    @Test
    fun `nus parses one reading per line`() {
        val single = GattReadingParser.parseNus("temperature=23.5".toByteArray(), at)
        assertEquals(1, single.size)
        assertEquals(ReadingMetrics.TEMPERATURE, single.single().metric)
        assertEquals(23.5, single.single().value, 0.0)
        assertEquals("°C", single.single().unit)
        assertNull(single.single().rawSnapshot)

        val plain = GattReadingParser.parseNus("42".toByteArray(), at)
        assertEquals(1, plain.size)
        assertEquals(ReadingMetrics.OTHER, plain.single().metric)
        assertEquals(42.0, plain.single().value, 0.0)
        assertEquals("", plain.single().unit)
        assertEquals("42", plain.single().rawSnapshot)
    }

    @Test
    fun `nus multi line drops only the bad line`() {
        val samples = GattReadingParser.parseNus(
            "temperature=23.5\nbattery=88\nxx".toByteArray(),
            at,
        )
        assertEquals(2, samples.size)
        assertEquals(ReadingMetrics.TEMPERATURE, samples[0].metric)
        assertEquals(23.5, samples[0].value, 0.0)
        assertEquals(ReadingMetrics.BATTERY, samples[1].metric)
        assertEquals(88.0, samples[1].value, 0.0)
    }

    @Test
    fun `nus unknown metric falls back to other with raw snapshot`() {
        val sample = GattReadingParser.parseNus("foo=12".toByteArray(), at).single()
        assertEquals(ReadingMetrics.OTHER, sample.metric)
        assertEquals(12.0, sample.value, 0.0)
        assertEquals("foo=12", sample.rawSnapshot)
    }

    @Test
    fun `nus rejects non numeric and pathological lines`() {
        assertTrue(GattReadingParser.parseNus(byteArrayOf(), at).isEmpty())
        assertTrue(GattReadingParser.parseNus("   ".toByteArray(), at).isEmpty())
        assertTrue(GattReadingParser.parseNus("abc".toByteArray(), at).isEmpty())
        assertTrue(GattReadingParser.parseNus("=12".toByteArray(), at).isEmpty())
        assertTrue(GattReadingParser.parseNus("temperature=abc".toByteArray(), at).isEmpty())
        assertTrue(GattReadingParser.parseNus("temperature=NaN".toByteArray(), at).isEmpty())
        assertTrue(GattReadingParser.parseNus("temperature=Infinity".toByteArray(), at).isEmpty())
    }

    @Test
    fun `nus line limit only drops the offending line`() {
        // 单行超过 64 字符：只丢该行，后续合法行仍解析。
        val longLine = "temperature=" + "1".repeat(70)
        val samples = GattReadingParser.parseNus("$longLine\nbattery=77".toByteArray(), at)
        assertEquals(1, samples.size)
        assertEquals(ReadingMetrics.BATTERY, samples.single().metric)
        assertEquals(77.0, samples.single().value, 0.0)
    }

    @Test
    fun `nus whole payload over 256 bytes is dropped`() {
        val payload = "temperature=23.5\n".repeat(20)
        assertTrue(payload.toByteArray().size > 256)
        assertTrue(GattReadingParser.parseNus(payload.toByteArray(), at).isEmpty())
    }

    @Test
    fun `parse returns empty for empty and unknown characteristic`() {
        assertTrue(
            GattReadingParser.parse(
                ReadingUuids.NUS_TX,
                byteArrayOf(),
                at,
            ).isEmpty(),
        )
        assertTrue(
            GattReadingParser.parse(
                "0000180d-0000-1000-8000-00805f9b34fb",
                hex(0x00, 0x48),
                at,
            ).isEmpty(),
        )
    }

    @Test
    fun `parse dispatches heart rate and nus multi line`() {
        val heartRate = GattReadingParser.parse(
            ReadingUuids.HEART_RATE,
            hex(0x00, 0x48),
            at,
        )
        assertEquals(1, heartRate.size)
        assertEquals(72.0, heartRate.single().value, 0.0)

        val nus = GattReadingParser.parse(
            ReadingUuids.NUS_TX,
            "temperature=23.5\nbattery=88\nxx".toByteArray(),
            at,
        )
        assertEquals(2, nus.size)
    }

    @Test
    fun `byte snapshot is uppercase and truncated`() {
        val snapshot = GattReadingParser.byteSnapshot(hex(0x01, 0xAB), maxChars = 8)
        assertEquals("01:AB", snapshot)
        assertTrue(snapshot.length <= 8)
    }
}
