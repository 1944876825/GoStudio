package com.jmwl.gostudio.ui.screens.ai

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jmwl.gostudio.ai.ai_message
import com.jmwl.gostudio.ai.ai_message_role
import com.jmwl.gostudio.ai.ai_provider
import com.jmwl.gostudio.ai.ai_tool_execution
import com.jmwl.gostudio.ai.ai_tool_status
import com.jmwl.gostudio.ui.theme.app_theme_provider

/**
 * 提供商/模型选择器：顶部 pill（当前模型名 + 上下文用量徽标），点击弹出
 * 底部模型切换面板（搜索过滤 + 按实例分组 + 能力徽标）。
 * 没有任何已配置实例时，点击直接跳设置页。
 * 切换下一轮请求即生效（agent loop 每轮通过 settings_provider 重读设置）。
 *
 * @param current_choice 当前会话生效的模型选择（完整连接快照）
 * @param instances 已配置的提供商实例列表（面板数据源）
 * @param agent_running agent 是否运行中（提示「下一轮生效」）
 * @param context_usage 估算上下文用量 0..1+（徽标显示，超阈值变色）
 * @param on_context_badge_click 用量徽标点击（打开用量详情/压缩菜单）
 */
@Composable
fun ai_model_selector(
    current_choice: com.jmwl.gostudio.ai.ai_model_choice,
    instances: List<com.jmwl.gostudio.ai.provider_instance>,
    on_choice: (com.jmwl.gostudio.ai.ai_model_choice) -> Unit,
    on_open_settings: () -> Unit,
    agent_running: Boolean = false,
    context_usage: Float = 0f,
    on_context_badge_click: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val colors = app_theme_provider.colors
    var sheet_open by remember { mutableStateOf(false) }
    val has_any_configured = instances.any { it.is_ready }

    Row(
        modifier = modifier.clip(RoundedCornerShape(10.dp)).clickable {
            if (has_any_configured) sheet_open = true else on_open_settings()
        }.padding(horizontal = 6.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            text = current_choice.model.ifBlank { current_choice.label.ifBlank { "未配置 AI" } },
            fontSize = 17.sp,
            fontWeight = FontWeight.Bold,
            color = if (has_any_configured) colors.title_large else colors.danger,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false)
        )
        if (has_any_configured) {
            ai_context_usage_badge(usage = context_usage, on_click = on_context_badge_click)
            Icon(Icons.Default.ArrowDropDown, contentDescription = "切换模型", tint = colors.subtitle, modifier = Modifier.size(20.dp))
        } else {
            Spacer(modifier = Modifier.width(4.dp))
            Icon(Icons.Default.Settings, contentDescription = "去设置", tint = colors.subtitle, modifier = Modifier.size(15.dp))
        }
    }

    if (sheet_open) {
        ai_model_selector_sheet(
            current_choice = current_choice,
            instances = instances,
            agent_running = agent_running,
            on_choice = {
                on_choice(it)
                sheet_open = false
            },
            on_open_settings = {
                sheet_open = false
                on_open_settings()
            },
            on_dismiss = { sheet_open = false }
        )
    }
}

/**
 * 上下文用量徽标：显示估算百分比，健康时低调灰、≥80% 黄、≥95% 红。
 * 点击展开用量菜单（查看详情/立即压缩）。
 */
@Composable
fun ai_context_usage_badge(usage: Float, on_click: (() -> Unit)? = null) {
    val colors = app_theme_provider.colors
    val percent = (usage * 100).toInt().coerceIn(0, 999)
    val tint = when {
        usage >= 0.95f -> colors.danger
        usage >= 0.8f -> colors.warning
        else -> colors.subtitle.copy(alpha = 0.8f)
    }
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(9.dp))
            .background(tint.copy(alpha = 0.12f))
            .then(if (on_click != null) Modifier.clickable { on_click() } else Modifier)
            .padding(horizontal = 6.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        Icon(
            Icons.Default.DataUsage,
            contentDescription = "上下文用量",
            tint = tint,
            modifier = Modifier.size(11.dp)
        )
        Text(
            text = "$percent%",
            fontSize = 10.sp,
            fontWeight = FontWeight.Medium,
            color = tint
        )
    }
}

/** 模型选择面板数据：实例 + 该实例的可选模型 */
private data class ai_model_group(
    val instance: com.jmwl.gostudio.ai.provider_instance,
    val models: List<String>
)

/**
 * 底部模型切换面板：搜索过滤 + 按提供商实例分组 + 能力徽标 + 当前高亮。
 * 参考 pi 的 model selector（模糊搜索 + provider 前缀匹配）。
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun ai_model_selector_sheet(
    current_choice: com.jmwl.gostudio.ai.ai_model_choice,
    instances: List<com.jmwl.gostudio.ai.provider_instance>,
    agent_running: Boolean,
    on_choice: (com.jmwl.gostudio.ai.ai_model_choice) -> Unit,
    on_open_settings: () -> Unit,
    on_dismiss: () -> Unit
) {
    val colors = app_theme_provider.colors
    var query by remember { mutableStateOf("") }

    // 只显示配置完整的实例；每个实例的可选模型 = 拉取缓存 + 预置列表去重
    val groups = remember(instances) {
        instances.filter { it.is_ready }.map { inst ->
            ai_model_group(instance = inst, models = inst.selectable_models())
        }
    }
    val filtered = remember(groups, query) {
        if (query.isBlank()) groups
        else groups.map { g ->
            g.copy(models = g.models.filter {
                it.contains(query, ignoreCase = true) ||
                    g.instance.label.contains(query, ignoreCase = true) ||
                    g.instance.provider.display_name.contains(query, ignoreCase = true)
            })
        }.filter { it.models.isNotEmpty() || it.instance.label.contains(query, ignoreCase = true) }
    }

    androidx.compose.material3.ModalBottomSheet(
        onDismissRequest = on_dismiss,
        containerColor = colors.gradient_start,
        sheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).navigationBarsPadding()) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "切换模型",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = colors.title_large,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = on_open_settings) {
                    Text("管理提供商", fontSize = 12.sp, color = colors.title_highlight)
                }
            }
            // 搜索框
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                placeholder = { Text("搜索模型或提供商…", fontSize = 13.sp, color = colors.input_hint) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = colors.subtitle, modifier = Modifier.size(17.dp)) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { query = "" }, modifier = Modifier.size(26.dp)) {
                            Icon(Icons.Default.Close, contentDescription = "清空", tint = colors.subtitle, modifier = Modifier.size(15.dp))
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp, color = colors.input_text),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = colors.title_highlight,
                    unfocusedBorderColor = colors.input_border,
                    cursorColor = colors.title_highlight,
                    focusedContainerColor = colors.card_bg,
                    unfocusedContainerColor = colors.card_bg
                )
            )
            // 运行中提示：切换下一轮生效
            if (agent_running) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(colors.warning_bg.copy(alpha = 0.5f))
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(Icons.Default.Info, contentDescription = null, tint = colors.warning, modifier = Modifier.size(13.dp))
                    Text(
                        "Agent 运行中，切换将在下一轮请求生效",
                        fontSize = 11.sp, color = colors.warning
                    )
                }
            }
            // 分组模型列表
            LazyColumn(
                modifier = Modifier.fillMaxWidth().weight(1f, fill = false).heightIn(max = 420.dp),
                contentPadding = PaddingValues(vertical = 6.dp)
            ) {
                if (filtered.isEmpty()) {
                    item {
                        Text(
                            "没有匹配的模型",
                            fontSize = 12.sp, color = colors.subtitle,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 20.dp).wrapContentWidth(Alignment.CenterHorizontally)
                        )
                    }
                }
                filtered.forEach { group ->
                    item(key = "group-${group.instance.id}") {
                        Column(modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Box(
                                    modifier = Modifier.size(7.dp).background(
                                        color = if (group.instance.is_ready) colors.success else colors.subtitle,
                                        shape = androidx.compose.foundation.shape.CircleShape
                                    )
                                )
                                Text(
                                    group.instance.label.ifBlank { group.instance.provider.display_name },
                                    fontSize = 12.sp, fontWeight = FontWeight.Medium,
                                    color = colors.card_text_title
                                )
                                Text(
                                    host_of(group.instance.base_url),
                                    fontSize = 10.sp, color = colors.subtitle,
                                    maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }
                    items(group.models.size, key = { i -> "m-${group.instance.id}-${group.models[i]}" }) { i ->
                        val model = group.models[i]
                        val selected = group.instance.provider == current_choice.provider &&
                            model == current_choice.model && group.instance.base_url == current_choice.base_url
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable {
                                    on_choice(
                                        com.jmwl.gostudio.ai.ai_model_choice(
                                            provider = group.instance.provider,
                                            model = model,
                                            base_url = group.instance.base_url,
                                            api_key = group.instance.api_key,
                                            label = group.instance.label
                                        )
                                    )
                                }
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                model,
                                fontSize = 13.sp,
                                fontFamily = FontFamily.Monospace,
                                color = if (selected) colors.title_highlight else colors.card_text_title,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                            )
                            // 能力徽标：上下文长度 + 多模态
                            group.instance.model_caps[model]?.let { caps ->
                                if (caps.context_tokens > 0) {
                                    ai_model_cap_chip(text = format_token_count(caps.context_tokens))
                                }
                                if (caps.is_multimodal) {
                                    ai_model_cap_chip(text = "多模态")
                                }
                            }
                            if (selected) {
                                Icon(Icons.Default.Check, contentDescription = "当前模型", tint = colors.title_highlight, modifier = Modifier.size(16.dp))
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

/** 能力徽标 chip（上下文长度/多模态） */
@Composable
private fun ai_model_cap_chip(text: String) {
    val colors = app_theme_provider.colors
    Text(
        text = text,
        fontSize = 9.sp,
        color = colors.subtitle,
        modifier = Modifier
            .clip(RoundedCornerShape(5.dp))
            .background(colors.editor_bg.copy(alpha = 0.5f))
            .padding(horizontal = 5.dp, vertical = 2.dp)
    )
}

/** base_url → host（组头展示），解析失败返回原串 */
private fun host_of(base_url: String): String = runCatching {
    java.net.URI(base_url.trim()).host ?: base_url
}.getOrDefault(base_url)

/** token 数 → 简短展示（如 128K / 1M） */
internal fun format_token_count(tokens: Long): String = when {
    tokens >= 1_000_000 -> {
        val m = tokens / 1_000_000.0
        if (m >= 10) "${m.toInt()}M" else String.format(java.util.Locale.US, "%.1fM", m)
    }
    tokens >= 1_000 -> "${(tokens / 1_000)}K"
    else -> tokens.toString()
}

/**
 * 单条消息气泡。
 * - user: 右对齐，强调色背景
 * - assistant: 左对齐，卡片背景，支持代码块/流式光标
 * - tool: 不单独显示气泡（归入 assistant 的工具卡片）
 *
 * @param show_thinking 是否展示工具执行卡片（由调用方从设置读一次后传入，避免每条消息都读磁盘）
 * @param on_copy/on_share/on_delete/on_regenerate/on_edit 长按菜单动作；为 null 表示该动作不可用
 */
@Composable
fun ai_message_bubble(
    message: ai_message,
    show_thinking: Boolean = true,
    on_copy: (() -> Unit)? = null,
    on_share: (() -> Unit)? = null,
    on_delete: (() -> Unit)? = null,
    on_regenerate: (() -> Unit)? = null,
    on_edit: ((String) -> Unit)? = null
) {
    // TOOL 结果消息不单独显示气泡：内容已归入其 assistant 消息的工具卡片。
    // 若在这里渲染，工具原始输出（ls 目录列表、文件内容等）会以聊天气泡形式
    // 重复出现，且长按删除会拆散 assistant/tool_calls 配对导致下轮请求被拒。
    if (message.role == ai_message_role.TOOL) return

    val has_visible = message.has_visible_text ||
        (show_thinking && message.tool_executions.isNotEmpty()) ||
        // 流式占位（还没收到首个 token）也要显示气泡，承载「思考中」加载动画
        message.streaming
    if (!has_visible) return

    // 压缩摘要消息：折叠卡片（参考 pi 的 compaction summary message）
    if (message.is_summary) {
        ai_summary_card(message)
        return
    }
    // 系统通知（暂停提示等）：弱化的居中提示条，不带气泡
    if (message.is_system_notice) {
        ai_system_notice(message.text)
        return
    }

    val colors = app_theme_provider.colors
    val is_user = message.role == ai_message_role.USER

    val bubble_content: @Composable () -> Unit = {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 3.dp),
            horizontalArrangement = if (is_user) Arrangement.End else Arrangement.Start
        ) {
            Surface(
                modifier = Modifier.widthIn(max = 320.dp),
                shape = RoundedCornerShape(
                    topStart = 14.dp, topEnd = 14.dp,
                    bottomStart = if (is_user) 14.dp else 4.dp,
                    bottomEnd = if (is_user) 4.dp else 14.dp
                ),
                color = when {
                    message.is_error -> colors.danger_bg.copy(alpha = 0.5f)
                    is_user -> colors.title_highlight.copy(alpha = 0.15f)
                    else -> colors.card_bg
                }
            ) {
                Column(modifier = Modifier.padding(horizontal = 11.dp, vertical = 8.dp)) {
                    // 工具调用卡片（assistant 消息附带，受思考过程开关控制）
                    if (show_thinking && message.tool_executions.isNotEmpty()) {
                        message.tool_executions.forEach { exec ->
                            ai_tool_execution_card(exec)
                            Spacer(modifier = Modifier.height(4.dp))
                        }
                    }
                    // reasoning 思考链卡片（DeepSeek reasoning_content / Anthropic thinking）
                    if (show_thinking && message.reasoning.isNotBlank()) {
                        ai_reasoning_card(message.reasoning, message.streaming)
                        Spacer(modifier = Modifier.height(4.dp))
                    }
                    // 流式中且还没有文本/思考：显示「思考中」加载动画
                    if (message.streaming && !message.has_visible_text && message.reasoning.isBlank()) {
                        ai_thinking_indicator()
                    }
                    // 文本内容：assistant 走 Markdown 渲染；user 按原文显示
                    // （用户输入里的 * # - ` 不应被当成语法格式化）
                    if (message.has_visible_text) {
                        if (is_user) {
                            Text(
                                text = message.text,
                                fontSize = 13.sp,
                                lineHeight = 19.sp,
                                color = if (message.is_error) colors.danger else colors.card_text_title
                            )
                        } else {
                            ai_markdown_text(
                                text = message.text,
                                color = if (message.is_error) colors.danger else colors.card_text_title,
                                streaming = message.streaming
                            )
                        }
                    }
                }
            }
        }
    }

    // 有操作回调时，包一层长按菜单
    if (on_copy != null || on_delete != null) {
        ai_message_with_actions(
            message = message,
            on_copy = { on_copy?.invoke() },
            on_share = { on_share?.invoke() },
            on_delete = { on_delete?.invoke() },
            on_regenerate = { on_regenerate?.invoke() },
            on_edit = on_edit,
            content = bubble_content
        )
    } else {
        bubble_content()
    }
}

/**
 * 工具调用卡片（可折叠，展示工具名/状态/结果）。
 */
@Composable
fun ai_tool_execution_card(exec: ai_tool_execution) {
    val colors = app_theme_provider.colors
    var expanded by remember(exec.call.id) { mutableStateOf(false) }

    val (icon, tint) = when (exec.status) {
        ai_tool_status.RUNNING -> Icons.Default.HourglassEmpty to colors.warning
        ai_tool_status.ERROR -> Icons.Default.ErrorOutline to colors.danger
        ai_tool_status.DONE -> Icons.Default.CheckCircle to colors.success
        else -> Icons.Default.PlayCircle to colors.subtitle
    }

    Surface(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
            .clickable { expanded = !expanded },
        color = colors.dialog_clone_bg.copy(alpha = 0.5f)
    ) {
        Column(modifier = Modifier.padding(horizontal = 9.dp, vertical = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(13.dp))
                Text(
                    text = tool_display_name(exec.call.name),
                    fontSize = 11.sp, fontWeight = FontWeight.Medium,
                    color = colors.card_text_title
                )
                Text(
                    text = when (exec.status) {
                        ai_tool_status.RUNNING -> "执行中…"
                        ai_tool_status.ERROR -> "失败"
                        ai_tool_status.DONE -> "完成"
                        else -> ""
                    },
                    fontSize = 10.sp, color = tint
                )
                Spacer(Modifier.weight(1f))
                Icon(
                    if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null, tint = colors.subtitle, modifier = Modifier.size(13.dp)
                )
            }
            AnimatedVisibility(visible = expanded, enter = expandVertically(), exit = shrinkVertically()) {
                Column(modifier = Modifier.padding(top = 6.dp)) {
                    // 工具参数
                    Text(
                        text = exec.call.arguments_json.take(300),
                        fontSize = 9.5.sp, fontFamily = FontFamily.Monospace,
                        color = colors.card_text_subtitle,
                        modifier = Modifier.fillMaxWidth().background(colors.editor_bg.copy(alpha = 0.4f)).padding(6.dp)
                    )
                    // 结果
                    if (exec.result.isNotBlank() || exec.error_message != null) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = exec.to_result_content().take(2000),
                            fontSize = 9.5.sp, fontFamily = FontFamily.Monospace,
                            color = if (exec.error_message != null) colors.danger else colors.card_text_subtitle,
                            // heightIn 必须在 verticalScroll 外层：这里处于 expandVertically 内，
                            // 动画会用无界高度测量内容，先滚动后限高会让 scrollable 拿到 infinity 直接崩溃
                            modifier = Modifier.fillMaxWidth().heightIn(max = 180.dp).verticalScroll(rememberScrollState())
                                .background(colors.editor_bg.copy(alpha = 0.4f)).padding(6.dp)
                        )
                    }
                }
            }
        }
    }
}

/** 工具名的中文展示 */
private fun tool_display_name(name: String): String = when (name) {
    "read" -> "📖 读取文件"
    "write" -> "✏️ 写入文件"
    "edit" -> "🔧 修改代码"
    "bash" -> "▶️ 执行命令"
    "grep" -> "🔍 搜索"
    "ls" -> "📁 列目录"
    else -> "🔨 $name"
}

/**
 * @deprecated 由 [ai_markdown_text] 取代（完整 Markdown 渲染）。保留空壳避免外部引用断裂。
 */
@Composable
fun ai_text_with_code_blocks(text: String, color: Color, streaming: Boolean) {
    ai_markdown_text(text = text, color = color, streaming = streaming)
}

/**
 * reasoning 思考链卡片（可折叠）。流式中默认展开，结束后默认折叠。
 */
@Composable
fun ai_reasoning_card(reasoning: String, streaming: Boolean) {
    val colors = app_theme_provider.colors
    val context = androidx.compose.ui.platform.LocalContext.current
    val auto_expand = remember { com.jmwl.gostudio.ai.load_ai_settings(context).auto_expand_thinking }
    // key 含 streaming：流式结束（true→false）时重置回「默认折叠」，与注释声明一致；
    // 只用 isNotEmpty 做 key 时结束后会一直保持展开
    var expanded by remember(reasoning.isNotEmpty(), streaming) { mutableStateOf(streaming || auto_expand) }

    Surface(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
            .clickable { expanded = !expanded },
        color = colors.editor_bg.copy(alpha = 0.4f)
    ) {
        Column(modifier = Modifier.padding(horizontal = 9.dp, vertical = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                Text("💭", fontSize = 11.sp)
                Text(
                    text = if (streaming) "思考中…" else "思考过程",
                    fontSize = 11.sp, fontWeight = FontWeight.Medium,
                    color = colors.subtitle
                )
                Spacer(Modifier.weight(1f))
                Icon(
                    if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null, tint = colors.subtitle, modifier = Modifier.size(13.dp)
                )
            }
            AnimatedVisibility(visible = expanded, enter = expandVertically(), exit = shrinkVertically()) {
                Text(
                    text = reasoning,
                    fontSize = 10.5.sp,
                    fontFamily = FontFamily.Monospace,
                    color = colors.subtitle,
                    lineHeight = 15.sp,
                    modifier = Modifier.padding(top = 5.dp).heightIn(max = 200.dp).verticalScroll(rememberScrollState())
                )
            }
        }
    }
}

/**
 * 「思考中…」加载指示器：圆形进度 + 动态省略号。
 * 用于发送后等待首个 token、以及 agent 轮次之间的等待反馈。
 */
@Composable
fun ai_thinking_indicator() {
    val colors = app_theme_provider.colors
    val transition = rememberInfiniteTransition(label = "thinking")
    val dots by transition.animateFloat(
        initialValue = 0f,
        targetValue = 3f,
        animationSpec = infiniteRepeatable(tween(1200, easing = androidx.compose.animation.core.LinearEasing)),
        label = "dots"
    )
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
        modifier = Modifier.padding(vertical = 3.dp)
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(14.dp),
            strokeWidth = 1.5.dp,
            color = colors.title_highlight,
            trackColor = colors.title_highlight.copy(alpha = 0.15f)
        )
        Text(
            text = "思考中" + ".".repeat((dots.toInt() % 3) + 1),
            fontSize = 12.sp,
            color = colors.subtitle
        )
    }
}

/**
 * 等待气泡（assistant 样式）：agent 正在运行但还没有流式占位消息时，
 * 在消息流末尾显示，保证从点发送到收到首个 token 全程有加载反馈。
 */
@Composable
fun ai_waiting_bubble() {
    val colors = app_theme_provider.colors
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 3.dp)
    ) {
        Surface(
            shape = RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp, bottomStart = 4.dp, bottomEnd = 14.dp),
            color = colors.card_bg
        ) {
            Column(modifier = Modifier.padding(horizontal = 11.dp, vertical = 8.dp)) {
                ai_thinking_indicator()
            }
        }
    }
}

/**
 * 上下文压缩摘要卡片（参考 pi 的 CompactionSummaryMessageComponent）。
 * 折叠时一行「已压缩 N 条消息 · 约 X tokens」；点击展开 Markdown 摘要。
 */
@Composable
fun ai_summary_card(message: ai_message) {
    val colors = app_theme_provider.colors
    var expanded by remember(message.timestamp) { mutableStateOf(false) }

    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 3.dp)
            .clip(RoundedCornerShape(10.dp))
            .clickable { expanded = !expanded },
        color = colors.dialog_clone_bg.copy(alpha = 0.6f)
    ) {
        Column(modifier = Modifier.padding(horizontal = 11.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(
                    Icons.Default.Compress,
                    contentDescription = null,
                    tint = colors.subtitle,
                    modifier = Modifier.size(14.dp)
                )
                Text(
                    text = "已压缩 ${message.summary_origin_count} 条消息" +
                        (if (message.summary_tokens_before > 0) " · 约 ${format_token_count(message.summary_tokens_before)} tokens" else ""),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    color = colors.subtitle,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (expanded) "收起摘要" else "展开摘要",
                    tint = colors.subtitle,
                    modifier = Modifier.size(14.dp)
                )
            }
            AnimatedVisibility(visible = expanded, enter = expandVertically(), exit = shrinkVertically()) {
                Column(modifier = Modifier.padding(top = 6.dp)) {
                    ai_markdown_text(
                        text = message.text,
                        color = colors.card_text_subtitle,
                        streaming = false
                    )
                }
            }
        }
    }
}

/**
 * 系统通知（暂停提示等）：弱化的居中提示条，不参与长按菜单。
 */
@Composable
fun ai_system_notice(text: String) {
    val colors = app_theme_provider.colors
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(colors.warning_bg.copy(alpha = 0.35f))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Icon(
            Icons.Default.PauseCircle,
            contentDescription = null,
            tint = colors.warning,
            modifier = Modifier.size(13.dp)
        )
        Text(text = text, fontSize = 11.sp, color = colors.subtitle)
    }
}

/**
 * 暂停横幅：agent 处于暂停态时显示在输入区上方，点击「继续」恢复。
 */
@Composable
fun ai_pause_banner(queued_count: Int = 0, on_resume: () -> Unit) {
    val colors = app_theme_provider.colors
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp),
        shape = RoundedCornerShape(12.dp),
        color = colors.warning_bg.copy(alpha = 0.5f)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(Icons.Default.PauseCircle, contentDescription = null, tint = colors.warning, modifier = Modifier.size(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text("已暂停", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = colors.card_text_title)
                Text(
                    text = if (queued_count > 0) "已排队 $queued_count 条消息，恢复后发送" else "当前步骤已完成，输入消息或点继续",
                    fontSize = 10.5.sp, color = colors.subtitle
                )
            }
            TextButton(onClick = on_resume) {
                Icon(Icons.Default.PlayArrow, contentDescription = null, tint = colors.title_highlight, modifier = Modifier.size(15.dp))
                Spacer(Modifier.width(2.dp))
                Text("继续", fontSize = 13.sp, color = colors.title_highlight)
            }
        }
    }
}

/**
 * 上下文压缩进行中指示器：消息流末尾显示（压缩要调一次模型，可能数秒）。
 */
@Composable
fun ai_compacting_indicator() {
    val colors = app_theme_provider.colors
    val transition = androidx.compose.animation.core.rememberInfiniteTransition(label = "compacting")
    val dots by transition.animateFloat(
        initialValue = 0f,
        targetValue = 3f,
        animationSpec = androidx.compose.animation.core.infiniteRepeatable(
            androidx.compose.animation.core.tween(1200, easing = androidx.compose.animation.core.LinearEasing)
        ),
        label = "compact-dots"
    )
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(colors.dialog_clone_bg.copy(alpha = 0.5f))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(13.dp),
            strokeWidth = 1.5.dp,
            color = colors.title_highlight,
            trackColor = colors.title_highlight.copy(alpha = 0.15f)
        )
        Text(
            text = "上下文接近上限，正在压缩历史消息" + ".".repeat((dots.toInt() % 3) + 1),
            fontSize = 11.sp,
            color = colors.subtitle
        )
    }
}

/**
 * 上下文用量详情弹层：徽标点击打开，展示用量/上限和「立即压缩」入口。
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun ai_context_usage_sheet(
    usage: Float,
    limit_chars: Int,
    on_compact_now: () -> Unit,
    on_dismiss: () -> Unit
) {
    val colors = app_theme_provider.colors
    val percent = (usage * 100).toInt().coerceIn(0, 999)
    val used_chars = (usage * limit_chars).toLong()
    val used_tokens = used_chars / 4
    val limit_tokens = limit_chars.toLong() / 4
    val tint = when {
        usage >= 0.95f -> colors.danger
        usage >= 0.8f -> colors.warning
        else -> colors.success
    }

    androidx.compose.material3.ModalBottomSheet(
        onDismissRequest = on_dismiss,
        containerColor = colors.gradient_start,
        sheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("上下文用量", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = colors.title_large)
            // 用量条
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                androidx.compose.material3.LinearProgressIndicator(
                    progress = { usage.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
                    color = tint,
                    trackColor = colors.input_border.copy(alpha = 0.3f)
                )
                Text(
                    text = "约 ${format_token_count(used_tokens)} / ${format_token_count(limit_tokens)} tokens（$percent%）",
                    fontSize = 12.sp, color = colors.subtitle
                )
            }
            Text(
                text = "超过阈值（默认 80%）时会自动把较早的对话压缩成一份结构化摘要，保留最近的消息原文，以继续长会话。",
                fontSize = 11.5.sp, lineHeight = 16.sp, color = colors.subtitle
            )
            Button(
                onClick = {
                    on_compact_now()
                    on_dismiss()
                },
                modifier = Modifier.fillMaxWidth(),
                colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = colors.title_highlight)
            ) {
                Icon(Icons.Default.Compress, contentDescription = null, modifier = Modifier.size(15.dp))
                Spacer(Modifier.width(6.dp))
                Text("立即压缩", fontSize = 13.sp)
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}
