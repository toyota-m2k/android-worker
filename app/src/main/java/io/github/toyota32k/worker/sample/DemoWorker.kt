package io.github.toyota32k.worker.sample

import android.content.Context
import androidx.work.Data
import androidx.work.WorkerParameters
import io.github.toyota32k.dialog.task.UtImmortalTask
import io.github.toyota32k.dialog.task.showConfirmMessageBox
import io.github.toyota32k.utils.NamedMutex
import io.github.toyota32k.utils.worker.NotificationProcessor
import io.github.toyota32k.utils.worker.UtTaskWorker
import io.github.toyota32k.utils.worker.WorkerParams
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first

class DemoWorker(context: Context, params: WorkerParameters) : UtTaskWorker(context, params) {
    companion object {
        fun start(context: Context, foreground: Boolean = false, durationInSeconds: Long = 60) {
            val params = DemoParams(foreground, durationInSeconds)
            executeOneTimeWorker<DemoWorker>(context, params.produce())
        }
    }
    class DemoParams(inputData: Data?) : WorkerParams(inputData) {
        constructor(foreground:Boolean, durationInSeconds:Long) : this(null) {
            this.foreground = foreground
            this.durationInSeconds = durationInSeconds
        }
        var durationInSeconds: Long by delegate.longNonnull(60) // 秒数
        var foreground: Boolean by delegate.booleanFalse
    }

    override suspend fun doWork(): Result {
        val title = "DemoWorker"
        val text = "Running demo task"
        val icon = NotificationProcessor.DEFAULT_DOWNLOAD_ICON
        val params = DemoParams(inputData)
        val taskName = if (params.foreground) {
            if (!enableForeground(title, text, icon)) {
                UtImmortalTask.awaitTask("demoWorkerTaskInForeground") {
                    showConfirmMessageBox("Forground Worker", "Foreground notification is not allowed.")
                }
                return error("Failed to enable foreground notification")
            }
            "demoWorkerTaskInForeground"
        } else {
            "demoWorkerTask"
        }
        val modeless = showModelessDialog<ProgressDialog.ProgressViewModel>(taskName) { ProgressDialog() } ?: return error("Failed to show dialog")
        modeless.executeOn { dialogViewModel ->
            val finished = MutableStateFlow(false)
            var cancelled = false
            dialogViewModel.onCancel {
                cancelled = true
                finished.value = true
            }
            fun progress(current: Long, total: Long) {
                val percent = dialogViewModel.setProgress(current, total)
                if (params.foreground) {
                    notifyProgress(current == total, percent)
                }
            }
            for (i in 1..params.durationInSeconds) {
                delay(1000) // 1秒待機
                progress(i, params.durationInSeconds)
                if (cancelled) {
                    break
                }
            }
            dialogViewModel.complete()
            finished.first { it } // 完了を待つ
        }
        return succeeded()
    }
}