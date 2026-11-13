package com.platojobs.pkotlinapp.ui.detail

import com.platojobs.pkotlinapp.data.ble.RssiReadState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** 锁住 RSSI 读数行的三态文案：正常、刷新失败但有旧值、从未成功过。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "zh-rCN")
class RssiReadHintTest {

    private val context = RuntimeEnvironment.getApplication()

    @Test
    fun `stale with previous value says refresh failed and shows that reading`() {
        val text = rssiUpdatedAtText(context, TIMESTAMP, RssiReadState.STALE)
        assertTrue(text, text!!.startsWith("刷新失败，显示上次读数 · "))
    }

    @Test
    fun `stale without any successful read says read failed with no reading`() {
        assertEquals(
            "读取失败，暂无读数",
            rssiUpdatedAtText(context, null, RssiReadState.STALE),
        )
    }

    @Test
    fun `current reading shows read time and idle hides the line`() {
        val text = rssiUpdatedAtText(context, TIMESTAMP, RssiReadState.CURRENT)
        assertTrue(text, text!!.startsWith("读数时间 "))
        assertNull(rssiUpdatedAtText(context, null, RssiReadState.IDLE))
    }

    private companion object {
        const val TIMESTAMP = 1_762_000_000_000L
    }
}
