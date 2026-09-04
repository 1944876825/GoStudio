package com.jmwl.gostudio.ui.screens.ai

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Compress
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Label
import androidx.compose.material.icons.filled.ModelTraining
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.UnfoldMore
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Waves
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jmwl.gostudio.ai.ai_client
import com.jmwl.gostudio.ai.ai_provider
import com.jmwl.gostudio.ai.ai_settings_state
import com.jmwl.gostudio.ai.model_capabilities
import com.jmwl.gostudio.ai.provider_instance
import com.jmwl.gostudio.ai.with_active_instance
import com.jmwl.gostudio.ai.with_instance
import com.jmwl.gostudio.ai.without_instance
import com.jmwl.gostudio.ui.toast.app_toast
import com.jmwl.gostudio.ui.theme.app_colors
import com.jmwl.gostudio.ui.components.sub_page_top_bar
import com.jmwl.gostudio.ui.theme.app_theme_provider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * AI 设置主页：菜单式 4 入口（模型配置 / AI 行为 / MCP 配置 / Skill 配置）。
 * 点进入各自子页。子页用 AnimatedVisibility 滑入覆盖。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ai_settings_screen(
    initial: ai_settings_state,
    on_back: () -> Unit,
    on_save: (ai_settings_state) -> Unit,
    on_change: (ai_settings_state) -> Unit,
    project_dir: java.io.File? = null
) {
    val colors = app_theme_provider.colors
    var settings by remember { mutableStateOf(initial) }
    // 子页导航：null=主页，否则为子页标识
    var sub_page by remember { mutableStateOf<String?>(null) }

    // 系统返回键：子页打开时先回 AI 主页，而不是退出整个设置路由
    BackHandler(enabled = sub_page != null) { sub_page = null }

    // 边到边模式下 adjustResize 不压缩窗口，键盘遮挡要靠 imePadding 自己让位
    Box(modifier = Modifier.fillMaxSize().imePadding()) {
        Column(modifier = Modifier.fillMaxSize()) {
            // 固定顶栏：内容上滑时标题与返回键仍可见
            sub_page_top_bar("AI 设置", on_back)

            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
            ) {
            Spacer(modifier = Modifier.height(24.dp))

            // 4 个菜单入口
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp)
                    .clip(RoundedCornerShape(12.dp))
            ) {
                ai_navigation_card(
                    icon = Icons.Default.SmartToy,
                    title = "模型配置",
                    description = run {
                        val active = settings.instances.firstOrNull { it.id == settings.active_instance_id }
                            ?: settings.instances.firstOrNull()
                        val name = active?.label?.ifBlank { active.provider.display_name } ?: settings.provider.display_name
                        "$name · ${settings.model}"
                    },
                    colors = colors,
                    is_top = true,
                    is_bottom = false,
                    trailing = { ai_chevron(colors) },
                    on_click = { sub_page = "model" }
                )
                ai_group_divider()
                ai_navigation_card(
                    icon = Icons.Default.Psychology,
                    title = "AI 行为",
                    description = "思考深度、提示词、语气、思考过程",
                    colors = colors,
                    is_top = false,
                    is_bottom = false,
                    trailing = { ai_chevron(colors) },
                    on_click = { sub_page = "behavior" }
                )
                ai_group_divider()
                ai_navigation_card(
                    icon = Icons.Default.Hub,
                    title = "MCP 配置",
                    description = "外部工具服务器",
                    colors = colors,
                    is_top = false,
                    is_bottom = false,
                    trailing = { ai_chevron(colors) },
                    on_click = { sub_page = "mcp" }
                )
                ai_group_divider()
                ai_navigation_card(
                    icon = Icons.Default.AutoAwesome,
                    title = "Skill 配置",
                    description = "技能管理与创建",
                    colors = colors,
                    is_top = false,
                    is_bottom = true,
                    trailing = { ai_chevron(colors) },
                    on_click = { sub_page = "skill" }
                )
            }

            Spacer(modifier = Modifier.height(40.dp))
            }
        }

        // 子页覆盖层
        sub_page?.let { page ->
            androidx.compose.animation.AnimatedVisibility(
                visible = true,
                enter = androidx.compose.animation.slideInHorizontally(initialOffsetX = { it }) +
                    androidx.compose.animation.fadeIn(),
                exit = androidx.compose.animation.slideOutHorizontally(targetOffsetX = { it }) +
                    androidx.compose.animation.fadeOut()
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(colors.editor_bg)
                ) {
                    when (page) {
                        "model" -> ai_model_settings_screen(
                            initial = settings,
                            on_back = { sub_page = null },
                            on_change = { new_settings ->
                                settings = new_settings
                                // 模型配置是“变更即存”：只持久化，不退出设置页。
                                on_change(new_settings)
                            }
                        )
                        "behavior" -> ai_behavior_settings_screen(
                            initial = settings,
                            on_back = { sub_page = null },
                            on_save = { new_settings ->
                                settings = new_settings
                                on_save(new_settings)
                                sub_page = null
                            }
                        )
                        "mcp" -> ai_mcp_settings_screen(
                            project_dir = project_dir,
                            on_back = { sub_page = null }
                        )
                        "skill" -> ai_skill_settings_screen(
                            project_dir = project_dir,
                            on_back = { sub_page = null }
                        )
                    }
                }
            }
        }
    }
}

/** 菜单卡片的尾部箭头 */
@Composable
private fun ai_chevron(colors: app_colors) {
    Icon(
        Icons.AutoMirrored.Filled.KeyboardArrowRight,
        contentDescription = "进入",
        tint = colors.card_chevron,
        modifier = Modifier.size(18.dp)
    )
}

// ==================== 子页：模型配置 ====================
// 交互结构逐段移植 OpenMinis Providers：按类型分组的实例列表 + Form 式分区详情页 + 三步添加向导。
// 视觉沿用 GoStudio 主题；功能按现有能力裁剪（仅 API Key 认证，无 OAuth/文件导入）。

/** 顶栏：返回 + 标题 + 尾部动作（列表页的 +、向导的 ✕） */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun om_top_bar(
    colors: app_colors,
    title: String,
    on_back: () -> Unit,
    trailing: (@Composable () -> Unit)? = null
) {
    TopAppBar(
        title = {
            Text(title, color = colors.title_large, fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        navigationIcon = {
            IconButton(onClick = on_back) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回", tint = colors.top_button_icon)
            }
        },
        actions = { trailing?.invoke() },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ai_model_settings_screen(
    initial: ai_settings_state,
    on_back: () -> Unit,
    on_change: (ai_settings_state) -> Unit
) {
    val colors = app_theme_provider.colors
    var settings by remember { mutableStateOf(initial) }
    // 覆盖层：实例详情页 / 三步添加向导
    var detail_id by remember { mutableStateOf<String?>(null) }
    var add_open by remember { mutableStateOf(false) }
    var pending_delete by remember { mutableStateOf<provider_instance?>(null) }

    // 系统返回键：详情覆盖层打开时先关详情（向导覆盖层在 ai_add_provider_flow 内部自己处理逐步返回）
    BackHandler(enabled = detail_id != null && !add_open) { detail_id = null }

    // 变更即存（与 OpenMinis 一致：每次改动直接落盘，无底部保存条）
    fun commit(next: ai_settings_state) {
        settings = next
        on_change(next)
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            // 固定顶栏：列表上滑时「添加提供商」仍可见
            om_top_bar(
                colors = colors, title = "模型配置", on_back = on_back,
                trailing = {
                    IconButton(onClick = { add_open = true }) {
                        Icon(Icons.Default.Add, contentDescription = "添加提供商", tint = colors.top_button_icon)
                    }
                }
            )

            Column(
                modifier = Modifier.weight(1f).verticalScroll(rememberScrollState())
            ) {
            if (settings.instances.isEmpty()) {
                // 空态（对应 OpenMinis "No providers configured"）
                Column(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 56.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(Icons.Default.Key, contentDescription = null, tint = colors.subtitle.copy(alpha = 0.35f), modifier = Modifier.size(34.dp))
                    Text("未配置提供商", fontSize = 13.sp, color = colors.subtitle)
                    Text("添加一个提供商开始使用", fontSize = 11.sp, color = colors.subtitle.copy(alpha = 0.7f))
                }
            } else {
                // 按提供商类型分组（对应 OpenMinis 按 ProviderType 分 Section）
                ai_provider.entries.forEach { p ->
                    val of_type = settings.instances.filter { it.provider == p }
                    if (of_type.isEmpty()) return@forEach
                    ai_group_title(colors = colors, title = p.display_name)
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp).clip(RoundedCornerShape(12.dp))
                    ) {
                        of_type.forEachIndexed { index, inst ->
                            if (index > 0) ai_group_divider()
                            ai_provider_instance_row(
                                instance = inst,
                                is_active = inst.id == settings.active_instance_id,
                                colors = colors,
                                on_open = { detail_id = inst.id },
                                on_set_active = { commit(settings.with_active_instance(inst.id)) }
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Agent 能力（全局开关）
            ai_group_title(colors = colors, title = "Agent 能力")
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp).clip(RoundedCornerShape(12.dp))
            ) {
                ai_switch_card(icon = Icons.Default.AutoAwesome, title = "工具调用", description = "让 AI 能读写文件、执行命令", checked = settings.enable_tools, colors = colors, is_top = true, is_bottom = false) { commit(settings.copy(enable_tools = it)) }
                ai_group_divider()
                ai_switch_card(icon = Icons.Default.Terminal, title = "执行命令", description = "允许 bash / go build 等命令", checked = settings.enable_bash, colors = colors, is_top = false, is_bottom = false) { commit(settings.copy(enable_bash = it)) }
                ai_group_divider()
                ai_switch_card(icon = Icons.Default.Edit, title = "修改文件", description = "允许写入 / 修改项目文件", checked = settings.enable_write, colors = colors, is_top = false, is_bottom = true) { commit(settings.copy(enable_write = it)) }
            }

            Spacer(modifier = Modifier.height(96.dp))
            }
        }

        // 实例详情覆盖层
        detail_id?.let { id ->
            settings.instances.firstOrNull { it.id == id }?.let { inst ->
                androidx.compose.animation.AnimatedVisibility(
                    visible = true,
                    enter = androidx.compose.animation.slideInHorizontally(initialOffsetX = { it }) +
                        androidx.compose.animation.fadeIn(),
                    exit = androidx.compose.animation.slideOutHorizontally(targetOffsetX = { it }) +
                        androidx.compose.animation.fadeOut()
                ) {
                    Box(modifier = Modifier.fillMaxSize().background(colors.editor_bg)) {
                        ai_provider_detail_screen(
                            instance = inst,
                            is_active = inst.id == settings.active_instance_id,
                            on_back = { detail_id = null },
                            on_set_active = { commit(settings.with_active_instance(id)) },
                            on_update = { updated -> commit(settings.with_instance(updated)) },
                            on_delete = { pending_delete = inst }
                        )
                    }
                }
            }
        }

        // 三步添加向导覆盖层
        androidx.compose.animation.AnimatedVisibility(
            visible = add_open,
            enter = androidx.compose.animation.slideInHorizontally(initialOffsetX = { it }) +
                androidx.compose.animation.fadeIn(),
            exit = androidx.compose.animation.slideOutHorizontally(targetOffsetX = { it }) +
                androidx.compose.animation.fadeOut()
        ) {
            Box(modifier = Modifier.fillMaxSize().background(colors.editor_bg)) {
                ai_add_provider_flow(
                    colors = colors,
                    existing = settings.instances,
                    on_close = { add_open = false },
                    on_created = { inst ->
                        commit(settings.with_instance(inst))
                        add_open = false
                        detail_id = inst.id
                    }
                )
            }
        }
    }

    // 删除确认
    pending_delete?.let { inst ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { pending_delete = null },
            containerColor = colors.dialog_bg,
            title = { Text("删除提供商", color = colors.dialog_text) },
            text = { Text("确定删除「${inst.label.ifBlank { inst.provider.display_name }}」？密钥与模型列表将一并清除。", fontSize = 13.sp, color = colors.subtitle) },
            confirmButton = {
                TextButton(onClick = {
                    commit(settings.without_instance(inst.id))
                    pending_delete = null
                    detail_id = null
                }) { Text("删除", color = colors.danger) }
            },
            dismissButton = { TextButton(onClick = { pending_delete = null }) { Text("取消", color = colors.subtitle) } }
        )
    }
}

/** 实例列表行（对应 OpenMinis InstanceRow：状态点 + 名称 + 凭证摘要 + 模型数 + 停用徽标） */
@Composable
private fun ai_provider_instance_row(
    instance: provider_instance,
    is_active: Boolean,
    colors: app_colors,
    on_open: () -> Unit,
    on_set_active: () -> Unit
) {
    val interaction_source = remember { MutableInteractionSource() }
    val is_pressed by interaction_source.collectIsPressedAsState()
    val background_color = if (is_pressed) colors.card_pressed else colors.card_bg

    Row(
        modifier = Modifier.fillMaxWidth().background(background_color)
            .clickable(interactionSource = interaction_source, indication = null, onClick = on_open)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // 状态点：绿 = 已配置且启用
        Box(
            modifier = Modifier.size(8.dp).clip(CircleShape).background(
                if (instance.is_ready) colors.success else colors.card_text_subtitle.copy(alpha = 0.4f)
            )
        )
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                instance.label.ifBlank { instance.provider.display_name },
                fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                color = if (instance.enabled) colors.card_text_title else colors.card_text_subtitle,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                Text("API Key", fontSize = 10.sp, color = colors.card_text_subtitle)
                Text("·", fontSize = 10.sp, color = colors.card_text_subtitle.copy(alpha = 0.6f))
                Text(instance.masked_key(), fontSize = 10.sp, color = colors.card_text_subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text("${instance.selectable_models().size} 个模型", fontSize = 10.sp, color = colors.card_text_subtitle.copy(alpha = 0.75f))
        }
        if (is_active) {
            ai_instance_pill(text = "使用中", colors = colors, active = true)
        } else if (instance.is_ready) {
            Box(
                modifier = Modifier.clip(RoundedCornerShape(999.dp))
                    .background(colors.title_highlight.copy(alpha = 0.14f))
                    .clickable(onClick = on_set_active)
                    .padding(horizontal = 10.dp, vertical = 4.dp)
            ) {
                Text("使用", fontSize = 10.sp, fontWeight = FontWeight.SemiBold, color = colors.title_highlight)
            }
        }
        if (!instance.enabled) {
            ai_instance_pill(text = "已停用", colors = colors, active = false)
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "配置", tint = colors.card_chevron, modifier = Modifier.size(18.dp))
    }
}

/** 小徽标（使用中 / 已停用） */
@Composable
private fun ai_instance_pill(text: String, colors: app_colors, active: Boolean) {
    Box(
        modifier = Modifier.clip(RoundedCornerShape(999.dp))
            .background(
                if (active) colors.success.copy(alpha = 0.16f)
                else colors.card_text_subtitle.copy(alpha = 0.14f)
            )
            .padding(horizontal = 8.dp, vertical = 3.dp)
    ) {
        Text(text, fontSize = 9.sp, fontWeight = FontWeight.SemiBold, color = if (active) colors.success else colors.card_text_subtitle)
    }
}

/** 实例详情页（对应 OpenMinis ProviderInstanceDetailView 的分区顺序：标签/凭证/接口/状态/模型/删除） */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ai_provider_detail_screen(
    instance: provider_instance,
    is_active: Boolean,
    on_back: () -> Unit,
    on_set_active: () -> Unit,
    on_update: (provider_instance) -> Unit,
    on_delete: () -> Unit
) {
    val colors = app_theme_provider.colors
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var key_visible by remember { mutableStateOf(false) }
    var model_menu_open by remember { mutableStateOf(false) }
    var fetching_models by remember { mutableStateOf(false) }
    // 模型列表脚注（成功=来源 / 失败=诊断，对应 OpenMinis Models section footer）
    var fetch_note by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
    var testing by remember { mutableStateOf(false) }
    // 连接测试结果：(成功?, 回复或错误信息, 耗时ms)
    var test_result by remember { mutableStateOf<Triple<Boolean, String, Long>?>(null) }
    var add_model_open by remember { mutableStateOf(false) }
    var add_model_input by remember { mutableStateOf("") }
    // 正在编辑能力的模型 id（null=关闭弹窗）
    var caps_edit_model by remember { mutableStateOf<String?>(null) }

    fun update(transform: (provider_instance) -> provider_instance) = on_update(transform(instance))

    fun temp_settings() = ai_settings_state(
        provider = instance.provider,
        base_url = instance.base_url,
        model = instance.model,
        api_key = instance.api_key
    )

    fun refresh_models() {
        if (instance.api_key.isBlank()) {
            app_toast.show(context, "请先填写 API Key", app_toast.LENGTH_SHORT)
            return
        }
        if (instance.base_url.isBlank()) {
            app_toast.show(context, "请先填写 Base URL", app_toast.LENGTH_SHORT)
            return
        }
        fetching_models = true
        fetch_note = null
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { ai_client(temp_settings()).fetch_models() }
            }
            fetching_models = false
            result.onSuccess { models ->
                if (models.isEmpty()) {
                    fetch_note = false to "端点返回了空列表，可手动添加模型 ID"
                } else {
                    fetch_note = true to "已从 /models 获取 ${models.size} 个模型"
                    update { it.copy(models = models) }
                }
            }.onFailure { e ->
                fetch_note = false to "获取失败: ${e.message ?: e.javaClass.simpleName}"
            }
        }
    }

    fun run_test() {
        if (!instance.is_ready) {
            app_toast.show(context, "请先填写 Base URL / 模型 / API Key", app_toast.LENGTH_SHORT)
            return
        }
        testing = true
        test_result = null
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { ai_client(temp_settings()).quick_test() }
            }
            testing = false
            result.onSuccess { (reply, ms) ->
                test_result = Triple(true, reply.ifBlank { "(空回复)" }, ms)
            }.onFailure { e ->
                test_result = Triple(false, e.message ?: e.javaClass.simpleName, 0L)
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            // 固定顶栏：长表单上滑时标题与返回仍可见（本页变更即存，无保存按钮）
            ai_sub_page_header(colors = colors, title = instance.label.ifBlank { "提供商" }, on_back = on_back)

            Column(
                modifier = Modifier.weight(1f).verticalScroll(rememberScrollState())
            ) {
            Spacer(modifier = Modifier.height(24.dp))

            if (!is_active) {
                Box(modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp)) {
                    ai_save_button(colors = colors, label = "设为当前使用") { on_set_active() }
                }
                Spacer(modifier = Modifier.height(18.dp))
            }

            // ===== 标签 =====
            ai_group_title(colors = colors, title = "标签")
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp).clip(RoundedCornerShape(12.dp))
            ) {
                ai_input_card(icon = Icons.Default.Label, title = "实例名称", colors = colors, is_top = true, is_bottom = true) {
                    OutlinedTextField(
                        value = instance.label,
                        onValueChange = { text -> update { it.copy(label = text) } },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text(instance.provider.display_name, color = colors.input_hint, fontSize = 13.sp) },
                        singleLine = true, shape = RoundedCornerShape(0.dp), colors = field_colors(colors)
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // ===== 凭证 =====
            ai_group_title(colors = colors, title = "凭证")
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp).clip(RoundedCornerShape(12.dp))
            ) {
                ai_input_card(icon = Icons.Default.Key, title = "API Key", colors = colors, is_top = true, is_bottom = true) {
                    OutlinedTextField(
                        value = instance.api_key,
                        onValueChange = { text -> update { it.copy(api_key = strip_whitespace(text)) } },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("sk-...", color = colors.input_hint, fontSize = 13.sp) },
                        singleLine = true,
                        visualTransformation = if (key_visible) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = {
                            Box(modifier = Modifier.size(28.dp).clip(CircleShape).clickable { key_visible = !key_visible }, contentAlignment = Alignment.Center) {
                                Icon(if (key_visible) Icons.Default.VisibilityOff else Icons.Default.Visibility, contentDescription = if (key_visible) "隐藏" else "显示", tint = colors.subtitle, modifier = Modifier.size(16.dp))
                            }
                        },
                        shape = RoundedCornerShape(0.dp), colors = field_colors(colors)
                    )
                }
            }
            ai_hint_text(colors = colors, text = "密钥已加密存储在设备本地，不会离开设备", success = true)

            Spacer(modifier = Modifier.height(14.dp))

            // ===== 接口 =====
            ai_group_title(colors = colors, title = "接口")
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp).clip(RoundedCornerShape(12.dp))
            ) {
                ai_input_card(icon = Icons.Default.Cloud, title = "Base URL", colors = colors, is_top = true, is_bottom = false) {
                    OutlinedTextField(
                        value = instance.base_url,
                        onValueChange = { text -> update { it.copy(base_url = strip_whitespace(text)) } },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text(instance.provider.base_url.ifBlank { "https://api.example.com/v1" }, color = colors.input_hint, fontSize = 13.sp) },
                        singleLine = true, shape = RoundedCornerShape(0.dp), colors = field_colors(colors)
                    )
                }
                ai_group_divider()
                ai_input_card(icon = Icons.Default.ModelTraining, title = "默认模型", colors = colors, is_top = false, is_bottom = true) {
                    OutlinedTextField(
                        value = instance.model,
                        onValueChange = { text -> update { it.copy(model = strip_whitespace(text)) } },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text(instance.provider.default_model.ifBlank { "如 glm-4.6、deepseek-chat" }, color = colors.input_hint, fontSize = 13.sp) },
                        singleLine = true, shape = RoundedCornerShape(0.dp),
                        trailingIcon = {
                            if (instance.provider.supports_model_list || instance.models.isNotEmpty()) {
                                Box {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Default.ArrowDropDown, contentDescription = "选择模型", tint = colors.subtitle,
                                            modifier = Modifier.size(22.dp).clip(CircleShape).clickable { model_menu_open = true })
                                        if (instance.provider.supports_model_list) {
                                            Spacer(modifier = Modifier.width(6.dp))
                                            if (fetching_models) {
                                                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = colors.title_highlight)
                                            } else {
                                                Icon(Icons.Default.Refresh, contentDescription = "获取模型列表", tint = colors.title_highlight,
                                                    modifier = Modifier.size(18.dp).clip(CircleShape).clickable { refresh_models() })
                                            }
                                        }
                                    }
                                    val candidates = instance.selectable_models()
                                    DropdownMenu(expanded = model_menu_open, onDismissRequest = { model_menu_open = false }) {
                                        if (candidates.isEmpty()) {
                                            DropdownMenuItem(text = { Text("无候选，点刷新获取", color = colors.subtitle) }, onClick = { model_menu_open = false })
                                        } else {
                                            candidates.forEach { m ->
                                                DropdownMenuItem(text = { Text(m, color = colors.dialog_text) }, onClick = {
                                                    update { it.copy(model = m) }
                                                    model_menu_open = false
                                                })
                                            }
                                        }
                                    }
                                }
                            }
                        },
                        colors = field_colors(colors)
                    )
                }
            }
            ai_hint_text(colors = colors, text = if (instance.provider == ai_provider.ANTHROPIC) "Anthropic Messages API 格式（/messages）。" else "OpenAI 兼容格式（/chat/completions）。")

            Spacer(modifier = Modifier.height(14.dp))

            // ===== 状态 =====
            ai_group_title(colors = colors, title = "状态")
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp).clip(RoundedCornerShape(12.dp))
            ) {
                ai_switch_card(icon = Icons.Default.Visibility, title = "启用", description = "停用后不出现在会话选择器中", checked = instance.enabled, colors = colors, is_top = true, is_bottom = true) { checked -> update { it.copy(enabled = checked) } }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // ===== 模型列表（行点击=设为默认；眼睛=隐藏；垃圾桶=移除；组头刷新 + 脚注诊断）=====
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 22.dp, end = 18.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "模型",
                    fontSize = 10.sp, fontWeight = FontWeight.Bold, color = colors.title_highlight,
                    modifier = Modifier.weight(1f)
                )
                Text("${instance.selectable_models().size}", fontSize = 10.sp, color = colors.subtitle)
                Spacer(modifier = Modifier.width(10.dp))
                if (instance.provider.supports_model_list) {
                    Box(
                        modifier = Modifier.size(26.dp).clip(CircleShape).background(colors.card_bg).clickable { refresh_models() },
                        contentAlignment = Alignment.Center
                    ) {
                        if (fetching_models) {
                            CircularProgressIndicator(modifier = Modifier.size(13.dp), strokeWidth = 2.dp, color = colors.title_highlight)
                        } else {
                            Icon(Icons.Default.Refresh, contentDescription = "刷新模型列表", tint = colors.title_highlight, modifier = Modifier.size(14.dp))
                        }
                    }
                }
            }
            val candidates = instance.selectable_models()
            if (candidates.isEmpty()) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp).clip(RoundedCornerShape(12.dp)).background(colors.card_bg)
                ) {
                    Box(modifier = Modifier.fillMaxWidth().padding(vertical = 20.dp), contentAlignment = Alignment.Center) {
                        Text("暂无模型，刷新获取或手动添加", fontSize = 11.sp, color = colors.subtitle)
                    }
                }
            } else {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp).clip(RoundedCornerShape(12.dp))
                ) {
                    candidates.forEachIndexed { index, m ->
                        if (index > 0) ai_group_divider()
                        val hidden = m in instance.hidden_models
                        val row_interaction = remember { MutableInteractionSource() }
                        val row_pressed by row_interaction.collectIsPressedAsState()
                        Row(
                            modifier = Modifier.fillMaxWidth()
                                .background(if (row_pressed) colors.card_pressed else colors.card_bg)
                                .clickable(interactionSource = row_interaction, indication = null) { update { it.copy(model = m) } }
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    m,
                                    fontSize = 12.sp,
                                    fontWeight = if (m == instance.model) FontWeight.SemiBold else FontWeight.Normal,
                                    color = when {
                                        hidden -> colors.card_text_subtitle.copy(alpha = 0.55f)
                                        m == instance.model -> colors.title_highlight
                                        else -> colors.card_text_title
                                    },
                                    maxLines = 1, overflow = TextOverflow.Ellipsis
                                )
                                if (m == instance.model) {
                                    Text("默认模型", fontSize = 9.sp, color = colors.title_highlight.copy(alpha = 0.8f))
                                }
                                // 能力徽标：上下文长度 / 图片 / 视频（已标注时显示）
                                instance.model_caps[m]?.takeIf { !it.is_empty }?.let { caps ->
                                    Spacer(modifier = Modifier.height(3.dp))
                                    ai_caps_badges(caps, colors)
                                }
                            }
                            // 能力标注：上下文长度 / 多模态输入
                            Box(
                                modifier = Modifier.size(26.dp).clip(CircleShape).clickable { caps_edit_model = m },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    Icons.Default.Tune,
                                    contentDescription = "模型能力",
                                    tint = if (instance.model_caps[m]?.is_empty == false) colors.title_highlight else colors.subtitle,
                                    modifier = Modifier.size(15.dp)
                                )
                            }
                            // 隐藏/恢复（不删除，只是不再出现在候选里）
                            Box(
                                modifier = Modifier.size(26.dp).clip(CircleShape).clickable {
                                    update { it.copy(hidden_models = if (hidden) it.hidden_models - m else it.hidden_models + m) }
                                },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    if (hidden) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                    contentDescription = if (hidden) "恢复" else "隐藏",
                                    tint = if (hidden) colors.subtitle.copy(alpha = 0.5f) else colors.subtitle,
                                    modifier = Modifier.size(15.dp)
                                )
                            }
                            // 移除：拉取的从缓存删除；纯预置的转为隐藏
                            Box(
                                modifier = Modifier.size(26.dp).clip(CircleShape).clickable {
                                    update {
                                        if (m in it.models) it.copy(models = it.models - m)
                                        else it.copy(hidden_models = it.hidden_models + m)
                                    }
                                },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Default.Close, contentDescription = "移除", tint = colors.danger.copy(alpha = 0.8f), modifier = Modifier.size(15.dp))
                            }
                        }
                    }
                }
            }
            fetch_note?.let { (ok, note) ->
                ai_hint_text(colors = colors, text = if (ok) "✓ $note" else "✗ $note", success = ok)
            }
            ai_hint_text(colors = colors, text = "点模型行的调节图标，可标注上下文长度与图片/视频输入能力")
            Spacer(modifier = Modifier.height(10.dp))
            Box(modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp)) {
                ai_add_button(colors = colors, label = "添加自定义模型") { add_model_open = true }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // ===== 连接测试（对应 OpenMinis Quick Test）=====
            ai_group_title(colors = colors, title = "连接测试")
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp).clip(RoundedCornerShape(12.dp)).background(colors.card_bg).padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("发送一条最小对话请求，验证该模型与密钥真实可用", fontSize = 10.sp, color = colors.card_text_subtitle)
                if (testing) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp, color = colors.title_highlight)
                        Text("测试中…", fontSize = 12.sp, color = colors.subtitle)
                    }
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                            .background(colors.title_highlight.copy(alpha = 0.14f))
                            .clickable { run_test() }
                            .padding(vertical = 10.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Bolt, contentDescription = null, tint = colors.title_highlight, modifier = Modifier.size(15.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("测试连接", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = colors.title_highlight)
                    }
                }
                test_result?.let { (ok, message, ms) ->
                    if (ok) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Icon(Icons.Default.Check, contentDescription = null, tint = colors.success, modifier = Modifier.size(14.dp))
                            Text("「$message」 · ${ms}ms", fontSize = 12.sp, color = colors.success, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    } else {
                        Text(message, fontSize = 11.sp, color = colors.danger, maxLines = 4, overflow = TextOverflow.Ellipsis)
                    }
                }
            }

            Spacer(modifier = Modifier.height(22.dp))

            // ===== 删除提供商 =====
            Box(modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp).clip(RoundedCornerShape(12.dp)).background(colors.danger.copy(alpha = 0.10f))) {
                Text(
                    "删除提供商",
                    fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = colors.danger,
                    modifier = Modifier.fillMaxWidth().clickable(onClick = on_delete).padding(vertical = 13.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            }

            Spacer(modifier = Modifier.height(96.dp))
            }
        }
    }

    // 添加自定义模型
    if (add_model_open) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { add_model_open = false },
            containerColor = colors.dialog_bg,
            title = { Text("添加自定义模型", color = colors.dialog_text) },
            text = {
                OutlinedTextField(
                    value = add_model_input,
                    onValueChange = { add_model_input = strip_whitespace(it) },
                    label = { Text("模型 ID") },
                    singleLine = true,
                    colors = field_colors(colors)
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val m = add_model_input.trim()
                    if (m.isNotBlank()) {
                        update { it.copy(models = (it.models + m).distinct(), hidden_models = it.hidden_models - m) }
                    }
                    add_model_input = ""
                    add_model_open = false
                }) { Text("添加", color = colors.title_highlight) }
            },
            dismissButton = {
                TextButton(onClick = {
                    add_model_input = ""
                    add_model_open = false
                }) { Text("取消", color = colors.subtitle) }
            }
        )
    }

    // 模型能力编辑（上下文长度 / 多模态输入）
    caps_edit_model?.let { m ->
        ai_model_caps_dialog(
            colors = colors,
            model_name = m,
            initial = instance.model_caps[m] ?: model_capabilities(),
            on_save = { caps ->
                update {
                    it.copy(model_caps = if (caps.is_empty) it.model_caps - m else it.model_caps + (m to caps))
                }
                caps_edit_model = null
            },
            on_dismiss = { caps_edit_model = null }
        )
    }
}

/** 常用上下文窗口快捷档位（tokens） */
private val context_presets = listOf(
    "8K" to 8_000L, "32K" to 32_000L, "128K" to 128_000L, "256K" to 256_000L, "1M" to 1_000_000L
)

/** token 数格式化为 128K / 1.5M 等短标签 */
private fun format_context_tokens(tokens: Long): String = when {
    tokens >= 1_000_000 -> {
        val m = tokens / 1_000_000.0
        if (m % 1.0 == 0.0) "${m.toLong()}M" else String.format(java.util.Locale.US, "%.1fM", m)
    }
    tokens >= 1_000 -> {
        val k = tokens / 1_000.0
        if (k % 1.0 == 0.0) "${k.toLong()}K" else String.format(java.util.Locale.US, "%.1fK", k)
    }
    else -> tokens.toString()
}

/** 模型能力编辑弹窗：上下文长度（tokens）+ 图片/视频输入开关。/models 端点不返回这些信息，靠手动标注 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ai_model_caps_dialog(
    colors: app_colors,
    model_name: String,
    initial: model_capabilities,
    on_save: (model_capabilities) -> Unit,
    on_dismiss: () -> Unit
) {
    var tokens_text by remember(model_name) {
        mutableStateOf(if (initial.context_tokens > 0) initial.context_tokens.toString() else "")
    }
    var image_on by remember(model_name) { mutableStateOf(initial.supports_image) }
    var video_on by remember(model_name) { mutableStateOf(initial.supports_video) }
    val selected_tokens = tokens_text.toLongOrNull() ?: 0L

    androidx.compose.material3.AlertDialog(
        onDismissRequest = on_dismiss,
        containerColor = colors.dialog_bg,
        title = { Text("模型能力", color = colors.dialog_text) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    model_name,
                    fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                    color = colors.subtitle,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                OutlinedTextField(
                    value = tokens_text,
                    onValueChange = { tokens_text = it.filter { ch -> ch.isDigit() }.take(10) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("上下文长度（tokens）") },
                    placeholder = { Text("留空使用全局默认", fontSize = 13.sp) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    colors = field_colors(colors)
                )
                // 常用窗口长度快捷档位
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    context_presets.forEach { (label, value) ->
                        ai_caps_preset_chip(label, selected = value == selected_tokens, colors = colors) {
                            tokens_text = value.toString()
                        }
                    }
                }
                // 多模态输入
                ai_caps_toggle_row(title = "图片输入", description = "多模态：可接收图片附件", checked = image_on, colors = colors) { image_on = it }
                ai_caps_toggle_row(title = "视频输入", description = "多模态：可接收视频附件", checked = video_on, colors = colors) { video_on = it }
                Text(
                    "上下文长度用于自动压缩过长的历史消息（按 1 token ≈ 4 字符估算）；文本输入为所有模型默认支持。",
                    fontSize = 10.sp, lineHeight = 13.sp, color = colors.subtitle
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                on_save(model_capabilities(
                    context_tokens = selected_tokens,
                    supports_image = image_on,
                    supports_video = video_on
                ))
            }) { Text("保存", color = colors.title_highlight) }
        },
        dismissButton = { TextButton(onClick = on_dismiss) { Text("取消", color = colors.subtitle) } }
    )
}

/** 弹窗里的多模态开关行 */
@Composable
private fun ai_caps_toggle_row(
    title: String,
    description: String,
    checked: Boolean,
    colors: app_colors,
    on_toggle: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
            .background(colors.card_bg)
            .clickable { on_toggle(!checked) }
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontSize = 13.sp, color = colors.card_text_title)
            Text(description, fontSize = 10.sp, color = colors.card_text_subtitle)
        }
        ai_small_switch(checked = checked, colors = colors)
    }
}

/** 快捷档位 chip（选中态高亮） */
@Composable
private fun ai_caps_preset_chip(label: String, selected: Boolean, colors: app_colors, on_click: () -> Unit) {
    Box(
        modifier = Modifier.clip(RoundedCornerShape(999.dp))
            .background(if (selected) colors.title_highlight else colors.title_highlight.copy(alpha = 0.12f))
            .clickable(onClick = on_click)
            .padding(horizontal = 10.dp, vertical = 4.dp)
    ) {
        Text(
            label, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
            color = if (selected) colors.dialog_clone_text else colors.title_highlight
        )
    }
}

/** 模型行下方的徽标组：上下文长度 / 图片 / 视频 */
@Composable
private fun ai_caps_badges(caps: model_capabilities, colors: app_colors) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        if (caps.context_tokens > 0) ai_caps_chip(format_context_tokens(caps.context_tokens), colors)
        if (caps.supports_image) ai_caps_chip("图片", colors)
        if (caps.supports_video) ai_caps_chip("视频", colors)
    }
}

/** 单个能力徽标 */
@Composable
private fun ai_caps_chip(text: String, colors: app_colors) {
    Box(
        modifier = Modifier.clip(RoundedCornerShape(4.dp))
            .background(colors.title_highlight.copy(alpha = 0.12f))
            .padding(horizontal = 4.dp, vertical = 1.dp)
    ) {
        Text(text, fontSize = 9.sp, color = colors.title_highlight)
    }
}

/** 三步添加向导（对应 OpenMinis AddProviderView：选择提供商 → 认证方式 → 配置） */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ai_add_provider_flow(
    colors: app_colors,
    existing: List<provider_instance>,
    on_close: () -> Unit,
    on_created: (provider_instance) -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    // 0=选择提供商 1=认证方式 2=配置
    var step by remember { mutableStateOf(0) }
    var picked_type by remember { mutableStateOf<ai_provider?>(null) }
    var label_input by remember { mutableStateOf("") }
    var label_edited by remember { mutableStateOf(false) }
    var key_input by remember { mutableStateOf("") }
    var key_visible by remember { mutableStateOf(false) }
    var base_url_input by remember { mutableStateOf("") }
    val type = picked_type

    fun default_label(p: ai_provider): String {
        val count = existing.count { it.provider == p }
        return if (count == 0) p.display_name else "${p.display_name} ${count + 1}"
    }

    fun reset_and_back() {
        step = 0
        picked_type = null
        label_input = ""
        label_edited = false
        key_input = ""
        key_visible = false
        base_url_input = ""
    }

    fun finish() {
        val t = type ?: return
        val inst = provider_instance(
            id = java.util.UUID.randomUUID().toString(),
            label = label_input.trim().ifBlank { default_label(t) },
            provider = t,
            base_url = base_url_input.trim().ifBlank { t.base_url },
            model = t.default_model,
            api_key = key_input.trim()
        )
        on_created(inst)
    }

    // 系统返回键与顶栏返回一致：逐步后退（第 2 步→第 1 步→第 0 步→退出向导）
    BackHandler { if (step > 0) reset_and_back() else on_close() }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            om_top_bar(
                colors = colors,
                title = when {
                    type != null -> "配置 ${type.display_name}"
                    step == 1 -> "认证方式"
                    else -> "添加提供商"
                },
                on_back = { if (step > 0) reset_and_back() else on_close() },
                trailing = {
                    // 与 OpenMinis 一致：✕ 只在进入流程的步骤出现（第 0 步仅保留返回/取消，避免两个关闭按钮）
                    if (step > 0) {
                        IconButton(onClick = on_close) {
                            Icon(Icons.Default.Close, contentDescription = "取消", tint = colors.top_button_icon)
                        }
                    }
                }
            )

            AnimatedContent(
                targetState = step,
                transitionSpec = {
                    if (targetState > initialState) {
                        (slideInHorizontally { it } + fadeIn()) togetherWith (slideOutHorizontally { -it } + fadeOut())
                    } else {
                        (slideInHorizontally { -it } + fadeIn()) togetherWith (slideOutHorizontally { it } + fadeOut())
                    }
                },
                label = "addProviderStep"
            ) { current_step ->
                Column(
                    modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                ) {
                    when {
                        // 第一步：选择提供商（对应 typePickerSection）
                        current_step == 0 -> {
                            ai_group_title(colors = colors, title = "选择提供商")
                            Column(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp).clip(RoundedCornerShape(12.dp))
                            ) {
                                ai_add_provider_types.forEachIndexed { index, (p, subtitle) ->
                                    if (index > 0) ai_group_divider()
                                    ai_navigation_card(
                                        icon = add_provider_icon(p),
                                        title = p.display_name,
                                        description = subtitle,
                                        colors = colors,
                                        is_top = index == 0,
                                        is_bottom = index == ai_add_provider_types.lastIndex,
                                        trailing = { ai_chevron(colors) },
                                        on_click = {
                                            picked_type = p
                                            if (!label_edited) label_input = default_label(p)
                                            step = 1
                                        }
                                    )
                                }
                            }
                            ai_hint_text(colors = colors, text = "同一提供商可添加多个实例（如工作账号与个人账号）。")
                        }
                        // 第二步：认证方式（GoStudio 仅支持 API Key，单选项）
                        current_step == 1 -> {
                            val t = type
                            ai_group_title(colors = colors, title = "认证方式")
                            Column(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp).clip(RoundedCornerShape(12.dp))
                            ) {
                                ai_navigation_card(
                                    icon = Icons.Default.Key,
                                    title = "API Key",
                                    description = "使用 ${t?.display_name ?: ""} 账号的 API Key 认证",
                                    colors = colors,
                                    is_top = true,
                                    is_bottom = true,
                                    trailing = { ai_chevron(colors) },
                                    on_click = { step = 2 }
                                )
                            }
                        }
                        // 第三步：配置（对应 configureSection：标签 / API Key / API 地址 / 添加按钮）
                        type != null -> {
                            val t = type
                            ai_group_title(colors = colors, title = "标签")
                            Column(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp).clip(RoundedCornerShape(12.dp))
                            ) {
                                ai_input_card(icon = Icons.Default.Label, title = "提供商名称", colors = colors, is_top = true, is_bottom = true) {
                                    OutlinedTextField(
                                        value = label_input,
                                        onValueChange = {
                                            label_input = it
                                            label_edited = true
                                        },
                                        modifier = Modifier.fillMaxWidth(),
                                        placeholder = { Text(default_label(t), color = colors.input_hint, fontSize = 13.sp) },
                                        singleLine = true, shape = RoundedCornerShape(0.dp), colors = field_colors(colors)
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(14.dp))

                            ai_group_title(colors = colors, title = "API Key")
                            Column(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp).clip(RoundedCornerShape(12.dp))
                            ) {
                                ai_input_card(icon = Icons.Default.Security, title = "密钥", colors = colors, is_top = true, is_bottom = true) {
                                    OutlinedTextField(
                                        value = key_input,
                                        onValueChange = { key_input = strip_whitespace(it) },
                                        modifier = Modifier.fillMaxWidth(),
                                        placeholder = { Text("sk-...", color = colors.input_hint, fontSize = 13.sp) },
                                        singleLine = true,
                                        visualTransformation = if (key_visible) VisualTransformation.None else PasswordVisualTransformation(),
                                        trailingIcon = {
                                            Box(modifier = Modifier.size(28.dp).clip(CircleShape).clickable { key_visible = !key_visible }, contentAlignment = Alignment.Center) {
                                                Icon(if (key_visible) Icons.Default.VisibilityOff else Icons.Default.Visibility, contentDescription = if (key_visible) "隐藏" else "显示", tint = colors.subtitle, modifier = Modifier.size(16.dp))
                                            }
                                        },
                                        shape = RoundedCornerShape(0.dp), colors = field_colors(colors)
                                    )
                                }
                            }
                            ai_hint_text(colors = colors, text = "密钥已加密存储在设备本地，不会离开设备", success = true)

                            Spacer(modifier = Modifier.height(14.dp))

                            ai_group_title(colors = colors, title = "API 地址（可选）")
                            Column(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp).clip(RoundedCornerShape(12.dp))
                            ) {
                                ai_input_card(icon = Icons.Default.Cloud, title = "Base URL", colors = colors, is_top = true, is_bottom = true) {
                                    OutlinedTextField(
                                        value = base_url_input,
                                        onValueChange = { base_url_input = strip_whitespace(it) },
                                        modifier = Modifier.fillMaxWidth(),
                                        placeholder = { Text(t.base_url.ifBlank { "https://api.example.com/v1" }, color = colors.input_hint, fontSize = 13.sp) },
                                        singleLine = true, shape = RoundedCornerShape(0.dp), colors = field_colors(colors)
                                    )
                                }
                            }
                            ai_hint_text(colors = colors, text = "留空使用默认端点，可填兼容中转地址")

                            Spacer(modifier = Modifier.height(20.dp))

                            Box(modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp)) {
                                ai_save_button(colors = colors, label = "添加提供商") {
                                    if (key_input.trim().isBlank()) {
                                        app_toast.show(context, "请先填写 API Key", app_toast.LENGTH_SHORT)
                                    } else {
                                        finish()
                                    }
                                }
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(48.dp))
                }
            }
        }
    }
}

/** 添加向导第一步每个类型的说明（对应 OpenMinis ProviderType.pickerSubtitle） */
private val ai_add_provider_types: List<Pair<ai_provider, String>> = listOf(
    ai_provider.ZHIPU to "智谱开放平台 BigModel · GLM 系列",
    ai_provider.ZHIPU_CODING to "智谱编程套餐（GLM Coding Plan）专用端点",
    ai_provider.DEEPSEEK to "DeepSeek 开放平台 · deepseek-chat / reasoner",
    ai_provider.KIMI to "Moonshot 开放平台 · Kimi 系列",
    ai_provider.OPENAI to "OpenAI 官方或任意兼容中转 · GPT 系列",
    ai_provider.XAI to "xAI 开放平台 · Grok 系列",
    ai_provider.ANTHROPIC to "Anthropic Messages API · Claude 系列",
    ai_provider.CUSTOM to "任意 OpenAI 兼容端点 · 自填 Base URL 与模型"
)

/** 提供商类型的图标（对应 OpenMinis providerIcon，用咱们主题的中性色） */
private fun add_provider_icon(p: ai_provider): ImageVector = when (p) {
    ai_provider.ZHIPU -> Icons.Default.AutoAwesome
    ai_provider.ZHIPU_CODING -> Icons.Default.Code
    ai_provider.DEEPSEEK -> Icons.Default.Waves
    ai_provider.KIMI -> Icons.Default.DarkMode
    ai_provider.OPENAI -> Icons.Default.Public
    ai_provider.XAI -> Icons.Default.Cancel
    ai_provider.ANTHROPIC -> Icons.Default.Star
    ai_provider.CUSTOM -> Icons.Default.Tune
}

// ==================== 子页：AI 行为 ====================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ai_behavior_settings_screen(
    initial: ai_settings_state,
    on_back: () -> Unit,
    on_save: (ai_settings_state) -> Unit
) {
    val colors = app_theme_provider.colors
    var settings by remember { mutableStateOf(initial) }
    val tones = listOf("friendly" to "友好", "professional" to "专业", "concise" to "简洁")
    val depths = listOf(0 to "标准", 1 to "深入", 2 to "极致")

    // 本地副本与进入页面时的初始状态不一致 = 有未保存修改
    val dirty = settings != initial
    var confirm_discard by remember { mutableStateOf(false) }

    // 返回（顶栏返回键）：有未保存修改先确认，避免误触丢改动
    fun back_requested() {
        if (dirty) confirm_discard = true else on_back()
    }

    BackHandler(enabled = dirty) { confirm_discard = true }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            // 固定顶栏 + 右上角保存：内容上滑时保存按钮始终可见；无改动时置灰提示已同步
            om_top_bar(
                colors = colors, title = "AI 行为",
                on_back = { back_requested() },
                trailing = {
                    TextButton(onClick = { on_save(settings) }) {
                        Text(
                            "保存",
                            fontSize = 14.sp,
                            fontWeight = if (dirty) FontWeight.Bold else FontWeight.Normal,
                            color = if (dirty) colors.title_highlight else colors.subtitle
                        )
                    }
                }
            )
            Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            Spacer(modifier = Modifier.height(24.dp))

            // 思考深度
            ai_group_title(colors = colors, title = "思考深度")
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp).clip(RoundedCornerShape(12.dp))) {
                depths.forEachIndexed { index, (value, label) ->
                    if (index > 0) ai_group_divider()
                    ai_radio_card(
                        icon = Icons.Default.Psychology,
                        title = label,
                        description = when (value) { 0 -> "平衡速度与质量（10 轮）"; 1 -> "更深入的多步推理（20 轮）"; else -> "最大推理深度（40 轮）" },
                        selected = settings.thinking_depth == value,
                        colors = colors,
                        is_top = index == 0,
                        is_bottom = index == depths.lastIndex
                    ) {
                        settings = settings.copy(
                            thinking_depth = value,
                            max_agent_iterations = when (value) { 0 -> 10; 1 -> 20; else -> 40 }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(22.dp))

            // 交流语气
            ai_group_title(colors = colors, title = "交流语气")
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp).clip(RoundedCornerShape(12.dp))) {
                tones.forEachIndexed { index, (value, label) ->
                    if (index > 0) ai_group_divider()
                    ai_radio_card(
                        icon = Icons.Default.RecordVoiceOver,
                        title = label,
                        description = when (value) { "friendly" -> "亲切、易懂、带鼓励"; "professional" -> "严谨、专业、重准确"; else -> "精炼、直击要点" },
                        selected = settings.conversation_tone == value,
                        colors = colors,
                        is_top = index == 0,
                        is_bottom = index == tones.lastIndex
                    ) { settings = settings.copy(conversation_tone = value) }
                }
            }

            Spacer(modifier = Modifier.height(22.dp))

            // 自定义提示词
            ai_group_title(colors = colors, title = "自定义提示词")
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp).clip(RoundedCornerShape(12.dp))) {
                ai_input_card(icon = Icons.Default.Edit, title = "追加系统提示", colors = colors, is_top = true, is_bottom = true) {
                    OutlinedTextField(
                        value = settings.custom_system_prompt,
                        onValueChange = { settings = settings.copy(custom_system_prompt = it) },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("追加到默认系统提示之后，例如：始终先解释再改代码", color = colors.input_hint, fontSize = 13.sp) },
                        minLines = 3, maxLines = 6,
                        shape = RoundedCornerShape(0.dp), colors = field_colors(colors)
                    )
                }
            }

            Spacer(modifier = Modifier.height(22.dp))

            // 思考过程
            ai_group_title(colors = colors, title = "思考过程")
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp).clip(RoundedCornerShape(12.dp))) {
                ai_switch_card(icon = Icons.Default.Visibility, title = "显示思考过程", description = "展示 AI 读文件、执行命令等中间步骤", checked = settings.show_thinking_process, colors = colors, is_top = true, is_bottom = false) { settings = settings.copy(show_thinking_process = it) }
                ai_group_divider()
                ai_switch_card(icon = Icons.Default.UnfoldMore, title = "自动展开", description = "思考过程默认展开（否则折叠只显示标题）", checked = settings.auto_expand_thinking, colors = colors, is_top = false, is_bottom = true) { settings = settings.copy(auto_expand_thinking = it) }
            }

            Spacer(modifier = Modifier.height(22.dp))

            // 上下文管理（自动压缩）
            ai_group_title(colors = colors, title = "上下文管理")
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp).clip(RoundedCornerShape(12.dp))) {
                ai_switch_card(
                    icon = Icons.Default.Compress,
                    title = "自动压缩历史",
                    description = "用量超阈值时把较早的对话摘要成检查点，继续长会话",
                    checked = settings.auto_compact,
                    colors = colors,
                    is_top = true,
                    is_bottom = !settings.auto_compact
                ) { settings = settings.copy(auto_compact = it) }
                if (settings.auto_compact) {
                    ai_group_divider()
                    Column(modifier = Modifier.fillMaxWidth().background(colors.card_bg).padding(horizontal = 14.dp, vertical = 8.dp)) {
                        Text("触发阈值", fontSize = 12.sp, color = colors.card_text_title, fontWeight = FontWeight.Medium)
                        Spacer(modifier = Modifier.height(6.dp))
                        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf(70 to "70%", 80 to "80%", 90 to "90%").forEach { (value, label) ->
                                ai_caps_preset_chip(
                                    label = label,
                                    selected = settings.compact_threshold_percent == value,
                                    colors = colors
                                ) { settings = settings.copy(compact_threshold_percent = value) }
                            }
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            "上下文用量超过模型窗口的该比例时触发（模型级上下文标注优先于全局上限）",
                            fontSize = 10.sp, lineHeight = 13.sp, color = colors.card_text_subtitle
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(40.dp))
            }
        }
    }

    // 返回时有未保存修改：保存 / 放弃 二选一（点空白处留在本页）
    if (confirm_discard) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirm_discard = false },
            containerColor = colors.dialog_bg,
            title = { Text("未保存的修改", color = colors.dialog_text) },
            text = { Text("AI 行为的改动尚未保存，离开将丢失。", fontSize = 13.sp, color = colors.subtitle) },
            confirmButton = {
                TextButton(onClick = {
                    confirm_discard = false
                    on_save(settings)
                }) { Text("保存", color = colors.title_highlight) }
            },
            dismissButton = {
                TextButton(onClick = {
                    confirm_discard = false
                    on_back()
                }) { Text("放弃修改", color = colors.danger) }
            }
        )
    }
}

// ==================== 子页占位：MCP / Skill（阶段 2 实现） ====================

// ==================== 子页：MCP 配置（表单 + 原始 JSON 双模式） ====================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ai_mcp_settings_screen(project_dir: java.io.File?, on_back: () -> Unit) {
    val colors = app_theme_provider.colors
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    // 复用 mcp_manager 读配置（无项目时 project_dir=null，只管全局）
    val manager = remember(project_dir) {
        com.jmwl.gostudio.ai.mcp.ai_mcp_manager(project_dir)
    }
    var global_configs by remember { mutableStateOf(manager.list_global_configs()) }
    var project_configs by remember { mutableStateOf(manager.list_project_configs()) }
    var show_raw by remember { mutableStateOf(false) }
    var raw_global by remember { mutableStateOf("") }
    var raw_project by remember { mutableStateOf("") }
    var show_add_dialog by remember { mutableStateOf(false) }
    var add_target_global by remember { mutableStateOf(true) }

    fun reload() {
        global_configs = manager.list_global_configs()
        project_configs = manager.list_project_configs()
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            ai_sub_page_header(colors = colors, title = "MCP 配置", on_back = on_back)
            Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            Spacer(modifier = Modifier.height(16.dp))

            // 模式切换：表单 / 原始 JSON
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ai_mode_tab("表单", !show_raw, colors) { show_raw = false }
                ai_mode_tab("原始 JSON", show_raw, colors) {
                    raw_global = runCatching { manager.global_config_file().readText() }.getOrDefault("{\n  \"mcpServers\": {}\n}")
                    raw_project = manager.project_config_file()?.let { runCatching { it.readText() }.getOrDefault("{\n  \"mcpServers\": {}\n}") } ?: ""
                    show_raw = true
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            if (!show_raw) {
                // ===== 表单模式 =====
                // 全局服务器
                ai_group_title(colors = colors, title = "全局服务器（~/.ai/mcp.json）")
                Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp).clip(RoundedCornerShape(12.dp))) {
                    if (global_configs.isEmpty()) {
                        ai_empty_hint(colors, "暂无全局服务器")
                    } else {
                        global_configs.forEachIndexed { index, cfg ->
                            if (index > 0) ai_group_divider()
                            ai_mcp_server_card(cfg, colors, is_top = index == 0, is_bottom = index == global_configs.lastIndex) {
                                manager.save_configs(manager.global_config_file(), global_configs.filter { it.name != cfg.name })
                                reload()
                            }
                        }
                    }
                }
                Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 10.dp)) {
                    ai_add_button(colors) { add_target_global = true; show_add_dialog = true }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // 项目服务器
                ai_group_title(colors = colors, title = if (project_dir != null) "项目服务器（.ai/mcp.json）" else "项目服务器（需在项目中配置）")
                Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp).clip(RoundedCornerShape(12.dp))) {
                    if (project_configs.isEmpty()) {
                        ai_empty_hint(colors, "暂无项目服务器")
                    } else {
                        project_configs.forEachIndexed { index, cfg ->
                            if (index > 0) ai_group_divider()
                            ai_mcp_server_card(cfg, colors, is_top = index == 0, is_bottom = index == project_configs.lastIndex) {
                                manager.project_config_file()?.let { f ->
                                    manager.save_configs(f, project_configs.filter { it.name != cfg.name })
                                    reload()
                                }
                            }
                        }
                    }
                }
                if (project_dir != null) {
                    Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 10.dp)) {
                        ai_add_button(colors) { add_target_global = false; show_add_dialog = true }
                    }
                }
            } else {
                // ===== 原始 JSON 模式 =====
                ai_group_title(colors = colors, title = "全局 mcp.json")
                Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp).clip(RoundedCornerShape(12.dp))) {
                    OutlinedTextField(
                        value = raw_global,
                        onValueChange = { raw_global = it },
                        modifier = Modifier.fillMaxWidth().padding(8.dp),
                        minLines = 6, maxLines = 12,
                        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 11.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace),
                        shape = RoundedCornerShape(8.dp), colors = field_colors(colors)
                    )
                }
                Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 8.dp)) {
                    ai_save_button(colors = colors, label = "保存全局") {
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                runCatching { manager.global_config_file().writeText(raw_global) }
                            }
                            reload()
                            app_toast.show(context, "已保存", app_toast.LENGTH_SHORT)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                if (project_dir != null) {
                    ai_group_title(colors = colors, title = "项目 mcp.json")
                    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp).clip(RoundedCornerShape(12.dp))) {
                        OutlinedTextField(
                            value = raw_project,
                            onValueChange = { raw_project = it },
                            modifier = Modifier.fillMaxWidth().padding(8.dp),
                            minLines = 6, maxLines = 12,
                            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 11.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace),
                            shape = RoundedCornerShape(8.dp), colors = field_colors(colors)
                        )
                    }
                    Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 8.dp)) {
                        ai_save_button(colors = colors, label = "保存项目") {
                            scope.launch {
                                withContext(Dispatchers.IO) {
                                    manager.project_config_file()?.let { runCatching { it.writeText(raw_project) } }
                                }
                                reload()
                                app_toast.show(context, "已保存", app_toast.LENGTH_SHORT)
                            }
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(40.dp))
            }
        }
    }

    // 添加 server 对话框
    if (show_add_dialog) {
        ai_mcp_add_dialog(
            colors = colors,
            is_global = add_target_global,
            on_dismiss = { show_add_dialog = false },
            on_confirm = { name, command, args, env ->
                val cfg = com.jmwl.gostudio.ai.mcp.ai_mcp_manager.mcp_server_config(name, command, args, env)
                if (add_target_global) {
                    manager.save_configs(manager.global_config_file(), global_configs + cfg)
                } else {
                    manager.project_config_file()?.let { manager.save_configs(it, project_configs + cfg) }
                }
                reload()
                show_add_dialog = false
            }
        )
    }
}

/** MCP server 卡片（名称 + command + 删除） */
@Composable
private fun ai_mcp_server_card(
    cfg: com.jmwl.gostudio.ai.mcp.ai_mcp_manager.mcp_server_config,
    colors: app_colors,
    is_top: Boolean,
    is_bottom: Boolean,
    on_delete: () -> Unit
) {
    val interaction_source = remember { MutableInteractionSource() }
    Row(
        modifier = Modifier.fillMaxWidth().background(colors.card_bg)
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        ai_icon_chip(Icons.Default.Hub, colors)
        Column(verticalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.weight(1f)) {
            Text(cfg.name, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = colors.card_text_title)
            Text(cfg.command + if (cfg.args.isNotEmpty()) " " + cfg.args.joinToString(" ") else "",
                fontSize = 10.sp, color = colors.card_text_subtitle, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        }
        Surface(
            modifier = Modifier.size(28.dp), shape = CircleShape,
            color = colors.danger_bg.copy(alpha = 0.3f), onClick = on_delete
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(Icons.Default.Delete, contentDescription = "删除", tint = colors.danger, modifier = Modifier.size(14.dp))
            }
        }
    }
}

/** 添加 MCP server 对话框 */
@Composable
private fun ai_mcp_add_dialog(
    colors: app_colors,
    is_global: Boolean,
    on_dismiss: () -> Unit,
    on_confirm: (String, String, List<String>, Map<String, String>) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var command by remember { mutableStateOf("") }
    var args_text by remember { mutableStateOf("") }
    var env_text by remember { mutableStateOf("") }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = on_dismiss,
        containerColor = colors.dialog_bg,
        title = { Text(if (is_global) "添加全局服务器" else "添加项目服务器", color = colors.dialog_text) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("名称") }, singleLine = true, colors = field_colors(colors))
                OutlinedTextField(value = command, onValueChange = { command = it }, label = { Text("命令 (command)") }, singleLine = true, colors = field_colors(colors))
                OutlinedTextField(value = args_text, onValueChange = { args_text = it }, label = { Text("参数 (空格分隔)") }, singleLine = true, colors = field_colors(colors))
                OutlinedTextField(value = env_text, onValueChange = { env_text = it }, label = { Text("环境变量 (KEY=value,逗号分隔)") }, singleLine = true, colors = field_colors(colors))
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (name.isNotBlank() && command.isNotBlank()) {
                    val args = args_text.split(" ").filter { it.isNotBlank() }
                    val env = env_text.split(",").mapNotNull {
                        val idx = it.indexOf('=')
                        if (idx > 0) it.substring(0, idx).trim() to it.substring(idx + 1).trim() else null
                    }.toMap()
                    on_confirm(name.trim(), command.trim(), args, env)
                }
            }) { Text("添加", color = colors.title_highlight) }
        },
        dismissButton = { TextButton(onClick = on_dismiss) { Text("取消", color = colors.subtitle) } }
    )
}

// ==================== 子页：Skill 配置 ====================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ai_skill_settings_screen(project_dir: java.io.File?, on_back: () -> Unit) {
    val colors = app_theme_provider.colors
    val context = androidx.compose.ui.platform.LocalContext.current
    val paths = remember { com.jmwl.gostudio.toolchain.toolchain_runtime_provider.paths() }
    val manager = remember(project_dir) {
        com.jmwl.gostudio.ai.skills.ai_skill_manager(
            File(paths.home_dir, ".ai/skills"),
            project_dir?.let { File(it, ".ai/skills") },
            com.jmwl.gostudio.plugins.plugin_manager.skill_dirs()
        ).also { it.discover() }
    }
    var skills by remember { mutableStateOf(manager.all()) }
    var show_create_dialog by remember { mutableStateOf(false) }
    var viewing_skill by remember { mutableStateOf<com.jmwl.gostudio.ai.skills.ai_skill?>(null) }

    fun reload() { skills = manager.all() }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            ai_sub_page_header(colors = colors, title = "Skill 配置", on_back = on_back)
            Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            Spacer(modifier = Modifier.height(20.dp))

            // 创建按钮 + AI 创建提示
            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ai_add_button(colors, label = "创建技能") { show_create_dialog = true }
            }
            ai_hint_text(colors = colors, text = "💡 也可在对话里让 AI 自动创建技能（描述需求，如\"创建一个 code-review 技能\"）")

            Spacer(modifier = Modifier.height(16.dp))

            ai_group_title(colors = colors, title = "已安装技能（${skills.size}）")
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp).clip(RoundedCornerShape(12.dp))) {
                if (skills.isEmpty()) {
                    ai_empty_hint(colors, "暂无技能")
                } else {
                    skills.forEachIndexed { index, skill ->
                        if (index > 0) ai_group_divider()
                        ai_skill_card(skill, colors, is_top = index == 0, is_bottom = index == skills.lastIndex,
                            on_view = { viewing_skill = skill },
                            on_delete = {
                                if (manager.delete_skill(skill.name)) { reload(); app_toast.show(context, "已删除 ${skill.name}", app_toast.LENGTH_SHORT) }
                                else app_toast.show(context, "内置技能不可删除", app_toast.LENGTH_SHORT)
                            }
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(40.dp))
            }
        }
    }

    // 创建技能对话框
    if (show_create_dialog) {
        ai_skill_create_dialog(
            colors = colors,
            on_dismiss = { show_create_dialog = false },
            on_create = { name, desc, content ->
                if (manager.create_skill(name, desc, content)) {
                    reload()
                    app_toast.show(context, "技能 $name 已创建", app_toast.LENGTH_SHORT)
                } else {
                    app_toast.show(context, "创建失败", app_toast.LENGTH_SHORT)
                }
                show_create_dialog = false
            }
        )
    }

    // 查看技能内容
    viewing_skill?.let { skill ->
        val content = remember(skill) { manager.read_skill_content(skill.name) }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { viewing_skill = null },
            containerColor = colors.dialog_bg,
            title = { Text(skill.name, color = colors.dialog_text, fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    Text(skill.description, fontSize = 12.sp, color = colors.subtitle)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(content ?: "(无法读取)", fontSize = 11.sp, color = colors.card_text_title,
                        modifier = Modifier.verticalScroll(rememberScrollState()).heightIn(max = 300.dp))
                }
            },
            confirmButton = { TextButton(onClick = { viewing_skill = null }) { Text("关闭", color = colors.title_highlight) } }
        )
    }
}

/** Skill 卡片 */
@Composable
private fun ai_skill_card(
    skill: com.jmwl.gostudio.ai.skills.ai_skill,
    colors: app_colors,
    is_top: Boolean, is_bottom: Boolean,
    on_view: () -> Unit,
    on_delete: () -> Unit
) {
    val interaction_source = remember { MutableInteractionSource() }
    val source_label = when (skill.source) {
        com.jmwl.gostudio.ai.skills.skill_source.BUILT_IN -> "内置"
        com.jmwl.gostudio.ai.skills.skill_source.GLOBAL -> "全局"
        com.jmwl.gostudio.ai.skills.skill_source.PROJECT -> "项目"
        com.jmwl.gostudio.ai.skills.skill_source.PLUGIN -> "插件"
    }
    val can_delete = skill.source != com.jmwl.gostudio.ai.skills.skill_source.BUILT_IN &&
        skill.source != com.jmwl.gostudio.ai.skills.skill_source.PLUGIN
    Row(
        modifier = Modifier.fillMaxWidth().background(colors.card_bg)
            .clickable(interactionSource = interaction_source, indication = null, onClick = on_view)
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        ai_icon_chip(Icons.Default.AutoAwesome, colors)
        Column(verticalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(skill.name, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = colors.card_text_title)
                Spacer(modifier = Modifier.width(6.dp))
                Surface(shape = RoundedCornerShape(4.dp), color = colors.title_highlight.copy(alpha = 0.15f)) {
                    Text(source_label, fontSize = 9.sp, color = colors.title_highlight, modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp))
                }
            }
            Text(skill.description, fontSize = 10.sp, color = colors.card_text_subtitle, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        }
        if (can_delete) {
            Surface(modifier = Modifier.size(28.dp), shape = CircleShape, color = colors.danger_bg.copy(alpha = 0.3f), onClick = on_delete) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.Delete, contentDescription = "删除", tint = colors.danger, modifier = Modifier.size(14.dp))
                }
            }
        }
    }
}

/** 创建技能对话框 */
@Composable
private fun ai_skill_create_dialog(
    colors: app_colors,
    on_dismiss: () -> Unit,
    on_create: (String, String, String) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var desc by remember { mutableStateOf("") }
    var content by remember { mutableStateOf("") }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = on_dismiss,
        containerColor = colors.dialog_bg,
        title = { Text("创建技能", color = colors.dialog_text) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("名称（英文）") }, singleLine = true, colors = field_colors(colors))
                OutlinedTextField(value = desc, onValueChange = { desc = it }, label = { Text("描述（何时触发）") }, singleLine = true, colors = field_colors(colors))
                OutlinedTextField(value = content, onValueChange = { content = it }, label = { Text("指令正文") }, minLines = 4, maxLines = 8, colors = field_colors(colors))
            }
        },
        confirmButton = {
            TextButton(onClick = { if (name.isNotBlank() && content.isNotBlank()) on_create(name.trim(), desc.trim(), content) }) {
                Text("创建", color = colors.title_highlight)
            }
        },
        dismissButton = { TextButton(onClick = on_dismiss) { Text("取消", color = colors.subtitle) } }
    )
}

// ==================== 小工具组件 ====================

@Composable
private fun ai_mode_tab(label: String, active: Boolean, colors: app_colors, on_click: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = if (active) colors.title_highlight else colors.card_bg,
        onClick = on_click
    ) {
        Text(label, fontSize = 12.sp, fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
            color = if (active) colors.dialog_clone_text else colors.subtitle,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp))
    }
}

@Composable
private fun ai_empty_hint(colors: app_colors, text: String) {
    Box(modifier = Modifier.fillMaxWidth().background(colors.card_bg).padding(vertical = 16.dp), contentAlignment = Alignment.Center) {
        Text(text, fontSize = 12.sp, color = colors.subtitle)
    }
}

@Composable
private fun ai_add_button(colors: app_colors, label: String = "添加", on_click: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        color = colors.title_highlight.copy(alpha = 0.12f),
        onClick = on_click
    ) {
        Row(modifier = Modifier.padding(vertical = 10.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Add, contentDescription = null, tint = colors.title_highlight, modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.width(6.dp))
            Text(label, color = colors.title_highlight, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }
    }
}

// ==================== 共用子页头部 ====================

@Composable
private fun ai_sub_page_header(colors: app_colors, title: String, on_back: () -> Unit) {
    // 统一二级页顶栏：标题与返回键同行
    sub_page_top_bar(title, on_back)
}

/** 单选卡片（用于思考深度/语气选择） */
@Composable
private fun ai_radio_card(
    icon: ImageVector,
    title: String,
    description: String,
    selected: Boolean,
    colors: app_colors,
    is_top: Boolean,
    is_bottom: Boolean,
    on_select: () -> Unit
) {
    val interaction_source = remember { MutableInteractionSource() }
    val is_pressed by interaction_source.collectIsPressedAsState()
    val background_color = if (is_pressed) colors.card_pressed else colors.card_bg

    Row(
        modifier = Modifier.fillMaxWidth().background(background_color)
            .clickable(interactionSource = interaction_source, indication = null, onClick = on_select)
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        ai_icon_chip(icon, colors)
        Column(verticalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.weight(1f)) {
            Text(title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = colors.card_text_title)
            Text(description, fontSize = 10.sp, lineHeight = 12.sp, fontWeight = FontWeight.Light, color = colors.card_text_subtitle)
        }
        if (selected) {
            Icon(Icons.Default.Check, contentDescription = "已选中", tint = colors.title_highlight, modifier = Modifier.size(18.dp))
        }
    }
}

// ---------- 内部组件（与 editor_settings_panel 风格统一）----------

@Composable
private fun ai_group_title(colors: app_colors, title: String) {
    Text(
        text = title,
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
        color = colors.title_highlight,
        modifier = Modifier.padding(start = 22.dp, bottom = 10.dp)
    )
}

@Composable
private fun ai_group_divider() {
    Spacer(modifier = Modifier.height(1.dp))
}

private fun ai_item_shape(is_top: Boolean, is_bottom: Boolean, radius: Dp = 12.dp): RoundedCornerShape {
    return when {
        is_top && is_bottom -> RoundedCornerShape(radius)
        is_top -> RoundedCornerShape(topStart = radius, topEnd = radius, bottomStart = 0.dp, bottomEnd = 0.dp)
        is_bottom -> RoundedCornerShape(topStart = 0.dp, topEnd = 0.dp, bottomStart = radius, bottomEnd = radius)
        else -> RoundedCornerShape(0.dp)
    }
}

/** 图标圆角徽章（与 editor_settings_icon 一致） */
@Composable
private fun ai_icon_chip(icon: ImageVector, colors: app_colors) {
    Box(
        modifier = Modifier
            .size(28.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(colors.card_icon_bg.copy(alpha = 0.15f)),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = colors.card_icon_bg,
            modifier = Modifier.size(16.dp)
        )
    }
}

/** 带图标 + 标题 + 副标题 + 尾部控件的导航卡片（用于提供商） */
@Composable
private fun ai_navigation_card(
    icon: ImageVector,
    title: String,
    description: String,
    colors: app_colors,
    is_top: Boolean,
    is_bottom: Boolean,
    trailing: @Composable () -> Unit,
    on_click: () -> Unit
) {
    val interaction_source = remember { MutableInteractionSource() }
    val is_pressed by interaction_source.collectIsPressedAsState()
    val background_color = if (is_pressed) colors.card_pressed else colors.card_bg

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(background_color)
            .clickable(
                interactionSource = interaction_source,
                indication = null,
                onClick = on_click
            )
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        ai_icon_chip(icon, colors)
        Column(
            verticalArrangement = Arrangement.spacedBy(2.dp),
            modifier = Modifier.weight(1f)
        ) {
            Text(
                text = title,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = colors.card_text_title
            )
            Text(
                text = description,
                fontSize = 10.sp,
                lineHeight = 12.sp,
                fontWeight = FontWeight.Light,
                color = colors.card_text_subtitle
            )
        }
        trailing()
    }
}

/** 带图标 + 标题 + 输入框的卡片（用于 base_url / model / key） */
@Composable
private fun ai_input_card(
    icon: ImageVector,
    title: String,
    colors: app_colors,
    is_top: Boolean,
    is_bottom: Boolean,
    content: @Composable () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.card_bg)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ai_icon_chip(icon, colors)
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = title,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = colors.card_text_title
            )
        }
        content()
    }
}

/** 带图标 + 标题 + 副标题 + 开关的卡片（与 editor_settings_switch_card 一致） */
@Composable
private fun ai_switch_card(
    icon: ImageVector,
    title: String,
    description: String,
    checked: Boolean,
    colors: app_colors,
    is_top: Boolean,
    is_bottom: Boolean,
    on_checked_change: (Boolean) -> Unit
) {
    val interaction_source = remember { MutableInteractionSource() }
    val is_pressed by interaction_source.collectIsPressedAsState()
    val background_color = if (is_pressed) colors.card_pressed else colors.card_bg

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(background_color)
            .clickable(
                interactionSource = interaction_source,
                indication = null
            ) { on_checked_change(!checked) }
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        ai_icon_chip(icon, colors)
        Column(
            verticalArrangement = Arrangement.spacedBy(2.dp),
            modifier = Modifier.weight(1f)
        ) {
            Text(
                text = title,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = colors.card_text_title
            )
            Text(
                text = description,
                fontSize = 10.sp,
                lineHeight = 12.sp,
                fontWeight = FontWeight.Light,
                color = colors.card_text_subtitle
            )
        }
        ai_small_switch(checked = checked, colors = colors)
    }
}

/** 小开关（与 editor_settings_small_switch 一致） */
@Composable
private fun ai_small_switch(checked: Boolean, colors: app_colors) {
    Box(
        modifier = Modifier
            .size(width = 34.dp, height = 20.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(
                if (checked) colors.card_icon_bg.copy(alpha = 0.36f)
                else colors.card_text_subtitle.copy(alpha = 0.18f)
            )
            .padding(3.dp)
    ) {
        Box(
            modifier = Modifier
                .align(if (checked) Alignment.CenterEnd else Alignment.CenterStart)
                .size(14.dp)
                .clip(CircleShape)
                .background(if (checked) colors.card_icon_bg else colors.card_text_subtitle)
        )
    }
}

@Composable
private fun ai_hint_text(colors: app_colors, text: String, success: Boolean = false) {
    Text(
        text = text,
        fontSize = 10.sp,
        color = if (success) colors.success else colors.subtitle,
        modifier = Modifier.padding(start = 22.dp, top = 8.dp, end = 22.dp)
    )
}

@Composable
private fun ai_save_button(colors: app_colors, label: String = "保存设置", on_click: () -> Unit) {
    val interaction_source = remember { MutableInteractionSource() }
    val is_pressed by interaction_source.collectIsPressedAsState()
    val background_color = if (is_pressed) colors.title_highlight.copy(alpha = 0.85f) else colors.title_highlight

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(background_color)
            .clickable(
                interactionSource = interaction_source,
                indication = null,
                onClick = on_click
            )
            .padding(vertical = 14.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Default.Security,
            contentDescription = null,
            tint = colors.dialog_clone_text,
            modifier = Modifier.size(18.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = label,
            color = colors.dialog_clone_text,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

/** 密钥/URL/模型 ID 里不存在合法空白，输入（含粘贴）时把空格、换行、制表符直接滤掉 */
private fun strip_whitespace(s: String): String = s.filterNot { it.isWhitespace() }

@Composable
private fun field_colors(colors: app_colors) = OutlinedTextFieldDefaults.colors(
    focusedTextColor = colors.input_text,
    unfocusedTextColor = colors.input_text,
    focusedBorderColor = Color.Transparent,
    unfocusedBorderColor = Color.Transparent,
    cursorColor = colors.title_highlight,
    focusedContainerColor = Color.Transparent,
    unfocusedContainerColor = Color.Transparent
)
