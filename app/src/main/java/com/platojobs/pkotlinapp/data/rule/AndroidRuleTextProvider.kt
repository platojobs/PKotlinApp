package com.platojobs.pkotlinapp.data.rule

import android.content.Context
import com.platojobs.pkotlinapp.R
import com.platojobs.pkotlinapp.domain.rule.RuleTextProvider

class AndroidRuleTextProvider(private val context: Context) : RuleTextProvider {

    override fun lowRssi(deviceName: String, rssi: Int, threshold: Int): String =
        context.getString(R.string.rule_event_low_rssi, deviceName, rssi, threshold)

    override fun disconnected(deviceName: String): String =
        context.getString(R.string.rule_event_disconnected, deviceName)

    override fun bondLost(deviceName: String): String =
        context.getString(R.string.rule_event_bond_lost, deviceName)
}
