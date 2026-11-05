package com.platojobs.pkotlinapp.ui.common

import androidx.annotation.StringRes
import androidx.fragment.app.Fragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.platojobs.pkotlinapp.R

/**
 * 先说明、后申请：用户点「继续」才弹系统权限框，避免首次进入直接出现系统弹窗。
 */
object PermissionRationale {

    fun show(
        fragment: Fragment,
        @StringRes titleRes: Int,
        @StringRes messageRes: Int,
        onContinue: () -> Unit,
    ) {
        MaterialAlertDialogBuilder(fragment.requireContext())
            .setTitle(titleRes)
            .setMessage(messageRes)
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.permission_dialog_continue) { _, _ -> onContinue() }
            .show()
    }
}
