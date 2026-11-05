package com.platojobs.pkotlinapp.ui.intelligence

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.platojobs.pkotlinapp.R
import com.platojobs.pkotlinapp.databinding.ItemRuleBinding
import com.platojobs.pkotlinapp.databinding.ItemRuleEventBinding
import com.platojobs.pkotlinapp.databinding.ItemSceneRowBinding
import com.platojobs.pkotlinapp.domain.model.DeviceRule
import com.platojobs.pkotlinapp.domain.model.RuleEvent
import com.platojobs.pkotlinapp.domain.model.RuleType
import com.platojobs.pkotlinapp.domain.scene.Scene
import com.platojobs.pkotlinapp.ui.common.TimeFormat
import com.platojobs.pkotlinapp.ui.home.SceneProgress
import com.platojobs.pkotlinapp.ui.home.sceneActionLabel

class RuleAdapter(
    private val onToggle: (DeviceRule, Boolean) -> Unit,
    private val onDelete: (DeviceRule) -> Unit,
) : ListAdapter<DeviceRule, RuleAdapter.ViewHolder>(DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemRuleBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding, onToggle, onDelete)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class ViewHolder(
        private val binding: ItemRuleBinding,
        private val onToggle: (DeviceRule, Boolean) -> Unit,
        private val onDelete: (DeviceRule) -> Unit,
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(rule: DeviceRule) {
            val context = binding.root.context
            binding.ruleTitle.text = context.getString(
                when (rule.type) {
                    RuleType.LOW_RSSI -> R.string.rule_low_rssi
                    RuleType.DISCONNECTED -> R.string.rule_disconnected
                    RuleType.BOND_LOST -> R.string.rule_bond_lost
                },
            )
            val thresholdText = if (rule.type == RuleType.LOW_RSSI) {
                context.getString(R.string.rule_threshold_value, rule.threshold)
            } else {
                context.getString(R.string.rule_threshold_not_applicable)
            }
            val enabledText = context.getString(
                if (rule.enabled) R.string.rule_enabled_desc else R.string.rule_disabled_desc,
            )
            binding.ruleDetail.text =
                context.getString(R.string.list_meta_line, thresholdText, enabledText)

            binding.ruleSwitch.setOnCheckedChangeListener(null)
            binding.ruleSwitch.isChecked = rule.enabled
            binding.ruleSwitch.setOnCheckedChangeListener { _, isChecked ->
                onToggle(rule, isChecked)
            }
            binding.deleteRuleButton.setOnClickListener { onDelete(rule) }
        }
    }

    private companion object {
        val DIFF = object : DiffUtil.ItemCallback<DeviceRule>() {
            override fun areItemsTheSame(oldItem: DeviceRule, newItem: DeviceRule) =
                oldItem.id == newItem.id

            override fun areContentsTheSame(oldItem: DeviceRule, newItem: DeviceRule) =
                oldItem == newItem
        }
    }
}

class RuleEventAdapter : ListAdapter<RuleEvent, RuleEventAdapter.ViewHolder>(DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemRuleEventBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false,
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class ViewHolder(
        private val binding: ItemRuleEventBinding,
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(event: RuleEvent) {
            val context = binding.root.context
            binding.eventMessage.text = event.message
            binding.eventMeta.text = context.getString(
                R.string.list_meta_line,
                event.mac,
                TimeFormat.relative(context, event.timestamp),
            )
        }
    }

    private companion object {
        val DIFF = object : DiffUtil.ItemCallback<RuleEvent>() {
            override fun areItemsTheSame(oldItem: RuleEvent, newItem: RuleEvent) =
                oldItem.id == newItem.id && oldItem.timestamp == newItem.timestamp

            override fun areContentsTheSame(oldItem: RuleEvent, newItem: RuleEvent) =
                oldItem == newItem
        }
    }
}

class SceneAdapter(
    private val onRun: (Scene) -> Unit,
    private val onEdit: (Scene) -> Unit,
    private val onDelete: (Scene) -> Unit,
) : ListAdapter<Scene, SceneAdapter.ViewHolder>(DIFF) {

    var progress: SceneProgress? = null
        set(value) {
            field = value
            if (itemCount > 0) notifyItemRangeChanged(0, itemCount)
        }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemSceneRowBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false,
        )
        return ViewHolder(binding, onRun, onEdit, onDelete)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position), progress)
    }

    class ViewHolder(
        private val binding: ItemSceneRowBinding,
        private val onRun: (Scene) -> Unit,
        private val onEdit: (Scene) -> Unit,
        private val onDelete: (Scene) -> Unit,
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(scene: Scene, progress: SceneProgress?) {
            val context = binding.root.context
            binding.sceneName.text = scene.name
            val running = progress?.takeIf { it.sceneId == scene.id }
            if (running != null) {
                binding.sceneMeta.text = context.getString(
                    R.string.intelligence_scene_progress,
                    sceneActionLabel(context, running.action),
                    running.index + 1,
                    running.total,
                )
                binding.sceneRun.isEnabled = false
            } else {
                binding.sceneMeta.text = context.getString(
                    R.string.home_scene_actions_count,
                    scene.actions.size,
                )
                binding.sceneRun.isEnabled = true
            }
            binding.sceneRun.setOnClickListener { onRun(scene) }
            binding.sceneEdit.setOnClickListener { onEdit(scene) }
            binding.sceneDelete.setOnClickListener { onDelete(scene) }
            binding.root.setOnClickListener { onEdit(scene) }
        }
    }

    private companion object {
        val DIFF = object : DiffUtil.ItemCallback<Scene>() {
            override fun areItemsTheSame(oldItem: Scene, newItem: Scene) = oldItem.id == newItem.id

            override fun areContentsTheSame(oldItem: Scene, newItem: Scene) =
                oldItem == newItem
        }
    }
}
