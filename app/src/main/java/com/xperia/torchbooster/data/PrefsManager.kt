package com.xperia.torchbooster.data
import com.xperia.torchbooster.root.BoostPolicy
import android.content.Context
class PrefsManager(context: Context) {
    val store = context.getSharedPreferences("torch_booster", Context.MODE_PRIVATE)
    fun flag(key: String, default: Boolean = false) = store.getBoolean(key, default)
    fun number(key: String, default: Int) = store.getInt(key, default)
    fun set(key: String, value: Boolean) { store.edit().putBoolean(key, value).apply() }
    fun set(key: String, value: Int) { store.edit().putInt(key, value).apply() }
    val enabled get() = flag("enabled")
    val acknowledged get() = flag("ack")
    val high get() = flag("high")
    val brightness get() = number("brightness", BoostPolicy.DEFAULT).coerceIn(BoostPolicy.MIN, BoostPolicy.limit(high))
    val dual get() = flag("dual", true)
    val qs get() = flag("qs", true)
    val video get() = flag("video")
    val delay get() = number("delay", 60).coerceIn(20, 200)
    val poll get() = number("poll", 400).coerceIn(250, 1000)
}
