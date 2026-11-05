package com.platojobs.pkotlinapp.ui

import android.animation.ValueAnimator
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.animation.DecelerateInterpolator
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.widget.NestedScrollView
import androidx.fragment.app.Fragment
import androidx.fragment.app.commit
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.RecyclerView
import com.platojobs.pkotlinapp.PKotlinApplication
import com.platojobs.pkotlinapp.R
import com.platojobs.pkotlinapp.databinding.ActivityMainBinding
import com.platojobs.pkotlinapp.ui.common.applyBottomInset
import com.platojobs.pkotlinapp.ui.common.applyHorizontalInsets
import com.platojobs.pkotlinapp.ui.common.applyTopInset
import com.platojobs.pkotlinapp.ui.home.HomeFragment
import com.platojobs.pkotlinapp.ui.intelligence.IntelligenceFragment
import com.platojobs.pkotlinapp.ui.market.MarketFragment
import com.platojobs.pkotlinapp.ui.onboarding.OnboardingActivity
import com.platojobs.pkotlinapp.ui.products.ProductsFragment
import com.platojobs.pkotlinapp.ui.profile.ProfileFragment
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * 五模块容器：首页 / 智能 / 产品 / 市场 / 我的。
 * 顶部大标题是自绘的：展开 96dp，滚动超过 12dp 后 200ms 收为 56dp。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val fragmentTags = mapOf(
        R.id.nav_home to "home",
        R.id.nav_intelligence to "intelligence",
        R.id.nav_products to "products",
        R.id.nav_market to "market",
        R.id.nav_profile to "profile",
    )

    private val titleRes = mapOf(
        R.id.nav_home to R.string.nav_home,
        R.id.nav_intelligence to R.string.nav_intelligence,
        R.id.nav_products to R.string.nav_products,
        R.id.nav_market to R.string.nav_market,
        R.id.nav_profile to R.string.nav_profile,
    )

    private var headerCollapsed = false
    private var headerAnimator: ValueAnimator? = null
    private var attachedScroll: View? = null
    private var suppressNavSelection = false
    private var recyclerListener: RecyclerView.OnScrollListener? = null
    private var nestedScrollListener: NestedScrollView.OnScrollChangeListener? = null
    private var scrollListener: View.OnScrollChangeListener? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.root.applyHorizontalInsets()
        binding.appHeader.applyTopInset()
        binding.bottomNav.applyBottomInset()

        // 系统栏图标跟随主题明暗：浅色主题用深色图标，深色主题用浅色图标。
        val night = (resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = !night
            isAppearanceLightNavigationBars = !night
        }

        val restoredTab = savedInstanceState?.getInt(STATE_TAB, R.id.nav_home) ?: R.id.nav_home

        val app = application as PKotlinApplication
        if (savedInstanceState == null && !app.container.settings.onboardingCompleted) {
            startActivity(Intent(this, OnboardingActivity::class.java))
        }

        binding.bottomNav.setOnItemSelectedListener { item ->
            if (!suppressNavSelection) showTab(item.itemId)
            true
        }

        binding.shellSkeleton.configure(
            rows = 4,
            rowHeightPx = (112 * resources.displayMetrics.density).toInt(),
        )
        binding.shellSkeleton.visibility = View.VISIBLE
        supportFragmentManager.registerFragmentLifecycleCallbacks(
            object : androidx.fragment.app.FragmentManager.FragmentLifecycleCallbacks() {
                override fun onFragmentViewCreated(
                    fm: androidx.fragment.app.FragmentManager,
                    f: androidx.fragment.app.Fragment,
                    v: View,
                    savedInstanceState: Bundle?,
                ) {
                    binding.shellSkeleton.visibility = View.GONE
                }
            },
            false,
        )
        if (supportFragmentManager.fragments.isNotEmpty()) {
            binding.shellSkeleton.visibility = View.GONE
        }

        // 首帧只出应用壳 + 骨架；首页在下一帧挂载，避免首帧承担列表构建。
        // 本地数据不等网络，同步/清理在首帧之后投递到 IO 线程。
        suppressNavSelection = true
        binding.bottomNav.selectedItemId = restoredTab
        suppressNavSelection = false
        val initialTitle = getString(titleRes[restoredTab] ?: R.string.app_name)
        binding.headerTitleLarge.text = initialTitle
        binding.headerTitleCollapsed.text = initialTitle
        binding.root.postOnAnimation {
            showTab(restoredTab)
            app.startBackgroundWork()
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                app.container.syncManager.status.collect { status ->
                    binding.syncBanner.visibility =
                        if (status.localOnly) View.VISIBLE else View.GONE
                }
            }
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (binding.bottomNav.selectedItemId != R.id.nav_home) {
                    binding.bottomNav.selectedItemId = R.id.nav_home
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt(STATE_TAB, binding.bottomNav.selectedItemId)
        super.onSaveInstanceState(outState)
    }

    /** 首页「今日告警摘要」入口：跳到智能模块。 */
    fun openIntelligence() {
        binding.bottomNav.selectedItemId = R.id.nav_intelligence
    }

    /** 各 Fragment 在 onViewCreated 里把主滚动容器交给大标题联动。 */
    fun attachHeaderScroll(scrollable: View?) {
        recyclerListener?.let { listener ->
            (attachedScroll as? RecyclerView)?.removeOnScrollListener(listener)
        }
        nestedScrollListener?.let {
            (attachedScroll as? NestedScrollView)
                ?.setOnScrollChangeListener(null as NestedScrollView.OnScrollChangeListener?)
        }
        scrollListener?.let {
            (attachedScroll as? android.widget.ScrollView)
                ?.setOnScrollChangeListener(null as View.OnScrollChangeListener?)
        }
        recyclerListener = null
        nestedScrollListener = null
        scrollListener = null
        attachedScroll = scrollable

        when (scrollable) {
            is RecyclerView -> {
                val listener = object : RecyclerView.OnScrollListener() {
                    override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                        updateHeader(recyclerView.computeVerticalScrollOffset())
                    }
                }
                recyclerListener = listener
                scrollable.addOnScrollListener(listener)
            }

            is NestedScrollView -> {
                val listener = NestedScrollView.OnScrollChangeListener { view, _, y, _, _ ->
                    updateHeader(view.scrollY)
                }
                nestedScrollListener = listener
                scrollable.setOnScrollChangeListener(listener)
            }

            is android.widget.ScrollView -> {
                val listener = View.OnScrollChangeListener { view, _, y, _, _ ->
                    updateHeader(view.scrollY)
                }
                scrollListener = listener
                scrollable.setOnScrollChangeListener(listener)
            }
        }
    }

    private fun updateHeader(offset: Int) {
        val threshold = resources.getDimensionPixelSize(R.dimen.header_scroll_threshold)
        setHeaderCollapsed(offset >= threshold)
    }

    private fun setHeaderCollapsed(collapse: Boolean, animate: Boolean = true) {
        if (headerCollapsed == collapse) return
        headerCollapsed = collapse
        val expanded = resources.getDimensionPixelSize(R.dimen.header_h_expanded)
        val collapsed = resources.getDimensionPixelSize(R.dimen.nav_bar_h)
        val target = if (collapse) collapsed else expanded

        headerAnimator?.cancel()
        if (!animate) {
            binding.headerLargeArea.updateHeight(target)
        } else {
            val from = binding.headerLargeArea.height.takeIf { it > 0 } ?: expanded
            val duration = resources.getInteger(R.integer.duration_200).toLong()
            headerAnimator = ValueAnimator.ofInt(from, target).apply {
                this.duration = duration
                interpolator = DecelerateInterpolator()
                addUpdateListener { binding.headerLargeArea.updateHeight(it.animatedValue as Int) }
                start()
            }
        }

        val duration = resources.getInteger(R.integer.duration_200).toLong()
        binding.headerTitleCollapsed.animate()
            .alpha(if (collapse) 1f else 0f)
            .setDuration(duration)
            .start()
        binding.headerTitleLarge.animate()
            .alpha(if (collapse) 0f else 1f)
            .setDuration(duration)
            .start()
        binding.headerDivider.animate()
            .alpha(if (collapse) 1f else 0f)
            .setDuration(duration)
            .start()
    }

    private fun View.updateHeight(height: Int) {
        layoutParams = layoutParams.apply { this.height = height }
        requestLayout()
    }

    private fun showTab(itemId: Int) {
        val tag = fragmentTags[itemId] ?: return
        val fragmentManager = supportFragmentManager
        fragmentManager.commit {
            setReorderingAllowed(true)
            fragmentTags.values.forEach { otherTag ->
                if (otherTag != tag) {
                    fragmentManager.findFragmentByTag(otherTag)?.let { fragment ->
                        if (!fragment.isHidden) hide(fragment)
                    }
                }
            }
            val existing = fragmentManager.findFragmentByTag(tag)
            if (existing == null) {
                add(R.id.fragmentContainer, createFragment(itemId), tag)
            } else if (existing.isHidden) {
                show(existing)
            }
        }
        val title = getString(titleRes[itemId] ?: R.string.app_name)
        binding.headerTitleLarge.text = title
        binding.headerTitleCollapsed.text = title
        attachHeaderScroll(null)
        setHeaderCollapsed(false, animate = false)
    }

    private fun createFragment(itemId: Int): Fragment = when (itemId) {
        R.id.nav_intelligence -> IntelligenceFragment()
        R.id.nav_products -> ProductsFragment()
        R.id.nav_market -> MarketFragment()
        R.id.nav_profile -> ProfileFragment()
        else -> HomeFragment()
    }

    private companion object {
        const val STATE_TAB = "state_selected_tab"
    }
}
