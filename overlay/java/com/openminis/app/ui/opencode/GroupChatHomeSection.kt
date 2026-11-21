package com.openminis.app.ui.opencode

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.ext.group.AiAvatars
import com.openminis.app.ext.group.GroupStore
import com.openminis.app.ext.opencode.runtime.FreeTokenManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 主页是否要显示群聊模块（会话列表用它决定要不要切到列表分支）。
 *
 * 会话列表每次重组都会问一次，直接读文件会把主线程拖慢，所以加一层 1 秒的内存缓存：
 * 建群/删群后最多晚 1 秒生效，对用户没有感知。
 */
private var groupCacheAt = 0L
private var groupCacheValue = false

fun hasGroupChats(context: Context): Boolean {
    val now = System.currentTimeMillis()
    if (now - groupCacheAt < 1000L) return groupCacheValue
    groupCacheValue = runCatching { GroupStore(context).groups().isNotEmpty() }.getOrDefault(false)
    groupCacheAt = now
    return groupCacheValue
}

/**
 * 主页（会话列表顶部）的群聊模块。
 *
 * 为什么放在会话列表内部而不是 Settings 里：群聊是「对话」的一种，用户找一个
 * 聊天时先看主页。放在这里也顺手解决了「免费通道要先进设置页才会启动」的问题——
 * 主页一出现就尝试按用户上次的选择拉起它（[FreeTokenManager.autoStartIfOptedIn]），
 * 这样普通对话里选「内置 OpenCode（免费）」才是真的能用。
 */
@Composable
fun GroupChatHomeSection(
    onOpen: (String) -> Unit,
    onCreate: () -> Unit,
) {
    val context = LocalContext.current
    val store = remember(context) { GroupStore(context) }

    LaunchedEffect(Unit) {
        runCatching { FreeTokenManager.get(context).autoStartIfOptedIn() }
    }

    val groups = runCatching { store.groups() }.getOrDefault(emptyList())
    if (groups.isEmpty()) return

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "群聊（${groups.size}）",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "新建",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { onCreate() }
                    .padding(horizontal = 8.dp, vertical = 2.dp),
            )
        }

        groups.take(4).forEach { g ->
            val members = runCatching { store.members(g.id) }.getOrDefault(emptyList())
            val last = runCatching { store.lastMessage(g.id) }.getOrNull()

            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onOpen(g.id) },
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row {
                        members.take(3).forEachIndexed { index, m ->
                            val spec = AiAvatars.resolve(m)
                            Box(
                                modifier = Modifier
                                    .padding(start = if (index == 0) 0.dp else (-8).dp)
                                    .size(30.dp)
                                    .clip(CircleShape)
                                    .background(Color(spec.color)),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = spec.label,
                                    color = Color.White,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                        }
                    }
                    Spacer(Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(g.name, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            text = last?.let { msg ->
                                val who = msg.memberName ?: "我"
                                "$who：${msg.text.take(40)}"
                            } ?: "${members.size} 位成员 · 还没有消息",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    last?.let {
                        Text(
                            text = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
                                .format(Date(it.createdAt)),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}
