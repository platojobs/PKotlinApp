package com.platojobs.pkotlinapp.ui.common

import android.app.Activity
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File

/**
 * 导出文件分享：文件只落在 cacheDir/exports，经 FileProvider 授予临时读权限，
 * 交给系统分享面板（ACTION_SEND）。CSV 与账号 JSON 导出共用这一套。
 */
object FileSharing {

    fun share(activity: Activity, file: File, mimeType: String, chooserTitle: String): Boolean =
        runCatching {
            val uri = FileProvider.getUriForFile(
                activity,
                "${activity.packageName}.fileprovider",
                file,
            )
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = mimeType
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            activity.startActivity(Intent.createChooser(intent, chooserTitle))
            true
        }.getOrDefault(false)
}
