package com.platojobs.pkotlinapp.ui.intelligence

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.ViewCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.platojobs.pkotlinapp.R
import com.platojobs.pkotlinapp.databinding.ItemRuleBinding
import com.platojobs.pkotlinapp.databinding.ItemRuleEventBinding
import com.platojobs.pkotlinapp.databinding.ItemSceneRowBinding
import com.platojobs.pkotlinapp.databinding.ItemSceneRunBinding
import com.platojobs.pkotlinapp.domain.model.DeviceRule
import com.platojobs.pkotlinapp.domain.model.RuleEvent
import com.platojobs.pkotlinapp.domain.model.RuleType
import com.platojobs.pkotlinapp.domain.reading.ReadingMetrics
import com.platojobs.pkotlinapp.domain.rule.MetricThreshold
import com.platojobs.pkotlinapp.domain.scene.Scene
import com.platojobs.pkotlinapp.domain.scene.SceneRun
import com.platojobs.pkotlinapp.domain.scene.SceneTrigger
import com.platojobs.pkotlinapp.ui.common.TimeFormat
import com.platojobs.pkotlinapp.ui.home.SceneProgress
import com.platojobs.pkotlinapp.ui.home.sceneActionLabel

private fun metricLabel(context: android.content.Context, metric: String?): String =
    context.getString(
        when (ReadingMetrics.normalize(metric.orEmpty())) {
            ReadingMetrics.TEMPERATURE -> R.string.readings_metric_temperature
            ReadingMetrics.HUMIDITY -> R.string.readings_metric_humidity
            ReadingMetrics.BATTERY -> R.string.readings_metric_battery
            ReadingMetrics.HEART_RATE -> R.string.readings_metric_heart_rate
            ReadingMetrics.CO2 -> R.string.readings_metric_co2
            ReadingMetrics.PRESSURE -> R.string.readings_metric_pressure
            else -> R.string.readings_metric_other
        },
    )

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
            val enabledText = context.getString(
                if (rule.enabled) R.string.rule_enabled_desc else R.string.rule_disabled_desc,
            )
            binding.ruleTitle.text = when (rule.type) {
                RuleType.LOW_RSSI -> context.getString(R.string.rule_low_rssi)
                RuleType.DISCONNECTED -> context.getString(R.string.rule_disconnected)
                RuleType.BOND_LOST -> context.getString(R.string.rule_bond_lost)
                RuleType.METRIC_THRESHOLD -> context.getString(
                    R.string.rule_metric_title,
                    metricLabel(context, rule.metric),
                    rule.comparator.symbol,
                    MetricThreshold.formatValue(rule.thresholdValue) +
                        MetricThreshold.displayUnit(rule.metric.orEmpty()),
                )

                RuleType.SCHEDULE -> context.getString(
                    R.string.scene_trigger_summary_schedule,
                    rule.intervalMinutes ?: 0,
                )
            }
            binding.ruleDetail.text = if (rule.type == RuleType.METRIC_THRESHOLD) {
                context.resources.getQuantityString(
                    R.plurals.rule_metric_consecutive,
                    rule.consecutive,
                    rule.consecutive,
                    enabledText,
                )
            } else {
                val thresholdText = if (rule.type == RuleType.LOW_RSSI) {
                    context.getString(R.string.rule_threshold_value, rule.threshold)
                } else {
                    context.getString(R.string.rule_threshold_not_applicable)
                }
                context.getString(R.string.list_meta_line, thresholdText, enabledText)
            }

            binding.ruleSwitch.setOnCheckedChangeListener(null)
            binding.ruleSwitch.isChecked = rule.enabled
            ViewCompat.setStateDescription(
                binding.ruleSwitch,
                context.getString(
                    if (rule.enabled) R.string.rule_state_enabled else R.string.rule_state_disabled,
                ),
            )
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
    private val onEnabledChange: (Scene, Boolean) -> Unit,
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
        return ViewHolder(binding, onRun, onEdit, onDelete, onEnabledChange)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position), progress)
    }

    class ViewHolder(
        private val binding: ItemSceneRowBinding,
        private val onRun: (Scene) -> Unit,
        private val onEdit: (Scene) -> Unit,
        private val onDelete: (Scene) -> Unit,
        private val onEnabledChange: (Scene, Boolean) -> Unit,
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(scene: Scene, progress: SceneProgress?) {
            val context = binding.root.context
            binding.sceneName.text = scene.name
            val running = progress?.takeIf { it.sceneId == scene.id }
            val actionsCount = context.resources.getQuantityString(
                R.plurals.home_scene_actions_count,
                scene.actions.size,
                scene.actions.size,
            )
            val trigger = context.getString(
                R.string.scene_meta_trigger,
                actionsCount,
                sceneTriggerLabel(context, scene),
            )
            binding.sceneMeta.text = if (running != null) {
                context.getString(
                    R.string.intelligence_scene_progress,
                    sceneActionLabel(context, running.action),
                    running.index + 1,
                    running.total,
                )
            } else if (!scene.enabled) {
                context.getString(R.string.scene_meta_trigger, trigger, context.getString(R.string.scene_state_disabled))
            } else {
                trigger
            }
            binding.sceneRun.isEnabled = running == null
            binding.sceneEnabled.setOnCheckedChangeListener(null)
            binding.sceneEnabled.isChecked = scene.enabled
            binding.sceneEnabled.setOnCheckedChangeListener { _, checked ->
                onEnabledChange(scene, checked)
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

/** 执行记录列表：只读展示引擎/手动真实写入的结果，不提供任何编辑入口。 */
class SceneRunAdapter : ListAdapter<SceneRun, SceneRunAdapter.ViewHolder>(DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemSceneRunBinding.inflate(
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
        private val binding: ItemSceneRunBinding,
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(run: SceneRun) {
            val context = binding.root.context
            binding.runTitle.text = run.sceneName
            val triggerLabel = when (run.triggerType) {
                MANUAL_TRIGGER -> context.getString(R.string.intelligence_runs_trigger_manual)
                else -> context.getString(
                    R.string.intelligence_runs_trigger_rule,
                    sceneTriggerTypeLabel(context, run.triggerType),
                    run.triggerValue,
                )
            }
            binding.runMeta.text = context.getString(
                R.string.list_meta_line,
                TimeFormat.relative(context, run.timestamp),
                triggerLabel,
            )
            binding.runResult.text = when {
                run.failureCount == 0 -> context.getString(
                    R.string.intelligence_runs_result_success,
                    run.successCount,
                )

                run.successCount == 0 -> context.getString(
                    R.string.intelligence_runs_result_failure,
                    run.failureCount,
                )

                else -> context.getString(
                    R.string.intelligence_runs_result_partial,
                    run.successCount,
                    run.failureCount,
                )
            }
            val failure = run.failureReason?.takeIf(String::isNotBlank)
            binding.runFailure.visibility = if (failure == null) View.GONE else View.VISIBLE
            binding.runFailure.text = failure?.let {
                context.getString(R.string.intelligence_runs_failure_reason, it)
            }
            binding.runDetail.visibility =
                if (run.detail.isBlank()) View.GONE else View.VISIBLE
            binding.runDetail.text = run.detail
            binding.root.contentDescription = context.getString(
                R.string.list_meta_line,
                run.sceneName,
                binding.runResult.text,
            )
        }
    }

    private companion object {
        val DIFF = object : DiffUtil.ItemCallback<SceneRun>() {
            override fun areItemsTheSame(oldItem: SceneRun, newItem: SceneRun) = oldItem.id == newItem.id

            override fun areContentsTheSame(oldItem: SceneRun, newItem: SceneRun) = oldItem == newItem
        }
    }
}

/** 场景触发条件的人类可读摘要（与规则模型字段一一对应）。 */
fun sceneTriggerLabel(context: Context, scene: Scene): String {
    val trigger = scene.trigger ?: return context.getString(R.string.scene_trigger_summary_manual)
    return when (trigger) {
        SceneTrigger.Manual -> context.getString(R.string.scene_trigger_summary_manual)
        SceneTrigger.Disconnected -> context.getString(R.string.scene_trigger_summary_disconnected)
        is SceneTrigger.LowRssi ->
            context.getString(R.string.scene_trigger_summary_low_rssi, trigger.threshold)

        is SceneTrigger.MetricThreshold -> {
            val unit = MetricThreshold.displayUnit(trigger.metric)
            context.getString(
                R.string.scene_trigger_summary_metric,
                metricLabel(context, trigger.metric),
                trigger.comparator.symbol,
                MetricThreshold.formatValue(trigger.thresholdValue) + unit,
            )
        }

        is SceneTrigger.Schedule ->
            context.getString(R.string.scene_trigger_summary_schedule, trigger.intervalMinutes)
    }
}

/** 执行记录里的触发来源标签；未知类型原样展示，避免猜测。 */
fun sceneTriggerTypeLabel(context: Context, triggerType: String): String = when (triggerType) {
    MANUAL_TRIGGER -> context.getString(R.string.scene_trigger_type_manual)
    RuleType.LOW_RSSI.name -> context.getString(R.string.scene_trigger_type_low_rssi)
    RuleType.DISCONNECTED.name -> context.getString(R.string.scene_trigger_type_disconnected)
    RuleType.METRIC_THRESHOLD.name -> context.getString(R.string.scene_trigger_type_metric)
    RuleType.SCHEDULE.name -> context.getString(R.string.scene_trigger_type_schedule)
    RuleType.BOND_LOST.name -> context.getString(R.string.scene_trigger_type_bond_lost)
    else -> triggerType
}

private const val MANUAL_TRIGGER = "MANUAL"
