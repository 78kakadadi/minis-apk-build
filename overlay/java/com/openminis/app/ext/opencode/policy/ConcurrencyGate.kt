package com.openminis.app.ext.opencode.policy

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 按 **provider** 计数的并发闸门（规格 §9.10）。
 *
 * 三处容易做错的地方，这里逐一固定：
 *
 *  1. **上限按 provider 计，不按成员计**。一台本地 OpenCode 服务器默认只吃 1–2 路并发，
 *     与群聊里坐了几个免费成员无关。按成员计会让 5 个成员同时打爆服务器，全部超时。
 *  2. **只有免费成员排队**。付费成员各自走 Key 池，永不被免费队列阻塞——所以这个
 *     闸门只包住 opencode-local 的调用点，不能包住整个编排循环。
 *  3. **位置从 1 起算且实时**。队列里第一个等待者显示「第 1 位」（规格 §9.10 原文），
 *     不是「第 2 位」。
 *
 * 取消语义：等待中的成员被取消时必须**真正离队**，否则会永久占住一个名额，
 * 表现为「后面所有人卡住」。这一点在 [withPermit] 的 catch 里处理。
 */
class ConcurrencyGate(private val limit: Int) {

    init {
        require(limit >= 1) { "并发上限必须 ≥ 1，收到 $limit" }
    }

    private class Waiter(val signal: CompletableDeferred<Unit>)

    private val mutex = Mutex()
    private var active = 0
    private val queue = ArrayDeque<Waiter>()

    /** 当前占用数与排队数，供状态 chip / 调试端点读取。 */
    suspend fun stats(): Stats = mutex.withLock { Stats(active, queue.size) }

    data class Stats(val active: Int, val queued: Int)

    /**
     * 取许可执行 [block]。
     *
     * [onQueued] 在**确定需要排队后**调用一次，参数是计算出的排队位置，
     * 宿主用它把「排队中（第 N 位）」写进该成员的气泡。
     */
    suspend fun <T> withPermit(
        onQueued: suspend (position: Int) -> Unit = {},
        block: suspend () -> T,
    ): T {
        val waiter: Waiter? = mutex.withLock {
            if (active < limit) {
                active += 1
                null
            } else {
                Waiter(CompletableDeferred()).also { queue.addLast(it) }
            }
        }

        if (waiter != null) {
            val position = mutex.withLock { queue.indexOf(waiter) + 1 }
            if (position > 0) onQueued(position)

            try {
                waiter.signal.await()
            } catch (e: CancellationException) {
                // 取消：要么还在队列里（直接摘掉），要么许可已经发给自己（归还）
                mutex.withLock {
                    if (!queue.remove(waiter)) {
                        handOverLocked()
                    }
                }
                throw e
            }
        }

        try {
            return block()
        } finally {
            mutex.withLock { handOverLocked() }
        }
    }

    /**
     * 释放一个名额：直接转交给队首等待者（而不是先减后加），
     * 这样在交接瞬间 `active` 不会短暂低于上限，避免后来的协程插队破坏 FIFO。
     */
    private fun handOverLocked() {
        while (true) {
            val next = queue.removeFirstOrNull()
            if (next == null) {
                active -= 1
                return
            }
            if (next.signal.isActive) {
                next.signal.complete(Unit)
                return // active 不变：名额从自己直接转给 next
            }
            // 已取消的残留，跳过继续找下一个
        }
    }
}
