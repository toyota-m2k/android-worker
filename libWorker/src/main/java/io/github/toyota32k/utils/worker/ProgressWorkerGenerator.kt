package io.github.toyota32k.utils.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ListenableWorker
import androidx.work.OneTimeWorkRequest
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import io.github.toyota32k.logger.UtLog
import io.github.toyota32k.utils.IAwaiter
import io.github.toyota32k.utils.UtLib
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.shareIn
import java.util.UUID
import kotlin.coroutines.CoroutineContext

// UI側から 進捗・終了監視 ができるWorkerの実装を支援するクラス群
// IWorkerAwaiter インターフェースを通じて、Workerの終了を監視することができる。
// しかし、Workerは、実行中にプロセスが再起動すると、再度開始されるが、
// その場合、UI側、それ（Workerが再開されたこと）を知るすべがないので、監視を続けることができない。
// つまり、「Workerが終了したらUI側で何かする」という実装には使えない。
// 進捗・終了監視がオプショナルな利用シーン（<--あるのか？）でのみ利用する。
//
// このWorkerの動作を見ると、Worker は、本来、UIから切り離されて、独立して動作するように設計するべきなのだと思われる。
// しかし、Socket通信がOSによって遮断される問題を回避するため、現在、コルーチンやサブスレッドで通信を行っている箇所を、
// Workerで実行するよう書き換えようとしたとき、単純にUIから分離することができなくて行き詰った。
// ProgressWorker は、そのために実装したクラスだが、実際に利用してみると、あまり使い勝手が良くないことがわかった。
// そこで、Worker本来の設計思想からはやや逸脱するが、目的によって使い分けられる２つのクラスを追加した。
//
// - InProcWorker
//   - プロセスが動作している間だけ動作するWorker
//   - プロセスが再起動したときの、Worker再起動を抑止する。
//   - 処理内容をラムダ式で渡すことができ、withContext() の置き換えとして利用できる。
//
// - ForegroundWorker
//   - プロセスが再起動するとWorkerを再起動する。
//   - doWork()からUI（ダイアログやメッセージボックス）を表示できる。
//   - ただし、UI側からの監視はできないので、doWork()内で処理が完結する必要がある。

/**
 * Workerの完了を待ち合わせるための i/f (IAwaiter)
 * 完了時の WorkInfo を lastWorkInfoに保持する。
 * WorkInfo.outputData によって、SUCCEEDED/FAILED以外の情報を返したいときに利用することを想定。
 */
interface IWorkerAwaiter : IAwaiter<Boolean> {
    val lastWorkInfo: WorkInfo?
}

/**
 * progressコールバック付きCoroutineWorker の実装用基底クラス
 * 派生クラスで、fun doWork() をオーバーライドし、Workerの処理を記述する。
 * doWork() 内では、CoroutineWorkerのメソッドに加えて、以下のメソッドが利用できる。
 *
 * - fun progress() 進捗をProgressAwaiterに送信する --> ProgressWorkerGeneratorに渡した onProgress ハンドラで受け取る
 * - fun customEvent() 任意のDataをProgressAwaiterに送信する --> ProgressWorkerGeneratorに渡した onCustomEvent ハンドラで受け取る
 */
abstract class ProgressWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    companion object {
        const val PROGRESS_TOTAL_LENGTH = "total_length"
        const val PROGRESS_CURRENT_BYTES = "current_bytes"
        const val PROGRESS_CUSTOM = "custom_progress_event"
    }
    protected suspend fun progress(current:Long, total:Long) {
        setProgress(
            workDataOf(
                PROGRESS_CURRENT_BYTES to current,
                PROGRESS_TOTAL_LENGTH to total
            ))
    }
    protected suspend fun customEvent(eventData:Data.Builder) {
        eventData.putBoolean(PROGRESS_CUSTOM, true)
        setProgress(eventData.build())
    }
}

/**
 * ProgressWorkerを生成・実行するためのヘルパークラス。
 */
object ProgressWorkerGenerator {
    val logger = UtLog("PWG", UtLib.logger)

    /**
     * ProgressWorkerと通信し、実行を待ち合わせて、結果を中継するための IWorkerAwaiter実装クラス。
     */
    class ProgressAwaiter(
        scope:CoroutineScope,
        val workManager:WorkManager, val id:UUID,
        val onProgress:((current:Long, total:Long)->Unit)?,
        val onCustomEvent:((Data)->Unit)?): IWorkerAwaiter {
        override var lastWorkInfo: WorkInfo? = null

        private val result:Flow<Boolean> = flow {
            workManager.getWorkInfoByIdFlow(id)
                .collect { workInfo ->
                    when (workInfo?.state) {
                        WorkInfo.State.RUNNING -> {
                            workInfo.progress.apply {
                                if (getBoolean(ProgressWorker.Companion.PROGRESS_CUSTOM, false)) {
                                    onCustomEvent?.invoke(this)
                                } else {
                                    onProgress?.invoke(
                                        getLong(ProgressWorker.Companion.PROGRESS_CURRENT_BYTES, 0L),
                                        getLong(ProgressWorker.Companion.PROGRESS_TOTAL_LENGTH, 0L)
                                    )
                                }
                            }
                        }
                        WorkInfo.State.SUCCEEDED -> {
                            logger.info("work succeeded")
                            lastWorkInfo = workInfo
                            emit(true)
                        }
                        WorkInfo.State.FAILED, WorkInfo.State.CANCELLED -> {
                            logger.info("work failed")
                            lastWorkInfo = workInfo
                            emit(false)
                        }
                        else -> {}
                    }
                }
            logger.info("Completed")
        }.shareIn(scope, started = SharingStarted.Eagerly, replay = 1)

        /**
         * 結果を待ち合わせる。
         */
        override suspend fun await(): Boolean {
            return result.first()
        }

        /**
         * Workerの処理を中止する。
         */
        override fun cancel() {
            workManager.cancelWorkById(id)
        }
    }

    /**
     * 最小限の指定で ProgressWorkerを開始する。
     */
    inline fun <reified T:ProgressWorker> process(context:Context, data:Data, noinline onProgress:((current:Long, total:Long)->Unit)?): IWorkerAwaiter {
        return builder<T>().setInputData(data).apply { if(onProgress!=null) onProgress(onProgress) }.build(context)
    }

    /**
     * ProgressWorker開始用の Builderクラス
     */
    class Builder(clazz:Class<out ListenableWorker>) {
        private val request = OneTimeWorkRequest.Builder(clazz)
        private var expedited: OutOfQuotaPolicy = OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST
        private var coroutineContext:CoroutineContext = Dispatchers.IO
        private var onProgress: ((current:Long, total:Long)->Unit)? = null
        private var onCustomEvent: ((Data)->Unit)? = null


        fun setExpedited(expedited: OutOfQuotaPolicy): Builder {
            this.expedited = expedited
            return this
        }
        fun setInputData(data:Data) : Builder {
            request.setInputData(data)
            return this
        }
        fun customize(fn:(OneTimeWorkRequest.Builder)->Unit): Builder {
            fn(request)
            return this
        }
        fun onProgress(fn:(current:Long, total:Long)->Unit): Builder {
            onProgress = fn
            return this
        }
        fun onCustomEvent(fn:(Data)->Unit): Builder {
            onCustomEvent = fn
            return this
        }
        fun build(context:Context): IWorkerAwaiter {
            val req = request.setExpedited(expedited).build()

            val workManager = WorkManager.getInstance(context)
            workManager.enqueue(req)
            return ProgressAwaiter(CoroutineScope(coroutineContext), workManager, req.id, onProgress, onCustomEvent)
        }
    }

    inline fun <reified T:ProgressWorker> builder():Builder {
        return Builder(T::class.java)
    }
    fun builder(clazz:Class<out ListenableWorker>):Builder {
        return Builder(clazz)
    }
}