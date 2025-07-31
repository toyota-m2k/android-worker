package io.github.toyota32k.utils.worker

import androidx.work.Data

/**
 * タイプセーフな androidx.work.Data の作成(produce)と読み取り（consume) をサポートするクラス。
 * - コンストラクタに null を渡すと、Data作成用に初期化される。プロパティにデータをセットして produce()を呼び出すことで Dataインスタンスが作成される。
 * - コンストラクタに データインスタンス（inputData）を渡すと、inputDataからの読み出し用に初期化される（Worker内で、パラメータを取り出すために利用する）
 *
 */
abstract class WorkerParams(inputData: Data?) {
    protected val delegate = WorkerDataDelegate(inputData)
    val forConsumer get():Boolean = delegate.data != null
    val forProducer get():Boolean = delegate.data == null

//    val hoge:Int by delegate.intZero

    fun produce():Data {
        assert(forProducer)
        return delegate.composeData()
    }
    fun produce(builder: Data.Builder):Data.Builder {
        assert(forProducer)
        return builder.putAll(delegate.dic)
    }
}