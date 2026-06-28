package com.linkcast.receiver.auth

import android.annotation.SuppressLint
import android.content.Context
import android.provider.Settings
import android.util.Log
import androidx.core.content.edit
import com.linkcast.receiver.Prefs
import java.util.Locale
import java.util.concurrent.TimeUnit

object DeviceCredential {

    private const val TAG = "DeviceCredential"

    // 是否启用本地有效期窗口(仅日志,不拦截)。false = 不启用、不输出
    const val EXPIRY_ENABLED = false

    // 本地有效期天数
    const val VALIDITY_DAYS = 7L

    private val PREFS = Prefs.CREDENTIAL
    private const val KEY_ID = "device_cred_id"
    private const val KEY_ISSUED_AT = "device_cred_issued_at"

    private val validityMs: Long get() = TimeUnit.DAYS.toMillis(VALIDITY_DAYS)

    // 读取设备 ANDROID_ID 作为凭据身份
    @SuppressLint("HardwareIds")
    private fun readAndroidId(context: Context): String =
        Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
            ?.uppercase(Locale.ROOT)
            .orEmpty()

    // 凭据身份:首次取出后存本地、固定不变;超期只打日志,仍原样返回
    fun identity(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val existing = prefs.getString(KEY_ID, null)?.takeIf { it.isNotBlank() }
        if (existing != null) {
            if (isExpired(context)) {
                Log.w(TAG, "凭据已超过本地有效期($VALIDITY_DAYS 天),仍原样返回")
            }
            return existing
        }
        val id = readAndroidId(context)
        prefs.edit {
            putString(KEY_ID, id)
                .putLong(KEY_ISSUED_AT, System.currentTimeMillis())
        }
        return id
    }

    // 是否已超过本地有效期(仅 EXPIRY_ENABLED 时生效),只读不拦截
    fun isExpired(context: Context): Boolean {
        if (!EXPIRY_ENABLED) return false
        val issued =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(KEY_ISSUED_AT, 0L)
        if (issued <= 0L) return false
        return System.currentTimeMillis() - issued > validityMs
    }

    // 本地有效期剩余天数(到期为 0),仅用于展示
    fun remainingDays(context: Context): Long {
        val issued =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(KEY_ISSUED_AT, 0L)
        if (issued <= 0L) return VALIDITY_DAYS
        val left = validityMs - (System.currentTimeMillis() - issued)
        return if (left <= 0L) 0L else TimeUnit.MILLISECONDS.toDays(left) + 1
    }

    // 清除本地记录:下次 identity() 会重新取同一个 ANDROID_ID 并重新计时
    fun reset(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            remove(KEY_ID).remove(KEY_ISSUED_AT)
        }
    }
}
