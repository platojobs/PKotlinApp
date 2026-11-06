package com.platojobs.pkotlinapp.ui.intelligence

import android.Manifest
import android.app.AlertDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.platojobs.pkotlinapp.PKotlinApplication
import com.platojobs.pkotlinapp.R
import com.platojobs.pkotlinapp.databinding.DialogRuleAddBinding
import com.platojobs.pkotlinapp.databinding.DialogSceneActionBinding
import com.platojobs.pkotlinapp.databinding.DialogSceneEditBinding
import com.platojobs.pkotlinapp.databinding.FragmentIntelligenceBinding
import com.platojobs.pkotlinapp.databinding.ItemSceneEditActionBinding
import com.platojobs.pkotlinapp.domain.model.Device
import com.platojobs.pkotlinapp.domain.model.RuleComparator
import com.platojobs.pkotlinapp.domain.model.RuleType
import com.platojobs.pkotlinapp.domain.model.displayName
import com.platojobs.pkotlinapp.domain.reading.ReadingMetrics
import com.platojobs.pkotlinapp.domain.rule.MetricThreshold
import com.platojobs.pkotlinapp.domain.rule.RuleTemplate
import com.platojobs.pkotlinapp.domain.scene.SCENE_MUTE_MINUTES
import com.platojobs.pkotlinapp.domain.scene.SCENE_SCHEDULE_INTERVALS
import com.platojobs.pkotlinapp.domain.scene.Scene
import com.platojobs.pkotlinapp.domain.scene.SceneAction
import com.platojobs.pkotlinapp.domain.scene.SceneActionType
import com.platojobs.pkotlinapp.domain.scene.SceneRunResult
import com.platojobs.pkotlinapp.domain.scene.SceneTrigger
import com.platojobs.pkotlinapp.ui.MainActivity
import com.platojobs.pkotlinapp.ui.common.EmptyStateView
import com.platojobs.pkotlinapp.ui.common.NotificationPermission
import com.platojobs.pkotlinapp.ui.common.SkeletonView
import com.platojobs.pkotlinapp.ui.common.ThreeStateHost
import kotlinx.coroutines.launch

/**
 * 智能模块：自动化（规则与最近触发）+ 场景（仅本机）。
 * 原「设备」页的规则入口整体迁移到这里，不再保留重复入口。
 */
class IntelligenceFragment : Fragment(R.layout.fragment_intelligence) {

    private var _binding: FragmentIntelligenceBinding? = null
    private val binding get() = _binding!!

    private val viewModel: IntelligenceViewModel by viewModels {
        IntelligenceViewModel.factory(requireActivity().application as PKotlinApplication)
    }

    private lateinit var threeState: ThreeStateHost
    private lateinit var ruleAdapter: RuleAdapter
    private lateinit var eventAdapter: RuleEventAdapter
    private lateinit var sceneAdapter: SceneAdapter
    private lateinit var runAdapter: SceneRunAdapter

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = FragmentIntelligenceBinding.bind(view)

        ruleAdapter = RuleAdapter(
            onToggle = { rule, enabled -> viewModel.setRuleEnabled(rule, enabled) },
            onDelete = { rule -> viewModel.deleteRule(rule) },
        )
        eventAdapter = RuleEventAdapter()
        sceneAdapter = SceneAdapter(
            onRun = { scene -> viewModel.runScene(scene) },
            onEdit = ::showSceneDialog,
            onDelete = ::confirmDeleteScene,
            onEnabledChange = { scene, enabled -> viewModel.setSceneEnabled(scene, enabled) },
        )
        runAdapter = SceneRunAdapter()

        binding.rulesList.layoutManager = LinearLayoutManager(requireContext())
        binding.rulesList.adapter = ruleAdapter
        binding.eventsList.layoutManager = LinearLayoutManager(requireContext())
        binding.eventsList.adapter = eventAdapter
        binding.scenesList.layoutManager = LinearLayoutManager(requireContext())
        binding.scenesList.adapter = sceneAdapter
        binding.runsList.layoutManager = LinearLayoutManager(requireContext())
        binding.runsList.adapter = runAdapter

        binding.skeletonIntelligence.configure(rows = 6, rowHeightPx = dp(72))
        threeState = ThreeStateHost(
            content = binding.intelligenceContent,
            skeleton = binding.skeletonIntelligence,
            overlay = binding.intelligenceOverlay,
        )

        bindSectionEmpty(binding.rulesEmptyContainer) {
            EmptyStateView(requireContext()).apply {
                setIcon(R.drawable.ic_intelligence)
                setTitle(getString(R.string.intelligence_rules_empty_title))
                setHint(getString(R.string.intelligence_rules_empty_hint))
                setAction(getString(R.string.intelligence_rules_add)) { showAddRuleDialog() }
            }
        }
        bindSectionEmpty(binding.scenesEmptyContainer) {
            EmptyStateView(requireContext()).apply {
                setIcon(R.drawable.ic_scene)
                setTitle(getString(R.string.intelligence_scenes_empty_title))
                setHint(getString(R.string.intelligence_scenes_empty_hint))
                setAction(getString(R.string.intelligence_scenes_add)) { showSceneDialog(null) }
            }
        }
        bindSectionEmpty(binding.runsEmptyContainer) {
            EmptyStateView(requireContext()).apply {
                setIcon(R.drawable.ic_intelligence)
                setTitle(getString(R.string.intelligence_runs_empty_title))
                setHint(getString(R.string.intelligence_runs_empty_hint))
            }
        }

        binding.addRuleButton.setOnClickListener { showAddRuleDialog() }
        binding.recommendRulesButton.setOnClickListener { showRecommendationsDialog() }
        binding.addSceneButton.setOnClickListener { showSceneDialog(null) }
        binding.clearRunsButton.setOnClickListener { confirmClearRuns() }

        (activity as? MainActivity)?.attachHeaderScroll(binding.intelligenceScroll)
        observe()
    }

    private fun observe() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    ruleAdapter.submitList(state.rules)
                    eventAdapter.submitList(state.events)
                    sceneAdapter.submitList(state.scenes)
                    runAdapter.submitList(state.runs)
                    binding.rulesEmptyContainer.visibility =
                        if (state.rules.isEmpty()) View.VISIBLE else View.GONE
                    binding.eventsEmpty.visibility =
                        if (state.events.isEmpty()) View.VISIBLE else View.GONE
                    binding.scenesEmptyContainer.visibility =
                        if (state.scenes.isEmpty()) View.VISIBLE else View.GONE
                    binding.runsEmptyContainer.visibility =
                        if (state.runs.isEmpty()) View.VISIBLE else View.GONE
                    when {
                        state.loading -> threeState.showLoading()
                        state.loadError != null -> threeState.showError(
                            state.loadError,
                            viewModel::refresh,
                        )
                        else -> threeState.showContent()
                    }
                }
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.sceneProgress.collect { progress ->
                    sceneAdapter.progress = progress
                }
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.sceneResults.collect { result ->
                    showSceneResult(result)
                }
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.messages.collect { message ->
                    toast(message)
                }
            }
        }
    }

    private fun bindSectionEmpty(container: FrameLayout, factory: () -> EmptyStateView) {
        container.addView(
            factory(),
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
            ).apply { gravity = Gravity.CENTER_HORIZONTAL },
        )
    }

    private fun showAddRuleDialog() {
        viewLifecycleOwner.lifecycleScope.launch {
            // 只有真实采集过的指标才出现在下拉里；无读数时隐藏指标阈值选项。
            buildAddRuleDialog(viewModel.availableMetrics())
        }
    }

    private fun buildAddRuleDialog(metrics: List<String>) {
        val dialogBinding = DialogRuleAddBinding.inflate(layoutInflater)
        dialogBinding.typeMetric.visibility = if (metrics.isEmpty()) View.GONE else View.VISIBLE

        val metricLabels = metrics.map { metric ->
            metricLabel(metric) + MetricThreshold.displayUnit(metric).let { unit ->
                if (unit.isBlank()) "" else "（$unit）"
            }
        }
        dialogBinding.metricSpinner.adapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_dropdown_item,
            metricLabels,
        )

        val comparators = listOf(
            RuleComparator.GREATER,
            RuleComparator.LESS,
            RuleComparator.GREATER_OR_EQUAL,
            RuleComparator.LESS_OR_EQUAL,
        )
        dialogBinding.comparatorSpinner.adapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_dropdown_item,
            comparators.map { comparatorLabel(it) },
        )

        fun selectedType(): RuleType = when (dialogBinding.typeGroup.checkedRadioButtonId) {
            R.id.typeDisconnected -> RuleType.DISCONNECTED
            R.id.typeBondLost -> RuleType.BOND_LOST
            R.id.typeMetric -> RuleType.METRIC_THRESHOLD
            else -> RuleType.LOW_RSSI
        }

        fun renderType() {
            val type = selectedType()
            dialogBinding.thresholdLayout.visibility =
                if (type == RuleType.LOW_RSSI) View.VISIBLE else View.GONE
            dialogBinding.metricFields.visibility =
                if (type == RuleType.METRIC_THRESHOLD) View.VISIBLE else View.GONE
            dialogBinding.typeDescription.setText(
                when (type) {
                    RuleType.LOW_RSSI -> R.string.rule_desc_low_rssi
                    RuleType.DISCONNECTED,
                    RuleType.DEVICE_OFFLINE,
                    -> R.string.rule_desc_disconnected
                    RuleType.BOND_LOST -> R.string.rule_desc_bond_lost
                    RuleType.METRIC_THRESHOLD -> R.string.rule_metric_desc
                    RuleType.SCHEDULE -> R.string.scene_trigger_schedule
                },
            )
        }

        dialogBinding.typeGroup.setOnCheckedChangeListener { _, _ -> renderType() }
        renderType()

        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.rule_add_title)
            .setView(dialogBinding.root)
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.action_save, null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                when (val type = selectedType()) {
                    RuleType.METRIC_THRESHOLD -> {
                        val metric = metrics.getOrNull(dialogBinding.metricSpinner.selectedItemPosition)
                            ?: return@setOnClickListener
                        val threshold = dialogBinding.metricThresholdInput.text
                            ?.toString()?.trim()?.toDoubleOrNull()
                        if (threshold == null || !threshold.isFinite()) {
                            dialogBinding.metricThresholdLayout.error =
                                getString(R.string.rule_metric_threshold_error)
                            return@setOnClickListener
                        }
                        val consecutive = dialogBinding.consecutiveInput.text?.toString()?.toIntOrNull()
                        if (consecutive == null || consecutive !in 1..MetricThreshold.MAX_CONSECUTIVE) {
                            dialogBinding.consecutiveLayout.error =
                                getString(R.string.rule_metric_consecutive_error)
                            return@setOnClickListener
                        }
                        dialogBinding.metricThresholdLayout.error = null
                        dialogBinding.consecutiveLayout.error = null
                        viewModel.addMetricRule(
                            metric = metric,
                            comparator = comparators[dialogBinding.comparatorSpinner.selectedItemPosition],
                            threshold = threshold,
                            consecutive = consecutive,
                        )
                    }

                    else -> {
                        val threshold = if (type == RuleType.LOW_RSSI) {
                            val value = dialogBinding.thresholdInput.text?.toString()?.toIntOrNull()
                            if (value == null || value !in -100..0) {
                                dialogBinding.thresholdLayout.error =
                                    getString(R.string.rule_threshold_error)
                                return@setOnClickListener
                            }
                            dialogBinding.thresholdLayout.error = null
                            value
                        } else {
                            0
                        }
                        viewModel.addRule(type, threshold)
                    }
                }
                dialog.dismiss()
                requestNotificationIfNeeded()
            }
        }
        dialog.show()
    }

    private fun showRecommendationsDialog() {
        viewLifecycleOwner.lifecycleScope.launch {
            val result = viewModel.loadRecommendations()
            if (result.templates.isEmpty()) {
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.intelligence_recommend_title)
                    .setMessage(
                        if (result.hasRealMetrics) {
                            R.string.intelligence_recommend_none_left
                        } else {
                            R.string.intelligence_recommend_empty
                        },
                    )
                    .setPositiveButton(R.string.intelligence_recommend_close, null)
                    .show()
                return@launch
            }
            val templates = result.templates
            val labels = templates.map { templateLabel(it) }
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.intelligence_recommend_title)
                .setItems(labels.toTypedArray()) { _, which ->
                    val template = templates.getOrNull(which) ?: return@setItems
                    viewModel.addRecommendedRule(template)
                }
                .setNegativeButton(R.string.action_cancel, null)
                .show()
        }
    }

    private fun templateLabel(template: RuleTemplate): String = getString(
        R.string.intelligence_recommend_entry,
        template.deviceName,
        metricLabel(template.metric),
        template.comparator.symbol,
        MetricThreshold.formatValue(template.threshold),
        template.unit,
    )

    private fun metricLabel(metric: String): String = getString(
        when (ReadingMetrics.normalize(metric)) {
            ReadingMetrics.TEMPERATURE -> R.string.readings_metric_temperature
            ReadingMetrics.HUMIDITY -> R.string.readings_metric_humidity
            ReadingMetrics.BATTERY -> R.string.readings_metric_battery
            ReadingMetrics.HEART_RATE -> R.string.readings_metric_heart_rate
            ReadingMetrics.CO2 -> R.string.readings_metric_co2
            ReadingMetrics.PRESSURE -> R.string.readings_metric_pressure
            else -> R.string.readings_metric_other
        },
    )

    private fun comparatorLabel(comparator: RuleComparator): String = getString(
        when (comparator) {
            RuleComparator.GREATER -> R.string.rule_comparator_greater
            RuleComparator.LESS -> R.string.rule_comparator_less
            RuleComparator.GREATER_OR_EQUAL -> R.string.rule_comparator_gte
            RuleComparator.LESS_OR_EQUAL -> R.string.rule_comparator_lte
        },
    )

    private fun requestNotificationIfNeeded() {
        if (NotificationPermission.requiresRuntimePermission() &&
            !NotificationPermission.isGranted(requireContext())
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun showSceneDialog(scene: Scene?) {
        viewLifecycleOwner.lifecycleScope.launch {
            val currentMetric = (scene?.trigger as? SceneTrigger.MetricThreshold)?.metric
            val metrics = (viewModel.availableMetrics() + listOfNotNull(currentMetric)).distinct()
            buildSceneDialog(scene, metrics)
        }
    }

    private fun buildSceneDialog(scene: Scene?, metrics: List<String>) {
        val dialogBinding = DialogSceneEditBinding.inflate(layoutInflater)
        val actions = scene?.actions?.toMutableList() ?: mutableListOf()
        dialogBinding.sceneNameInput.setText(scene?.name.orEmpty())

        fun renderActions() {
            dialogBinding.actionContainer.removeAllViews()
            dialogBinding.actionEmpty.visibility =
                if (actions.isEmpty()) View.VISIBLE else View.GONE
            actions.forEachIndexed { index, action ->
                val row = ItemSceneEditActionBinding.inflate(
                    layoutInflater,
                    dialogBinding.actionContainer,
                    false,
                )
                row.actionIndex.text = getString(R.string.scene_action_index, index + 1)
                row.actionLabel.text = actionLabel(action)
                row.actionDetail.text = actionDetail(action)
                row.actionRemove.setOnClickListener {
                    actions.removeAt(index)
                    renderActions()
                }
                dialogBinding.actionContainer.addView(row.root)
            }
            dialogBinding.sceneError.visibility = View.GONE
        }

        renderActions()
        dialogBinding.addActionButton.setOnClickListener {
            showActionPicker { action ->
                actions += action
                renderActions()
            }
        }

        // 触发条件与规则模型共用字段：先选类型，再渲染对应输入。
        dialogBinding.triggerMetric.visibility =
            if (metrics.isEmpty()) View.GONE else View.VISIBLE
        val metricLabels = metrics.map { metric ->
            metricLabel(metric) + MetricThreshold.displayUnit(metric).let { unit ->
                if (unit.isBlank()) "" else "（$unit）"
            }
        }
        dialogBinding.triggerMetricSpinner.adapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_dropdown_item,
            metricLabels,
        )
        val comparators = listOf(
            RuleComparator.GREATER,
            RuleComparator.LESS,
            RuleComparator.GREATER_OR_EQUAL,
            RuleComparator.LESS_OR_EQUAL,
        )
        dialogBinding.triggerComparatorSpinner.adapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_dropdown_item,
            comparators.map { comparatorLabel(it) },
        )
        dialogBinding.triggerIntervalSpinner.adapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_dropdown_item,
            SCENE_SCHEDULE_INTERVALS.map { getString(R.string.scene_trigger_interval_option, it) },
        )

        fun selectedTrigger(): SceneTrigger = when (dialogBinding.triggerGroup.checkedRadioButtonId) {
            R.id.triggerDisconnected -> SceneTrigger.Disconnected
            R.id.triggerLowRssi -> SceneTrigger.LowRssi(
                dialogBinding.rssiThresholdInput.text?.toString()?.trim()?.toIntOrNull() ?: 0,
            )

            R.id.triggerMetric -> SceneTrigger.MetricThreshold(
                metric = metrics.getOrNull(dialogBinding.triggerMetricSpinner.selectedItemPosition)
                    .orEmpty(),
                comparator = comparators[
                    dialogBinding.triggerComparatorSpinner.selectedItemPosition.coerceAtLeast(0),
                ],
                thresholdValue = dialogBinding.triggerMetricThresholdInput.text
                    ?.toString()?.trim()?.toDoubleOrNull() ?: Double.NaN,
                consecutive = dialogBinding.triggerConsecutiveInput.text
                    ?.toString()?.trim()?.toIntOrNull() ?: 1,
            )

            R.id.triggerSchedule -> SceneTrigger.Schedule(
                SCENE_SCHEDULE_INTERVALS[
                    dialogBinding.triggerIntervalSpinner.selectedItemPosition.coerceAtLeast(0),
                ],
            )

            else -> SceneTrigger.Manual
        }

        fun renderTrigger() {
            val type = selectedTrigger()
            dialogBinding.rssiThresholdLayout.visibility =
                if (type is SceneTrigger.LowRssi) View.VISIBLE else View.GONE
            dialogBinding.metricTriggerFields.visibility =
                if (type is SceneTrigger.MetricThreshold) View.VISIBLE else View.GONE
            dialogBinding.scheduleTriggerFields.visibility =
                if (type is SceneTrigger.Schedule) View.VISIBLE else View.GONE
        }

        // 按已有场景的触发条件回填，编辑不会把条件重置成手动。
        when (val trigger = scene?.trigger) {
            is SceneTrigger.Disconnected -> dialogBinding.triggerDisconnected.isChecked = true
            is SceneTrigger.LowRssi -> {
                dialogBinding.triggerLowRssi.isChecked = true
                dialogBinding.rssiThresholdInput.setText(trigger.threshold.toString())
            }

            is SceneTrigger.MetricThreshold -> {
                dialogBinding.triggerMetric.isChecked = true
                dialogBinding.triggerMetricSpinner.setSelection(
                    metrics.indexOf(trigger.metric).takeIf { it >= 0 } ?: 0,
                )
                dialogBinding.triggerComparatorSpinner.setSelection(
                    comparators.indexOf(trigger.comparator).takeIf { it >= 0 } ?: 0,
                )
                dialogBinding.triggerMetricThresholdInput.setText(
                    MetricThreshold.formatValue(trigger.thresholdValue),
                )
                dialogBinding.triggerConsecutiveInput.setText(trigger.consecutive.toString())
            }

            is SceneTrigger.Schedule -> {
                dialogBinding.triggerSchedule.isChecked = true
                dialogBinding.triggerIntervalSpinner.setSelection(
                    SCENE_SCHEDULE_INTERVALS.indexOf(trigger.intervalMinutes).takeIf { it >= 0 } ?: 0,
                )
            }

            else -> dialogBinding.triggerManual.isChecked = true
        }
        dialogBinding.triggerGroup.setOnCheckedChangeListener { _, _ -> renderTrigger() }
        renderTrigger()

        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(
                if (scene == null) R.string.scene_add_title else R.string.scene_edit_title,
            )
            .setView(dialogBinding.root)
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.action_save, null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = dialogBinding.sceneNameInput.text?.toString()?.trim().orEmpty()
                val trigger = selectedTrigger()
                val candidate = Scene(
                    id = scene?.id ?: 0L,
                    name = name,
                    actions = actions,
                    enabled = scene?.enabled ?: true,
                    trigger = trigger,
                )
                val reason = candidate.invalidReason()
                if (reason != null) {
                    dialogBinding.sceneError.text = reason
                    dialogBinding.sceneError.visibility = View.VISIBLE
                    return@setOnClickListener
                }
                viewModel.saveScene(
                    id = scene?.id,
                    name = name,
                    actions = actions,
                    trigger = trigger.takeUnless { it is SceneTrigger.Manual },
                    enabled = scene?.enabled ?: true,
                )
                dialog.dismiss()
                requestNotificationIfNeeded()
            }
        }
        dialog.show()
    }

    private fun confirmClearRuns() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.intelligence_runs_clear)
            .setMessage(R.string.intelligence_runs_hint)
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.action_confirm) { _, _ -> viewModel.clearSceneRuns() }
            .show()
    }

    private fun showActionPicker(onPick: (SceneAction) -> Unit) {
        val devices = viewModel.uiState.value.devices
        val dialogBinding = DialogSceneActionBinding.inflate(layoutInflater)
        val deviceLabels = devices.map {
            listOfNotNull(it.displayName(), it.mac).distinct().joinToString(" · ")
        }

        dialogBinding.deviceSpinner.adapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_dropdown_item,
            deviceLabels,
        )
        dialogBinding.muteSpinner.adapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_dropdown_item,
            SCENE_MUTE_MINUTES.map { getString(R.string.scene_action_mute_minutes, it) },
        )

        var pickedDevice = devices.firstOrNull()?.mac
        dialogBinding.deviceSpinner.onItemSelectedListener =
            object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    parent: AdapterView<*>?,
                    view: View?,
                    position: Int,
                    id: Long,
                ) {
                    pickedDevice = devices.getOrNull(position)?.mac
                }

                override fun onNothingSelected(parent: AdapterView<*>?) {
                    pickedDevice = null
                }
            }

        fun selectedType(): SceneActionType = when (dialogBinding.actionTypeGroup.checkedRadioButtonId) {
            R.id.typeDisconnect -> SceneActionType.DISCONNECT_DEVICE
            R.id.typeStartCollection -> SceneActionType.START_COLLECTION
            R.id.typeStopCollection -> SceneActionType.STOP_COLLECTION
            R.id.typeMute -> SceneActionType.MUTE_ALERTS
            R.id.typeSync -> SceneActionType.SYNC_NOW
            R.id.typeNotify -> SceneActionType.NOTIFY
            R.id.typeRecordLog -> SceneActionType.RECORD_LOG
            R.id.typeMarkDevice -> SceneActionType.MARK_DEVICE
            else -> SceneActionType.CONNECT_DEVICE
        }

        fun renderType() {
            val needsDevice = selectedType() in setOf(
                SceneActionType.CONNECT_DEVICE,
                SceneActionType.DISCONNECT_DEVICE,
                SceneActionType.MARK_DEVICE,
            )
            dialogBinding.deviceSpinner.visibility =
                if (needsDevice) View.VISIBLE else View.GONE
            dialogBinding.muteSpinner.visibility =
                if (selectedType() == SceneActionType.MUTE_ALERTS) View.VISIBLE else View.GONE
        }

        dialogBinding.actionTypeGroup.setOnCheckedChangeListener { _, _ -> renderType() }
        renderType()

        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.scene_action_add)
            .setView(dialogBinding.root)
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.action_save) { _, _ ->
                val type = selectedType()
                val action = when (type) {
                    SceneActionType.CONNECT_DEVICE,
                    SceneActionType.DISCONNECT_DEVICE,
                    SceneActionType.MARK_DEVICE,
                    -> SceneAction(type, deviceMac = pickedDevice)

                    SceneActionType.MUTE_ALERTS -> SceneAction(
                        type,
                        muteMinutes = SCENE_MUTE_MINUTES[
                            dialogBinding.muteSpinner.selectedItemPosition.coerceAtLeast(0),
                        ],
                    )

                    else -> SceneAction(type)
                }
                val invalid = action.invalidReason()
                if (invalid != null) {
                    toast(invalid)
                    return@setPositiveButton
                }
                onPick(action)
            }
            .create()
        dialog.show()
    }

    private fun confirmDeleteScene(scene: Scene) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.scene_delete_title)
            .setMessage(getString(R.string.scene_delete_message, scene.name))
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.action_confirm) { _, _ ->
                viewModel.deleteScene(scene)
            }
            .show()
    }

    private fun showSceneResult(result: SceneRunResult) {
        val sceneName = viewModel.sceneName(result.sceneId)
        if (result.allSucceeded) {
            toast(result.summary(sceneName))
            return
        }
        val lines = result.outcomes.mapIndexed { index, outcome ->
            val status = getString(
                if (outcome.success) R.string.intelligence_scene_status_success
                else R.string.intelligence_scene_status_failure,
            )
            "${index + 1}. $status · ${actionLabel(outcome.action)}\n   ${outcome.message}"
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.intelligence_scene_result_title, sceneName))
            .setMessage(lines.joinToString("\n\n"))
            .setPositiveButton(R.string.action_confirm, null)
            .show()
    }

    private fun actionLabel(action: SceneAction): String = getString(
        when (action.type) {
            SceneActionType.CONNECT_DEVICE -> R.string.scene_action_connect_device
            SceneActionType.DISCONNECT_DEVICE -> R.string.scene_action_disconnect_device
            SceneActionType.START_COLLECTION -> R.string.scene_action_start_collection
            SceneActionType.STOP_COLLECTION -> R.string.scene_action_stop_collection
            SceneActionType.MUTE_ALERTS -> R.string.scene_action_mute_alerts
            SceneActionType.SYNC_NOW -> R.string.scene_action_sync_now
            SceneActionType.NOTIFY -> R.string.scene_action_notify
            SceneActionType.RECORD_LOG -> R.string.scene_action_record_log
            SceneActionType.MARK_DEVICE -> R.string.scene_action_mark_device
        },
    )

    private fun actionDetail(action: SceneAction): String = when (action.type) {
        SceneActionType.CONNECT_DEVICE,
        SceneActionType.DISCONNECT_DEVICE,
        SceneActionType.MARK_DEVICE,
        -> deviceName(action.deviceMac)

        SceneActionType.MUTE_ALERTS ->
            getString(R.string.scene_action_mute_minutes, action.muteMinutes ?: 0)

        SceneActionType.START_COLLECTION,
        SceneActionType.STOP_COLLECTION,
        -> getString(R.string.scene_action_current_device)

        SceneActionType.SYNC_NOW -> getString(R.string.scene_action_sync_now)
        SceneActionType.NOTIFY -> getString(R.string.scene_action_notify)
        SceneActionType.RECORD_LOG -> getString(R.string.scene_action_record_log)
    }

    private fun deviceName(mac: String?): String {
        val known = viewModel.uiState.value.devices.firstOrNull {
            it.mac.equals(mac, ignoreCase = true)
        }
        return known?.displayName()?.takeIf(String::isNotBlank)
            ?: mac ?: getString(R.string.scene_action_device_required)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun toast(message: String) {
        Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show()
    }

    override fun onDestroyView() {
        binding.rulesList.adapter = null
        binding.eventsList.adapter = null
        binding.scenesList.adapter = null
        binding.runsList.adapter = null
        _binding = null
        super.onDestroyView()
    }
}
