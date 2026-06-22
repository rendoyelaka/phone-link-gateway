package com.smsgateway.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class ForwardTarget(
    val id: String,
    val address: String,
    val label: String,
    val type: Type
) {
    enum class Type { TELEGRAM, SMS }

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("address", address)
        put("label", label)
        put("type", type.name)
    }

    companion object {
        fun fromJson(obj: JSONObject) = ForwardTarget(
            id = obj.getString("id"),
            address = obj.getString("address"),
            label = obj.getString("label"),
            type = Type.valueOf(obj.getString("type"))
        )
    }
}

object ForwardManager {

    private const val PREF_KEY = "forward_targets"

    fun getForwardTargets(context: Context): List<ForwardTarget> {
        val prefs = context.getSharedPreferences("gateway_config", Context.MODE_PRIVATE)
        val json = prefs.getString(PREF_KEY, "[]") ?: "[]"
        val arr = JSONArray(json)
        return (0 until arr.length()).map { ForwardTarget.fromJson(arr.getJSONObject(it)) }
    }

    fun addTarget(context: Context, target: ForwardTarget) {
        val list = getForwardTargets(context).toMutableList()
        list.add(target)
        saveTargets(context, list)
    }

    fun removeTarget(context: Context, id: String) {
        val list = getForwardTargets(context).filter { it.id != id }
        saveTargets(context, list)
    }

    fun clearAll(context: Context) {
        saveTargets(context, emptyList())
    }

    private fun saveTargets(context: Context, list: List<ForwardTarget>) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        context.getSharedPreferences("gateway_config", Context.MODE_PRIVATE)
            .edit().putString(PREF_KEY, arr.toString()).apply()
    }

    fun generateId(): String = System.currentTimeMillis().toString()
}
