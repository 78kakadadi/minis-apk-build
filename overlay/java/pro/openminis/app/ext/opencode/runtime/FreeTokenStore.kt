package pro.openminis.app.ext.opencode.runtime

import android.content.Context

/**
 * 免费通道的用户选择与偏好。
 *
 * 用 SharedPreferences 而不是 DataStore：这里只有三个标量，DataStore 的
 * 协程/迁移开销换不来任何收益，而初始化路径上每多一次挂起就多一个
 * 「首屏还没恢复状态」的窗口。
 */
class FreeTokenStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("minis_free_token", Context.MODE_PRIVATE)

    var optIn: Boolean
        get() = prefs.getBoolean(KEY_OPT_IN, false)
        set(value) = prefs.edit().putBoolean(KEY_OPT_IN, value).apply()

    var promptShown: Boolean
        get() = prefs.getBoolean(KEY_PROMPT_SHOWN, false)
        set(value) = prefs.edit().putBoolean(KEY_PROMPT_SHOWN, value).apply()

    var defaultModel: String?
        get() = prefs.getString(KEY_MODEL, null)
        set(value) = prefs.edit().putString(KEY_MODEL, value).apply()

    /** 已注册到上游 Provider 体系的实例 id（避免重复注册）。 */
    var providerInstanceId: String?
        get() = prefs.getString(KEY_INSTANCE, null)
        set(value) = prefs.edit().putString(KEY_INSTANCE, value).apply()

    /** 最近一次失败原因，供设置页首屏显示（进程被杀后仍能看到）。 */
    var lastError: String?
        get() = prefs.getString(KEY_LAST_ERROR, null)
        set(value) = prefs.edit().putString(KEY_LAST_ERROR, value).apply()

    /** 最近一次成功启动的端口，仅用于诊断。 */
    var lastPort: Int
        get() = prefs.getInt(KEY_LAST_PORT, 0)
        set(value) = prefs.edit().putInt(KEY_LAST_PORT, value).apply()

    private companion object {
        const val KEY_OPT_IN = "opt_in"
        const val KEY_PROMPT_SHOWN = "prompt_shown"
        const val KEY_MODEL = "default_model"
        const val KEY_INSTANCE = "provider_instance_id"
        const val KEY_LAST_ERROR = "last_error"
        const val KEY_LAST_PORT = "last_port"
    }
}
