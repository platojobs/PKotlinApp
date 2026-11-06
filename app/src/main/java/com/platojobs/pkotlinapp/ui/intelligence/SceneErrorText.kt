package com.platojobs.pkotlinapp.ui.intelligence

import android.content.Context
import androidx.annotation.StringRes
import com.platojobs.pkotlinapp.R

/**
 * 服务端场景错误码 → 可读文案（中英双语资源）。
 *
 * 后端 6 个 `SCENE_*` 校验错误 + 场景不存在 / 版本冲突；
 * 未知码统一落到通用文案，不把原始 message 直接展示给用户。
 */
object SceneErrorText {

    @StringRes
    fun resourceOf(code: String?): Int = when (code) {
        ERROR_NAME_INVALID -> R.string.scene_error_name_invalid
        ERROR_COOLDOWN_OUT_OF_RANGE -> R.string.scene_error_cooldown_out_of_range
        ERROR_TRIGGER_INVALID -> R.string.scene_error_trigger_invalid
        ERROR_ACTION_INVALID -> R.string.scene_error_action_invalid
        ERROR_DEVICE_NOT_FOUND -> R.string.scene_error_device_not_found
        ERROR_METRIC_NOT_SUPPORTED -> R.string.scene_error_metric_not_supported
        ERROR_NOT_FOUND, "SCENE_NOT_FOUND" -> R.string.scene_error_not_found
        ERROR_CONFLICT, "SCENE_VERSION_CONFLICT" -> R.string.scene_error_version_conflict
        else -> R.string.scene_error_unknown
    }

    fun of(context: Context, code: String?): String =
        context.getString(resourceOf(code))

    const val ERROR_NAME_INVALID = "SCENE_NAME_INVALID"
    const val ERROR_COOLDOWN_OUT_OF_RANGE = "SCENE_COOLDOWN_OUT_OF_RANGE"
    const val ERROR_TRIGGER_INVALID = "SCENE_TRIGGER_INVALID"
    const val ERROR_ACTION_INVALID = "SCENE_ACTION_INVALID"
    const val ERROR_DEVICE_NOT_FOUND = "SCENE_DEVICE_NOT_FOUND"
    const val ERROR_METRIC_NOT_SUPPORTED = "SCENE_METRIC_NOT_SUPPORTED"
    const val ERROR_NOT_FOUND = "NOT_FOUND"
    const val ERROR_CONFLICT = "CONFLICT"
}
