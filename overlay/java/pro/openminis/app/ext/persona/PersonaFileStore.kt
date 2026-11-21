package pro.openminis.app.ext.persona

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 人设的文件持久化。
 *
 * 换掉内存实现是这个功能"能用"的前提：之前页面刷新一次人设就没了，
 * 用户会认为"无法创建人设"。
 *
 * 与群聊同理，落在独立文件而不是上游 Room 库：新增表在用户降级时会
 * 让整库 schema 校验失败，代价远大于收益。
 */
class PersonaFileStore(context: Context) : PersonaStore {

    private val file = File(context.filesDir, "minis-ext/personas.json")

    override fun loadAll(): List<Persona> {
        if (!file.exists()) return emptyList()
        val root = runCatching { JSONObject(file.readText()) }.getOrNull() ?: return emptyList()
        val arr = root.optJSONArray("items") ?: return emptyList()
        val out = ArrayList<Persona>(arr.length())
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            when (val decoded = PersonaJson.decode(obj.toString())) {
                is PersonaDecodeResult.Ok -> out.add(decoded.persona)
                is PersonaDecodeResult.Failed -> Unit
            }
        }
        return out
    }

    override fun saveAll(items: List<Persona>) {
        val arr = JSONArray()
        items.forEach { p ->
            // 用 PersonaJson 而不是直接把 data class 塞进 JSONObject：
            // 导出格式只有一处定义，磁盘与 .mpersona 永远不会漂移
            runCatching { arr.put(JSONObject(PersonaJson.encode(p))) }
        }
        val root = JSONObject().put("items", arr)
        file.parentFile?.mkdirs()
        file.writeText(root.toString())
    }

    override fun loadDefaultId(): String? {
        if (!file.exists()) return null
        val root = runCatching { JSONObject(file.readText()) }.getOrNull() ?: return null
        return root.optString("defaultId").takeIf { it.isNotBlank() }
    }

    override fun saveDefaultId(id: String) {
        val root = if (file.exists()) {
            runCatching { JSONObject(file.readText()) }.getOrNull() ?: JSONObject()
        } else JSONObject()
        root.put("defaultId", id)
        val items = root.optJSONArray("items") ?: JSONArray()
        root.put("items", items)
        file.parentFile?.mkdirs()
        file.writeText(root.toString())
    }
}
