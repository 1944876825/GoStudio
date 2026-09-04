package com.jmwl.gostudio.ai

import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.mutableStateListOf
import com.jmwl.gostudio.ai.tools.ai_tool_registry
import com.jmwl.gostudio.ai.tools.execute_safely
import com.jmwl.gostudio.ai.tools.string_or
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

/**
 * AI Agent 会话状态 + 调度循环（接入 skill/@引用/AGENTS.md/MCP/持久化/压缩/steering/暂停 全部能力）。
 *
 * 新增能力（均通过构造函数可选注入，不传则降级为第一阶段行为）：
 * - [input_processor]：@文件引用、/命令模板、/skill 激活
 * - [session_store] + [session_id]：会话持久化（JSONL）
 * - [skill_manager]：skill 索引注入 system prompt
 * - [mcp_manager]：MCP 工具服务器（start/stop 生命周期）
 * - [file_change_notifier]：write/edit 改文件后通知编辑器刷新
 * - [steering_queue]：运行中排队新消息
 *
 * 暂停/恢复（参考 pi 的 turn 边界机制）：
 * [pause] 后 agent 不会打断正在进行的流式回复或工具调用，而是在
 * 「当前工具批次结束、下一轮请求前」的边界停下；排队的 steering 消息保留，
 * [resume] 时注入并继续循环。
 *
 * 自动上下文压缩（参考 pi 的 threshold compaction）：
 * 每次发送/每轮请求前估算上下文用量，超过阈值（[ai_settings_state.compact_threshold_percent]）
 * 时把旧消息摘要成一条 checkpoint 消息并回写 [messages]（UI 显示为摘要卡片，持久化）。
 */
class ai_agent_loop(
    private val settings_provider: () -> ai_settings_state,
    private val env_provider: () -> ai_environment_context,
    private val tool_registry: ai_tool_registry,
    private val scope_launcher: (suspend () -> Unit) -> Job,
    private val input_processor: ai_input_processor? = null,
    private val session_store: ai_session_store? = null,
    private var session_id: String = "default",
    private val skill_manager: com.jmwl.gostudio.ai.skills.ai_skill_manager? = null,
    private val mcp_manager: com.jmwl.gostudio.ai.mcp.ai_mcp_manager? = null,
    private val file_change_notifier: ai_file_change_notifier? = null,
    private val steering_queue: ai_steering_queue? = null
) {
    val messages = mutableStateListOf<ai_message>()

    private val _is_running = MutableStateFlow(false)
    val is_running: StateFlow<Boolean> = _is_running

    /** 暂停中（当前步骤完成后停住，等待 resume 或新消息） */
    private val _is_paused = MutableStateFlow(false)
    val is_paused: StateFlow<Boolean> = _is_paused

    /** 正在执行上下文压缩（UI 显示压缩指示器） */
    private val _compaction_running = MutableStateFlow(false)
    val compaction_running: StateFlow<Boolean> = _compaction_running

    /** 估算的上下文用量（0..1+，相对 effective_context_chars；UI 用量徽标） */
    private val _context_usage = MutableStateFlow(0f)
    val context_usage: StateFlow<Float> = _context_usage

    /** 运行中排队的 steering 消息数（UI 反馈「已排队 N 条」） */
    private val _queued_count = MutableStateFlow(0)
    val queued_count: StateFlow<Int> = _queued_count

    /** MCP server 连接数（UI 可展示） */
    private val _mcp_server_count = MutableStateFlow(0)
    val mcp_server_count: StateFlow<Int> = _mcp_server_count

    private var current_job: Job? = null
    private var cancelled = false
    @Volatile private var paused = false
    private val main_handler = Handler(Looper.getMainLooper())

    /** 最近一次构建的 system prompt 长度（上下文用量估算用，避免每次重读 AGENTS.md） */
    @Volatile private var last_system_prompt_len = 0

    /** 是否已初始化（启动时拉起 MCP、加载 skill、恢复会话） */
    private var initialized = false

    private fun on_main(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) action()
        else main_handler.post { action() }
    }

    /** 初始化：启动 MCP、发现 skill、恢复历史会话。在 IO 线程跑一次。 */
    suspend fun initialize() {
        if (initialized) return
        initialized = true
        withContext(Dispatchers.IO) {
            // 恢复历史会话
            session_store?.let {
                val history = it.load_session(session_id)
                if (history.isNotEmpty()) on_main { messages.addAll(history) }
            }
            // 发现 skill
            skill_manager?.discover()
            // 启动 MCP server
            mcp_manager?.let {
                val count = it.start(tool_registry)
                _mcp_server_count.value = count
            }
            on_main { refresh_context_usage() }
        }
    }

    fun send_user_message(text: String) {
        if (_is_running.value) {
            // 运行中：排队为 steering 消息
            steering_queue?.enqueue(text)
            _queued_count.value = steering_queue?.size() ?: 0
            return
        }
        val settings = settings_provider()
        if (!settings.is_configured()) {
            on_main {
                messages.add(ai_message(
                    role = ai_message_role.ASSISTANT,
                    text = "⚠️ AI 还没配置。请点击右上角 ⚙️ 设置，填写 API 提供商和密钥。",
                    is_error = true
                ))
            }
            return
        }
        // 暂停中收到新消息：注入排队消息 + 本条，解除暂停继续跑
        if (_is_paused.value) {
            paused = false
            _is_paused.value = false
            val queued = steering_queue?.drain() ?: emptyList()
            _queued_count.value = 0
            for (q in queued) {
                val processed_q = input_processor?.process(q) ?: q
                on_main { messages.add(ai_message(role = ai_message_role.USER, text = processed_q)) }
            }
        }
        // 过 input_processor（@引用、/命令、/skill）
        val processed = input_processor?.process(text) ?: text
        on_main {
            messages.add(ai_message(role = ai_message_role.USER, text = processed))
            refresh_context_usage()
        }
        start_loop()
    }

    /**
     * 暂停：不打断当前流式回复/工具调用，在其完成后的边界停住。
     * 排队的 steering 消息保留，resume 时注入。
     */
    fun pause() {
        if (!_is_running.value) return
        paused = true
        _is_paused.value = true
    }

    /**
     * 恢复：loop 还在跑则下个边界自然继续；已停在暂停点则注入排队消息并重启循环。
     */
    fun resume() {
        if (!_is_paused.value) return
        paused = false
        _is_paused.value = false
        if (_is_running.value) return
        val queued = steering_queue?.drain() ?: emptyList()
        _queued_count.value = 0
        for (q in queued) {
            val processed = input_processor?.process(q) ?: q
            on_main { messages.add(ai_message(role = ai_message_role.USER, text = processed)) }
        }
        val last = messages.lastOrNull()
        val can_continue = queued.isNotEmpty() ||
            last?.role == ai_message_role.TOOL ||
            (last?.role == ai_message_role.ASSISTANT && last.tool_calls.isNotEmpty())
        if (can_continue) start_loop()
    }

    fun cancel() {
        cancelled = true
        paused = false
        _is_paused.value = false
        _queued_count.value = 0
        current_job?.cancel()
        on_main {
            _is_running.value = false
            messages.lastOrNull { it.streaming }?.let { it.streaming = false }
        }
    }

    fun clear_messages() {
        if (_is_running.value) cancel()
        steering_queue?.clear()
        _queued_count.value = 0
        on_main {
            messages.clear()
            refresh_context_usage()
        }
        session_store?.delete_session(session_id)
    }

    /** 当前会话 id */
    fun current_session_id(): String = session_id

    /** 列出所有历史会话（UI 展示用） */
    fun list_sessions(): List<ai_session_meta> = session_store?.list_sessions() ?: emptyList()

    /** 切换到指定会话：清内存 → 加载该会话历史 → 重设 session_id */
    suspend fun switch_session(new_id: String) {
        if (_is_running.value) cancel()
        paused = false
        _is_paused.value = false
        steering_queue?.clear()
        _queued_count.value = 0
        session_id = new_id
        val history = session_store?.load_session(new_id) ?: emptyList()
        on_main {
            messages.clear()
            messages.addAll(history)
            refresh_context_usage()
        }
    }

    /** 新建空会话：生成时间戳 id，清内存 */
    fun new_session(): String {
        if (_is_running.value) cancel()
        paused = false
        _is_paused.value = false
        steering_queue?.clear()
        _queued_count.value = 0
        val new_id = "chat-" + System.currentTimeMillis()
        session_id = new_id
        on_main {
            messages.clear()
            refresh_context_usage()
        }
        return new_id
    }

    /** 重命名当前会话（只改 sidecar 标题） */
    fun rename_session(new_title: String) {
        session_store?.rename_session(session_id, new_title)
    }

    /** 删除指定会话（不切换当前） */
    fun delete_session_by_id(id: String) {
        session_store?.delete_session(id)
    }

    /**
     * 重新生成最后一条 assistant 回复：
     * 找到最后一条 assistant 消息及其后的所有 TOOL 消息，一并移除，然后用上一条 user 消息重跑。
     */
    fun regenerate_last() {
        if (_is_running.value) return
        if (messages.isEmpty()) return
        on_main {
            // 从后往前找最后一条 assistant
            var lastAssistantIdx = -1
            for (i in messages.indices.reversed()) {
                if (messages[i].role == ai_message_role.ASSISTANT) {
                    lastAssistantIdx = i
                    break
                }
            }
            if (lastAssistantIdx < 0) return@on_main
            // 删除该 assistant 及其之后的所有消息
            while (messages.size > lastAssistantIdx) messages.removeAt(messages.size - 1)
            // 上一条 user 必须存在才能重跑
            val hasUser = messages.any { it.role == ai_message_role.USER }
            if (!hasUser) return@on_main
        }
        persist_session_via_scope()
        start_loop()
    }

    /**
     * 删除指定索引的单条消息。若删的是 assistant，连带其后属于它的 TOOL 结果消息。
     */
    fun delete_message(index: Int) {
        if (_is_running.value) return
        if (index !in messages.indices) return
        on_main {
            val msg = messages[index]
            messages.removeAt(index)
            // 若删的是 assistant，移除紧随其后的 TOOL 消息（直到下一条 user/assistant）
            if (msg.role == ai_message_role.ASSISTANT) {
                while (messages.size > index && messages[index].role == ai_message_role.TOOL) {
                    messages.removeAt(index)
                }
            }
            // 若删的是 user，其后紧随的 assistant+tool 一并删（避免悬空）
            if (msg.role == ai_message_role.USER) {
                while (messages.size > index && messages[index].role != ai_message_role.USER) {
                    messages.removeAt(index)
                }
            }
            refresh_context_usage()
        }
        persist_session_via_scope()
    }

    /**
     * 编辑某条 user 消息并重发：改写文本，删除其后所有消息，重新跑 loop。
     */
    fun edit_and_resend_user(index: Int, new_text: String) {
        if (_is_running.value) return
        if (index !in messages.indices) return
        val trimmed = new_text.trim()
        if (trimmed.isEmpty()) return
        val processed = input_processor?.process(trimmed) ?: trimmed
        on_main {
            if (messages[index].role != ai_message_role.USER) return@on_main
            messages[index] = messages[index].copy(text = processed)
            // 删除其后所有消息
            while (messages.size > index + 1) messages.removeAt(messages.size - 1)
            refresh_context_usage()
        }
        persist_session_via_scope()
        start_loop()
    }

    /** 手动立即压缩当前上下文（设置/徽标菜单入口） */
    fun compact_now() {
        if (_is_running.value || _compaction_running.value) return
        val settings = settings_provider()
        if (!settings.is_configured()) return
        scope_launcher {
            perform_compaction(settings, force = true)
            on_main { refresh_context_usage() }
        }
    }

    private fun persist_session_via_scope() {
        scope_launcher {
            persist_session()
        }
    }

    /** 退出时清理 MCP server */
    fun shutdown() {
        mcp_manager?.stop()
    }

    private fun start_loop() {
        cancelled = false
        // 每次用户发送/steering 续跑重新计轮次（与旧版行为一致，防单轮失控）
        iteration_guard = 0
        _is_running.value = true
        current_job = scope_launcher { run_agent_loop() }
    }

    private suspend fun run_agent_loop() = withContext(Dispatchers.IO) {
        try {
        var settings = settings_provider()
        val env = env_provider()

        while (true) {
            // 每轮重读设置：会话内切换模型即时生效（下一轮请求用新模型）
            settings = settings_provider()
            if (iteration_guard >= settings.max_agent_iterations || cancelled || paused) break
            iteration_guard++

            val enabled_tool_names = if (settings.enable_tools) tool_registry.all().map { it.name } else emptyList()
            val system_prompt = build_full_system_prompt(env, enabled_tool_names, settings)
            last_system_prompt_len = system_prompt.length

            // 自动上下文压缩：发送前/每轮请求前检查阈值（长工具输出可能中途超限）
            if (settings.auto_compact) {
                perform_compaction(settings, force = false)
            } else {
                hard_trim_history(settings)
            }
            on_main { refresh_context_usage() }

            val history_snapshot = on_main_and_wait { messages.toList() }
            val request_messages = buildList {
                add(ai_message(role = ai_message_role.SYSTEM, text = system_prompt))
                addAll(history_snapshot.filter {
                    it.role != ai_message_role.SYSTEM && !it.is_error && !it.is_system_notice
                })
            }
            val final_messages = request_messages
            val client = ai_client(settings)
            val tools_api = if (settings.enable_tools) tool_registry.to_api_tools() else emptyList()

            val assistant_msg = ai_message(role = ai_message_role.ASSISTANT, streaming = true)
            on_main { messages.add(assistant_msg) }
            val msg_index_holder = intArrayOf(-1)
            on_main { msg_index_holder[0] = messages.size - 1 }

            val collected_tool_calls = mutableListOf<ai_tool_call>()
            var had_error = false

            client.stream_chat(final_messages, tools_api, object : ai_stream_callback {
                override fun on_text(delta: String) {
                    assistant_msg.text += delta
                    val idx = msg_index_holder[0]
                    // 用 copy() 创建新实例触发 Compose 更新（同引用 set 不会重组）
                    on_main { if (idx in messages.indices) messages[idx] = assistant_msg.copy() }
                }
                override fun on_reasoning(delta: String) {
                    // reasoning 模型的思考链增量（UI 展示用，不发给 API）
                    assistant_msg.reasoning += delta
                    val idx = msg_index_holder[0]
                    on_main { if (idx in messages.indices) messages[idx] = assistant_msg.copy() }
                }
                override fun on_done(tool_calls: List<ai_tool_call>) {
                    collected_tool_calls.addAll(tool_calls)
                }
                override fun on_error(message: String) {
                    assistant_msg.text = "⚠️ $message"
                    assistant_msg.is_error = true
                    had_error = true
                    val idx = msg_index_holder[0]
                    on_main { if (idx in messages.indices) messages[idx] = assistant_msg.copy() }
                }
            })

            assistant_msg.streaming = false
            on_main { msg_index_holder[0].let { idx -> if (idx in messages.indices) messages[idx] = assistant_msg.copy() } }

            if (had_error || cancelled) break
            if (collected_tool_calls.isEmpty()) break

            // 固化 tool_calls
            val execs = collected_tool_calls.map { ai_tool_execution(call = it) }
            on_main {
                val idx = msg_index_holder[0]
                if (idx in messages.indices) {
                    messages[idx] = messages[idx].copy(tool_calls = collected_tool_calls, tool_executions = execs)
                }
            }

            val changed_files = mutableListOf<String>()

            for (exec in execs) {
                // 暂停/取消：不启动下一个工具（已完成的保留）
                if (cancelled || paused) break
                exec.status = ai_tool_status.RUNNING
                on_main { msg_index_holder[0].let { idx -> if (idx in messages.indices) messages[idx] = update_execution(messages[idx], exec) } }

                val tool = tool_registry.get(exec.call.name)
                val params = JsonParser.parseString(exec.call.arguments_json).asJsonObject
                // 按设置开关过滤 write/bash
                val blocked = when (exec.call.name) {
                    "write" -> !settings.enable_write
                    "bash" -> !settings.enable_bash
                    else -> false
                }
                exec.result = if (blocked) {
                    exec.error_message = "该工具已被设置禁用"
                    "该工具已被设置禁用（请在 AI 设置里开启）"
                } else if (tool != null) {
                    val timeout = if (exec.call.name == "bash") 60_000L else 15_000L
                    tool.execute_safely(params, timeout)
                } else {
                    exec.error_message = "未知工具: ${exec.call.name}"
                    "未知工具: ${exec.call.name}"
                }
                exec.status = if (exec.error_message != null) ai_tool_status.ERROR else ai_tool_status.DONE

                // write/edit 改了文件，记录路径通知编辑器刷新
                if (exec.call.name in listOf("write", "edit") && exec.error_message == null) {
                    params.string_or("path").takeIf { it.isNotBlank() }?.let { changed_files.add(it) }
                }

                on_main { msg_index_holder[0].let { idx -> if (idx in messages.indices) messages[idx] = update_execution(messages[idx], exec) } }
                on_main {
                    messages.add(ai_message(
                        role = ai_message_role.TOOL,
                        text = exec.to_result_content(),
                        tool_call_id = exec.call.id
                    ))
                }
            }

            // 通知编辑器刷新被改的文件
            if (changed_files.isNotEmpty()) {
                file_change_notifier?.notify_changed(changed_files)
            }
            // 持久化会话
            persist_session()

            // 暂停边界：当前工具批次结束、下一轮请求前停住（保留 steering 队列）
            if (paused && !cancelled) {
                on_main {
                    messages.add(ai_message(
                        role = ai_message_role.ASSISTANT,
                        text = "⏸ 已暂停。当前步骤已完成，排队的消息已保留。输入新消息或点击「继续」恢复。",
                        is_system_notice = true
                    ))
                    _is_running.value = false
                }
                return@withContext
            }
        }

        // 处理 steering 队列：有排队消息则作为新 user 消息继续（暂停时保留队列不处理）
        val steering = if (paused) emptyList() else (steering_queue?.drain() ?: emptyList())
        if (!paused) _queued_count.value = 0
        on_main { _is_running.value = false }
        if (steering.isNotEmpty() && !cancelled) {
            for (msg in steering) {
                val processed = input_processor?.process(msg) ?: msg
                on_main { messages.add(ai_message(role = ai_message_role.USER, text = processed)) }
            }
            start_loop()
            return@withContext
        }

        if (iteration_guard >= settings.max_agent_iterations && !cancelled) {
            on_main {
                messages.add(ai_message(
                    role = ai_message_role.ASSISTANT,
                    text = "（已达到最大轮次 ${settings.max_agent_iterations}，停止以避免失控。如需继续请重新描述需求。）"
                ))
            }
        }
        persist_session()
        on_main { refresh_context_usage() }
        } catch (e: Throwable) {
            // 捕获 loop 内任何异常，显示到对话里（避免静默失败）
            val err_text = "⚠️ agent loop 异常: ${e.javaClass.simpleName}: ${e.message ?: ""}"
            on_main {
                messages.add(ai_message(role = ai_message_role.ASSISTANT, text = err_text, is_error = true))
                _is_running.value = false
            }
        } finally {
            on_main {
                _is_running.value = false
                refresh_context_usage()
            }
        }
    }

    /** 循环轮次计数（每次发送/续跑由 start_loop 重置，防单次任务失控） */
    private var iteration_guard = 0

    /**
     * 自动/手动压缩：阈值触发（或 force）时把旧消息摘要成 checkpoint 并回写 messages。
     * 回写后摘要消息带 is_summary 标记（UI 折叠卡片），并立即持久化。
     * @return 是否执行了压缩
     */
    private suspend fun perform_compaction(settings: ai_settings_state, force: Boolean): Boolean {
        val limit_chars = settings.effective_context_chars()
        val used_chars = on_main_and_wait {
            last_system_prompt_len + messages.sumOf { it.estimated_chars().toLong() }
        }
        if (!force && !ai_compaction.should_compact(used_chars, limit_chars, settings.compact_threshold_percent)) {
            return false
        }
        // 近期保留量：上限的 20%，至少 8000 字符（参考 pi keepRecentTokens=20K/200K 的比例）
        val keep_recent = (limit_chars * 0.2f).toInt().coerceAtLeast(8_000)
        // 已有摘要 → 增量合并模式
        val prev_summary = on_main_and_wait { messages.firstOrNull { it.is_summary }?.text }

        _compaction_running.value = true
        try {
            val snapshot = on_main_and_wait { messages.toList() }
            val result = ai_compaction.compact(snapshot, keep_recent, ai_client(settings), prev_summary)
            if (result == null) return false
            // 回写：[新摘要] + 保留的近期消息
            on_main {
                val kept = messages.drop(result.compacted_count)
                messages.clear()
                messages.add(result.summary_message)
                messages.addAll(kept)
            }
            persist_session()
            return true
        } finally {
            _compaction_running.value = false
        }
    }

    /**
     * 兜底截断（自动压缩关闭时）：超出上限就从最老的消息开始丢弃（不生成摘要）。
     * 切点复用压缩的边界规则，避免拆散 assistant/tool 配对。
     */
    private suspend fun hard_trim_history(settings: ai_settings_state) {
        val limit_chars = settings.effective_context_chars()
        val used_chars = on_main_and_wait { messages.sumOf { it.estimated_chars().toLong() } }
        if (used_chars <= limit_chars) return
        // 保留近期约 60% 上限
        val keep_recent = (limit_chars * 0.6f).toInt()
        val snapshot = on_main_and_wait { messages.toList() }
        val cut = ai_compaction.find_cut_point(snapshot, keep_recent)
        if (cut <= 0) return
        on_main {
            val kept = messages.drop(cut)
            messages.clear()
            messages.addAll(kept)
        }
    }

    /** 估算上下文用量并更新 StateFlow（主线程调用；徽标/压缩判断的数据源） */
    private fun refresh_context_usage() {
        val settings = settings_provider()
        val limit = settings.effective_context_chars().toLong().coerceAtLeast(1)
        val used = last_system_prompt_len + messages.sumOf { it.estimated_chars().toLong() }
        _context_usage.value = (used.toFloat() / limit).coerceIn(0f, 1.5f)
    }

    /** 构建完整 system prompt：基础 + AGENTS.md + skill 索引 + 用户自定义提示词 */
    private fun build_full_system_prompt(env: ai_environment_context, tools: List<String>, settings: ai_settings_state): String {
        val base = build_system_prompt(env, tools, settings.conversation_tone)
        val sb = StringBuilder(base)
        // AGENTS.md / .ai 上下文
        val context_files = read_context_files(env.project_dir)
        if (context_files.isNotBlank()) {
            sb.appendLine().appendLine(context_files)
        }
        // skill 索引
        val skills = skill_manager?.skill_index_text()
        if (!skills.isNullOrEmpty()) {
            sb.appendLine().appendLine("## 技能（Skills）").appendLine(skills)
        }
        // 用户自定义提示词
        if (settings.custom_system_prompt.isNotBlank()) {
            sb.appendLine().appendLine("## 附加指令").appendLine(settings.custom_system_prompt.trim())
        }
        return sb.toString()
    }

    private suspend fun persist_session() {
        session_store?.let { store ->
            val snapshot = on_main_and_wait { messages.toList() }
            withContext(Dispatchers.IO) { store.save_session(session_id, snapshot) }
        }
    }

    private fun update_execution(msg: ai_message, exec: ai_tool_execution): ai_message {
        val newExecs = msg.tool_executions.toMutableList()
        val idx = newExecs.indexOfFirst { it.call.id == exec.call.id }
        if (idx >= 0) newExecs[idx] = exec else newExecs.add(exec)
        return msg.copy(tool_executions = newExecs)
    }

    private suspend fun <T> on_main_and_wait(action: () -> T): T = withContext(Dispatchers.Main) { action() }
}
