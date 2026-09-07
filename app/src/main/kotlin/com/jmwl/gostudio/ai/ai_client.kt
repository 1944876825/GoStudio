package com.jmwl.gostudio.ai

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * 把 HTTP 错误响应转成用户可读的消息：
 * 优先提取 JSON 里的 error.message（很多网关返回 {"error":{"message":...}}），
 * 再附上常见状态码的中文提示（401/403 密钥或权限、429 限流、5xx 服务端）。
 */
internal fun friendly_http_error(prefix: String, code: Int, body: String, fallback_message: String): String {
    val trimmed = body.trim().take(300)
    val detail = runCatching {
        val obj = JsonParser.parseString(trimmed).asJsonObject
        (obj.getAsJsonObject("error")?.get("message") ?: obj.get("message"))
            ?.takeIf { !it.isJsonNull }?.asString
    }.getOrNull()?.trim()?.ifBlank { null }
        ?: trimmed.ifBlank { fallback_message }
    val hint = when (code) {
        401, 403 -> "\n💡 请检查 API Key 是否有效，或切换到该 Key 有权限的模型（顶部可快捷切换）。"
        404 -> "\n💡 请检查 Base URL 和模型名是否正确。"
        429 -> "\n💡 请求过于频繁或额度不足，请稍后重试。"
        in 500..599 -> "\n💡 服务端错误，请稍后重试。"
        else -> ""
    }
    return "$prefix $code: $detail$hint"
}

/**
 * AI 流式响应回调。
 * - on_text: 收到一段文本增量（可能很短，如单个字/词），UI 累加显示
 * - on_reasoning: reasoning 模型的思考链增量（DeepSeek reasoning_content / Anthropic thinking block）
 * - on_done: 一轮回复完成，[tool_calls] 为模型要求调用的工具（可能为空）
 */
interface ai_stream_callback {
    fun on_text(delta: String)
    fun on_reasoning(delta: String) {}
    fun on_done(tool_calls: List<ai_tool_call>)
    fun on_error(message: String)
}

/**
 * AI API 客户端。根据 provider 分发：
 * - OpenAI 兼容（智谱/DeepSeek/Kimi/OpenAI/xAI）：POST /chat/completions，标准 OpenAI 格式
 * - Anthropic：POST /messages，Messages API 格式（system 顶层、content blocks、tool_use）
 */
class ai_client(
    private val settings: ai_settings_state
) {
    // 粘贴的 key/URL/模型名常带首尾空格换行（旧数据也可能存了带空白的），发请求前统一裁掉
    private val base_url = settings.base_url.trim()
    private val api_key = settings.api_key.trim()
    private val model = settings.model.trim()

    private val gson = Gson()
    private val json_media_type = "application/json; charset=utf-8".toMediaType()

    private val http_client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    /** 连接测试专用：短超时，避免坏端点把测试卡到两分钟 */
    private val test_client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(25, TimeUnit.SECONDS)
        .build()

    suspend fun stream_chat(
        messages: List<ai_message>,
        tools: List<Map<String, Any>>,
        callback: ai_stream_callback
    ) {
        if (!settings.is_configured()) {
            callback.on_error("AI 未配置：请在设置里填写 base_url、model、api_key")
            return
        }
        try {
            if (settings.provider == ai_provider.ANTHROPIC) {
                stream_anthropic(messages, tools, callback)
            } else {
                stream_openai(messages, tools, callback)
            }
        } catch (e: Exception) {
            callback.on_error("网络错误: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    /**
     * 拉取可用模型列表。OpenAI 兼容端点 GET /models（Bearer）；
     * Anthropic 也有 /v1/models（x-api-key + anthropic-version，默认分页 20 条故带 limit）。
     * 两者的响应都是 data[].id 结构，解析方式一致。
     * @return 模型 id 排序列表；失败抛异常由调用方提示
     */
    fun fetch_models(): List<String> {
        val is_anthropic = settings.provider == ai_provider.ANTHROPIC
        val url = base_url.trimEnd('/') + "/models" + if (is_anthropic) "?limit=1000" else ""
        val request = Request.Builder()
            .url(url)
            .apply {
                if (is_anthropic) {
                    header("x-api-key", api_key)
                    header("anthropic-version", "2023-06-01")
                } else {
                    header("Authorization", "Bearer ${api_key}")
                }
            }
            .header("Accept", "application/json")
            .get()
            .build()
        http_client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val errBody = response.body?.string()?.take(300) ?: ""
                throw RuntimeException(friendly_http_error("获取模型列表失败", response.code, errBody, response.message))
            }
            val body = response.body?.string() ?: throw RuntimeException("空响应")
            val json = JsonParser.parseString(body).asJsonObject
            val data = json.getAsJsonArray("data") ?: throw RuntimeException("响应无 data 字段")
            return data.mapNotNull { elem ->
                runCatching { elem.asJsonObject.get("id")?.takeIf { !it.isJsonNull }?.asString }.getOrNull()
            }.filter { it.isNotBlank() }.sorted()
        }
    }

    /**
     * 连接测试（参考 OpenMinis 的 ModelQuickTestSheet）：发一条最小非流式请求，
     * 确认「这个模型 + 这把密钥」真的能用。返回 (回复文本, 耗时毫秒)；失败抛异常。
     * [test_model] 允许临时测任意模型（模型列表批量测试），默认测当前配置的模型。
     */
    fun quick_test(test_model: String = model): Pair<String, Long> {
        val started = System.currentTimeMillis()
        if (settings.provider == ai_provider.ANTHROPIC) {
            val payload = linkedMapOf<String, Any>(
                "model" to test_model,
                "max_tokens" to 64,
                "messages" to listOf(mapOf("role" to "user", "content" to "连接测试，请只回复两个字：可用"))
            )
            val request = Request.Builder()
                .url(base_url.trimEnd('/') + "/messages")
                .header("x-api-key", api_key)
                .header("anthropic-version", "2023-06-01")
                .header("Content-Type", "application/json")
                .post(gson.toJson(payload).toRequestBody(json_media_type))
                .build()
            test_client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw RuntimeException(friendly_http_error("连接测试失败", response.code, response.body?.string()?.take(300) ?: "", response.message))
                }
                val json = JsonParser.parseString(response.body?.string() ?: "").asJsonObject
                val text = json.getAsJsonArray("content")
                    ?.mapNotNull { c -> c.asJsonObject.get("text")?.takeIf { !it.isJsonNull }?.asString }
                    ?.joinToString("") ?: ""
                return text.trim() to (System.currentTimeMillis() - started)
            }
        }
        val payload = linkedMapOf<String, Any>(
            "model" to test_model,
            "max_tokens" to 64,
            "messages" to listOf(mapOf("role" to "user", "content" to "连接测试，请只回复两个字：可用"))
        )
        val request = Request.Builder()
            .url(base_url.trimEnd('/') + "/chat/completions")
            .header("Authorization", "Bearer ${api_key}")
            .header("Content-Type", "application/json")
            .post(gson.toJson(payload).toRequestBody(json_media_type))
            .build()
        test_client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw RuntimeException(friendly_http_error("连接测试失败", response.code, response.body?.string()?.take(300) ?: "", response.message))
            }
            val json = JsonParser.parseString(response.body?.string() ?: "").asJsonObject
            val text = json.getAsJsonArray("choices")?.firstOrNull()?.asJsonObject
                ?.getAsJsonObject("message")?.get("content")?.takeIf { !it.isJsonNull }?.asString ?: ""
            return text.trim() to (System.currentTimeMillis() - started)
        }
    }

    // ============ OpenAI 兼容 ============
    private suspend fun stream_openai(messages: List<ai_message>, tools: List<Map<String, Any>>, callback: ai_stream_callback) {
        val url = base_url.trimEnd('/') + "/chat/completions"
        val body = build_request_body(messages, tools)
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer ${api_key}")
            .header("Content-Type", "application/json")
            .header("Accept", "text/event-stream")
            .post(body.toRequestBody(json_media_type))
            .build()

        http_client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val errBody = response.body?.string()?.take(500) ?: ""
                Log.e("GoStudio_AI", "OpenAI 请求失败 ${response.code}\nURL: $url\n请求体: ${body.take(800)}\n响应: $errBody")
                callback.on_error(friendly_http_error("请求失败", response.code, errBody, response.message))
                return
            }
            parse_openai_sse(response.body?.byteStream() ?: return, callback)
        }
    }

    private fun build_request_body(messages: List<ai_message>, tools: List<Map<String, Any>>): String {
        val payload = linkedMapOf<String, Any>(
            "model" to model,
            "messages" to messages.map { it.to_api_map() },
            "stream" to true
        )
        if (tools.isNotEmpty()) payload["tools"] = tools
        return gson.toJson(payload)
    }

    private fun parse_openai_sse(stream: java.io.InputStream, callback: ai_stream_callback) {
        val tool_acc = mutableMapOf<Int, Triple<String, String, StringBuilder>>()
        val reader = stream.bufferedReader(Charsets.UTF_8)
        while (true) {
            val line = reader.readLine() ?: break
            if (!line.startsWith("data:")) continue
            val data = line.removePrefix("data:").trim()
            if (data == "[DONE]") break
            if (data.isEmpty()) continue
            val chunk = runCatching { JsonParser.parseString(data).asJsonObject }.getOrElse { continue }
            val choice = chunk.getAsJsonArray("choices")?.firstOrNull()?.asJsonObject ?: continue
            chunk.get("error")?.asJsonObject?.let { err ->
                callback.on_error("API 错误: ${err.get("message")?.asString ?: data}"); return
            }
            val delta = choice.getAsJsonObject("delta") ?: continue
            delta.get("content")?.takeIf { !it.isJsonNull }?.asString?.let { if (it.isNotEmpty()) callback.on_text(it) }
            // reasoning 思考链（DeepSeek/OpenAI o-series）：reasoning_content 或 reasoning 字段
            delta.get("reasoning_content")?.takeIf { !it.isJsonNull }?.asString?.let { if (it.isNotEmpty()) callback.on_reasoning(it) }
            delta.get("reasoning")?.takeIf { !it.isJsonNull }?.asString?.let { if (it.isNotEmpty()) callback.on_reasoning(it) }
            delta.getAsJsonArray("tool_calls")?.forEach { tcElem ->
                val tc = tcElem.asJsonObject
                val index = tc.get("index")?.asInt ?: 0
                val fn = tc.getAsJsonObject("function") ?: return@forEach
                val existing = tool_acc[index]
                val id = tc.get("id")?.takeIf { !it.isJsonNull }?.asString ?: existing?.first ?: ""
                val name = fn.get("name")?.takeIf { !it.isJsonNull }?.asString ?: existing?.second ?: ""
                val argsBuilder = existing?.third ?: StringBuilder()
                argsBuilder.append(fn.get("arguments")?.takeIf { !it.isJsonNull }?.asString ?: "")
                tool_acc[index] = Triple(id, name, argsBuilder)
            }
        }
        val tool_calls = tool_acc.toSortedMap().values.map { (id, name, args) ->
            ai_tool_call(id = id, name = name, arguments_json = args.toString())
        }
        callback.on_done(tool_calls)
    }

    // ============ Anthropic Messages API ============
    // 关键差异：system 是顶层字段；messages 只有 user/assistant；
    // tool 结果作为 user 消息的 content block（type=tool_result）；
    // tool 调用作为 assistant 的 content block（type=tool_use）。
    private suspend fun stream_anthropic(messages: List<ai_message>, tools: List<Map<String, Any>>, callback: ai_stream_callback) {
        val url = base_url.trimEnd('/') + "/messages"
        // 分离 system（第一条 system 消息）和对话消息
        val system_text = messages.firstOrNull { it.role == ai_message_role.SYSTEM }?.text ?: ""
        val conversation = messages.filter { it.role != ai_message_role.SYSTEM }

        val body = build_anthropic_body(system_text, conversation, tools)
        val request = Request.Builder()
            .url(url)
            .header("x-api-key", api_key)
            .header("anthropic-version", "2023-06-01")
            .header("Content-Type", "application/json")
            .post(body.toRequestBody(json_media_type))
            .build()

        http_client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val errBody = response.body?.string()?.take(500) ?: ""
                Log.e("GoStudio_AI", "Anthropic 请求失败 ${response.code}\nURL: $url\n响应: $errBody")
                callback.on_error(friendly_http_error("请求失败", response.code, errBody, response.message))
                return
            }
            parse_anthropic_sse(response.body?.byteStream() ?: return, callback)
        }
    }

    private fun build_anthropic_body(system: String, conversation: List<ai_message>, tools: List<Map<String, Any>>): String {
        // Anthropic 要求：连续同角色消息必须合并（多个 tool 结果都是 user 角色，不能 user-user 相邻）
        val merged = merge_consecutive_same_role(conversation)
        val payload = linkedMapOf<String, Any>(
            "model" to model,
            "max_tokens" to 8192, // Anthropic 必填
            "system" to system,
            "stream" to true,
            "messages" to merged
        )
        // tools 转成 Anthropic 格式（input_schema 替代 parameters）
        if (tools.isNotEmpty()) {
            payload["tools"] = tools.map { t ->
                @Suppress("UNCHECKED_CAST")
                val fn = t["function"] as Map<String, Any>
                // parameters 现在是 JsonObject（to_api_tools 直接传对象），Anthropic 用 input_schema
                val schema = fn["parameters"] as JsonObject
                linkedMapOf<String, Any>(
                    "name" to fn["name"]!!,
                    "description" to fn["description"]!!,
                    "input_schema" to schema
                )
            }
        }
        return gson.toJson(payload)
    }

    /**
     * 把 ai_message 转成 Anthropic 的 content 格式。
     * Anthropic 的 content 是数组，每个元素有 type：
     * - user 消息：[{type:text,text:...}] 或含 tool_result 时 [{type:tool_result,tool_use_id,content:...}]
     * - assistant 消息：[{type:text,text:...}, {type:tool_use,id,name,input}]
     */
    private fun anthropic_message_to_content(msg: ai_message): Map<String, Any> {
        val role = when (msg.role) {
            ai_message_role.ASSISTANT -> "assistant"
            else -> "user"
        }
        val content_blocks = mutableListOf<Map<String, Any>>()
        // tool 结果消息（role=TOOL）→ user 的 tool_result block
        if (msg.role == ai_message_role.TOOL) {
            content_blocks.add(mapOf(
                "type" to "tool_result",
                "tool_use_id" to msg.tool_call_id,
                "content" to msg.text
            ))
        } else {
            if (msg.text.isNotBlank()) {
                content_blocks.add(mapOf("type" to "text", "text" to msg.text))
            }
            // assistant 的 tool_calls → tool_use blocks
            for (tc in msg.tool_calls) {
                val input = runCatching { gson.fromJson(tc.arguments_json, JsonObject::class.java) }
                    .getOrElse { JsonObject() }
                content_blocks.add(mapOf(
                    "type" to "tool_use",
                    "id" to tc.id,
                    "name" to tc.name,
                    "input" to input
                ))
            }
        }
        // 连续同角色的 tool 结果需要合并，这里简单处理：每条消息独立成块
        return mapOf("role" to role, "content" to content_blocks)
    }

    /**
     * 合并连续同角色的消息（Anthropic 要求 role 必须交替 user/assistant）。
     * 把相邻同 role 的 content blocks 拼进同一个 message。
     */
    private fun merge_consecutive_same_role(conversation: List<ai_message>): List<Map<String, Any>> {
        val result = mutableListOf<Pair<String, MutableList<Map<String, Any>>>>()
        for (msg in conversation) {
            val role = when (msg.role) {
                ai_message_role.ASSISTANT -> "assistant"
                else -> "user"
            }
            val blocks = build_anthropic_content_blocks(msg)
            val last = result.lastOrNull()
            if (last != null && last.first == role) {
                last.second.addAll(blocks)
            } else {
                result.add(role to blocks.toMutableList())
            }
        }
        return result.map { mapOf("role" to it.first, "content" to it.second) }
    }

    /** 单条消息转 Anthropic content blocks（不含 role 包装） */
    private fun build_anthropic_content_blocks(msg: ai_message): List<Map<String, Any>> {
        val blocks = mutableListOf<Map<String, Any>>()
        if (msg.role == ai_message_role.TOOL) {
            blocks.add(mapOf(
                "type" to "tool_result",
                "tool_use_id" to msg.tool_call_id,
                "content" to msg.text
            ))
        } else {
            if (msg.text.isNotBlank()) blocks.add(mapOf("type" to "text", "text" to msg.text))
            for (tc in msg.tool_calls) {
                val input = runCatching { gson.fromJson(tc.arguments_json, JsonObject::class.java) }.getOrElse { JsonObject() }
                blocks.add(mapOf("type" to "tool_use", "id" to tc.id, "name" to tc.name, "input" to input))
            }
        }
        return blocks
    }

    /**
     * 解析 Anthropic SSE 流。
     * 事件类型：message_start / content_block_start / content_block_delta / content_block_stop / message_delta / message_stop
     * 关键：content_block_delta 的 delta.type=text_delta 时有 text；=input_json_delta 时有 partial_json（拼工具参数）
     */
    private fun parse_anthropic_sse(stream: java.io.InputStream, callback: ai_stream_callback) {
        val reader = stream.bufferedReader(Charsets.UTF_8)
        // 当前 content block 的工具累积：index -> (id, name, args_builder)
        val tool_blocks = mutableMapOf<Int, Triple<String, String, StringBuilder>>()
        var current_index = -1

        while (true) {
            val line = reader.readLine() ?: break
            if (!line.startsWith("data:")) continue
            val data = line.removePrefix("data:").trim()
            if (data.isEmpty() || data == "[DONE]") continue
            val evt = runCatching { JsonParser.parseString(data).asJsonObject }.getOrElse { continue }

            // 错误
            evt.get("type")?.asString?.let { type ->
                when (type) {
                    "error" -> {
                        val err = evt.getAsJsonObject("error")
                        callback.on_error("Anthropic 错误: ${err?.get("message")?.asString ?: data}")
                        return
                    }
                    "content_block_start" -> {
                        val idx = evt.get("index")?.asInt ?: 0
                        val block = evt.getAsJsonObject("content_block")
                        if (block?.get("type")?.asString == "tool_use") {
                            val id = block.get("id")?.asString ?: ""
                            val name = block.get("name")?.asString ?: ""
                            tool_blocks[idx] = Triple(id, name, StringBuilder())
                            current_index = idx
                        }
                    }
                    "content_block_delta" -> {
                        val idx = evt.get("index")?.asInt ?: current_index
                        val delta = evt.getAsJsonObject("delta")
                        when (delta?.get("type")?.asString) {
                            "text_delta" -> {
                                delta.get("text")?.takeIf { !it.isJsonNull }?.asString?.let {
                                    if (it.isNotEmpty()) callback.on_text(it)
                                }
                            }
                            "thinking_delta" -> {
                                // Anthropic extended thinking
                                delta.get("thinking")?.takeIf { !it.isJsonNull }?.asString?.let {
                                    if (it.isNotEmpty()) callback.on_reasoning(it)
                                }
                            }
                            "input_json_delta" -> {
                                val partial = delta.get("partial_json")?.takeIf { !it.isJsonNull }?.asString ?: ""
                                tool_blocks[idx]?.third?.append(partial)
                                current_index = idx
                            }
                        }
                    }
                    "message_stop" -> {
                        // 流结束，组装 tool_calls
                        val tool_calls = tool_blocks.toSortedMap().values.map { (id, name, args) ->
                            ai_tool_call(id = id, name = name, arguments_json = args.toString().ifBlank { "{}" })
                        }
                        callback.on_done(tool_calls)
                        return
                    }
                }
            }
        }
        // 兜底（没收到 message_stop）
        val tool_calls = tool_blocks.toSortedMap().values.map { (id, name, args) ->
            ai_tool_call(id = id, name = name, arguments_json = args.toString().ifBlank { "{}" })
        }
        callback.on_done(tool_calls)
    }
}
