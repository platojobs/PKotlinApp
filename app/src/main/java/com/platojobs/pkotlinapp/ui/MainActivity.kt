package com.platojobs.pkotlinapp.ui

import android.os.Bundle
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.fragment.app.commit
import com.platojobs.pkotlinapp.R
import com.platojobs.pkotlinapp.databinding.ActivityMainBinding
import com.platojobs.pkotlinapp.ui.common.applyBottomInset
import com.platojobs.pkotlinapp.ui.common.applyHorizontalInsets
import com.platojobs.pkotlinapp.ui.common.applyTopInset
import com.platojobs.pkotlinapp.ui.devices.DevicesFragment
import com.platojobs.pkotlinapp.ui.home.HomeFragment
import com.platojobs.pkotlinapp.ui.market.MarketFragment
import com.platojobs.pkotlinapp.ui.products.ProductsFragment
import com.platojobs.pkotlinapp.ui.profile.ProfileFragment

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val fragmentTags = mapOf(
        R.id.nav_home to "home",
        R.id.nav_devices to "devices",
        R.id.nav_products to "products",
        R.id.nav_market to "market",
        R.id.nav_profile to "profile",
    )

    private val titleRes = mapOf(
        R.id.nav_home to R.string.nav_home,
        R.id.nav_devices to R.string.nav_devices,
        R.id.nav_products to R.string.nav_products,
        R.id.nav_market to R.string.nav_market,
        R.id.nav_profile to R.string.nav_profile,
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.root.applyHorizontalInsets()
        binding.appBar.applyTopInset()
        binding.bottomNav.applyBottomInset()

        val restoredTab = savedInstanceState?.getInt(STATE_TAB, R.id.nav_home) ?: R.id.nav_home
        binding.bottomNav.selectedItemId = restoredTab
        showTab(restoredTab)

        binding.bottomNav.setOnItemSelectedListener { item ->
            showTab(item.itemId)
            true
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
        binding.appBar.setTitle(titleRes[itemId] ?: R.string.app_name)
    }

    private fun createFragment(itemId: Int): Fragment = when (itemId) {
        R.id.nav_devices -> DevicesFragment()
        R.id.nav_products -> ProductsFragment()
        R.id.nav_market -> MarketFragment()
        R.id.nav_profile -> ProfileFragment()
        else -> HomeFragment()
    }

    private companion object {
        const val STATE_TAB = "state_selected_tab"
    }
}
