package dev.piko.shared.translate

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * 一个档案下的多个 API Key 的调用池：轮着用，坏了的先跳过。
 *
 * 为什么需要它：免费额度按 Key 算，几个 Key 轮着能把总额度用满；某个 Key 过期或余额尽了，
 * 不该让整部片的翻译挂掉——标记成坏，换下一个继续，过段时间（下一次翻译任务）再试它。
 *
 * 轮转是进程内的：重启 App 从第一个 Key 重新开始，不持久化指针。一次翻译任务内，
 * 被标记为坏的 Key 一直跳过；任务结束（池跟着 translator 一起重建）自然恢复。
 *
 * 线程安全：翻译是多批次并发的，取用与标记都会并发发生，用原子游标 + 并发表。
 */
class TranslationKeyPool(keys: List<String>) {
    private val order: List<String> = keys.filter { it.isNotBlank() }.distinct()
    private val cursor = AtomicInteger(0)

    /** key → true 表示本次任务内暂时跳过。 */
    private val broken = ConcurrentHashMap<String, Boolean>()

    val size: Int get() = order.size

    /** 取下一个可用的 Key；全坏了返回 null（调用方据此放弃，别死循环）。 */
    fun nextKey(): String? {
        if (order.isEmpty()) return null
        repeat(order.size) {
            // 游标可能溢出成负数，取两次模保证下标非负
            val raw = cursor.getAndIncrement()
            val idx = ((raw % order.size) + order.size) % order.size
            val key = order[idx]
            if (!broken.containsKey(key)) return key
        }
        return null
    }

    /** 这个 Key 报了认证/余额类错误：本次任务内跳过。 */
    fun reportBroken(key: String) {
        broken[key] = true
    }

    /** 这个 Key 刚成功用过：清除跳过标记（上次的失败可能只是暂时的）。 */
    fun reportOk(key: String) {
        broken.remove(key)
    }
}
