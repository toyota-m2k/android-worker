package io.github.toyota32k.utils.worker

import android.content.Context
import androidx.fragment.app.FragmentActivity
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.OneTimeWorkRequest
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import io.github.toyota32k.dialog.UtDialog
import io.github.toyota32k.dialog.task.UtDialogViewModel
import io.github.toyota32k.dialog.task.UtImmortalTask
import io.github.toyota32k.dialog.task.UtImmortalTaskBase
import io.github.toyota32k.dialog.task.UtImmortalTaskManager
import io.github.toyota32k.dialog.task.withActivity
import io.github.toyota32k.logger.UtLog
import io.github.toyota32k.utils.FlowableEvent


/**
 * UI（ダイアログ）が利用可能なWorkerの基底クラス
 *
 *   - プロセスが再起動するとWorkerを再起動する。
 *   - doWork()からUI（ダイアログやメッセージボックス）を表示できる。
 *   - ただし、UI側からの監視はできないので、doWork()内で処理が完結する必要がある。
 */
abstract class ForegroundWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    companion object {
        val logger = UtLog("WK", UtImmortalTaskManager.logger, ForegroundWorker::class.java)
        inline fun <reified T: CoroutineWorker> executeOneTimeWorker(context: Context, data: Data) {
            val req = OneTimeWorkRequest.Builder(T::class.java)
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .setInputData(data)
                .build()
            WorkManager.getInstance(context).enqueue(req)
        }
    }

    protected fun error(message:String):Result {
        logger.error(message)
        return Result.failure(workDataOf("error" to message))
    }

    /**
     * モードレスダイアログを表示する。
     * ビューモデルが初期化されて、ダイアログを表示(showDialog)される（直前）まで待機する。
     * ダイアログの操作はViewModelを通して行う。
     */
    protected suspend inline fun <reified T: UtDialogViewModel> showModelessDialog(taskName:String, noinline fn:(vm:T)-> UtDialog):T {
        val event = FlowableEvent()
        var vm:T? = null
        UtImmortalTask.launchTask(taskName) {
            vm = UtDialogViewModel.create(T::class.java, this)
            val dlg = fn(vm)
            event.set()
            showDialog(this.taskName) { dlg }
        }
        event.waitOne()
        if (vm==null) throw kotlin.IllegalStateException("view model is null")
        return vm
    }

    protected fun launchTask(taskName:String=this::class.java.name, callback:suspend UtImmortalTaskBase.()->Unit) = UtImmortalTask.launchTask(taskName, null, false, callback)
    protected suspend fun awaitTask(taskName:String=this::class.java.name, callback:suspend UtImmortalTaskBase.()->Unit) = UtImmortalTask.awaitTask(taskName, null, false,callback)
    protected suspend fun <T> awaitTaskResult(taskName:String=this::class.java.name, callback:suspend UtImmortalTaskBase.()->T):T = UtImmortalTask.awaitTaskResult<T>(taskName, false, callback)
    protected suspend fun <T> awaitTaskResult(defValue:T, taskName:String=this::class.java.name, callback:suspend UtImmortalTaskBase.()->T):T = UtImmortalTask.awaitTaskResult<T>(defValue, taskName, false, callback)
    protected suspend inline fun <reified T: FragmentActivity, R> withActivity(fn: (T)->R):R = UtImmortalTaskManager.mortalInstanceSource.withActivity<T,R>(fn)

    /**
     * フォアグラウンドで実行するWorker
     * Workerの実行中は、他のWorkerは実行されない。
     * Workerの実行中は、他のタスクは実行されない。
     * Workerの実行中は、他のフォアグラウンドWorkerは実行されない。
     */
    abstract override suspend fun doWork() : Result
}