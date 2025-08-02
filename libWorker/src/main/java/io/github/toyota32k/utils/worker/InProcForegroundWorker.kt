package io.github.toyota32k.utils.worker

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequest
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException

class InProcForegroundWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params)  {
    companion object {
        interface INotificationSink {
            fun notify(
                completed:Boolean,          // 処理が完了するときは trueにする（-->ユーザーがスワイプして通知を消すことができる）
                title:String?=null,         // nullのときは初期値（ForegroundWorkerParams#notificationTitle)を使用
                text:String?=null,          // nullのときは初期値（ForegroundWorkerParams#notificationText)を使用
                icon:Int=-1,                // -1のときは初期値（ForegroundWorkerParams#notificationIcon)を使用
                progressInPercent:Int=-1    // -1のときは進捗なし。0-100のときは進捗を表示する。
            )
            fun notify(completed:Boolean, prepare:(NotificationCompat.Builder)->Unit)
        }
        interface IForegroundWorkerEntry {
            suspend fun execute(notificationSink: INotificationSink)
        }
        class ForegroundWorkerEntry<T>(val callback:suspend (INotificationSink)->T): IForegroundWorkerEntry {
            var result:T? = null
            var exception: Throwable? = null
            override suspend fun execute(notificationSink: INotificationSink) {
                try {
                    result = callback(notificationSink)
                } catch(e:Throwable) {
                    exception = e
                }
            }
        }
        val foregroundWorkerMap = mutableMapOf<String, IForegroundWorkerEntry>()

        fun generateFgUniqueKey():String {
            while (true) {
                val key = UUID.randomUUID().toString()
                if (!InProcWorker.Companion.workerMap.containsKey(key)) return key
            }
        }

        /**
         * プロセス生存中にだけ動作するWorkerをForegroundで実行する。
         * アップロード/ダウンロード用のシステムアイコンを使用。
         *
         * @param context アプリケーションコンテキスト
         * @param title 通知の初期タイトル
         * @param text 通知の初期テキスト
         * @param uploading trueのときはアップロード、falseのときはダウンロードのシステムアイコンを使用する。
         * @param notificationId 通知ID。デフォルトは1。
         * @param notificationChannelId 通知チャンネルID（省略可）
         * @param notificationChannelName 通知チャンネル名（省略可）
         *
         */
        suspend inline fun <reified T> inProcForegroundWorker(
            context:Context,
            title:String,
            text:String,
            uploading: Boolean,
            notificationImportance: Int = NotificationProcessor.DEFAULT_IMPORTANCE,
            notificationId:Int = 1,
            notificationChannelId:String? = null,
            notificationChannelName:String? = null,
            noinline action:suspend (INotificationSink)->T):T {
            val params = ForegroundWorkerParams(title, text, uploading, notificationImportance, notificationId, notificationChannelId, notificationChannelName)
            return inProcForegroundWorker(context, params, action)
        }

        /**
         * プロセス生存中にだけ動作するWorkerをForegroundで実行する。
         * カスタムアイコンを使用する。
         * @param context アプリケーションコンテキスト
         * @param title 通知の初期タイトル
         * @param text 通知の初期テキスト
         * @param icon 通知の初期アイコンリソースID。
         * @param notificationId 通知ID。デフォルトは1。
         * @param notificationChannelId 通知チャンネルID（省略可）
         * @param notificationChannelName 通知チャンネル名（省略可）
         */
        suspend inline fun <reified T>  inProcForegroundWorker(
            context:Context,
            title:String,
            text:String,
            icon:Int,
            notificationImportance: Int = NotificationProcessor.DEFAULT_IMPORTANCE,
            notificationId:Int = 1,
            notificationChannelId:String? = null,
            notificationChannelName:String? = null,
            noinline action:suspend (INotificationSink)->T): T {
            val params = ForegroundWorkerParams(title, text, icon, notificationImportance, notificationId, notificationChannelId, notificationChannelName)
            return inProcForegroundWorker(context, params, action)
        }

        /**
         * 内部利用専用
         * 意味的に private だが inline で呼び出すために public にしている。
         */
        suspend inline fun <reified T>  inProcForegroundWorker(
            context:Context,
            params: ForegroundWorkerParams,
            noinline action:suspend (INotificationSink)->T): T {
            val entry = ForegroundWorkerEntry<T>(action)
            foregroundWorkerMap[params.workerKey] = entry
            val request = OneTimeWorkRequest.Builder(InProcForegroundWorker::class.java)
                .setInputData(params.produce())
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()
            val workManager = WorkManager.getInstance(context)
            workManager.enqueue(request)
            val result = workManager.getWorkInfoByIdFlow(request.id).mapNotNull { it?.state }.first { state ->
                when (state) {
                    WorkInfo.State.SUCCEEDED, WorkInfo.State.FAILED, WorkInfo.State.CANCELLED -> true
                    else -> false
                }
            }
            return when (result) {
                WorkInfo.State.SUCCEEDED -> entry.result as T
                WorkInfo.State.FAILED -> throw entry.exception ?: Exception("worker failed")
                WorkInfo.State.CANCELLED -> throw entry.exception ?: CancellationException("worker cancelled")
                else -> throw IllegalStateException("worker failed")
            }
        }
    }

    /**
     * Foreground通知関連のパラメータをWorkerに引き渡すためのWorkerParamsクラス。
     * @param inputData Workerから渡されるDataオブジェクト。
     */
    open class ForegroundWorkerParams(inputData: Data?) : WorkerParams(inputData) {
        constructor(
            title:String,
            text:String,
            icon:Int,
            notificationImportance:Int = NotificationProcessor.DEFAULT_IMPORTANCE,
            notificationId:Int = 1,
            notificationChannelId:String? = null,
            notificationChannelName:String? = null,
            ) : this(null) {
            workerKey = generateFgUniqueKey()
            notificationTitle = title
            notificationText = text
            notificationIcon = icon
            this.notificationImportance = notificationImportance
            this.notificationId = notificationId
            if (notificationChannelId!=null && notificationChannelName!=null) {
                this.notificationChannelId = notificationChannelId
                this.notificationChannelName = notificationChannelName
            }
        }
        constructor(
            title:String,
            text:String,
            uploading:Boolean,
            notificationImportance:Int = NotificationProcessor.DEFAULT_IMPORTANCE,
            notificationId:Int = 1,
            notificationChannelId:String? = null,
            notificationChannelName:String? = null,
        ) : this(null) {
            workerKey = generateFgUniqueKey()
            notificationTitle = title
            notificationText = text
            this.uploading = uploading
            this.notificationImportance = notificationImportance
            this.notificationId = notificationId
            if (notificationChannelId!=null && notificationChannelName!=null) {
                this.notificationChannelId = notificationChannelId
                this.notificationChannelName = notificationChannelName
            }
        }
        var workerKey: String by delegate.string // Workerの識別子。InProcWorkerで生成されるUUIDを指定する。
        var notificationTitle: String by delegate.string
        var notificationText: String by delegate.string
        var notificationIcon: Int by delegate.intMinusOne
        var notificationImportance: Int by delegate.intMinusOne
        var notificationId: Int by delegate.intNonnull(1)
        var notificationChannelId: String by delegate.stringNonnull(NotificationProcessor.DEFAULT_CHANNEL_ID) // 通知チャンネルID
        var notificationChannelName: String by delegate.stringNonnull(NotificationProcessor.DEFAULT_CHANNEL_NAME) // 通知チャンネル名
        var uploading: Boolean by delegate.booleanFalse     // notificationIcon==-1 のとき、このup/downに従ってシステムアイコンを表示する

        val resolvedIconId:Int get() {
            return if (notificationIcon != -1) {
                notificationIcon
            } else {
                if (uploading) android.R.drawable.stat_sys_upload else android.R.drawable.stat_sys_download
            }
        }
    }

//    private fun createNotification(channelId:String, channelName:String): Notification {
//
//        // Android 8.0 (API 26)以降は通知チャンネルが必要
//        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
//            val channel = NotificationChannel(
//                channelId,
//                channelName,
//                NotificationManager.IMPORTANCE_HIGH
//            )
//            val notificationManager = applicationContext.getSystemService(NotificationManager::class.java)
//            notificationManager.createNotificationChannel(channel)
//        }
//
//        return NotificationCompat.Builder(applicationContext, channelId)
//            .setContentTitle("ファイルの転送中")
//            .setContentText("ダウンロード/アップロードを実行しています...")
//            .setSmallIcon(android.R.drawable.stat_sys_upload)
//            .setOngoing(true) // ユーザーがスワイプで消せないようにする
//            .build()
//    }
//

    /**
     * Workerのエントリポイント
     */
    override suspend fun doWork(): Result {
        val params = ForegroundWorkerParams(inputData)
        val uuid = params.workerKey
        val entry = foregroundWorkerMap[uuid] ?: return Result.failure()
        val processor = NotificationProcessor(applicationContext,params.notificationChannelId,params.notificationChannelName,params.notificationImportance, params.notificationId)
        val initialNotification = processor.message(params.notificationTitle,params.notificationText,params.resolvedIconId,true).build()
//        val initialNotification = createNotification(params.notificationChannelId, params.notificationChannelName)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            setForeground(ForegroundInfo(params.notificationId,initialNotification,ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC))
        } else {
            setForeground(ForegroundInfo(params.notificationId,initialNotification))
        }
        entry.execute(NotificationSink(processor, params))
        return Result.success()
    }

    /**
     * INotificationSinkの実装
     * Workerからラムダに渡され、ラムダ側から通知の更新を行うために利用される。
     */
    private inner class NotificationSink(val processor: NotificationProcessor, val params:ForegroundWorkerParams) : INotificationSink {
        override fun notify(
            completed: Boolean,
            title: String?,
            text: String?,
            icon: Int,
            progressInPercent: Int
        ) {
            notify(completed) { builder ->
                if (title != null) builder.setContentTitle(title)
                if (text != null) builder.setContentText(text)
                if (icon != -1) builder.setSmallIcon(icon)
                if (progressInPercent >= 0 && progressInPercent <= 100) {
                    builder.setProgress(100, progressInPercent, false)
                }
            }
        }

        override fun notify(
            completed: Boolean,
            prepare: (NotificationCompat.Builder) -> Unit
        ) {
            val builder = processor.message(params.notificationTitle,params.notificationText,params.resolvedIconId,!completed)
            prepare(builder)
            processor.notify(builder.build())
        }
    }
}