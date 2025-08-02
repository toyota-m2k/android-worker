package io.github.toyota32k.utils.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.OneTimeWorkRequest
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import io.github.toyota32k.logger.UtLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import java.util.UUID

/**
 * プロセス生存中にだけ動作するWorkerクラス
 *
 *   - プロセスが動作している間だけ動作するWorker
 *   - プロセスが再起動したときの、Worker再起動は自動的にキャンセルされる。
 *   - 処理内容をラムダ式で渡すことができ、withContext() の置き換えとして利用できる。
 *
 *   コルーチンの `withContext()` を使って通信を行っている箇所を簡便に書き換える目的での利用を想定。
 *   Worker本来の「アプリが終了しても確実に実行される」特性を利用するなら、素直に CoroutineWorker を使う。
 *   Worker（doWork())からUI（ダイアログ/メッセージボックス）を表示したい場合は、
 *   CoroutineWorker に、UI(UtDialog/UtMessageBox)を表示する機能を追加した ForegroundWorker が利用できる。
 */
class InProcWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params)  {
    companion object {
        val logger = UtLog("InProcWorker")

        interface IWorkerEntry {
            suspend fun execute()
        }
        class WorkerEntry<T>(val callback:suspend ()->T): IWorkerEntry {
            var result:T? = null
            var exception: Throwable? = null
            override suspend fun execute() {
                try {
                    result = callback()
                } catch(e:Throwable) {
                    exception = e
                }
            }
        }

        val workerMap = mutableMapOf<String, IWorkerEntry>()
        const val KEY_WORKER_KEY = "IPW_UUID"

        fun generateUniqueKey():String {
            while (true) {
                val key = UUID.randomUUID().toString()
                if (!workerMap.containsKey(key)) return key
            }
        }

        suspend inline fun <reified T>  inProcWorker(context:Context, noinline action:suspend ()->T):T {
            val uuid = generateUniqueKey()
            val entry = WorkerEntry<T>(action)
            workerMap[uuid] = entry
            val request = OneTimeWorkRequest.Builder(InProcWorker::class.java)
                .setInputData(workDataOf(KEY_WORKER_KEY to uuid))
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

    override suspend fun doWork(): Result {
        val uuid = inputData.getString(KEY_WORKER_KEY) ?: return Result.failure()
        val entry = workerMap[uuid] ?: return Result.failure()
        entry.execute()
        return Result.success()
    }
}
