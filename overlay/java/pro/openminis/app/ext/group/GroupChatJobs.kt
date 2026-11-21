package pro.openminis.app.ext.group

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * 群聊发言任务的宿主（进程级，不跟界面生命周期走）。
 *
 * ## 为什么要有它
 *
 * 以前任务挂在 `rememberCoroutineScope()` 上，那是**跟随界面**的作用域：
 * 用户一按返回（或系统回收界面），job 立刻被取消，正在流式的回复直接断在
 * 半路——真机表现就是「AI 回复过程中退出界面再回来，回复没了」。
 *
 * 现在任务跑在进程级 scope 里，界面只是「观看者」：退出去不影响生成，
 * 重新进群从磁盘读草稿就能看到已经输出的部分，生成完成后再刷新成最终版。
 *
 * ## 并发约束
 *
 * 同一个群同一时刻只允许一轮：再发一条会取消上一轮，避免两轮交错往同一个
 * 消息文件里写（那正是「消息莫名其妙消失」的来源之一）。
 */
object GroupChatJobs {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val running = ConcurrentHashMap<String, Job>()

    fun isRunning(groupId: String): Boolean = running[groupId]?.isActive == true

    /** 正在发言的群 id 集合，主页/列表页想看「谁在说话」时用。 */
    fun activeGroups(): Set<String> = running.filterValues { it.isActive }.keys

    fun launch(groupId: String, block: suspend () -> Unit): Job {
        running.remove(groupId)?.cancel()
        // 先建一个不启动的 job、放进表里再 start：如果反过来（先 start 再 put），
        // 任务可能在 put 之前就跑完，finally 里的 remove 删不到自己，表里留下
        // 一个永远「已完成」的 job —— 之后 isRunning 判断会错。
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } finally {
                kotlin.coroutines.coroutineContext[Job]?.let { running.remove(groupId, it) }
            }
        }
        running[groupId] = job
        job.start()
        return job
    }

    fun cancel(groupId: String) {
        running.remove(groupId)?.cancel()
    }
}
