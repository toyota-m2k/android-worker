package io.github.toyota32k.utils.worker

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.fragment.app.FragmentActivity
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequest
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import io.github.toyota32k.dialog.UtDialog
import io.github.toyota32k.dialog.mortal.UtMortalActivity
import io.github.toyota32k.dialog.task.UtDialogViewModel
import io.github.toyota32k.dialog.task.UtImmortalTask
import io.github.toyota32k.dialog.task.UtImmortalTaskBase
import io.github.toyota32k.dialog.task.UtImmortalTaskManager
import io.github.toyota32k.dialog.task.getOwnerAsActivity
import io.github.toyota32k.dialog.task.withActivity
import io.github.toyota32k.dialog.task.withMortalActivity
import io.github.toyota32k.logger.UtLog
import io.github.toyota32k.utils.FlowableEvent

/**
 * UI（ダイアログ）が利用可能な（==UtImmortalTaskと連携する）Workerの基底クラス
 *
 *   - プロセスが再起動するとWorkerを再起動する。
 *   - doWork()からUI（ダイアログやメッセージボックス）を表示できる。
 *   - ただし、UI側からの監視はできないので、doWork()内で処理が完結する必要がある。
 */
abstract class UtTaskWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    companion object {
        val logger = UtLog("WK", UtImmortalTaskManager.logger, UtTaskWorker::class.java)

        inline fun <reified T: CoroutineWorker> executeOneTimeWorker(context: Context, data: Data) {
            val req = OneTimeWorkRequest.Builder(T::class.java)
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .setInputData(data)
                .build()
            WorkManager.getInstance(context).enqueue(req)
        }
    }

    // region Basic Utilities

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
    protected suspend fun getMortalActivity(): UtMortalActivity? = UtImmortalTaskManager.mortalInstanceSource.getOwnerAsActivity()
    protected suspend inline fun <R> withMortalActivity(fn: (UtMortalActivity)->R):R = fn(getMortalActivity() ?: throw IllegalStateException("activity is not available."))

    // endregion

    // region Foreground Worker

    private var foregroundNotifier:NotificationProcessor? = null
    val foregroundEnabled get() = foregroundNotifier != null
    lateinit var notificationTitle:String
    lateinit var notificationText:String
    var notificationIcon:Int = 0

    /**
     * Workerのフォアグラウンド実行を開始する。
     * doWork()のできるだけ早いタイミングで呼び出すこと。
     * @param title 通知の初期タイトル
     * @param text 通知の初期テキスト
     * @param icon 通知の初期アイコン
     * @param notificationId 通知ID... デフォルト: 1
     * @param channelId 通知チャンネルID（省略可）
     * @param channelName 通知チャンネル名(省略可）
     */
    protected suspend fun enableForeground(title:String, text:String, icon:Int, importance:Int= NotificationProcessor.DEFAULT_IMPORTANCE, notificationId:Int=1, channelId:String= NotificationProcessor.DEFAULT_CHANNEL_ID, channelName:String=NotificationProcessor.DEFAULT_CHANNEL_NAME ) {
        assert(notificationId>0)
        assert(!foregroundEnabled)

        notificationTitle = title
        notificationText = text
        notificationIcon = icon

        foregroundNotifier = NotificationProcessor(applicationContext, channelId, channelName, importance, notificationId).apply {
            val initialNotification = message(title, text, icon).build()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                setForeground(ForegroundInfo(notificationId,initialNotification,ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC))
            } else {
                setForeground(ForegroundInfo(notificationId,initialNotification))
            }
        }
    }

    /**
     * フォアグラウンドWorkerの通知を更新する。
     * @param completed 完了フラグ（true:にするとスワイプして通知を消せるようにする）
     * @param title 通知のタイトル
     * @param text 通知のテキスト
     * @param icon 通知のアイコン
     * @param progressInPercent 進捗のパーセント（負値ならメッセージのみ通知）
     */
    protected fun notify(completed:Boolean, title:String, text:String, icon:Int, progressInPercent:Int=-1) {
        foregroundNotifier?.apply {
            val notification = if (progressInPercent >= 0) {
                progress(title, text, icon, progressInPercent, !completed)
            } else {
                message(title, text, icon, !completed)
            }.build()
            notify(notification)
        } ?: throw IllegalStateException("foreground is not enabled. Call enableForeground() first.")
    }

    /**
     * フォアグラウンドWorkerの通知を更新する。（進捗なし：メッセージ用）
     */
    protected fun notifyMessage(completed:Boolean, title:String?=null, text:String?=null, icon:Int?=null) {
        notify(completed, title?:notificationTitle, text?:notificationText, icon?:notificationIcon, -1)
    }

    /**
     * フォアグラウンドWorkerの通知を更新する。（進捗あり：プログレス用）
     */
    protected fun notifyProgress(completed:Boolean, title:String, text:String, icon:Int, progressInPercent:Int) {
        notify(completed, title, text, icon, progressInPercent)
    }
    protected fun notifyProgress(completed:Boolean, progressInPercent:Int) {
        notify(completed, notificationTitle, notificationText, notificationIcon, progressInPercent)
    }


    // endregion Foreground Worker

}