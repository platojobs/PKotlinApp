package com.platojobs.pkotlinapp.ui.common

import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding

/** 横向系统栏/刘海安全区。 */
fun View.applyHorizontalInsets() {
    ViewCompat.setOnApplyWindowInsetsListener(this) { view, insets ->
        val bars = insets.getInsets(
            WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
        )
        view.updatePadding(left = bars.left, right = bars.right)
        insets
    }
}

/** 顶部系统栏安全区，用于 Toolbar。 */
fun View.applyTopInset() {
    ViewCompat.setOnApplyWindowInsetsListener(this) { view, insets ->
        val bars = insets.getInsets(
            WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
        )
        view.updatePadding(top = bars.top)
        insets
    }
}

/** 底部系统栏安全区（导航栏/手势条）。 */
fun View.applyBottomInset() {
    ViewCompat.setOnApplyWindowInsetsListener(this) { view, insets ->
        val bars = insets.getInsets(
            WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
        )
        view.updatePadding(bottom = bars.bottom)
        insets
    }
}
