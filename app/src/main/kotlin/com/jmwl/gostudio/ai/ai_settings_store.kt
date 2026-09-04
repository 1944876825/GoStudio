package com.jmwl.gostudio.ai

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.reflect.TypeToken

/** AI 助手设置：提供商 / API key / 模型 / 工具开关。用 EncryptedSharedPreferences 加密存储 API key。 */

internal const val ai_settings_prefs_name = "ai_settings"

/**
 * 预置的 AI 提供商（均兼容 OpenAI /chat/completions 格式，Anthropic 在 ai_client 单独适配）。
 * default_models 是该供应商常用模型列表，供会话栏快速切换。
 */
enum class ai_provider(
    val display_name: String,
    val base_url: String,
    val default_model: String,
    val default_models: List<String>
) {
    CUSTOM("自定义", "", "", emptyList()),
    ZHIPU("智谱 GLM", "https://open.bigmodel.cn/api/paas/v4/", "glm-4.6",
        listOf("glm-4.6", "glm-4.5", "glm-4.5-air", "glm-4.5-x", "glm-4-plus", "glm-4-air", "glm-4-flash", "glm-4-flashx", "glm-4-long")),
    ZHIPU_CODING("智谱 GLM (编程套餐)", "https://open.bigmodel.cn/api/coding/paas/v4/", "glm-4.6",
        listOf("glm-4.6", "glm-4.5", "glm-4.5-air", "glm-4.5-x")),
    DEEPSEEK("DeepSeek", "https://api.deepseek.com", "deepseek-chat",
        listOf("deepseek-v4-flash", "deepseek-v4-pro", "deepseek-chat", "deepseek-reasoner")),
    KIMI("Kimi", "https://api.moonshot.cn/v1", "moonshot-v1-auto",
        listOf("moonshot-v1-auto", "moonshot-v1-8k", "moonshot-v1-32k", "moonshot-v1-128k", "kimi-latest")),
    OPENAI("OpenAI", "https://api.openai.com/v1", "gpt-4o",
        listOf("gpt-4o", "gpt-4o-mini", "gpt-4.1", "gpt-4.1-mini", "o3-mini")),
    XAI("xAI (Grok)", "https://api.x.ai/v1", "grok-2",
        listOf("grok-2", "grok-2-latest", "grok-beta")),
    ANTHROPIC("Anthropic (Claude)", "https://api.anthropic.com/v1", "claude-sonnet-4-5",
        listOf("claude-sonnet-4-5", "claude-opus-4", "claude-haiku-4", "claude-3-7-sonnet", "claude-3-5-haiku"));

    val is_openai_compatible: Boolean get() = this != ANTHROPIC
    /** 模型列表可拉取：OpenAI 兼容端点有 GET /models，Anthropic 也有 /v1/models；个别中转不支持时由脚注诊断兜底 */
    val supports_model_list: Boolean get() = true
}

/**
 * 单个模型的能力标注（手动配置，端点 /models 不返回这些信息）。
 * - [context_tokens]：模型上下文窗口（token），>0 时优先于全局 max_context_chars 用于历史截断
 * - [supports_image] / [supports_video]：是否接收图片/视频输入（多模态）
 */
data class model_capabilities(
    val context_tokens: Long = 0,
    val supports_image: Boolean = false,
    val supports_video: Boolean = false
) {
    /** 任一能力有值（用于判断是否落库、是否显示徽标） */
    val is_empty: Boolean get() = context_tokens <= 0 && !supports_image && !supports_video
    /** 多模态：图片或视频任一支持 */
    val is_multimodal: Boolean get() = supports_image || supports_video
}

/**
 * 一个已配置的提供商实例（多实例模型，参考 OpenMinis 的 ProviderInstance）。
 * 同一类型可以添加多个（如两个不同的中转站）；`ai_settings_state` 的扁平字段
 * 始终镜像「当前使用」的实例，供 ai_client / 会话栏切换等既有逻辑直接消费。
 */
data class provider_instance(
    val id: String,
    val label: String,
    val provider: ai_provider,
    val base_url: String,
    val model: String,
    val api_key: String = "",
    val enabled: Boolean = true,
    /** 拉取/手填的模型列表缓存（候选 = 此列表 + 类型预置列表） */
    val models: List<String> = emptyList(),
    /** 用户隐藏的模型（从候选中排除，不删除） */
    val hidden_models: List<String> = emptyList(),
    /** 每个模型的能力标注（key=模型 id）：上下文长度、是否支持图片/视频输入 */
    val model_caps: Map<String, model_capabilities> = emptyMap()
) {
    /** key 掩码摘要（列表行展示用），如 sk-abc1…wxyz */
    fun masked_key(): String = when {
        api_key.isBlank() -> "未配置密钥"
        api_key.length <= 10 -> "••••"
        else -> api_key.take(6) + "…" + api_key.takeLast(4)
    }

    /** 模型选择候选：拉取列表 + 类型预置列表去重，排除隐藏 */
    fun selectable_models(): List<String> =
        (models + provider.default_models).distinct().filter { it !in hidden_models }

    /** 已配置且启用（列表行的状态点） */
    val is_ready: Boolean get() = enabled && base_url.isNotBlank() && model.isNotBlank() && api_key.isNotBlank()
}

/** 新建一个某类型的实例（label 自动编号避免同名） */
fun new_provider_instance(provider: ai_provider, existing: List<provider_instance>): provider_instance {
    val same_type_count = existing.count { it.provider == provider }
    val label = if (same_type_count == 0) provider.display_name else "${provider.display_name} ${same_type_count + 1}"
    return provider_instance(
        id = java.util.UUID.randomUUID().toString(),
        label = label,
        provider = provider,
        base_url = provider.base_url,
        model = provider.default_model
    )
}

/**
 * 会话内即时切换的模型选择（快照完整连接信息，支持同提供商多实例/自定义中转）。
 * 下一轮请求即生效——agent loop 每轮都通过 settings_provider 重新读设置，
 * activity 侧把该选择覆盖进返回的 ai_settings_state。
 */
data class ai_model_choice(
    val provider: ai_provider,
    val model: String,
    val base_url: String,
    val api_key: String,
    /** 展示名（实例 label 或提供商名） */
    val label: String = provider.display_name
) {
    companion object {
        /** 从全局设置的当前生效字段构造（无会话 override 时的默认选择） */
        fun from_settings(state: ai_settings_state): ai_model_choice = ai_model_choice(
            provider = state.provider,
            model = state.model,
            base_url = state.base_url,
            api_key = state.api_key,
            label = state.active_provider_instance()?.label ?: state.provider.display_name
        )
    }
}

/** 判断会话选择与全局当前生效配置是否一致（一致时 UI 不显示「已切换」标记） */
fun ai_model_choice.matches_settings(state: ai_settings_state): Boolean {
    val inst = state.active_provider_instance()
    return provider == state.provider && model == state.model &&
        base_url == state.base_url && (inst == null || label == inst.label)
}

data class ai_settings_state(
    val provider: ai_provider = ai_provider.ZHIPU,
    val base_url: String = ai_provider.ZHIPU.base_url,
    val model: String = ai_provider.ZHIPU.default_model,
    val api_key: String = "",
    /** 每个供应商独立的 API key（会话栏切供应商时回填对应 key） */
    val api_keys: Map<ai_provider, String> = emptyMap(),
    /** 多实例列表与当前使用的实例 id（详情见 provider_instance） */
    val instances: List<provider_instance> = emptyList(),
    val active_instance_id: String = "",
    /** 动态获取的模型列表缓存，key=base_url（避免重复请求 /v1/models） */
    val custom_models: Map<String, List<String>> = emptyMap(),
    /** 上下文窗口上限（用于截断历史消息），默认 60000 token 约等于 24 万字符估算 */
    val max_context_chars: Int = 200_000,
    /** 自动上下文压缩：估算用量超阈值时把旧消息摘要成 checkpoint（关闭则退化请求前截断） */
    val auto_compact: Boolean = true,
    /** 压缩触发阈值（上下文用量百分比，参考 pi 的 window-reserve 机制换算） */
    val compact_threshold_percent: Int = 80,
    val enable_tools: Boolean = true,
    val enable_bash: Boolean = true,
    val enable_write: Boolean = true,
    /** agent loop 最大轮次（防失控） */
    val max_agent_iterations: Int = 20,
    // ===== AI 行为 =====
    /** 思考深度：0=标准 1=深入 2=极致（影响推理轮次倾向） */
    val thinking_depth: Int = 0,
    /** 自定义系统提示词（追加到默认 system prompt 之后） */
    val custom_system_prompt: String = "",
    /** 交流语气：friendly / professional / concise */
    val conversation_tone: String = "friendly",
    /** verbose 模式：展示 agent 中间步骤（读文件/执行命令等思考过程） */
    val show_thinking_process: Boolean = true,
    /** 思考过程自动展开（false=折叠只显示标题） */
    val auto_expand_thinking: Boolean = false
)

/**
 * 切换到新提供商：保留所有供应商的 key（api_keys）+ 自定义模型缓存，
 * 回填新提供商的 key 到 api_key，并预置 base_url / model。
 * 自定义(CUSTOM)只切 provider，保留用户已填的 base_url/model/key。
 */
fun switch_provider(state: ai_settings_state, new_provider: ai_provider): ai_settings_state {
    if (new_provider == ai_provider.CUSTOM) {
        return state.copy(provider = new_provider)
    }
    return state.copy(
        provider = new_provider,
        base_url = new_provider.base_url,
        model = new_provider.default_model,
        api_key = state.api_keys[new_provider] ?: ""
    )
}

/** 兼容旧调用：从默认状态切到指定提供商（会清空 key，仅用于无 state 的场景） */
fun default_state_for_provider(provider: ai_provider): ai_settings_state {
    return ai_settings_state(
        provider = provider,
        base_url = provider.base_url,
        model = provider.default_model
    )
}

/** 当前使用的实例（active id 失效时回退第一个） */
fun ai_settings_state.active_provider_instance(): provider_instance? =
    instances.firstOrNull { it.id == active_instance_id } ?: instances.firstOrNull()

/**
 * 查某模型的能力标注：优先当前实例，再查同类型的其它实例
 * （会话内切到同提供商的其它模型时也能命中）。
 */
fun ai_settings_state.model_capabilities_of(model: String): model_capabilities? {
    if (model.isBlank()) return null
    active_provider_instance()?.model_caps[model]?.let { return it }
    return instances
        .firstOrNull { it.provider == provider && it.model_caps.containsKey(model) }
        ?.model_caps[model]
}

/**
 * 实际生效的历史截断上限（字符）：模型级上下文长度优先（按 1 token ≈ 4 字符估算，
 * 与 max_context_chars 默认值的换算一致），未标注时退回全局 max_context_chars。
 */
fun ai_settings_state.effective_context_chars(): Int {
    val tokens = model_capabilities_of(model)?.context_tokens ?: 0
    if (tokens <= 0) return max_context_chars
    return (tokens * 4).coerceIn(1_000L, Int.MAX_VALUE.toLong()).toInt()
}

/** 把实例字段镜像到全局生效配置（ai_client / 会话栏切换读这些扁平字段） */
private fun ai_settings_state.mirror_instance(instance: provider_instance): ai_settings_state = copy(
    provider = instance.provider,
    base_url = instance.base_url,
    model = instance.model,
    api_key = instance.api_key,
    api_keys = api_keys + (instance.provider to instance.api_key),
    custom_models = if (instance.models.isEmpty()) custom_models else custom_models + (instance.base_url to instance.models)
)

/** 新增或更新一个实例；若它是当前使用的实例（或还没有任何实例），同步镜像到全局生效字段 */
fun ai_settings_state.with_instance(updated: provider_instance): ai_settings_state {
    val next_instances = if (instances.any { it.id == updated.id }) {
        instances.map { if (it.id == updated.id) updated else it }
    } else instances + updated
    var next = copy(instances = next_instances)
    if (updated.id == active_instance_id || active_instance_id.isBlank()) {
        next = next.copy(active_instance_id = updated.id).mirror_instance(updated)
    }
    return next
}

/** 切换「当前使用」的实例（镜像到全局生效字段） */
fun ai_settings_state.with_active_instance(id: String): ai_settings_state {
    val instance = instances.firstOrNull { it.id == id } ?: return this
    return copy(active_instance_id = id).mirror_instance(instance)
}

/** 删除实例；若删的是当前使用的，切到剩余第一个 */
fun ai_settings_state.without_instance(id: String): ai_settings_state {
    val next = copy(instances = instances.filterNot { it.id == id })
    if (id != active_instance_id) return next
    val fallback = next.instances.firstOrNull() ?: return next.copy(active_instance_id = "")
    return next.with_active_instance(fallback.id)
}

private fun master_key(context: Context): MasterKey {
    return MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()
}

/** 获取加密的 SharedPreferences（API key 落盘加密） */
private fun encrypted_prefs(context: Context) = runCatching {
    EncryptedSharedPreferences.create(
        context,
        ai_settings_prefs_name,
        master_key(context),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )
}.getOrElse {
    // 加密存储初始化失败（极少见，如设备无 keystore）时回退到普通私有 prefs，避免完全不可用
    context.getSharedPreferences("${ai_settings_prefs_name}_fallback", Context.MODE_PRIVATE)
}

private val settings_gson = Gson()

/**
 * AI 设置内存缓存。
 *
 * [load_ai_settings] 每次调用都会执行 EncryptedSharedPreferences.create() + MasterKey
 * （Android Keystore 硬件操作，单次可达几十毫秒）+ Gson 解析。
 * 它绝不能出现在 Compose 组合/重组热路径里——否则每次光标移动触发的重组都会做 Keystore I/O，
 * 直接造成编辑器光标卡顿。UI 热路径一律用 [cached_ai_settings]。
 */
@Volatile
private var ai_settings_memory_cache: ai_settings_state? = null

/** 读 AI 设置（带内存缓存）。首次读磁盘，之后走内存；[save_ai_settings] 会同步更新缓存。 */
fun cached_ai_settings(context: Context): ai_settings_state {
    return ai_settings_memory_cache ?: load_ai_settings(context).also { ai_settings_memory_cache = it }
}

fun load_ai_settings(context: Context): ai_settings_state {
    val prefs = encrypted_prefs(context)
    val providerName = prefs.getString("provider", ai_provider.ZHIPU.name) ?: ai_provider.ZHIPU.name
    val provider = runCatching { ai_provider.valueOf(providerName) }.getOrDefault(ai_provider.ZHIPU)
    val api_key = prefs.getString("api_key", "") ?: ""

    // 反序列化每供应商 key 的 map；老数据没有则把单 key 迁移到当前 provider
    val api_keys: Map<ai_provider, String> = run {
        val json = prefs.getString("api_keys_json", null)
        if (json != null) {
            runCatching {
                val type = object : TypeToken<Map<String, String>>() {}.type
                @Suppress("UNCHECKED_CAST")
                val raw = settings_gson.fromJson<Map<String, String>>(json, type) ?: emptyMap()
                raw.mapNotNull { (k, v) ->
                    runCatching { ai_provider.valueOf(k) }.getOrNull()?.let { it to v }
                }.toMap()
            }.getOrDefault(emptyMap())
        } else if (api_key.isNotBlank()) {
            mapOf(provider to api_key)
        } else {
            emptyMap()
        }
    }

    // 反序列化动态获取的模型缓存
    val custom_models: Map<String, List<String>> = run {
        val json = prefs.getString("custom_models_json", null) ?: return@run emptyMap()
        runCatching {
            val type = object : TypeToken<Map<String, List<String>>>() {}.type
            val parsed: Map<String, List<String>> = settings_gson.fromJson(json, type) ?: emptyMap()
            parsed
        }.getOrDefault(emptyMap())
    }

    val base_url = prefs.getString("base_url", provider.base_url) ?: provider.base_url
    val model = prefs.getString("model", provider.default_model) ?: provider.default_model

    // 多实例列表；老数据没有 instances_json 时从旧的单提供商配置迁移
    val instances: List<provider_instance> = run {
        val json = prefs.getString("instances_json", null)
        if (json != null) {
            runCatching {
                settings_gson.fromJson(json, JsonArray::class.java)
            }.getOrNull()?.mapNotNull { elem -> instance_from_json(elem.asJsonObject) } ?: emptyList()
        } else {
            migrate_legacy_instances(provider, base_url, model, api_key, api_keys, custom_models)
        }
    }
    val active_instance_id = prefs.getString("active_instance_id", null)
        ?.takeIf { saved -> instances.any { it.id == saved } }
        ?: instances.firstOrNull { it.provider == provider }?.id
        ?: instances.firstOrNull()?.id.orEmpty()

    return ai_settings_state(
        provider = provider,
        base_url = base_url,
        model = model,
        api_key = api_key,
        api_keys = api_keys,
        instances = instances,
        active_instance_id = active_instance_id,
        custom_models = custom_models,
        max_context_chars = prefs.getInt("max_context_chars", 200_000),
        auto_compact = prefs.getBoolean("auto_compact", true),
        compact_threshold_percent = prefs.getInt("compact_threshold_percent", 80),
        enable_tools = prefs.getBoolean("enable_tools", true),
        enable_bash = prefs.getBoolean("enable_bash", true),
        enable_write = prefs.getBoolean("enable_write", true),
        max_agent_iterations = prefs.getInt("max_agent_iterations", 20),
        thinking_depth = prefs.getInt("thinking_depth", 0),
        custom_system_prompt = prefs.getString("custom_system_prompt", "") ?: "",
        conversation_tone = prefs.getString("conversation_tone", "friendly") ?: "friendly",
        show_thinking_process = prefs.getBoolean("show_thinking_process", true),
        auto_expand_thinking = prefs.getBoolean("auto_expand_thinking", false)
    )
}

fun save_ai_settings(context: Context, settings: ai_settings_state) {
    encrypted_prefs(context).edit().apply {
        putString("provider", settings.provider.name)
        putString("base_url", settings.base_url)
        putString("model", settings.model)
        putString("api_key", settings.api_key)
        putString("api_keys_json", settings_gson.toJson(settings.api_keys.mapKeys { it.key.name }))
        putString("instances_json", settings_gson.toJson(JsonArray().apply {
            settings.instances.forEach { inst -> add(instance_to_json(inst)) }
        }))
        putString("active_instance_id", settings.active_instance_id)
        putString("custom_models_json", settings_gson.toJson(settings.custom_models))
        putInt("max_context_chars", settings.max_context_chars)
        putBoolean("auto_compact", settings.auto_compact)
        putInt("compact_threshold_percent", settings.compact_threshold_percent)
        putBoolean("enable_tools", settings.enable_tools)
        putBoolean("enable_bash", settings.enable_bash)
        putBoolean("enable_write", settings.enable_write)
        putInt("max_agent_iterations", settings.max_agent_iterations)
        putInt("thinking_depth", settings.thinking_depth)
        putString("custom_system_prompt", settings.custom_system_prompt)
        putString("conversation_tone", settings.conversation_tone)
        putBoolean("show_thinking_process", settings.show_thinking_process)
        putBoolean("auto_expand_thinking", settings.auto_expand_thinking)
    }.apply()
    // 同步内存缓存，保证 cached_ai_settings 读到最新值
    ai_settings_memory_cache = settings
}

/** 是否已配置可用（有 base_url + model + api_key） */
fun ai_settings_state.is_configured(): Boolean {
    return base_url.isNotBlank() && model.isNotBlank() && api_key.isNotBlank()
}

// ============ 实例的 JSON 序列化（手写字段，缺字段时取默认值，避免 Gson 绕过构造器的坑） ============

private fun instance_to_json(i: provider_instance): JsonObject {
    val o = JsonObject()
    o.addProperty("id", i.id)
    o.addProperty("label", i.label)
    o.addProperty("provider", i.provider.name)
    o.addProperty("base_url", i.base_url)
    o.addProperty("model", i.model)
    o.addProperty("api_key", i.api_key)
    o.addProperty("enabled", i.enabled)
    o.add("models", JsonArray().apply { i.models.forEach { add(it) } })
    o.add("hidden_models", JsonArray().apply { i.hidden_models.forEach { add(it) } })
    o.add("model_caps", JsonObject().apply {
        i.model_caps.forEach { (model, caps) ->
            add(model, JsonObject().apply {
                addProperty("context_tokens", caps.context_tokens)
                addProperty("image", caps.supports_image)
                addProperty("video", caps.supports_video)
            })
        }
    })
    return o
}

private fun instance_from_json(o: JsonObject): provider_instance? = runCatching {
    provider_instance(
        id = o.get("id")?.takeIf { !it.isJsonNull }?.asString ?: java.util.UUID.randomUUID().toString(),
        label = o.get("label")?.takeIf { !it.isJsonNull }?.asString ?: "",
        provider = ai_provider.valueOf(o.get("provider").asString),
        // 读取时清洗：旧数据里可能存了粘贴带入的首尾空白/换行
        base_url = (o.get("base_url")?.takeIf { !it.isJsonNull }?.asString ?: "").trim(),
        model = (o.get("model")?.takeIf { !it.isJsonNull }?.asString ?: "").trim(),
        api_key = (o.get("api_key")?.takeIf { !it.isJsonNull }?.asString ?: "").filterNot { it.isWhitespace() },
        enabled = o.get("enabled")?.takeIf { !it.isJsonNull }?.asBoolean ?: true,
        models = o.getAsJsonArray("models")?.mapNotNull { e -> e.takeIf { !e.isJsonNull }?.asString } ?: emptyList(),
        hidden_models = o.getAsJsonArray("hidden_models")?.mapNotNull { e -> e.takeIf { !e.isJsonNull }?.asString } ?: emptyList(),
        model_caps = o.getAsJsonObject("model_caps")?.entrySet()?.mapNotNull { (model, elem) ->
            runCatching {
                val c = elem.asJsonObject
                model to model_capabilities(
                    context_tokens = c.get("context_tokens")?.takeIf { !it.isJsonNull }?.asLong ?: 0L,
                    supports_image = c.get("image")?.takeIf { !it.isJsonNull }?.asBoolean ?: false,
                    supports_video = c.get("video")?.takeIf { !it.isJsonNull }?.asBoolean ?: false
                )
            }.getOrNull()
        }?.toMap() ?: emptyMap()
    )
}.getOrNull()

/** 旧单提供商数据 → 多实例：当前提供商一个实例（带当前 base_url/model/key），其余有 key 的供应商各一个 */
private fun migrate_legacy_instances(
    provider: ai_provider,
    base_url: String,
    model: String,
    api_key: String,
    api_keys: Map<ai_provider, String>,
    custom_models: Map<String, List<String>>
): List<provider_instance> {
    val result = mutableListOf<provider_instance>()
    result.add(
        provider_instance(
            id = java.util.UUID.randomUUID().toString(),
            label = provider.display_name,
            provider = provider,
            base_url = base_url.ifBlank { provider.base_url },
            model = model.ifBlank { provider.default_model },
            api_key = api_key,
            models = custom_models[base_url] ?: emptyList()
        )
    )
    for (p in ai_provider.entries) {
        if (p == provider || p == ai_provider.CUSTOM) continue
        val key = api_keys[p]
        if (!key.isNullOrBlank()) {
            result.add(
                provider_instance(
                    id = java.util.UUID.randomUUID().toString(),
                    label = p.display_name,
                    provider = p,
                    base_url = p.base_url,
                    model = p.default_model,
                    api_key = key,
                    models = custom_models[p.base_url] ?: emptyList()
                )
            )
        }
    }
    return result
}
