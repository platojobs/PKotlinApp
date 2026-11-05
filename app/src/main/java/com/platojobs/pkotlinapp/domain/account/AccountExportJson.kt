package com.platojobs.pkotlinapp.domain.account

import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * 把多页 `GET /me/export` 合并成一个 JSON：user/devices 取并集（按 id 去重），
 * readings 按页顺序拼接；导出文件可直接作为账号数据备份。
 */
object AccountExportJson {

    /** 合并后的结构；`readings` 保持服务端原始条目。 */
    fun merge(pages: List<AccountExportPage>): Map<String, Any?> {
        val devices = LinkedHashMap<String, Map<String, Any?>>()
        val readings = ArrayList<Map<String, Any?>>()
        pages.forEach { page ->
            page.devices.forEach { device ->
                val key = device["id"]?.toString()
                    ?: device["externalId"]?.toString()
                    ?: device.hashCode().toString()
                devices.putIfAbsent(key, device)
            }
            readings += page.readings
        }
        val first = pages.firstOrNull()
        return linkedMapOf(
            "user" to first?.user,
            "devices" to devices.values.toList(),
            "readings" to readings,
            "exportedAt" to first?.exportedAtRaw,
            "pageCount" to pages.size,
        )
    }

    fun render(moshi: Moshi, pages: List<AccountExportPage>): String {
        val type = Types.newParameterizedType(Map::class.java, String::class.java, Any::class.java)
        return moshi.adapter<Map<String, Any?>>(type)
            .indent("  ")
            .toJson(merge(pages))
    }

    /** `account_export_<userId>_<UTC 秒>.json`，只落在 cacheDir/exports。 */
    fun fileName(userId: String?, timestamp: Long): String {
        val id = userId?.takeIf { it.isNotBlank() } ?: "anonymous"
        val formatter = SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        return "account_export_${id}_${formatter.format(Date(timestamp))}.json"
    }
}
