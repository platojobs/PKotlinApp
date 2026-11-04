package com.platojobs.pkotlinapp.ui.todo

import android.app.Dialog
import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.core.os.bundleOf
import androidx.fragment.app.DialogFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.platojobs.pkotlinapp.R
import com.platojobs.pkotlinapp.databinding.DialogTodoEditBinding
import com.platojobs.pkotlinapp.domain.model.Todo

/**
 * 新增 / 编辑待办的弹窗。结果通过 Fragment Result API 回传，避免持有 Activity 引用。
 */
class TodoEditorDialog : DialogFragment() {

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val args = requireArguments()
        val binding = DialogTodoEditBinding.inflate(layoutInflater)
        binding.inputTitle.setText(args.getString(ARG_TITLE).orEmpty())
        binding.inputNote.setText(args.getString(ARG_NOTE).orEmpty())

        val isCreate = args.getLong(ARG_ID, NEW_ID) == NEW_ID
        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(if (isCreate) R.string.todo_add_title else R.string.todo_edit_title)
            .setView(binding.root)
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.action_save, null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val title = binding.inputTitle.text?.toString()?.trim().orEmpty()
                if (title.isEmpty()) {
                    binding.titleLayout.error = getString(R.string.error_title_required)
                    binding.inputTitle.requestFocus()
                    return@setOnClickListener
                }
                parentFragmentManager.setFragmentResult(
                    RESULT_KEY,
                    bundleOf(
                        KEY_ID to args.getLong(ARG_ID, NEW_ID),
                        KEY_TITLE to title,
                        KEY_NOTE to binding.inputNote.text?.toString()?.trim().orEmpty(),
                    ),
                )
                dismiss()
            }
        }
        return dialog
    }

    companion object {
        const val RESULT_KEY = "todo_editor_result"
        const val KEY_ID = "result_id"
        const val KEY_TITLE = "result_title"
        const val KEY_NOTE = "result_note"
        const val NEW_ID = -1L
        const val TAG = "todo_editor"

        private const val ARG_ID = "arg_id"
        private const val ARG_TITLE = "arg_title"
        private const val ARG_NOTE = "arg_note"

        fun newInstance(todo: Todo?): TodoEditorDialog = TodoEditorDialog().apply {
            arguments = bundleOf(
                ARG_ID to (todo?.id ?: NEW_ID),
                ARG_TITLE to todo?.title.orEmpty(),
                ARG_NOTE to todo?.note.orEmpty(),
            )
        }
    }
}
