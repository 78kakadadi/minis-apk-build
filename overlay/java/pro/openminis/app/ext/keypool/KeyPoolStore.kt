package pro.openminis.app.ext.keypool

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * 号池配置：**一个 base URL + 多把 Key**。
 *
 * 为什么是「一个 URL」而不是「每把 Key 一个 URL」：中转站/官方网关的 Key
 * 是同一账户体系下的多个凭证，指向同一个入口；如果每把 Key 还能有不同 URL，
 * 那本质上就是多个独立渠道，应该拆成多个 provider 实例，而不是塞进一个池子。
 *
 * 明文 Key 只存在 [KeyPoolStore] 的加密偏好里（`EncryptedPrefsFactory`，
 * 与上游 provider 凭据同一套 Keystore），配置文件里只有 id 与备注。
 */
data class KeyPoolConfig(
    val baseUrl: String = "",
    /** 号池转发时替换成的模型名；留空表示原样透传请求里的 model。 */
    val defaultModel: String = "",
    val keys: List<PooledKey> = emptyList(),
) {
    val usableKeys: List<PooledKey> get() = keys.filter { it.enabled }

    /** 只在「有 URL 且有至少一把启用的 Key」时才算配置完整。 */
    val ready: Boolean get() = baseUrl.isNotBlank() && usableKeys.isNotEmpty()

    companion object {
        /** 号池里的 Key 都属于同一个逻辑渠道，用固定 provider 名做分组。 */
        const val PROVIDER = "keypool"
    }
}

/**
 * 号池持久化。
 *
 * 两处存储，按敏感度切分：
 *  - 普通偏好：baseUrl / 默认模型 / Key 的 id、备注、权重、启用状态；
 *  - 加密偏好：`secret_<id>` → 明文 Key。
 *
 * 这样清空普通偏好不会连带丢掉加密区（反之亦然），而设置页渲染列表时
 * 不需要解密任何东西——界面只显示备注与尾号，尾号在保存时一并算好存明文中。
 */
class KeyPoolStore(context: Context) {

    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("minis_key_pool", Context.MODE_PRIVATE)

    private val secrets by lazy {
        pro.openminis.app.util.EncryptedPrefsFactory.safeCreate(app, "keypool_secrets")
    }

    /** 号池注册到上游 provider 体系后的实例 id（未注册为 null）。 */
    var providerInstanceId: String?
        get() = prefs.getString(KEY_INSTANCE, null)
        set(value) = prefs.edit().putString(KEY_INSTANCE, value).apply()

    var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_ENABLED, value).apply()

    var gatewayPort: Int
        get() = prefs.getInt(KEY_PORT, 0)
        set(value) = prefs.edit().putInt(KEY_PORT, value).apply()

    /** 最近一次错误，设置页首屏可直接显示（进程被杀后仍看得到）。 */
    var lastError: String?
        get() = prefs.getString(KEY_LAST_ERROR, null)
        set(value) = prefs.edit().putString(KEY_LAST_ERROR, value).apply()

    fun load(): KeyPoolConfig {
        val raw = prefs.getString(KEY_CONFIG, null) ?: return KeyPoolConfig()
        return runCatching {
            val o = JSONObject(raw)
            val arr = o.optJSONArray("keys") ?: JSONArray()
            val keys = ArrayList<PooledKey>(arr.length())
            for (i in 0 until arr.length()) {
                val k = arr.optJSONObject(i) ?: continue
                val id = k.optString("id").takeIf { it.isNotBlank() } ?: continue
                keys.add(
                    PooledKey(
                        id = id,
                        provider = KeyPoolConfig.PROVIDER,
                        secretRef = "enc:secret_$id",
                        weight = k.optInt("weight", 1).coerceAtLeast(1),
                        enabled = k.optBoolean("enabled", true),
                        label = k.optString("label"),
                    ),
                )
            }
            KeyPoolConfig(
                baseUrl = o.optString("baseUrl").trim(),
                defaultModel = o.optString("defaultModel").trim(),
                keys = keys,
            )
        }.getOrElse { KeyPoolConfig() }
    }

    /** 保存配置；`secret` 非空时同步写入加密区（新增或改 Key 都走这里）。 */
    fun save(config: KeyPoolConfig) {
        val arr = JSONArray()
        config.keys.forEach { k ->
            arr.put(
                JSONObject()
                    .put("id", k.id)
                    .put("label", k.label)
                    .put("weight", k.weight.coerceAtLeast(1))
                    .put("enabled", k.enabled),
            )
        }
        val o = JSONObject()
            .put("baseUrl", config.baseUrl.trim())
            .put("defaultModel", config.defaultModel.trim())
            .put("keys", arr)
        prefs.edit().putString(KEY_CONFIG, o.toString()).apply()
        // 不在这里删 secret：删 Key 由 [removeKey] 明确处理，避免「保存列表时
        // 顺手清掉刚解密失败的那把」这种静默丢数据。
    }

    fun secret(keyId: String): String? = secrets.getString("secret_$keyId", null)

    fun putSecret(keyId: String, value: String) {
        secrets.edit().putString("secret_$keyId", value.trim()).apply()
    }

    fun removeKey(keyId: String) {
        val cfg = load()
        save(cfg.copy(keys = cfg.keys.filterNot { it.id == keyId }))
        secrets.edit().remove("secret_$keyId").apply()
    }

    /** 新建一把 Key：id 随机，备注默认「Key N」。 */
    fun newKey(existing: List<PooledKey>, label: String = ""): PooledKey {
        val id = "k_" + UUID.randomUUID().toString().replace("-", "").take(10)
        return PooledKey(
            id = id,
            provider = KeyPoolConfig.PROVIDER,
            secretRef = "enc:secret_$id",
            weight = 1,
            enabled = true,
            label = label.ifBlank { "Key ${existing.size + 1}" },
        )
    }

    /** 只给界面看的尾号，避免整串 Key 出现在截图/日志里。 */
    fun maskedTail(keyId: String): String {
        val s = secret(keyId) ?: return ""
        if (s.length <= 8) return "••••"
        return "••••" + s.takeLast(4)
    }

    private companion object {
        const val KEY_CONFIG = "config"
        const val KEY_INSTANCE = "provider_instance_id"
        const val KEY_ENABLED = "enabled"
        const val KEY_PORT = "gateway_port"
        const val KEY_LAST_ERROR = "last_error"
    }
}
