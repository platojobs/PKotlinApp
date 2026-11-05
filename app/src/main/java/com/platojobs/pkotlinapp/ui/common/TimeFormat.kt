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
            diff < 3_600_000L -> context.getString(R.string.time_minutes_ago, diff / 60_000L)
            diff < 86_400_000L -> context.getString(R.string.time_hours_ago, diff / 3_600_000L)
            diff < 7 * 86_400_000L -> context.getString(R.string.time_days_ago, diff / 86_400_000L)
            else -> SimpleDateFormat(
                context.getString(R.string.time_format),
                Locale.getDefault(),
            ).format(Date(timestamp))
        }
    }
}
