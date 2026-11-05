package com.platojobs.pkotlinapp.domain.reading

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** GATT 解析器回归：合法、非法、超长、非数字都必须不崩溃且不产生脏数据。 */
class GattReadingParserTest {

    private val at = 1_760_000_000_000L

    @Test
    fun `heart rate uint8 and uint16`() {
        val sample = GattReadingParser.parseHeartRate(byteArrayOf(0x00, 72), at)
        assertNotNull(sample)
        assertEquals(ReadingMetrics.HEART_RATE, sample!!.metric)
        assertEquals(72.0, sample.value, 0.0)
        assertEquals("bpm", sample.unit)

        // flags bit0=1 表示 uint16，300 是允许上界。
        val wide = GattReadingParser.parseHeartRate(byteArrayOf(0x01, 0x2C, 0x01), at)
        assertEquals(300.0, wide!!.value, 0.0)
    }

    @Test
    fun `heart rate rejects truncated and out of range`() {
        assertNull(GattReadingParser.parseHeartRate(byteArrayOf(), at))
        assertNull(GattReadingParser.parseHeartRate(byteArrayOf(0x00), at))
        assertNull(GattReadingParser.parseHeartRate(byteArrayOf(0x01, 0x2C), at))
        assertNull(GattReadingParser.parseHeartRate(byteArrayOf(0x00, 0), at))
        assertNull(GattReadingParser.parseHeartRate(byteArrayOf(0x01, 0x2D, 0x01), at))
    }

    @Test
    fun `battery accepts 0 to 100 and rejects others`() {
        val sample = GattReadingParser.parseBattery(byteArrayOf(88), at)
        assertEquals(88.0, sample!!.value, 0.0)
        assertEquals(ReadingMetrics.BATTERY, sample.metric)
        assertEquals("%", sample.unit)

        assertNull(GattReadingParser.parseBattery(byteArrayOf(), at))
        assertNull(GattReadingParser.parseBattery(byteArrayOf(101), at))
        assertNull(GattReadingParser.parseBattery(byteArrayOf(255.toByte()), at))
    }

    @Test
    fun `temperature parses ieee half and centi degree formats`() {
        // 0x5090 是 36.5 的 IEEE-754 half 编码。
        val half = GattReadingParser.parseTemperature(byteArrayOf(0x90.toByte(), 0x50), at)
        assertEquals(36.5, half!!.value, 0.01)

        // 3100 / 100 = 31.0℃，half 解释落在不合理区间，应回退到放大值。
        val scaled = GattReadingParser.parseTemperature(byteArrayOf(0x1C, 0x0C), at)
        assertEquals(31.0, scaled!!.value, 0.01)

        val negative = GattReadingParser.parseTemperature(byteArrayOf(0x18, 0xFC.toByte()), at)
        assertEquals(-10.0, negative!!.value, 0.01)
    }

    @Test
    fun `temperature rejects implausible and truncated values`() {
        assertNull(GattReadingParser.parseTemperature(byteArrayOf(), at))
        assertNull(GattReadingParser.parseTemperature(byteArrayOf(0x01), at))
        // 0x7FFF：half 为 NaN，放大值为 327.67℃，均超出 -40..125。
        assertNull(GattReadingParser.parseTemperature(byteArrayOf(0xFF.toByte(), 0x7F), at))
    }

    @Test
    fun `nus parses metric equals value and plain numbers`() {
        val temperature = GattReadingParser.parseNus("temperature=23.5".toByteArray(), at)
        assertEquals(ReadingMetrics.TEMPERATURE, temperature!!.metric)
        assertEquals(23.5, temperature.value, 0.0)
        assertEquals("°C", temperature.unit)
        assertNull(temperature.rawSnapshot)

        val plain = GattReadingParser.parseNus("42".toByteArray(), at)
        assertEquals(ReadingMetrics.OTHER, plain!!.metric)
        assertEquals(42.0, plain.value, 0.0)
        assertEquals("42", plain.rawSnapshot)
    }

    @Test
    fun `nus unknown metric falls back to other with raw snapshot`() {
        val sample = GattReadingParser.parseNus("foo=12".toByteArray(), at)
        assertEquals(ReadingMetrics.OTHER, sample!!.metric)
        assertEquals(12.0, sample.value, 0.0)
        assertEquals("foo=12", sample.rawSnapshot)
    }

    @Test
    fun `nus rejects non numeric overlong and pathological values`() {
        assertNull(GattReadingParser.parseNus(byteArrayOf(), at))
        assertNull(GattReadingParser.parseNus("   ".toByteArray(), at))
        assertNull(GattReadingParser.parseNus("abc".toByteArray(), at))
        assertNull(GattReadingParser.parseNus("=12".toByteArray(), at))
        assertNull(GattReadingParser.parseNus("temperature=abc".toByteArray(), at))
        assertNull(GattReadingParser.parseNus("temperature=NaN".toByteArray(), at))
        assertNull(GattReadingParser.parseNus("temperature=Infinity".toByteArray(), at))
        // 超长（>256 字节）整行丢弃，避免异常报文进入数据库。
        val overlong = "temperature=" + "1".repeat(300)
        assertNull(GattReadingParser.parseNus(overlong.toByteArray(), at))
        // 只有首行有效；空行被跳过。
        val multiline = GattReadingParser.parseNus("\n \ntemperature=25".toByteArray(), at)
        assertEquals(25.0, multiline!!.value, 0.0)
    }

    @Test
    fun `unknown characteristic is silently ignored`() {
        assertNull(
            GattReadingParser.parse(
                "0000180d-0000-1000-8000-00805f9b34fb",
                byteArrayOf(0x00, 60),
                at,
            ),
        )
    }

    @Test
    fun `byte snapshot is uppercase and truncated`() {
        val snapshot = GattReadingParser.byteSnapshot(byteArrayOf(0x01, 0xAB.toByte()), maxChars = 8)
        assertEquals("01:AB", snapshot)
        assertTrue(snapshot.length <= 8)
    }
}
