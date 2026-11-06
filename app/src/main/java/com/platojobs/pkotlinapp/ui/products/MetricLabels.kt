package com.platojobs.pkotlinapp.ui.products

import android.content.Context
import com.platojobs.pkotlinapp.R
import com.platojobs.pkotlinapp.domain.catalog.CatalogMetric

/**
 * 目录指标展示：已知 key 用本地化名称，未知 key 原样展示，不猜测含义。
 * 本机解析器不支持的特征必须带上「当前版本不支持」，不能假装能读。
 */
internal fun CatalogMetric.label(context: Context): String = when (key) {
    "heart_rate" -> context.getString(R.string.product_metric_heart_rate)
    "temperature_celsius" -> context.getString(R.string.product_metric_temperature)
    else -> if (unit.isBlank()) key else "$key (${unit})"
}

internal fun CatalogMetric.chipLabel(context: Context): String {
    val name = label(context)
    return if (supportedByApp) {
        name
    } else {
        context.getString(R.string.product_metric_unsupported_chip, name)
    }
}
