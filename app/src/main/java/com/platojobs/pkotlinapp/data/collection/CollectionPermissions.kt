package com.platojobs.pkotlinapp.data.collection

import android.content.Context
import android.os.Build
import com.platojobs.pkotlinapp.ui.common.NotificationPermission

/** 前台采集所需权限的只读判定，供 UI 提示与降级路径使用。 */
object CollectionPermissions {

    /** Android 13+ 通知权限被拒时，前台服务仍可运行，但抽屉里看不到常驻通知。 */
    fun notificationsVisible(context: Context): Boolean = NotificationPermission.isGranted(context)

    /** Android 14+ 需要声明 FOREGROUND_SERVICE_CONNECTED_DEVICE（manifest 已声明）。 */
    fun needsConnectedDeviceType(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE
}
