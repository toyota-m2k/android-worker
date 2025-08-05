package io.github.toyota32k.utils.worker

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.annotation.MainThread
import androidx.fragment.app.FragmentActivity
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequest
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import io.github.toyota32k.dialog.IUtDialog
import io.github.toyota32k.dialog.UtDialog
import io.github.toyota32k.dialog.UtDialogOwner
import io.github.toyota32k.dialog.mortal.UtMortalActivity
import io.github.toyota32k.dialog.task.UtDialogViewModel
import io.github.toyota32k.dialog.task.UtImmortalTask
import io.github.toyota32k.dialog.task.UtImmortalTaskBase
import io.github.toyota32k.dialog.task.UtImmortalTaskManager
import io.github.toyota32k.dialog.task.getOwnerAsActivity
import io.github.toyota32k.dialog.task.withActivity
import io.github.toyota32k.logger.UtLog
import io.github.toyota32k.utils.FlowableEvent
import io.github.toyota32k.utils.NamedMutex
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import java.util.UUID

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

        inline fun <reified T: CoroutineWorker> executeOneTimeWorker(context: Context, data: Data): UUID {
            val req = OneTimeWorkRequest.Builder(T::class.java)
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .setInputData(data)
                .build()
            WorkManager.getInstance(context).enqueue(req)
            return req.id
        }
    }

    // region Basic Utilities

    protected fun error(message:String):Result {
        logger.error(message)
        return Result.failure(workDataOf("error" to message))
    }
    protected fun succeeded():Result {
        logger.debug("worker succeeded.")
        return Result.success()
    }
    protected fun succeeded(data: Data):Result {
        logger.debug("worker succeeded.")
        return Result.success(data)
    }

    interface IModelessDialog<T:UtDialogViewModel> {
        val viewModel:T

        @get:MainThread
        @set:MainThread
        var keepModelessDialog:Boolean

        @MainThread
        fun close(positive:Boolean=false)
    }
    protected class ModelessDialogInfo<T:UtDialogViewModel>: IModelessDialog<T> {
        override lateinit var viewModel:T
        var dialog:UtDialog? = null
        override var keepModelessDialog:Boolean = true
        override fun close(positive: Boolean) {
            keepModelessDialog = false
            dialog?.complete(if(positive) IUtDialog.Status.POSITIVE else IUtDialog.Status.NEGATIVE)
            dialog = null
        }
    }

    /**
     * モードレスダイアログを表示する。（キャンセルボタン付きプログレスダイアログを想定）
     * ビューモデルが初期化されて、ダイアログを表示(showDialog)される（直前）まで待機する。
     * ダイアログの操作はViewModelを通して行う。
     *
     */
    protected suspend inline fun <reified T: UtDialogViewModel> showModelessDialog(
        taskName:String,
        noinline prepareDialog:(UtDialogOwner)-> UtDialog
        ):IModelessDialog<T>?
    {
        if (NamedMutex.isLocked(taskName)) {
            return null
        }
        val event = FlowableEvent()
        val info = ModelessDialogInfo<T>()
        UtImmortalTask.launchTask(taskName) {
            info.viewModel = UtDialogViewModel.create(T::class.java, this)
            event.set()
            var revive = 0
            while (info.keepModelessDialog) {
                // OSのタスク一覧からアプリを終了したとき、すべてのActivityが閉じた状態で、プロセスが残留することがある。
                // Activityが閉じるときに、その配下にあるUtDialogも閉じるので、この状態でアプリ（Activity）を再起動すると、
                // doWorkは再開（正確には継続実行）されても、モードレスダイアログは閉じたままになってしまう。
                // この問題に対処するため、keepModelessDialog フラグを用意し、これが true なら、ダイアログを開き直すようにした。
                // そのため、
                logger.debug("modeless dialog is showing. (revive=$revive)")
                try {
                    showDialog(
                        tag = this.taskName,
                        ownerChooser = { _ ->
                            if (!info.keepModelessDialog) throw IllegalStateException("modeless dialog is closed.")
                            true
                        },
                        dialogSource = {
                            prepareDialog(it).apply {
                                info.dialog = this
                            }
                        }
                    )
                    revive++
                } catch(e: Throwable) {
                    resumeTask(false)
                    break
                }
            }
            logger.debug("modeless dialog closed.")
        }
        event.waitOne()
        return info
    }

    /**
     * モードレスダイアログを表示した状態で action を実行する。
     * @param taskName タスク名
     * @param dialogSource ダイアログの生成
     * @param action 処理内容
     * @return true: 実行した / false: 指定されたタスク名が実行中のため何もしなかった
     */
    protected suspend inline fun <reified T: UtDialogViewModel> withModelessDialog(
        taskName:String,
        noinline dialogSource:(UtDialogOwner)-> UtDialog,
        noinline action:suspend (viewModel:T)->Unit
        ):Boolean
    {
        val modeless = showModelessDialog<T>(taskName, dialogSource) ?: return false
        try {
            action(modeless.viewModel)
        } finally {
            MainScope().launch {
                modeless.close()
            }
        }
        return true
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
            val notification = initialNotification(title, text, icon)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                setForeground(ForegroundInfo(notificationId,notification,ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC))
            } else {
                setForeground(ForegroundInfo(notificationId,notification))
            }
        }
    }

    /**
     * フォアグラウンドWorkerの通知を更新する。（進捗なし：メッセージ用）
     */
    protected fun notifyMessage(completed:Boolean, title:String?=null, text:String?=null, icon:Int?=null) {
        foregroundNotifier?.apply {
            message(title?:notificationTitle, text?:notificationText, icon?:notificationIcon, !completed)
        }
    }

    /**
     * フォアグラウンドWorkerの通知を更新する。（進捗あり：プログレス用）
     */
    protected fun notifyProgress(completed:Boolean, progressInPercent:Int, title:String?=null, text:String?=null, icon:Int?=null) {
        foregroundNotifier?.apply {
            progress(progressInPercent, title ?: notificationTitle, text ?: notificationText, icon ?: notificationIcon, !completed)
        }
    }

    // endregion Foreground Worker

}