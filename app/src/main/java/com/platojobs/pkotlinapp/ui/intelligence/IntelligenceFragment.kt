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
import com.platojobs.pkotlinapp.domain.model.RuleType
import com.platojobs.pkotlinapp.domain.model.displayName
import com.platojobs.pkotlinapp.domain.scene.SCENE_MUTE_MINUTES
import com.platojobs.pkotlinapp.domain.scene.Scene
import com.platojobs.pkotlinapp.domain.scene.SceneAction
import com.platojobs.pkotlinapp.domain.scene.SceneActionType
import com.platojobs.pkotlinapp.domain.scene.SceneRunResult
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
        )

        binding.rulesList.layoutManager = LinearLayoutManager(requireContext())
        binding.rulesList.adapter = ruleAdapter
        binding.eventsList.layoutManager = LinearLayoutManager(requireContext())
        binding.eventsList.adapter = eventAdapter
        binding.scenesList.layoutManager = LinearLayoutManager(requireContext())
        binding.scenesList.adapter = sceneAdapter

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

        binding.addRuleButton.setOnClickListener { showAddRuleDialog() }
        binding.addSceneButton.setOnClickListener { showSceneDialog(null) }

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
                    binding.rulesEmptyContainer.visibility =
                        if (state.rules.isEmpty()) View.VISIBLE else View.GONE
                    binding.eventsEmpty.visibility =
                        if (state.events.isEmpty()) View.VISIBLE else View.GONE
                    binding.scenesEmptyContainer.visibility =
                        if (state.scenes.isEmpty()) View.VISIBLE else View.GONE
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
        val dialogBinding = DialogRuleAddBinding.inflate(layoutInflater)

        fun selectedType(): RuleType = when (dialogBinding.typeGroup.checkedRadioButtonId) {
            R.id.typeDisconnected -> RuleType.DISCONNECTED
            R.id.typeBondLost -> RuleType.BOND_LOST
            else -> RuleType.LOW_RSSI
        }

        fun renderType() {
            val type = selectedType()
            dialogBinding.thresholdLayout.visibility =
                if (type == RuleType.LOW_RSSI) View.VISIBLE else View.GONE
            dialogBinding.typeDescription.setText(
                when (type) {
                    RuleType.LOW_RSSI -> R.string.rule_desc_low_rssi
                    RuleType.DISCONNECTED -> R.string.rule_desc_disconnected
                    RuleType.BOND_LOST -> R.string.rule_desc_bond_lost
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
                val type = selectedType()
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
                dialog.dismiss()
                requestNotificationIfNeeded()
            }
        }
        dialog.show()
    }

    private fun requestNotificationIfNeeded() {
        if (NotificationPermission.requiresRuntimePermission() &&
            !NotificationPermission.isGranted(requireContext())
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun showSceneDialog(scene: Scene?) {
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
                val candidate = Scene(scene?.id ?: 0L, name, actions)
                val reason = candidate.invalidReason()
                if (reason != null) {
                    dialogBinding.sceneError.text = reason
                    dialogBinding.sceneError.visibility = View.VISIBLE
                    return@setOnClickListener
                }
                viewModel.saveScene(scene?.id, name, actions)
                dialog.dismiss()
            }
        }
        dialog.show()
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
            else -> SceneActionType.CONNECT_DEVICE
        }

        fun renderType() {
            val needsDevice = selectedType() in setOf(
                SceneActionType.CONNECT_DEVICE,
                SceneActionType.DISCONNECT_DEVICE,
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
            val status = if (outcome.success) "成功" else "失败"
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
        },
    )

    private fun actionDetail(action: SceneAction): String = when (action.type) {
        SceneActionType.CONNECT_DEVICE,
        SceneActionType.DISCONNECT_DEVICE,
        -> deviceName(action.deviceMac)

        SceneActionType.MUTE_ALERTS ->
            getString(R.string.scene_action_mute_minutes, action.muteMinutes ?: 0)

        SceneActionType.START_COLLECTION,
        SceneActionType.STOP_COLLECTION,
        -> getString(R.string.scene_action_current_device)

        SceneActionType.SYNC_NOW -> getString(R.string.scene_action_sync_now)
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
        _binding = null
        super.onDestroyView()
    }
}
