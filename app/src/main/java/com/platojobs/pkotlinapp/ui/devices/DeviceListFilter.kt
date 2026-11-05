package com.platojobs.pkotlinapp.ui.devices

import com.platojobs.pkotlinapp.data.prefs.DeviceSortMode
import com.platojobs.pkotlinapp.domain.model.Device
import com.platojobs.pkotlinapp.domain.model.displayName

/**
 * 设备列表的检索/筛选/排序（纯 Kotlin）。
 * 搜索覆盖别名、广播名、型号展示名与 MAC；MAC 忽略分隔符且大小写不敏感。
 */
data class DeviceListFilter(
    val search: String = "",
    val sort: DeviceSortMode = DeviceSortMode.RECENT,
    val group: String? = null,
) {
    val isActive: Boolean get() = search.isNotBlank() || group != null
}

object DeviceListFiltering {

    /** 「未分组」筛选项的哨兵 key；真实分组名不会包含控制字符。 */
    const val GROUP_UNGROUPED = "\u0000ungrouped"

    /** 列表顶部的分组选项：真实分组（去重升序）+ 存在未分组设备时追加「未分组」。 */
    fun groupOptions(devices: List<Device>): List<String> {
        val groups = devices.mapNotNull { it.group?.trim()?.takeIf(String::isNotEmpty) }
            .distinct()
            .sortedWith(String.CASE_INSENSITIVE_ORDER)
            .toMutableList()
        if (devices.any { it.group.isNullOrBlank() }) groups.add(GROUP_UNGROUPED)
        return groups
    }

    fun apply(devices: List<Device>, filter: DeviceListFilter): List<Device> =
        devices.asSequence()
            .filter { matches(it, filter.search) }
            .filter { matchesGroup(it, filter.group) }
            .sortedWith(comparator(filter.sort))
            .toList()

    fun matches(device: Device, rawQuery: String): Boolean {
        val query = rawQuery.trim()
        if (query.isEmpty()) return true
        val needle = query.lowercase()
        val mac = device.mac.filter(Char::isLetterOrDigit).lowercase()
        val macQuery = query.filter(Char::isLetterOrDigit).lowercase()
        return listOfNotNull(
            device.name,
            device.advertisedName,
            device.deviceTypeName,
            device.deviceType,
        ).any { it.contains(needle, ignoreCase = true) } ||
            (macQuery.isNotEmpty() && mac.contains(macQuery)) ||
            device.mac.contains(needle, ignoreCase = true)
    }

    private fun matchesGroup(device: Device, group: String?): Boolean = when (group) {
        null -> true
        GROUP_UNGROUPED -> device.group.isNullOrBlank()
        else -> device.group?.trim() == group
    }

    private fun comparator(sort: DeviceSortMode): Comparator<Device> = when (sort) {
        DeviceSortMode.RECENT ->
            compareByDescending<Device> { it.lastSeenTimestamp }.thenBy { it.mac }

        DeviceSortMode.NAME ->
            Comparator<Device> { a, b ->
                val left = displayNameOf(a)
                val right = displayNameOf(b)
                when {
                    left.isEmpty() && right.isEmpty() -> a.mac.compareTo(b.mac)
                    left.isEmpty() -> 1
                    right.isEmpty() -> -1
                    else -> left.compareTo(right, ignoreCase = true).takeIf { it != 0 }
                        ?: a.mac.compareTo(b.mac)
                }
            }

        DeviceSortMode.RSSI ->
            compareByDescending<Device> { it.lastRssi }.thenBy { displayNameOf(it) }

        DeviceSortMode.ADDED ->
            compareByDescending<Device> { it.createdAt }.thenBy { it.mac }
    }

    private fun displayNameOf(device: Device): String = device.displayName().orEmpty().trim()
}
