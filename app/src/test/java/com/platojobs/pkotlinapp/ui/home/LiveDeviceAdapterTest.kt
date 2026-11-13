package com.platojobs.pkotlinapp.ui.home

import android.view.LayoutInflater
import android.view.View
import androidx.appcompat.view.ContextThemeWrapper
import com.platojobs.pkotlinapp.R
import com.platojobs.pkotlinapp.databinding.ItemLiveDeviceBinding
import com.platojobs.pkotlinapp.domain.model.ScannedDevice
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 扫描行与设备卡共用 [com.platojobs.pkotlinapp.ui.common.RssiLevel.isValid]：
 * 0 / 127 等非负值不是真实读数，不能渲染成「0 dBm」满格。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "zh-rCN")
class LiveDeviceAdapterTest {

    private val app = RuntimeEnvironment.getApplication()

    @Test
    fun `invalid rssi hides the dbm value instead of printing raw number`() {
        listOf(0, 127).forEach { raw ->
            val binding = bind(raw)
            assertEquals("rssi=$raw", View.GONE, binding.rssi.visibility)
            assertEquals("rssi=$raw", "", binding.rssi.text.toString())
            assertEquals(
                "rssi=$raw",
                app.getString(R.string.rssi_content_desc, app.getString(R.string.rssi_desc_unknown)),
                binding.signal.contentDescription,
            )
        }
    }

    @Test
    fun `valid rssi renders dbm and tier description`() {
        val binding = bind(-72)
        assertEquals(View.VISIBLE, binding.rssi.visibility)
        assertEquals("-72 dBm", binding.rssi.text.toString())
        assertEquals(
            app.getString(R.string.rssi_content_desc, app.getString(R.string.rssi_desc_fair)),
            binding.signal.contentDescription,
        )
    }

    private fun bind(rssi: Int): ItemLiveDeviceBinding {
        val themed = ContextThemeWrapper(app, R.style.Theme_PKotlinApp)
        val binding = ItemLiveDeviceBinding.inflate(LayoutInflater.from(themed))
        LiveDeviceAdapter.ViewHolder(binding) {}.bind(
            ScannedDevice(
                name = "温湿度计",
                mac = "AA:BB:CC:DD:EE:01",
                rssi = rssi,
                timestamp = 0L,
            ),
        )
        return binding
    }
}
