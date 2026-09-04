package com.jmwl.gostudio.ai

/**
 * AI 助手的对话消息模型。
 *
 * 既是 agent loop 内部维护的 OpenAI chat completions 消息结构（序列化发给 API），
 * 也带 UI 展示所需的元数据（时间戳、状态）。覆盖 4 种角色：
 * - system: 系统提示（agent 启动时注入，含工具描述/上下文）
 * - user: 用户输入
 * - assistant: 模型回复（含文本 + 可能的 tool_calls）
 * - tool: 工具执行结果（对应 assistant 消息里的每个 tool_call）
 */

/** 模型要求调用的工具（出现在 assistant 消息的 tool_calls 字段） */
data class ai_tool_call(
    val id: String,           // tool_call_id，工具结果消息要回填这个 id
    val name: String,         // 工具名（read/write/bash 等）
    /** 参数原始 JSON 字符串（保留原文，避免反复序列化丢精度） */
    val arguments_json: String
)

/** 工具执行状态 */
enum class ai_tool_status { PENDING, RUNNING, DONE, ERROR }

/** 单次工具执行的记录（UI 展示用，附带结果） */
data class ai_tool_execution(
    val call: ai_tool_call,
    var status: ai_tool_status = ai_tool_status.PENDING,
    var result: String = "",
    var error_message: String? = null
) {
    /** 给模型回填的 tool 消息内容（成功返回 result，失败返回错误说明） */
    fun to_result_content(): String = when (status) {
        ai_tool_status.ERROR -> "工具执行失败: ${error_message ?: "未知错误"}"
        else -> result.ifBlank { "(无输出)" }
    }
}

/** 消息角色 */
enum class ai_message_role { SYSTEM, USER, ASSISTANT, TOOL }

/**
 * 一条对话消息。
 * - system/user: 只用 [text]
 * - assistant: [text] 可空（纯工具调用时），[tool_calls] 是要求执行的工具列表，
 *   [tool_executions] 是实际执行记录（UI 用，发 API 时不带）
 * - tool: [tool_call_id] 标记是哪个工具的结果，[text] 是结果内容
 */
data class ai_message(
    val role: ai_message_role,
    var text: String = "",
    val tool_calls: List<ai_tool_call> = emptyList(),
    /** UI 专用：assistant 消息附带的工具执行记录（不发给 API） */
    val tool_executions: List<ai_tool_execution> = emptyList(),
    /** tool 消息：对应 assistant 消息里 tool_call 的 id */
    val tool_call_id: String = "",
    /** 是否正在流式接收（assistant 消息用，UI 据此显示加载态） */
    var streaming: Boolean = false,
    /** 错误标记（请求失败时，把错误信息作为 assistant 消息展示） */
    var is_error: Boolean = false,
    /** reasoning 模型的思考链内容（DeepSeek reasoning_content / Anthropic thinking block）。UI 展示用，不发给 API */
    var reasoning: String = "",
    /** 上下文压缩产生的摘要消息（UI 渲染为折叠卡片；发 API 时作为 user 角色的上下文注入） */
    val is_summary: Boolean = false,
    /** 摘要消息元数据：压缩了多少条消息、压缩前估算 token 数（仅 is_summary 时有意义） */
    val summary_origin_count: Int = 0,
    val summary_tokens_before: Long = 0,
    /** 系统通知（如"已暂停"）：仅 UI 展示，不发给 API */
    val is_system_notice: Boolean = false,
    val timestamp: Long = System.currentTimeMillis()
) {
    /** 是否有实际可见文本（空白/纯工具调用的 assistant 消息不显示气泡） */
    val has_visible_text: Boolean get() = text.isNotBlank()

    /** 估算该消息占用多少字符（上下文用量估算用：文本 + 工具调用参数） */
    fun estimated_chars(): Int = text.length + tool_calls.sumOf { it.name.length + it.arguments_json.length }
}

/**
 * 转成发给 OpenAI 兼容 API 的 JSON 对象（Gson 用 Map 序列化，避免依赖具体 POJO）。
 * 只包含 API 需要的字段，丢弃 UI 元数据（streaming/tool_executions 等）。
 */
fun ai_message.to_api_map(): Map<String, Any> {
    return when (role) {
        ai_message_role.SYSTEM -> mapOf("role" to "system", "content" to text)
        ai_message_role.USER -> mapOf("role" to "user", "content" to text)
        ai_message_role.TOOL -> mapOf(
            "role" to "tool",
            "tool_call_id" to tool_call_id,
            "content" to text
        )
        ai_message_role.ASSISTANT -> buildMap {
            put("role", "assistant")
            // OpenAI 规范：assistant 消息的 content 可空，但部分实现要求必须有，给空串更稳
            put("content", text)
            if (tool_calls.isNotEmpty()) {
                put("tool_calls", tool_calls.map { tc ->
                    mapOf(
                        "id" to tc.id,
                        "type" to "function",
                        "function" to mapOf(
                            "name" to tc.name,
                            "arguments" to tc.arguments_json
                        )
                    )
                })
            }
        }
    }
}
