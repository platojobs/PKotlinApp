package com.platojobs.pkotlinapp.data.collection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CollectionSessionTest {

    private val session = CollectionSession()

    @Test
    fun `未启动时状态为空且不计数`() {
        assertFalse(session.status.value.active)
        session.record("AA:BB:CC:DD:EE:FF")
        assertEquals(0, session.status.value.count)
    }

    @Test
    fun `启动只匹配当前设备并累计条数`() {
        assertTrue(session.start("AA:BB:CC:DD:EE:FF", "心率带"))
        val started = session.status.value
        assertTrue(started.active)
        assertEquals("心率带", started.deviceName)
        assertEquals(0, started.count)

        session.record("aa:bb:cc:dd:ee:ff")
        session.record("AA:BB:CC:DD:EE:FF")
        // 大小写不同的同一 MAC 计入；其它设备不计入。
        session.record("11:22:33:44:55:66")
        assertEquals(2, session.status.value.count)
        assertTrue(session.isActiveFor("aa:bb:cc:dd:ee:ff"))
        assertFalse(session.isActiveFor("11:22:33:44:55:66"))
    }

    @Test
    fun `停止后清空状态且不再计数`() {
        session.start("AA:BB:CC:DD:EE:FF", "温度计")
        session.record("AA:BB:CC:DD:EE:FF")
        session.stop()

        assertFalse(session.status.value.active)
        assertEquals(0, session.status.value.count)
        assertFalse(session.isActiveFor("AA:BB:CC:DD:EE:FF"))

        session.record("AA:BB:CC:DD:EE:FF")
        assertEquals(0, session.status.value.count)
    }

    @Test
    fun `空 MAC 拒绝启动`() {
        assertFalse(session.start("", "设备"))
        assertFalse(session.status.value.active)
    }

    @Test
    fun `重新启动会从头计数`() {
        session.start("AA:BB:CC:DD:EE:FF", "设备 A")
        session.record("AA:BB:CC:DD:EE:FF")
        session.record("AA:BB:CC:DD:EE:FF")
        assertEquals(2, session.status.value.count)

        session.start("11:22:33:44:55:66", "设备 B")
        assertEquals(0, session.status.value.count)
        assertEquals("设备 B", session.status.value.deviceName)
    }
}
