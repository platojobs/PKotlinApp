package com.platojobs.pkotlinapp.ui.common

import android.content.Context
import com.platojobs.pkotlinapp.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object TimeFormat {

    /** 只展示日期（会话到期日等不需要时分）。 */
    fun date(context: Context, timestamp: Long): String {
        if (timestamp <= 0L) return context.getString(R.string.time_unknown)
        return SimpleDateFormat(
            context.getString(R.string.time_format_date),
            Locale.getDefault(),
        ).format(Date(timestamp))
    }

    fun relative(context: Context, timestamp: Long): String {
        if (timestamp <= 0L) return context.getString(R.string.time_unknown)
        val diff = System.currentTimeMillis() - timestamp
        return when {
            diff < 60_000L -> context.getString(R.string.time_just_now)
            diff < 3_600_000L -> {
                val minutes = diff / 60_000L
                context.resources.getQuantityString(R.plurals.time_minutes_ago, minutes.toInt(), minutes)
            }
            diff < 86_400_000L -> {
                val hours = diff / 3_600_000L
                context.resources.getQuantityString(R.plurals.time_hours_ago, hours.toInt(), hours)
            }
            diff < 7 * 86_400_000L -> {
                val days = diff / 86_400_000L
                context.resources.getQuantityString(R.plurals.time_days_ago, days.toInt(), days)
            }
            else -> SimpleDateFormat(
                context.getString(R.string.time_format),
                Locale.getDefault(),
            ).format(Date(timestamp))
        }
    }
}
