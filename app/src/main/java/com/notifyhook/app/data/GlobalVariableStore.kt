package com.notifyhook.app.data

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

class GlobalVariableStore private constructor(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val gson = Gson()
    private val lock = Any()

    companion object {
        private const val PREFS_NAME = "notifyhook_global_vars"
        private const val KEY_GLOBAL_VARS = "global_variables"

        @Volatile
        private var instance: GlobalVariableStore? = null

        fun getInstance(context: Context): GlobalVariableStore {
            return instance ?: synchronized(this) {
                instance ?: GlobalVariableStore(context.applicationContext).also { instance = it }
            }
        }
    }

    fun get(name: String): String? {
        synchronized(lock) {
            return getAllInternal()[name]
        }
    }

    fun set(name: String, value: String) {
        synchronized(lock) {
            val variables = getAllInternal().toMutableMap()
            variables[name] = value
            saveAllInternal(variables)
        }
    }

    fun setAll(updates: Map<String, String>) {
        if (updates.isEmpty()) {
            return
        }
        synchronized(lock) {
            val variables = getAllInternal().toMutableMap()
            variables.putAll(updates)
            saveAllInternal(variables)
        }
    }

    fun getAll(): Map<String, String> {
        synchronized(lock) {
            return getAllInternal().toMap()
        }
    }

    fun clear() {
        synchronized(lock) {
            prefs.edit().remove(KEY_GLOBAL_VARS).apply()
        }
    }

    private fun getAllInternal(): Map<String, String> {
        val json = prefs.getString(KEY_GLOBAL_VARS, null) ?: return emptyMap()
        val type = object : TypeToken<Map<String, String>>() {}.type
        return try {
            gson.fromJson<Map<String, String>>(json, type) ?: emptyMap()
        } catch (e: Exception) {
            emptyMap()
        }
    }

    private fun saveAllInternal(variables: Map<String, String>) {
        prefs.edit().putString(KEY_GLOBAL_VARS, gson.toJson(variables)).apply()
    }
}
