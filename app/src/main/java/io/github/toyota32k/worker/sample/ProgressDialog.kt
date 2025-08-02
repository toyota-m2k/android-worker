package io.github.toyota32k.worker.sample

import android.os.Bundle
import android.view.View
import io.github.toyota32k.binder.command.LiteUnitCommand
import io.github.toyota32k.binder.command.ReliableCommand
import io.github.toyota32k.binder.command.bindCommand
import io.github.toyota32k.binder.progressBarBinding
import io.github.toyota32k.binder.textBinding
import io.github.toyota32k.dialog.UtDialogEx
import io.github.toyota32k.dialog.task.UtDialogViewModel
import io.github.toyota32k.dialog.task.getViewModel
import io.github.toyota32k.utils.IDisposable
import io.github.toyota32k.worker.sample.databinding.DialogProgressBinding
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

class ProgressDialog : UtDialogEx() {
    class ProgressViewModel : UtDialogViewModel() {
        val progress = MutableStateFlow(0)
        val progressText = MutableStateFlow("")
        val completed = MutableStateFlow(false)
        val message = MutableStateFlow("")
        val cancelCommand = LiteUnitCommand()
        val closeCommand = ReliableCommand<Boolean>()

        fun setProgress(current: Long, total: Long): Int {
            val percent = if (total <= 0L) 0 else (current * 100L / total).toInt().coerceIn(0, 100)
            progress.value = percent
            progressText.value = "$percent %"
            return percent
        }

        fun complete() {
            setProgress(100, 100)
            completed.value = true
        }

        fun closeDialog(positive:Boolean) {
            launchSubTask {
                withOwner {
                    closeCommand.invoke(positive)
                }
            }
        }

        fun onCancel(fn: () -> Unit): IDisposable {
            return cancelCommand.bindForever {
                fn()
                closeCommand.invoke(false)
            }
        }

        override fun onCleared() {
            super.onCleared()
            closeCommand
        }
    }
    private val viewModel by lazy { getViewModel<ProgressViewModel>() }
    private lateinit var controls: DialogProgressBinding

    override fun preCreateBodyView() {
        gravityOption = GravityOption.CENTER
        noHeader = true
        noFooter = true
        widthOption = WidthOption.LIMIT(400)
        heightOption = HeightOption.COMPACT
        cancellable = false
    }

    override fun createBodyView(savedInstanceState: Bundle?, inflater: IViewInflater): View {
        controls = DialogProgressBinding.inflate(inflater.layoutInflater)
        return controls.root.also { _->
            binder
                .textBinding(controls.message, viewModel.message)
                .textBinding(controls.progressText, viewModel.progressText)
                .textBinding(controls.cancelButton, viewModel.completed.map { if(it) "Close" else "Cancel" })
                .progressBarBinding(controls.progressBar, viewModel.progress)
                .bindCommand(viewModel.cancelCommand, controls.cancelButton)
                .bindCommand(viewModel.closeCommand) { if(it) onPositive() else onNegative() }
        }
    }

}