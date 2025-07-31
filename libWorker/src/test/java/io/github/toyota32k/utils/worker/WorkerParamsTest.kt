package io.github.toyota32k.utils.worker

import androidx.work.Data
import org.junit.Before
import org.junit.Test

class WorkerParamsTest {
    @Before
    fun setup() {
    }

    class TestWorkerParams(inputData: Data?) : WorkerParams(inputData) {
        var nullableString:String? by delegate.stringNullable
        var nonnullString:String by delegate.stringNonnull("hoge")
        var defaultString:String by delegate.string
        var defaultInt:Int by delegate.intZero
        var defaultLong:Long by delegate.longZero
        var defaultFloat:Float by delegate.floatZero
        var defaultDouble:Double by delegate.doubleZero
        var defaultBoolean:Boolean by delegate.booleanFalse
        var customInt:Int by delegate.intNonnull(30)
    }

    @Test
    fun workerParamsTest() {
        // Producer
        val producer = TestWorkerParams(null)
        producer.nullableString = "xxx"
        producer.defaultString = "yyy"
        producer.defaultInt = 15
        val data = producer.produce()
        val consumer = TestWorkerParams(data)
        assert(consumer.nullableString == "xxx")
        assert(consumer.defaultString == "yyy")
        assert(consumer.defaultInt == 15)
        assert(consumer.customInt == 30)
        assert(consumer.nonnullString == "hoge")

    }
}