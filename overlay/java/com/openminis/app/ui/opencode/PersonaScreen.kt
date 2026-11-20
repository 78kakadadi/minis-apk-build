package com.openminis.app.ui.opencode

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.ext.group.GroupStore
import com.openminis.app.ext.persona.Persona
import com.openminis.app.ext.persona.PersonaDeleteResult
import com.openminis.app.ext.persona.PersonaFileStore
import com.openminis.app.ext.persona.PersonaRepository
import com.openminis.app.ext.persona.PersonaResult
import com.openminis.app.ext.persona.PersonaSource
import com.openminis.app.ext.persona.PersonaUsageSource

private val PN_GREEN = Color(0xFF32D74B)
private val PN_ORANGE = Color(0xFFFF9F0A)
private val PN_RED = Color(0xFFFF453A)

/**
 * 人设管理。
 *
 * 这是「通过人设创建群员」的前半段：人设必须能真正存下来、改得动、删得掉。
 * 群聊页从同一个 [PersonaRepository] 读取，所以这里新建的人设立刻能在
 * 「添加成员」里选到。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PersonaScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val groupStore = remember { GroupStore(context) }
    val repo = remember {
        PersonaRepository(
            store = PersonaFileStore(context),
            usage = PersonaUsageSource { id -> groupStore.countMemberUsage(id) },
        )
    }

    var version by remember { mutableStateOf(0) }
    var editing by remember { mutableStateOf<Persona?>(null) }
    var creating by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf("") }

    var name by remember { mutableStateOf("") }
    var prompt by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }

    fun startCreate() {
        editing = null
        creating = true
        name = ""
        prompt = ""
        description = ""
        notice = ""
    }

    fun startEdit(p: Persona) {
        creating = false
        editing = p
        name = p.name
        prompt = p.systemPrompt
        description = p.description
        notice = ""
    }

    val list = remember(version) { repo.listForUi() }
    val defaultId = remember(version) { repo.defaultPersona().id }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("人设") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    TextButton(onClick = { startCreate() }) { Text("新建") }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (notice.isNotBlank()) {
                ExtCode(notice, tint = if (notice.startsWith("已")) PN_GREEN else PN_ORANGE)
            }

            if (creating || editing != null) {
                ExtCard(
                    title = if (creating) "新建人设" else "编辑人设",
                    subtitle = "系统提示词决定成员的行为，简介只用于列表展示、不进入模型上下文",
                ) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("名称") },
                        singleLine = true,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = description,
                        onValueChange = { description = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("简介（可选）") },
                        singleLine = true,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = prompt,
                        onValueChange = { prompt = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("系统提示词") },
                        minLines = 5,
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = {
                            val draft = Persona(
                                id = editing?.id ?: "",
                                name = name,
                                description = description,
                                systemPrompt = prompt,
                                source = editing?.source ?: PersonaSource.USER,
                                createdAt = editing?.createdAt ?: 0L,
                                memoryScope = editing?.memoryScope ?: com.openminis.app.ext.opencode.memory.MemoryScope.CROSS_SESSION,
                                memoryConfig = editing?.memoryConfig ?: com.openminis.app.ext.opencode.memory.MemoryConfig.DEFAULT,
                            )
                            val result = if (editing == null) repo.create(draft) else repo.update(draft)
                            when (result) {
                                is PersonaResult.Ok -> {
                                    notice = "已保存：${result.persona.name}"
                                    creating = false
                                    editing = null
                                    version += 1
                                }
                                is PersonaResult.Rejected -> notice = result.reason
                                is PersonaResult.NotFound -> notice = "人设不存在，可能已被删除"
                            }
                        }) { Text("保存") }
                        OutlinedButton(onClick = {
                            creating = false
                            editing = null
                            notice = ""
                        }) { Text("取消") }
                    }
                }
            }

            ExtCard(title = "人设库（${list.size}）", subtitle = "内置项可复制后修改；被群成员引用的人设删除时会提示回退") {
                list.forEach { p ->
                    val refs = repo.countOf(p.id)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(p.name, fontWeight = FontWeight.Medium)
                                if (p.id == defaultId) {
                                    Text(
                                        text = " 默认",
                                        color = PN_GREEN,
                                        fontSize = 11.sp,
                                    )
                                }
                                if (p.isBuiltin) {
                                    Text(
                                        text = " 内置",
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        fontSize = 11.sp,
                                    )
                                }
                            }
                            Text(
                                text = if (p.description.isBlank()) p.systemPrompt.take(60) else p.description,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (refs > 0) {
                                Text(
                                    text = "$refs 个群成员在用",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = PN_ORANGE,
                                )
                            }
                        }
                        if (!p.isBuiltin) {
                            TextButton(onClick = { startEdit(p) }) { Text("编辑") }
                        }
                        TextButton(onClick = {
                            when (val r = repo.duplicate(p.id)) {
                                is PersonaResult.Ok -> {
                                    notice = "已复制为「${r.persona.name}」"
                                    version += 1
                                }
                                is PersonaResult.Rejected -> notice = r.reason
                                is PersonaResult.NotFound -> notice = "人设不存在"
                            }
                        }) { Text("复制") }
                        TextButton(onClick = {
                            when (val r = repo.delete(p.id)) {
                                is PersonaDeleteResult.Deleted -> {
                                    notice = if (r.referencingMembers > 0) {
                                        "已删除，${r.referencingMembers} 个成员将回退到「${repo.defaultPersona().name}」"
                                    } else "已删除"
                                    version += 1
                                }
                                is PersonaDeleteResult.Rejected -> notice = r.reason
                                is PersonaDeleteResult.NotFound -> notice = "人设不存在"
                            }
                        }) { Text(if (p.isBuiltin) "删除" else "删除", color = PN_RED) }
                    }
                    TextButton(onClick = {
                        repo.setDefault(p.id)
                        notice = "已将「${p.name}」设为默认人设"
                        version += 1
                    }) { Text("设为默认", fontSize = 11.sp) }
                }
            }
        }
    }
}
