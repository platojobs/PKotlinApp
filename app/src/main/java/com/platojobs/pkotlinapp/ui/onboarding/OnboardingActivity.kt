package com.platojobs.pkotlinapp.ui.onboarding

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.platojobs.pkotlinapp.PKotlinApplication
import com.platojobs.pkotlinapp.R
import com.platojobs.pkotlinapp.databinding.ActivityOnboardingBinding
import com.platojobs.pkotlinapp.ui.common.BlePermissions
import com.platojobs.pkotlinapp.ui.common.NotificationPermission
import com.platojobs.pkotlinapp.ui.common.applyBottomInset
import com.platojobs.pkotlinapp.ui.common.applyHorizontalInsets
import com.platojobs.pkotlinapp.ui.common.applyTopInset

/**
 * 首次使用引导：3 步说明真实用途，用户点「继续」才弹系统权限框，可跳过。
 * 完成后（含跳过）写 [com.platojobs.pkotlinapp.data.prefs.AppSettings.onboardingCompleted]；
 * 中途返回不写，下次启动会再次展示。
 */
class OnboardingActivity : AppCompatActivity() {

    private lateinit var binding: ActivityOnboardingBinding
    private var step = 0
    private var showingResult = false

    private val settings by lazy {
        (application as PKotlinApplication).container.settings
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        renderResult()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        binding = ActivityOnboardingBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.onboardingRoot.applyHorizontalInsets()
        binding.onboardingTopBar.applyTopInset()
        binding.onboardingBottom.applyBottomInset()

        binding.skipButton.setOnClickListener { complete() }
        binding.settingsButton.setOnClickListener { openAppSettings() }
        binding.continueButton.setOnClickListener {
            if (showingResult) complete() else advance()
        }
        renderStep()
    }

    override fun onResume() {
        super.onResume()
        // 从系统设置返回后刷新授权状态，不额外弹框。
        if (showingResult) renderResult()
    }

    private fun steps(): List<Pair<Int, Int>> = listOf(
        R.string.onboarding_step_bluetooth_title to R.string.onboarding_step_bluetooth_body,
        R.string.onboarding_step_notification_title to R.string.onboarding_step_notification_body,
        if (BlePermissions.requiresLocation()) {
            R.string.onboarding_step_location_title_legacy to R.string.onboarding_step_location_body_legacy
        } else {
            R.string.onboarding_step_location_title_current to R.string.onboarding_step_location_body_current
        },
    )

    private fun renderStep() {
        val (titleRes, bodyRes) = steps()[step]
        binding.stepIndicator.text = getString(
            R.string.onboarding_step_indicator,
            step + 1,
            steps().size,
        )
        binding.stepTitle.setText(titleRes)
        binding.stepBody.setText(bodyRes)
    }

    private fun advance() {
        if (step < steps().lastIndex) {
            step++
            renderStep()
        } else {
            val permissions = neededPermissions()
            if (permissions.isEmpty()) {
                renderResult()
            } else {
                permissionLauncher.launch(permissions)
            }
        }
    }

    private fun neededPermissions(): Array<String> {
        val permissions = BlePermissions.runtimePermissions().toMutableList()
        if (NotificationPermission.requiresRuntimePermission()) {
            permissions += Manifest.permission.POST_NOTIFICATIONS
        }
        return permissions.toTypedArray()
    }

    private fun permissionStatus() = PermissionStatus(
        canScan = BlePermissions.hasScan(this),
        canConnect = BlePermissions.hasConnect(this),
        canNotify = NotificationPermission.isGranted(this),
    )

    private fun renderResult() {
        showingResult = true
        val status = permissionStatus()
        val issues = OnboardingPolicy.issues(status, NotificationPermission.requiresRuntimePermission())

        binding.stepGroup.visibility = View.GONE
        binding.skipButton.visibility = View.GONE
        binding.resultGroup.visibility = View.VISIBLE

        if (issues.isEmpty()) {
            binding.resultTitle.setText(R.string.onboarding_result_all_title)
            binding.resultBody.setText(R.string.onboarding_result_all_body)
            binding.continueButton.setText(R.string.onboarding_start)
            binding.settingsButton.visibility = View.GONE
            binding.settingsHint.visibility = View.GONE
            binding.resultBoundary.visibility = View.GONE
        } else {
            binding.resultTitle.setText(R.string.onboarding_result_limited_title)
            binding.resultBody.setText(R.string.onboarding_result_limited_body)
            binding.continueButton.setText(R.string.onboarding_use_limited)
            binding.settingsButton.visibility = View.VISIBLE
            binding.settingsHint.visibility = View.VISIBLE
            binding.settingsHint.text = getString(R.string.onboarding_settings_hint, getString(R.string.app_name))

            val boundaries = buildList {
                if (OnboardingPolicy.isLocalOnly(status)) {
                    add(getString(R.string.onboarding_result_local_only))
                    if (BlePermissions.requiresLocation() && !status.canScan) {
                        add(getString(R.string.onboarding_result_location_denied))
                    }
                }
                if (issues.contains(CapabilityIssue.NOTIFICATIONS)) {
                    add(getString(R.string.onboarding_result_no_notification))
                }
            }
            binding.resultBoundary.visibility = View.VISIBLE
            binding.resultBoundary.text = boundaries.joinToString("\n\n")
        }

        bindStatusLine(
            binding.statusScan,
            R.string.onboarding_status_scan,
            status.canScan,
        )
        bindStatusLine(
            binding.statusConnect,
            R.string.onboarding_status_connect,
            status.canConnect,
        )
        bindStatusLine(
            binding.statusNotification,
            R.string.onboarding_status_notification,
            status.canNotify,
        )
    }

    private fun bindStatusLine(view: android.widget.TextView, labelRes: Int, granted: Boolean) {
        view.text = getString(
            R.string.onboarding_permission_line,
            getString(labelRes),
            getString(
                if (granted) R.string.profile_permission_granted else R.string.profile_permission_denied,
            ),
        )
        view.setTextColor(
            ContextCompat.getColor(this, if (granted) R.color.success else R.color.danger),
        )
    }

    private fun openAppSettings() {
        startActivity(
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", packageName, null),
            ),
        )
    }

    private fun complete() {
        settings.onboardingCompleted = true
        finish()
    }
}
