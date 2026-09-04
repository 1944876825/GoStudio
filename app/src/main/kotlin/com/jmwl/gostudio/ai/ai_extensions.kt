package com.jmwl.gostudio.ai

import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * 文件变更通知器。
 *
 * AI 的 write/edit/bash 工具改了项目文件后，通过这里通知编辑器刷新对应文件，
 * 让用户立即看到改动（无需手动重开）。
 *
 * activity 注册一个监听器 [on_files_changed]，工具执行后调 [notify_changed]。
 */
class ai_file_change_notifier {
    @Volatile
    private var listener: ((List<String>) -> Unit)? = null

    /** 注册文件变更监听器（在主线程调用） */
    fun set_listener(cb: (List<String>) -> Unit) {
        listener = cb
    }

    /** 通知某些文件被改动了（工具执行后调用） */
    fun notify_changed(paths: List<String>) {
        if (paths.isEmpty()) return
        listener?.invoke(paths)
    }

    fun notify_changed(path: String) = notify_changed(listOf(path))
}

/**
 * Steering 消息队列（参考 pi）。
 *
 * agent 运行中用户输入的新消息会排队，在当前 agent 轮次结束后插入对话，
 * 不打断正在进行的工具调用。
 */
class ai_steering_queue {
    private val queue = ConcurrentLinkedQueue<String>()

    /** 用户在运行中排队一条消息 */
    fun enqueue(text: String) { queue.add(text) }

    /** 取出所有排队的消息（轮次结束时调用） */
    fun drain(): List<String> {
        val result = mutableListOf<String>()
        while (true) {
            val text = queue.poll() ?: break
            result.add(text)
        }
        return result
    }

    /** 是否有排队消息 */
    fun has_pending(): Boolean = !queue.isEmpty()

    /** 排队消息条数（UI「已排队 N 条」反馈） */
    fun size(): Int = queue.size

    fun clear() = queue.clear()
}
