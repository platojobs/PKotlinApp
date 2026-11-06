package com.platojobs.pkotlinapp.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 服务端（Go time.Time）输出 RFC3339Nano，小数位 0–9 位且会去掉末尾 0；
 * 客户端必须能解析，否则场景响应会被 toRemoteScene 整条丢弃。
 */
class SceneTimestampsTest {

    @Test
    fun `nano precision from go time is truncated to millis`() {
        val expected = SceneTimestamps.parse("2026-11-06T10:00:00.123Z")
        assertEquals(expected, SceneTimestamps.parse("2026-11-06T10:00:00.123456789Z"))
        assertEquals(expected, SceneTimestamps.parse("2026-11-06T10:00:00.123456Z"))
    }

    @Test
    fun `short fractional second is padded to millis`() {
        val expected = SceneTimestamps.parse("2026-11-06T10:00:00.100Z")
        assertEquals(expected, SceneTimestamps.parse("2026-11-06T10:00:00.1Z"))
        assertEquals(expected, SceneTimestamps.parse("2026-11-06T10:00:00.10Z"))
    }

    @Test
    fun `server local offset from list endpoint parses to the same instant`() {
        // create 返回 Z，list 返回服务器本地时区（+08:00），两者是同一时刻。
        val utc = SceneTimestamps.parse("2026-11-06T10:26:07.409599Z")
        assertEquals(utc, SceneTimestamps.parse("2026-11-06T18:26:07.409599+08:00"))
    }

    @Test
    fun `second precision and client millis format still parse`() {
        val second = SceneTimestamps.parse("2026-11-06T10:00:00Z")
        assertEquals(second, SceneTimestamps.parse(SceneTimestamps.format(second!!)))
        assertNull(SceneTimestamps.parse("not-a-time"))
        assertNull(SceneTimestamps.parse(null))
    }
}
