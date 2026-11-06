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

/**
 * 给可滚动容器叠加底部系统栏安全区：
 * 在 XML 里已有的 paddingBottom 基础上追加手势条/导航栏高度，
 * 配合 `android:clipToPadding="false"` 让最后一项能完整滚出手势条上方。
 */
fun View.applyBottomInsetPadding() {
    val baseBottom = paddingBottom
    ViewCompat.setOnApplyWindowInsetsListener(this) { view, insets ->
        val bars = insets.getInsets(
            WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
        )
        view.updatePadding(bottom = baseBottom + bars.bottom)
        insets
    }
}

/**
 * 给高度固定为 [baseHeight] 的控件（如 BottomNavigationView）补足底部系统栏安全区：
 * 控件整体高度增加 bars.bottom，同时把这段高度作为 padding，内容区仍是 baseHeight。
 */
fun View.applyBottomInsetHeight(baseHeight: Int) {
    ViewCompat.setOnApplyWindowInsetsListener(this) { view, insets ->
        val bars = insets.getInsets(
            WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
        )
        val target = baseHeight + bars.bottom
        if (view.layoutParams?.height != target) {
            view.layoutParams = view.layoutParams.apply { height = target }
            view.updatePadding(bottom = bars.bottom)
        }
        insets
    }
}
