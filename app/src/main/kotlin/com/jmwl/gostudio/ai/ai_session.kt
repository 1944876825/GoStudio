package com.jmwl.gostudio.ai

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File

/** 会话元信息（用于历史列表展示） */
data class ai_session_meta(
    val id: String,
    val title: String,
    val mtime: Long,
    val message_count: Int
)

/**
 * 会话持久化：按作用域（scope）隔离，每个会话一个 JSONL 文件，每行一条消息。
 *
 * 存储位置：`<app home>/.ai/sessions/<scope>/<session_id>.jsonl`
 * - 主界面全局问答：scope = `_global`
 * - 项目编辑器：scope = `p-<项目名>-<路径哈希>`（见 [project_scope]）
 *
 * scope 目录内另有 `.current` 标记文件记录该作用域上次激活的会话，
 * 重进项目时自动续上之前所在的会话，而不是总回到默认会话。
 * 旧版平铺在 sessions/ 根目录的会话文件在构造时一次性迁移进 scope 目录。
 */
class ai_session_store(
    private val sessions_dir: File,
    private val scope: String = GLOBAL_SCOPE,
    /** 旧版平铺会话文件名（不含扩展名），迁移进本 scope 用（项目 scope 传项目名） */
    private val legacy_ids: List<String> = emptyList()
) {
    private val gson = Gson()
    private val scope_dir = File(sessions_dir, sanitize_scope_name(scope))
    private val current_marker = File(scope_dir, CURRENT_MARKER)

    init {
        sessions_dir.mkdirs()
        scope_dir.mkdirs()
        migrate_legacy_sessions()
    }

    private fun session_file(session_id: String): File = File(scope_dir, "$session_id.jsonl")
    private fun meta_file(session_id: String): File = File(scope_dir, "$session_id.meta.json")

    /** 保存整个对话历史（覆盖写），同时更新会话元信息（标题/消息数） */
    fun save_session(session_id: String, messages: List<ai_message>) {
        val file = session_file(session_id)
        var first_user_text = ""
        var visible_count = 0
        file.bufferedWriter(Charsets.UTF_8).use { writer ->
            // 只持久化有意义的消息（跳过 streaming 中的占位、空 assistant、即时性系统通知）
            for (msg in messages) {
                if (msg.role == ai_message_role.SYSTEM) continue
                if (msg.streaming) continue
                if (msg.is_system_notice) continue
                if (msg.role == ai_message_role.ASSISTANT && !msg.has_visible_text && msg.tool_calls.isEmpty()) continue
                if (msg.role == ai_message_role.USER && first_user_text.isBlank()) {
                    first_user_text = msg.text.take(30).replace("\n", " ").trim()
                }
                visible_count++
                val obj = message_to_json(msg)
                writer.write(obj.toString())
                writer.newLine()
            }
        }
        // 更新 sidecar 元信息 + 上次激活标记（重进项目续上本会话）
        save_meta(session_id, first_user_text.ifBlank { "新对话" }, visible_count)
        set_current_session_id(session_id)
    }

    /** 保存会话元信息 sidecar（标题/消息数） */
    private fun save_meta(session_id: String, title: String, message_count: Int) {
        runCatching {
            val obj = JsonObject().apply {
                addProperty("title", title)
                addProperty("message_count", message_count)
            }
            meta_file(session_id).writeText(obj.toString())
        }
    }

    /** 加载历史会话 */
    fun load_session(session_id: String): List<ai_message> {
        val file = session_file(session_id)
        if (!file.isFile) return emptyList()
        return runCatching {
            file.bufferedReader(Charsets.UTF_8).useLines { lines ->
                lines.mapNotNull { line ->
                    runCatching { json_to_message(JsonParser.parseString(line).asJsonObject) }.getOrNull()
                }.toList()
            }
        }.getOrDefault(emptyList())
    }

    /** 列出本作用域的所有历史会话（按修改时间倒序），含标题/消息数 */
    fun list_sessions(): List<ai_session_meta> {
        return scope_dir.listFiles { f -> f.isFile && f.name.endsWith(".jsonl") }
            ?.map { file ->
                val id = file.nameWithoutExtension
                val meta = read_meta(id)
                ai_session_meta(
                    id = id,
                    title = meta?.first ?: "新对话",
                    mtime = file.lastModified(),
                    message_count = meta?.second ?: 0
                )
            }
            ?.sortedByDescending { it.mtime }
            ?: emptyList()
    }

    /** 本作用域上次激活的会话 id；标记缺失或会话文件已不存在时返回 null */
    fun read_current_session_id(): String? {
        val id = runCatching { current_marker.readText().trim() }.getOrNull() ?: return null
        if (id.isEmpty() || !File(scope_dir, "$id.jsonl").isFile) return null
        return id
    }

    /** 记录本作用域当前激活的会话（新建/切换/保存时调用） */
    fun set_current_session_id(session_id: String) {
        runCatching { current_marker.writeText(session_id) }
    }

    /**
     * 一次性迁移旧版平铺会话文件（sessions/&lt;id&gt;.jsonl）到本 scope 目录：
     * 全局 scope 收编 global.jsonl 和无法归属项目的 chat-*.jsonl；
     * 项目 scope 只收 legacy_ids 指定的文件（通常是项目名）。
     * 目标已存在时丢弃旧文件，避免覆盖。
     */
    private fun migrate_legacy_sessions() {
        runCatching {
            val flat_files = sessions_dir.listFiles { f -> f.isFile } ?: return
            val owned = mutableSetOf<String>()
            if (scope == GLOBAL_SCOPE) {
                owned.add(GLOBAL_SESSION_ID)
                for (f in flat_files) {
                    val id = legacy_id_of(f) ?: continue
                    if (id.startsWith("chat-")) owned.add(id)
                }
            } else {
                owned.addAll(legacy_ids)
            }
            for (id in owned) {
                for (name in listOf("$id.jsonl", "$id.meta.json")) {
                    val src = File(sessions_dir, name)
                    if (!src.isFile) continue
                    val dst = File(scope_dir, name)
                    if (dst.exists()) src.delete() else src.renameTo(dst)
                }
            }
        }
    }

    /** 平铺文件名 → 旧会话 id（.jsonl / .meta.json 两种） */
    private fun legacy_id_of(f: File): String? = when {
        f.name.endsWith(".jsonl") -> f.name.removeSuffix(".jsonl")
        f.name.endsWith(".meta.json") -> f.name.removeSuffix(".meta.json")
        else -> null
    }

    /** 读取 sidecar 元信息，返回 (title, message_count) */
    private fun read_meta(session_id: String): Pair<String, Int>? {
        val file = meta_file(session_id)
        if (!file.isFile) return null
        return runCatching {
            val obj = JsonParser.parseString(file.readText()).asJsonObject
            val title = obj.get("title")?.takeIf { !it.isJsonNull }?.asString ?: "新对话"
            val count = obj.get("message_count")?.takeIf { !it.isJsonNull }?.asInt ?: 0
            title to count
        }.getOrNull()
    }

    /** 重命名会话（只改 sidecar 标题） */
    fun rename_session(session_id: String, new_title: String) {
        val existing = read_meta(session_id) ?: ("新对话" to 0)
        save_meta(session_id, new_title.take(60), existing.second)
    }

    /** 删除某会话（含 sidecar） */
    fun delete_session(session_id: String) {
        session_file(session_id).delete()
        meta_file(session_id).delete()
    }

    companion object {
        /** 主界面全局问答的 scope */
        const val GLOBAL_SCOPE = "_global"
        /** 全局问答的会话 id（旧版平铺文件名，迁移用） */
        private const val GLOBAL_SESSION_ID = "global"
        /** scope 目录内记录上次激活会话的标记文件名 */
        private const val CURRENT_MARKER = ".current"

        /**
         * 项目 scope：目录名 + 路径哈希。项目统一放在 projects/ 根下时目录名
         * 天然唯一，但导入项目可在任意路径——加路径哈希保证同名不串会话。
         */
        fun project_scope(project_dir: File): String {
            val name = sanitize_scope_name(project_dir.name)
            val path = runCatching { project_dir.canonicalPath }.getOrDefault(project_dir.absolutePath)
            val hash = Integer.toHexString(path.hashCode())
            return "p-$name-$hash"
        }

        /** scope 目录名安全化：只留字母数字下划线连字符，截断 48 字符 */
        private fun sanitize_scope_name(name: String): String =
            name.replace(Regex("[^A-Za-z0-9_-]"), "_").take(48).ifBlank { "s" }
    }

    private fun message_to_json(msg: ai_message): JsonObject {
        val obj = JsonObject()
        obj.addProperty("role", msg.role.name)
        obj.addProperty("text", msg.text)
        if (msg.tool_calls.isNotEmpty()) {
            obj.add("tool_calls", gson.toJsonTree(msg.tool_calls.map { tc ->
                mapOf("id" to tc.id, "name" to tc.name, "arguments_json" to tc.arguments_json)
            }))
        }
        // 工具执行记录（UI 卡片 + 压缩转录用）：结果截断防会话文件膨胀
        if (msg.tool_executions.isNotEmpty()) {
            obj.add("tool_executions", gson.toJsonTree(msg.tool_executions.map { e ->
                mapOf<String, Any?>(
                    "id" to e.call.id,
                    "name" to e.call.name,
                    "arguments_json" to e.call.arguments_json,
                    "status" to e.status.name,
                    "result" to e.result.take(10_000),
                    "error_message" to e.error_message
                )
            }))
        }
        if (msg.tool_call_id.isNotEmpty()) obj.addProperty("tool_call_id", msg.tool_call_id)
        if (msg.is_error) obj.addProperty("is_error", true)
        if (msg.reasoning.isNotBlank()) obj.addProperty("reasoning", msg.reasoning)
        if (msg.is_summary) {
            obj.addProperty("is_summary", true)
            obj.addProperty("summary_origin_count", msg.summary_origin_count)
            obj.addProperty("summary_tokens_before", msg.summary_tokens_before)
        }
        return obj
    }

    private fun json_to_message(obj: JsonObject): ai_message {
        val role = runCatching { ai_message_role.valueOf(obj.get("role").asString) }.getOrDefault(ai_message_role.USER)
        val text = obj.get("text")?.takeIf { !it.isJsonNull }?.asString ?: ""
        val tool_calls = obj.getAsJsonArray("tool_calls")?.map { tc ->
            val tcObj = tc.asJsonObject
            ai_tool_call(
                id = tcObj.get("id").asString,
                name = tcObj.get("name").asString,
                arguments_json = tcObj.get("arguments_json").asString
            )
        } ?: emptyList()
        val tool_executions = obj.getAsJsonArray("tool_executions")?.mapNotNull { el ->
            // 单条损坏不能拖垮整个会话加载
            runCatching {
                val e = el.asJsonObject
                ai_tool_execution(
                    call = ai_tool_call(
                        id = e.get("id").asString,
                        name = e.get("name").asString,
                        arguments_json = e.get("arguments_json")?.takeIf { !it.isJsonNull }?.asString ?: ""
                    ),
                    status = ai_tool_status.valueOf(e.get("status").asString),
                    result = e.get("result")?.takeIf { !it.isJsonNull }?.asString ?: "",
                    error_message = e.get("error_message")?.takeIf { !it.isJsonNull }?.asString
                )
            }.getOrNull()
        } ?: emptyList()
        val tool_call_id = obj.get("tool_call_id")?.takeIf { !it.isJsonNull }?.asString ?: ""
        val is_error = obj.get("is_error")?.takeIf { !it.isJsonNull }?.asBoolean ?: false
        val reasoning = obj.get("reasoning")?.takeIf { !it.isJsonNull }?.asString ?: ""
        val is_summary = obj.get("is_summary")?.takeIf { !it.isJsonNull }?.asBoolean ?: false
        val summary_origin_count = obj.get("summary_origin_count")?.takeIf { !it.isJsonNull }?.asInt ?: 0
        val summary_tokens_before = obj.get("summary_tokens_before")?.takeIf { !it.isJsonNull }?.asLong ?: 0L
        return ai_message(
            role = role, text = text, tool_calls = tool_calls,
            tool_executions = tool_executions,
            tool_call_id = tool_call_id, is_error = is_error, reasoning = reasoning,
            is_summary = is_summary,
            summary_origin_count = summary_origin_count,
            summary_tokens_before = summary_tokens_before
        )
    }
}

/**
 * 上下文压缩（Compaction，机制参考 pi coding-agent 的 compaction.ts）。
 *
 * 触发：估算的上下文用量超过 `上限 - 预留量`（should_compact）。
 * 切点：从最新消息往回累计，保留约 keep_recent_chars 的近期消息；
 *       切点只落在 user/assistant 边界，绝不在 TOOL 结果上切
 *       （工具结果必须紧跟其 assistant 的 tool_calls）。
 * 摘要：优先调模型生成结构化 checkpoint（目标/约束/进展/决策/后续）；
 *       已有旧摘要时走「增量合并」prompt；模型失败退化启发式摘要。
 * 回写：压缩结果由 agent loop 写回 messages 并持久化，摘要消息带
 *       is_summary 标记，UI 渲染为折叠卡片。
 */
object ai_compaction {
    /** 模型摘要的 system 提示（结构化 checkpoint，参考 pi SUMMARIZATION_PROMPT） */
    private const val summary_system = "你是对话压缩助手。把提供的对话历史压缩成一份结构化的上下文检查点（checkpoint），" +
        "供另一个 LLM 接续完成工作。严格使用以下格式输出：\n\n" +
        "## 目标\n[用户想完成什么？会话覆盖多个任务时逐条列出]\n\n" +
        "## 约束与偏好\n- [用户提到的任何约束、偏好或要求；没有则写「（无）」]\n\n" +
        "## 进展\n### 已完成\n- [x] [已完成的任务/改动]\n\n### 进行中\n- [ ] [当前正在做的事]\n\n" +
        "### 受阻\n- [阻碍进度的问题；没有则写「（无）」]\n\n" +
        "## 关键决策\n- **[决策]**：[简要理由]\n\n" +
        "## 后续步骤\n1. [按顺序列出接下来该做什么]\n\n" +
        "## 关键上下文\n- [接续工作所需的数据、示例或引用；不适用则写「（无）」]\n\n" +
        "每节保持简洁。必须保留准确的文件路径、函数名和错误信息原文。只输出摘要正文。"

    /** 已有旧摘要时，把新消息合并进去的追加指令（参考 pi UPDATE_SUMMARIZATION_INSTRUCTIONS） */
    private const val update_instructions = "上面提供的是新产生的对话消息，请把它们合并进 <previous-summary> 标签里的现有摘要。规则：\n" +
        "- 保留现有摘要的全部信息\n" +
        "- 从新消息中补充新的进展、决策和上下文\n" +
        "- 更新「进展」：已完成的事项从「进行中」移到「已完成」\n" +
        "- 根据实际完成情况更新「后续步骤」\n" +
        "- 必须保留准确的文件路径、函数名和错误信息原文\n" +
        "- 已不再相关的内容可以删除\n" +
        "仍按原格式输出完整摘要。"

    /** 压缩结果：一条摘要消息 + 回写所需的元数据 */
    data class result(
        val summary_message: ai_message,
        /** 被压缩掉的消息条数（写回 messages 时用） */
        val compacted_count: Int,
        /** 压缩前被压缩部分的估算 token 数 */
        val tokens_before: Long
    )

    /**
     * 是否应触发压缩（参考 pi shouldCompact：用量 > 上限 - 预留）。
     * @param used_chars 当前估算字符量（含 system prompt）
     * @param max_chars 上下文字符上限（effective_context_chars）
     * @param threshold_percent 触发阈值百分比（0-100），默认 80：即预留 20% 余量
     */
    fun should_compact(used_chars: Long, max_chars: Int, threshold_percent: Int = 80): Boolean {
        if (max_chars <= 0) return false
        val threshold = (threshold_percent.coerceIn(5, 100).toLong() * max_chars) / 100
        return used_chars > threshold
    }

    /**
     * 从后往前找切点：保留约 [keep_recent_chars] 字符的近期消息。
     * 切点只落在 user / 摘要消息上——保留区从 user 边界开始，
     * 丢弃区里 assistant(tool_calls) 与其 TOOL 结果永远整组同进退，
     * 且压缩后请求的首条对话消息是摘要（user 角色），满足 Anthropic 的交替要求。
     * @return 切点索引（messages[0..cut) 将被压缩）；历史太短不足以切时返回 -1
     */
    fun find_cut_point(messages: List<ai_message>, keep_recent_chars: Int): Int {
        if (messages.size < 2) return -1
        var accumulated = 0L
        var cut = -1
        for (i in messages.indices.reversed()) {
            val msg = messages[i]
            accumulated += msg.estimated_chars()
            // 只有 user / 摘要消息是合法切点（保证保留区从完整回合开始）
            if (!(msg.role == ai_message_role.USER || msg.is_summary)) continue
            if (accumulated >= keep_recent_chars) {
                cut = i
                break
            }
        }
        return cut
    }

    /**
     * 执行压缩：生成摘要消息（不修改调用方数据，回写由 agent loop 负责）。
     * @param messages 完整历史（不含 system）
     * @param client 可选 AI 客户端（非空走模型摘要，失败/空走启发式兜底）
     * @param previous_summary 已有的摘要文本（增量合并模式），null 则首次压缩
     * @return null = 无需压缩（历史太短或切不开）
     */
    suspend fun compact(
        messages: List<ai_message>,
        keep_recent_chars: Int,
        client: ai_client? = null,
        previous_summary: String? = null
    ): result? {
        if (messages.size < 2) return null
        val cut = find_cut_point(messages, keep_recent_chars)
        if (cut <= 0) return null
        val to_compact = messages.subList(0, cut)
        val tokens_before = to_compact.sumOf { it.estimated_chars().toLong() } / 4

        // 优先模型摘要；失败退化启发式
        val summary_text = if (client != null) {
            runCatching { summarize_with_model(client, to_compact, previous_summary) }
                .onFailure { /* 静默退化 */ }
                .getOrNull()
        } else null
        val text = summary_text ?: build_summary(to_compact)
        return result(
            summary_message = ai_message(
                role = ai_message_role.USER,
                text = text,
                is_summary = true,
                summary_origin_count = to_compact.size,
                summary_tokens_before = tokens_before
            ),
            compacted_count = to_compact.size,
            tokens_before = tokens_before
        )
    }

    /**
     * 调模型把旧消息摘要（或合并进旧摘要）。
     * 用一次性收集的 callback 同步收集完整回复。
     */
    private suspend fun summarize_with_model(client: ai_client, messages: List<ai_message>, previous_summary: String?): String {
        val transcript = build_transcript(messages)
        val user_prompt = buildString {
            append("对话历史：\n\n")
            append(transcript)
            if (!previous_summary.isNullOrBlank()) {
                append("\n\n<previous-summary>\n").append(previous_summary).append("\n</previous-summary>\n\n")
                append(update_instructions)
            } else {
                append("\n\n请根据上面的对话历史生成结构化摘要。")
            }
        }
        val request = listOf(
            ai_message(role = ai_message_role.SYSTEM, text = summary_system),
            ai_message(role = ai_message_role.USER, text = user_prompt)
        )
        val acc = StringBuilder()
        val done = java.util.concurrent.CountDownLatch(1)
        var error: String? = null
        client.stream_chat(request, emptyList(), object : ai_stream_callback {
            override fun on_text(delta: String) { acc.append(delta) }
            override fun on_done(tool_calls: List<ai_tool_call>) { done.countDown() }
            override fun on_error(message: String) { error = message; done.countDown() }
        })
        done.await()
        if (error != null) throw RuntimeException(error)
        val text = acc.toString().trim()
        if (text.isBlank()) throw RuntimeException("空摘要")
        return text
    }

    /** 把旧消息渲染成供模型摘要的纯文本流水 */
    private fun build_transcript(messages: List<ai_message>): String {
        val sb = StringBuilder()
        for (msg in messages) {
            when (msg.role) {
                ai_message_role.USER -> {
                    sb.appendLine("用户：${msg.text.take(1200)}")
                }
                ai_message_role.ASSISTANT -> {
                    if (msg.has_visible_text) sb.appendLine("助手：${msg.text.take(1200)}")
                    for (tc in msg.tool_calls) {
                        val result = msg.tool_executions.firstOrNull { it.call.id == tc.id }
                        sb.appendLine("  调用工具 ${tc.name}(${tc.arguments_json.take(120)}) → ${result?.to_result_content()?.take(300) ?: "(未记录)"}")
                    }
                }
                ai_message_role.TOOL -> { /* 工具结果已在 assistant 里 */ }
                else -> {}
            }
        }
        return sb.toString()
    }

    /** 把一批旧消息压缩成结构化摘要（启发式兜底，不调模型） */
    private fun build_summary(messages: List<ai_message>): String {
        val sb = StringBuilder()
        sb.appendLine("（以下是之前对话的自动摘要，原始消息已压缩以节省上下文）")
        for (msg in messages) {
            when (msg.role) {
                ai_message_role.USER -> {
                    sb.appendLine("[用户] ${msg.text.take(300)}")
                }
                ai_message_role.ASSISTANT -> {
                    if (msg.has_visible_text) sb.appendLine("[助手] ${msg.text.take(400)}")
                    for (tc in msg.tool_calls) {
                        val result = msg.tool_executions.firstOrNull { it.call.id == tc.id }
                        sb.appendLine("  └ 调用工具 ${tc.name}(${tc.arguments_json.take(80)}) → ${result?.to_result_content()?.take(150) ?: "(未记录)"}")
                    }
                }
                ai_message_role.TOOL -> {
                    // 工具结果已在 assistant 消息的 tool_executions 里摘要，跳过
                }
                else -> {}
            }
        }
        return sb.toString().trimEnd()
    }
}
