package com.jmwl.gostudio.feedback

import android.content.Context

/**
 * 问题反馈的本地设置：联系方式（邮箱/QQ）与设备标识。
 *
 * 设备标识用 ANDROID_ID（应用签名级稳定、恢复出厂会重置，正适合
 * 「同一台设备看自己的反馈记录」场景）；取不到时退化为首次生成的 UUID。
 * 沿用 goproxy_store 的 SharedPreferences + 内存缓存模式。
 */
object feedback_settings_store {

    private const val prefs_name = "feedback_settings"
    private const val key_contact = "feedback_contact"
    private const val key_device_id = "feedback_device_id"

    @Volatile
    private var current_contact: String = ""

    @Volatile
    private var current_device_id: String = ""

    /** app 启动时加载持久化值。 */
    fun init(context: Context) {
        val prefs = context.getSharedPreferences(prefs_name, Context.MODE_PRIVATE)
        current_contact = prefs.getString(key_contact, "").orEmpty()
        current_device_id = prefs.getString(key_device_id, "").orEmpty()
    }

    var contact: String
        get() = current_contact
        set(value) {
            current_contact = value.trim()
        }

    /** 持久化联系方式。 */
    fun save_contact(context: Context, contact: String) {
        current_contact = contact.trim()
        context.getSharedPreferences(prefs_name, Context.MODE_PRIVATE)
            .edit().putString(key_contact, current_contact).apply()
    }

    /**
     * 取（必要时生成并持久化）设备标识。
     * ANDROID_ID 在极个别设备上会返回空/垃圾值（"9774d56d682e549c"），
     * 此时改用 UUID。
     */
    fun device_id(context: Context): String {
        if (current_device_id.isNotBlank()) return current_device_id
        val prefs = context.getSharedPreferences(prefs_name, Context.MODE_PRIVATE)
        val stored = prefs.getString(key_device_id, "").orEmpty()
        if (stored.isNotBlank()) {
            current_device_id = stored
            return stored
        }
        val android_id = android.provider.Settings.Secure.getString(
            context.contentResolver, android.provider.Settings.Secure.ANDROID_ID
        ).orEmpty()
        val generated = when {
            android_id.isNotBlank() && android_id != "9774d56d682e549c" -> android_id
            else -> java.util.UUID.randomUUID().toString().replace("-", "")
        }
        prefs.edit().putString(key_device_id, generated).apply()
        current_device_id = generated
        return generated
    }
}
