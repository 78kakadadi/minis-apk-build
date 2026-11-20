package com.openminis.app.ui.opencode

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.openminis.app.ext.group.GroupStore

/**
 * 长按「新对话」按钮后弹出的群聊入口。
 *
 * 为什么放在长按而不是新增一个按钮：底部一行已经被「新对话 / 搜索」两个圆形按钮
 * 占满，再加一个会挤压列表；而长按本来是这个按钮的隐藏手势（上游用来选模型组），
 * 复用同一个手势，用户不需要学习新位置。
 *
 * 建群与选群都走 [GroupStore]（群聊自己的一等存储），不复用上游会话的模型组概念，
 * 避免「看起来建了群，实际只是给会话打了标签」。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupChatFabMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    onOpen: (String) -> Unit,
) {
    if (!expanded) return

    val context = LocalContext.current
    val store = remember { GroupStore(context) }
    val prefs = remember { context.getSharedPreferences("ui_prefs", Context.MODE_PRIVATE) }
    val sheetState = rememberModalBottomSheetState()

    var groups by remember { mutableStateOf(store.groups()) }
    var name by remember { mutableStateOf("") }

    // 用户已经找到这个入口了，首次提示不用再出现。
    LaunchedEffect(Unit) { prefs.edit().putBoolean(PREF_GROUP_HINT_SEEN, true).apply() }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
        ) {
            Text(
                text = "群聊",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "一个群里可以放多个人设成员，各自绑定模型，按策略依次发言。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(16.dp))
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("新群名称（留空则叫「新群聊」）") },
                singleLine = true,
            )
            Spacer(Modifier.height(10.dp))
            Button(
                onClick = {
                    val created = store.createGroup(name)
                    name = ""
                    groups = store.groups()
                    onOpen(created.id)
                },
            ) {
                Icon(Icons.Filled.Add, contentDescription = null)
                Spacer(Modifier.width(4.dp))
                Text("创建群聊")
            }

            if (groups.isNotEmpty()) {
                Spacer(Modifier.height(18.dp))
                Text(
                    text = "已有群（${groups.size}）",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                groups.forEach { g ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onOpen(g.id) }
                            .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Outlined.Forum, contentDescription = null, tint = Color(0xFFFF9F0A))
                        Spacer(Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(g.name, fontWeight = FontWeight.Medium)
                            Text(
                                text = "${store.members(g.id).size} 位成员",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text(
                            text = "进入",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        }
    }
}

internal const val PREF_GROUP_HINT_SEEN = "group_chat_fab_hint_seen"

/**
 * 首次进入会话列表时，在新对话按钮上方浮一条小提示：长按可以建群聊。
 *
 * 只出现一次（写 SharedPreferences），点一下或 9 秒后自动消失。做法是「提示条」
 * 而不是教程遮罩：不挡住列表、不需要点「下一步」，用户没兴趣时自己会忽略。
 */
@Composable
fun GroupChatFabHint() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("ui_prefs", Context.MODE_PRIVATE) }
    var visible by remember { mutableStateOf(!prefs.getBoolean(PREF_GROUP_HINT_SEEN, false)) }

    LaunchedEffect(Unit) {
        if (!visible) return@LaunchedEffect
        kotlinx.coroutines.delay(9000)
        if (visible) {
            visible = false
            prefs.edit().putBoolean(PREF_GROUP_HINT_SEEN, true).apply()
        }
    }

    if (!visible) return

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clickable {
                visible = false
                prefs.edit().putBoolean(PREF_GROUP_HINT_SEEN, true).apply()
            },
        horizontalArrangement = Arrangement.Center,
    ) {
        Surface(
            color = MaterialTheme.colorScheme.secondaryContainer,
            shape = RoundedCornerShape(percent = 50),
            shadowElevation = 2.dp,
        ) {
            Text(
                text = "长按左下角的新对话按钮，可以创建群聊",
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
    }
}
