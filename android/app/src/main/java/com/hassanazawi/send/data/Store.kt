package com.hassanazawi.send.data

import android.content.Context
import android.os.Build
import android.provider.Settings
import com.hassanazawi.send.net.randomHex
import org.json.JSONArray
import org.json.JSONObject

data class Paired(val id: String, val name: String, val token: String, val at: Long)

/** Small key-value store: this device's identity, its name, and the PCs it has paired with. */
class Store(ctx: Context) {
    private val sp = ctx.getSharedPreferences("send", Context.MODE_PRIVATE)
    private val defaultName: String =
        (runCatching { Settings.Global.getString(ctx.contentResolver, "device_name") }.getOrNull())
            ?.takeIf { it.isNotBlank() } ?: Build.MODEL

    val deviceId: String = sp.getString("id", null) ?: randomHex(8).also { sp.edit().putString("id", it).apply() }

    var deviceName: String
        get() = sp.getString("name", null)?.takeIf { it.isNotBlank() } ?: defaultName
        set(v) = sp.edit().putString("name", v.trim().take(40)).apply()

    @Synchronized
    fun paired(): List<Paired> {
        val arr = runCatching { JSONArray(sp.getString("paired", "[]")) }.getOrDefault(JSONArray())
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            Paired(o.getString("id"), o.getString("name"), o.getString("token"), o.optLong("at"))
        }
    }

    @Synchronized
    fun addPaired(p: Paired) {
        val list = paired().filter { it.id != p.id } + p
        save(list)
    }

    @Synchronized
    fun removePaired(id: String) = save(paired().filter { it.id != id })

    fun find(id: String): Paired? = paired().firstOrNull { it.id == id }

    private fun save(list: List<Paired>) {
        val arr = JSONArray()
        for (p in list) arr.put(JSONObject().put("id", p.id).put("name", p.name).put("token", p.token).put("at", p.at))
        sp.edit().putString("paired", arr.toString()).apply()
    }
}
