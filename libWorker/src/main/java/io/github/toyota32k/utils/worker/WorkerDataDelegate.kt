package io.github.toyota32k.utils.worker

import androidx.work.Data
import kotlin.Any
import kotlin.Array
import kotlin.Boolean
import kotlin.BooleanArray
import kotlin.Double
import kotlin.Float
import kotlin.Int
import kotlin.IntArray
import kotlin.Long
import kotlin.String
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KClassifier
import kotlin.reflect.KProperty

/**
 * androidx.work.Data のデリゲートクラス
 * - 読み込み用 (data!=null)の場合は、Dataインスタンスからパラメータを取得する。
 * - 書き込み用 (data==null) の場合は、dic:MutableMapにデータを書き込む。
 *
 * 読み込み用に初期化していても、データの書き込みは止めていないが無意味なので assert(false)を出しておく。
 */
class WorkerDataDelegate(val data: Data?) {
    val dic = mutableMapOf<String,Any?>()

    private open inner class DelegateBase<R:Any>(val clazz: KClassifier) {
        @Suppress("UNCHECKED_CAST")
        fun get(key:String, def:R?): R? {
            if (data==null) {
                return dic[key] as? R
            }
            return when(clazz) {
                Boolean::class-> data.getBoolean(key, def as? Boolean ?: false)
                Byte::class -> data.getByte(key, def as? Byte ?: 0)
                Int::class -> data.getInt(key, def as? Int ?: 0)
                Long::class -> data.getLong(key, def as? Long ?: 0L)
                Float::class -> data.getFloat(key, def as? Float ?: 0f)
                Double::class -> data.getDouble(key, def as? Double ?: 0.0)
                String::class -> data.getString(key)
                Array<Boolean>::class -> data.getBooleanArray(key)
                Array<Byte>::class -> data.getByteArray(key)
                Array<Int>::class -> data.getIntArray(key)
                Array<Long>::class -> data.getLongArray(key)
                Array<Float>::class -> data.getFloatArray(key)
                Array<Double>::class -> data.getDoubleArray(key)
                Array<String>::class -> data.getStringArray(key)
                BooleanArray::class -> data.getBooleanArray(key)
                ByteArray::class -> data.getByteArray(key)
                IntArray::class -> data.getIntArray(key)
                LongArray::class -> data.getLongArray(key)
                FloatArray::class -> data.getFloatArray(key)
                DoubleArray::class -> data.getDoubleArray(key)
                else -> throw IllegalArgumentException("invalid type ${clazz.javaClass.simpleName}")
            } as? R
        }
    }

    private inner class NullableDelegate<R:Any>(clazz: KClassifier) : DelegateBase<R>(clazz), ReadWriteProperty<Any,R?> {
        override fun getValue(thisRef: Any, property: KProperty<*>): R? {
            return get(property.name, null)
        }
        override fun setValue(thisRef: Any, property: KProperty<*>, value: R?) {
            assert(data==null)
            dic[property.name] = value
        }
    }
    private inner class NonNullDelegate<R:Any>(clazz: KClassifier, val def:R) : DelegateBase<R>(clazz), ReadWriteProperty<Any,R> {
        override fun getValue(thisRef: Any, property: KProperty<*>): R {
            return super.get(property.name, def) ?: def
        }
        override fun setValue(thisRef: Any, property: KProperty<*>, value: R) {
            assert(data==null)
            dic[property.name] = value
        }
    }

    private inline fun <reified R:Any> nullable():ReadWriteProperty<Any,R?> {
        return NullableDelegate<R>(R::class)
    }
    private inline fun <reified R:Any> nonnull(def:R):ReadWriteProperty<Any,R> {
        return NonNullDelegate<R>(R::class, def)
    }

    // Int
    val intZero:ReadWriteProperty<Any,Int> by lazy { nonnull<Int>(0) }
    val intMinusOne:ReadWriteProperty<Any,Int> by lazy { nonnull<Int>(-1) }
    fun intNonnull(def:Int) : ReadWriteProperty<Any,Int> = nonnull(def)

    // Long
    val longZero:ReadWriteProperty<Any,Long> by lazy { nonnull(0L) }
    val longMinusOne:ReadWriteProperty<Any,Long> by lazy { nonnull(-1L) }
    fun longNonnull(def:Long) : ReadWriteProperty<Any,Long> = nonnull(def)

    // Float
    val floatZero:ReadWriteProperty<Any,Float> by lazy { nonnull(0f) }
    fun floatNonnull(def:Float) : ReadWriteProperty<Any,Float> = nonnull(def)

    // Double
    val doubleZero:ReadWriteProperty<Any,Double> by lazy { nonnull(0.0) }
    fun doubleNonnull(def:Double) : ReadWriteProperty<Any,Double> = nonnull(def)

    // Boolean
    val booleanFalse:ReadWriteProperty<Any,Boolean> by lazy { nonnull(false) }
    val booleanTrue: ReadWriteProperty<Any,Boolean> by lazy { nonnull(true) }
    fun booleanWithDefault(def:Boolean) : ReadWriteProperty<Any,Boolean> = nonnull(def)

    // String
    val string:ReadWriteProperty<Any,String> by lazy { nonnull("") }
    val stringNullable:ReadWriteProperty<Any,String?> by lazy { nullable<String>() }
    fun stringNonnull(def:String):ReadWriteProperty<Any,String> = nonnull(def)

    // IntArray
    val intArray:ReadWriteProperty<Any,IntArray> by lazy { nonnull(intArrayOf()) }
    val intArrayNullable:ReadWriteProperty<Any,IntArray?> by lazy { nullable<IntArray>() }
    fun intArrayNonnull(def:IntArray):ReadWriteProperty<Any,IntArray> { return nonnull(def) }

    // BooleanArray
    val booleanArray:ReadWriteProperty<Any,BooleanArray> by lazy { nonnull(booleanArrayOf()) }
    val booleanArrayNullable:ReadWriteProperty<Any,BooleanArray?> by lazy { nullable<BooleanArray>() }
    fun booleanArrayNonnull(def:BooleanArray): ReadWriteProperty<Any, BooleanArray> = nonnull(def)

    // Array<String>
    val stringArray:ReadWriteProperty<Any, Array<String>> by lazy { nonnull(emptyArray<String>()) }
    val stringArrayNullable:ReadWriteProperty<Any, Array<String>?> by lazy { nullable<Array<String>>() }
    fun stringArrayNonnull(def:Array<String>):ReadWriteProperty<Any,Array<String>> = nonnull(def)

    fun composeData():Data {
        return Data.Builder().putAll(dic).build()
    }
}