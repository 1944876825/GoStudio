package com.jmwl.gostudio.ui.screens.editor

import com.jmwl.gostudio.editor.model.editor_tab_item
import com.jmwl.gostudio.editor.model.editor_file_node
import com.jmwl.gostudio.project.project_ide_config

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jmwl.gostudio.ui.screens.ai.ai_markdown_text
import com.jmwl.gostudio.ui.theme.app_theme_provider
import com.jmwl.gostudio.ui.theme.motion
import com.jmwl.gostudio.ui.dialogs.editor.editor_create_entry_dialog
import com.jmwl.gostudio.ui.dialogs.editor.editor_create_file_template
import com.jmwl.gostudio.ui.dialogs.editor.editor_tool_dialog
import com.jmwl.gostudio.plugins.enabled_tools
import com.jmwl.gostudio.plugins.plugin_manager
import com.jmwl.gostudio.plugins.plugin_tool_def
import com.jmwl.gostudio.tools.tool_engine
import kotlinx.coroutines.launch
import io.github.rosemoe.sora.event.InterceptTarget
import io.github.rosemoe.sora.event.EventReceiver
import io.github.rosemoe.sora.event.LongPressEvent
import io.github.rosemoe.sora.event.PublishSearchResultEvent
import io.github.rosemoe.sora.widget.CodeEditor
import kotlin.math.roundToInt

private enum class editor_create_dialog_type { FILE, FOLDER }

private data class editor_create_dialog_request(
    val type: editor_create_dialog_type,
    val parent_path: String
)

private data class editor_long_press_menu(
    val position: Offset,
    val line: Int,
    val column: Int
)

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
internal fun editor_screen(
    project_name: String,
    project_root_path: String,
    editor: CodeEditor,
    current_file_name: String,
    /**
     * 光标状态以 provider/StateFlow 传入，在最小的叶子组合里读取：
     * 若直接以 Int/Boolean 参数传入，每次光标移动都会让整个 editor_screen 重组
     * （顶栏/标签栏/输出面板全部重新执行），造成主线程过慢、拖动光标卡顿。
     */
    cursor_line_provider: () -> Int,
    cursor_column_provider: () -> Int,
    cursor_selected_provider: () -> Boolean,
    can_goto_definition_flow: kotlinx.coroutines.flow.StateFlow<Boolean>,
    has_changes: Boolean,
    loading: Boolean,
    read_only: Boolean,
    can_undo: Boolean,
    can_redo: Boolean,
    file_nodes: List<editor_file_node>,
    expanded_paths: Set<String>,
    file_tree_loading: Boolean,
    project_exists: Boolean,
    has_open_file: Boolean,
    tabs: List<editor_tab_item>,
    selected_tab_path: String?,
    toolbar_visible: Boolean,
    output_panel_state: editor_output_panel_state,
    terminal_cwd: String,
    terminal_extra_environment: Map<String, String>,
    on_toggle_toolbar: () -> Unit,
    on_project_config_apply: (project_ide_config, () -> Unit) -> Unit,
    /** 项目配置页「编译」：按面板当前配置交叉编译并导出 */
    on_project_config_compile: (project_ide_config) -> Unit = {},
    on_compile_cancel: () -> Unit = {},
    compile_dialog_state: editor_compile_dialog_state? = null,
    is_app_project: Boolean = false,
    on_select_tab: (String) -> Unit,
    on_pin_tab: (String) -> Unit,
    on_close_tab: (String) -> Unit,
    on_close_other_tabs: (String) -> Unit,
    on_close_all_tabs: () -> Unit,
    on_build: () -> Unit,
    on_run: () -> Unit,
    on_test: () -> Unit = {},
    on_pack: () -> Unit = {},
    on_tidy: () -> Unit = {},
    on_save: () -> Unit,
    on_format: () -> Unit,
    on_toggle_read_only: () -> Unit,
    on_undo: () -> Unit,
    on_redo: () -> Unit,
    on_search_change: (String, Boolean, Boolean, Boolean) -> Boolean,
    on_search_previous: () -> Unit,
    on_search_next: () -> Unit,
    on_replace_current: (String) -> Unit,
    on_replace_all: (String) -> Unit,
    on_clear_search: () -> Unit,
    on_insert_symbol: (String) -> Unit,
    on_toggle_comment: () -> Unit = {},
    on_create_file: (String, String, editor_create_file_template) -> Unit,
    on_create_folder: (String, String) -> Unit,
    on_refresh_files: (String) -> Unit,
    on_rename_file_tree_node: (String, String) -> Unit,
    on_delete_file_tree_node: (String) -> Unit,
    on_directory_click: (String) -> Unit,
    on_file_click: (String) -> Unit,
    on_file_position_click: (String, Int, Int) -> Unit,
    goto_definition_running: Boolean,
    on_goto_definition: () -> Unit,
    structure_file_name: String? = null,
    structure_symbols: List<com.jmwl.gostudio.editor.core.editor_outline_symbol> = emptyList(),
    on_structure_navigate: (Int) -> Unit = {},
    on_project_search: suspend (String) -> List<editor_project_search_hit> = { emptyList() },
    file_diagnostics: List<com.jmwl.gostudio.lsp.gopls.gopls_diagnostic> = emptyList(),
    on_diagnostic_click: (com.jmwl.gostudio.lsp.gopls.gopls_diagnostic) -> Unit = {},
    ai_agent: com.jmwl.gostudio.ai.ai_agent_loop? = null,
    on_open_ai_settings: () -> Unit = {},
    ai_current_choice: com.jmwl.gostudio.ai.ai_model_choice = com.jmwl.gostudio.ai.ai_model_choice(
        provider = com.jmwl.gostudio.ai.ai_provider.ZHIPU, model = "", base_url = "", api_key = "", label = "未配置"
    ),
    ai_instances: List<com.jmwl.gostudio.ai.provider_instance> = emptyList(),
    on_ai_model_choice: (com.jmwl.gostudio.ai.ai_model_choice) -> Unit = {},
    ai_global_prompts_dir: java.io.File? = null,
    ai_project_prompts_dir: java.io.File? = null,
    /** 递增触发器：外部想让 AI 弹窗打开时把这个值 +1 */
    ai_open_trigger: Int = 0,
    /** 递增触发器：外部想让左侧日志栏打开时把这个值 +1（构建/打包前自动弹出） */
    sidebar_log_open_trigger: Int = 0,
    /** 递增触发器：App 编译完成并进入运行界面后切走编译日志 */
    sidebar_log_close_trigger: Int = 0,
    on_open_designer: () -> Unit = {},
    /** AI 设置覆盖层打开或正在退出时，禁用 AI 页自身返回处理 */
    ai_settings_visible: Boolean = false
) {
    val colors = app_theme_provider.colors
    var drawer_open by remember { mutableStateOf(false) }
    var drawer_width by remember { mutableStateOf(300.dp) }
    var selected_tool by remember { mutableStateOf(editor_sidebar_tool.FILE) }
    var search_visible by remember { mutableStateOf(false) }
    // 任意 xml 布局文件都可进可视化设计器（设计器默认编辑 layout.xml）。
    val is_xml_file = selected_tab_path?.endsWith(".xml") == true
    val is_markdown_file = selected_tab_path?.lowercase()?.let {
        it.endsWith(".md") || it.endsWith(".markdown")
    } == true
    // Markdown 文件默认进入预览；切换文件时重新初始化模式。
    var markdown_preview_enabled by remember(selected_tab_path) { mutableStateOf(true) }
    val show_markdown_preview = has_open_file && is_markdown_file && markdown_preview_enabled
    var search_query by remember { mutableStateOf("") }
    var search_replace_text by remember { mutableStateOf("") }
    var search_expanded by remember { mutableStateOf(false) }
    var search_match_case by remember { mutableStateOf(false) }
    var search_whole_word by remember { mutableStateOf(false) }
    var search_regex by remember { mutableStateOf(false) }
    var search_has_match by remember { mutableStateOf(false) }
    var search_panel_offset by remember { mutableStateOf(Offset.Zero) }
    var create_dialog_request by remember { mutableStateOf<editor_create_dialog_request?>(null) }
    var show_ai_page by remember { mutableStateOf(false) }
    var show_project_config by remember { mutableStateOf(false) }
    // 插件工具：每个已启用的工具在「更多」菜单独立一项（内置插件默认启用）
    var plugin_tools by remember { mutableStateOf(plugin_manager.enabled_tools(tool_engine.supported_kinds)) }
    var active_tool by remember { mutableStateOf<plugin_tool_def?>(null) }
    DisposableEffect(Unit) {
        val listener = { plugin_tools = plugin_manager.enabled_tools(tool_engine.supported_kinds) }
        plugin_manager.add_listener(listener)
        onDispose { plugin_manager.remove_listener(listener) }
    }
    // 外部触发打开 AI 页面（编辑器选区 AI 动作）
    LaunchedEffect(ai_open_trigger) {
        if (ai_open_trigger > 0) show_ai_page = true
    }
    var editor_focused by remember { mutableStateOf(false) }
    var long_press_menu by remember { mutableStateOf<editor_long_press_menu?>(null) }
    val terminal_state = remember_editor_terminal_state()
    val drawer_progress = remember { Animatable(0f) }
    val coroutine_scope = rememberCoroutineScope()
    var drawer_edge_dragging by remember { mutableStateOf(false) }
    var ai_edge_swipe_distance by remember { mutableStateOf(0f) }
    val safe_project_name = project_name.ifBlank { "项目" }
    val density = LocalDensity.current
    val drawer_width_px = with(density) { drawer_width.toPx() }
    val edge_swipe_zone_px = with(density) { 20.dp.toPx() }
    val ai_open_threshold_px = with(density) { 64.dp.toPx() }
    val drawer_progress_value = drawer_progress.value
    val editor_offset_px = (drawer_width_px * drawer_progress_value).roundToInt()
    val sidebar_offset_px = (-(drawer_width_px * (1f - drawer_progress_value))).roundToInt()
    val ime_visible = WindowInsets.ime.getBottom(density) > 0
    val show_symbol_bar = has_open_file && editor_focused && ime_visible

    LaunchedEffect(show_markdown_preview) {
        if (show_markdown_preview) {
            editor.clearFocus()
            editor_focused = false
            search_visible = false
            long_press_menu = null
        }
    }

    LaunchedEffect(drawer_open) {
        if (drawer_open) {
            editor.clearFocus()
            editor_focused = false
        }
        drawer_progress.animateTo(
            targetValue = if (drawer_open) 1f else 0f,
            animationSpec = tween(motion.SLOW, easing = motion.quiet)
        )
    }

    // 抽屉打开时返回键先关闭抽屉
    BackHandler(enabled = drawer_open) { drawer_open = false }

    LaunchedEffect(sidebar_log_open_trigger) {
        if (sidebar_log_open_trigger > 0) {
            selected_tool = editor_sidebar_tool.LOG
            drawer_open = true
        }
    }
    LaunchedEffect(sidebar_log_close_trigger) {
        if (sidebar_log_close_trigger > 0) {
            selected_tool = editor_sidebar_tool.FILE
            drawer_open = false
        }
    }

    // AI 页面打开时返回键先关闭页面
    BackHandler(enabled = show_ai_page) { show_ai_page = false }

    // 项目配置页打开时返回键先关闭页面
    BackHandler(enabled = show_project_config) { show_project_config = false }

    LaunchedEffect(search_visible) {
        if (search_visible) {
            search_expanded = false
            search_panel_offset = Offset.Zero
        }
    }

    LaunchedEffect(has_open_file) {
        if (!has_open_file) {
            search_visible = false
        }
    }

    DisposableEffect(editor) {
        val receipt = editor.subscribeEvent(PublishSearchResultEvent::class.java, EventReceiver { event, _ ->
            search_has_match = runCatching {
                val searcher = event.getSearcher()
                searcher.hasQuery() && searcher.matchedPositionCount > 0
            }.getOrDefault(false)
        })
        onDispose { receipt.unsubscribe() }
    }

    DisposableEffect(editor) {
        val receipt = editor.subscribeEvent(LongPressEvent::class.java, EventReceiver { event, _ ->
            editor.selectWord(event.line, event.column)
            if (editor.cursor.isSelected) {
                long_press_menu = editor_long_press_menu(
                    position = Offset(event.x, event.y),
                    line = event.line,
                    column = event.column
                )
                event.intercept(InterceptTarget.TARGET_EDITOR or InterceptTarget.TARGET_RECEIVERS)
            }
        })
        onDispose { receipt.unsubscribe() }
    }

    LaunchedEffect(search_visible, has_open_file, search_query, search_match_case, search_whole_word, search_regex) {
        if (search_visible && has_open_file) {
            search_has_match = on_search_change(search_query, search_match_case, search_whole_word, search_regex)
        } else {
            search_has_match = false
            on_clear_search()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(colors.editor_bg)
                .offset { IntOffset(editor_offset_px, 0) }
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Column(modifier = Modifier.statusBarsPadding()) {
                    AnimatedVisibility(
                        visible = toolbar_visible,
                        enter = slideInVertically(
                            animationSpec = tween(motion.BASE, easing = motion.quiet),
                            initialOffsetY = { -it }
                        ) + expandVertically(
                            animationSpec = tween(motion.BASE, easing = motion.quiet),
                            expandFrom = Alignment.Top
                        ),
                        exit = slideOutVertically(
                            animationSpec = tween(motion.BASE, easing = motion.quiet),
                            targetOffsetY = { -it }
                        ) + shrinkVertically(
                            animationSpec = tween(motion.BASE, easing = motion.quiet),
                            shrinkTowards = Alignment.Top
                        )
                    ) {
                        editor_top_bar(
                            project_name = safe_project_name,
                            has_changes = has_changes,
                            on_save = on_save,
                            read_only = read_only,
                            has_open_file = has_open_file,
                            on_toggle_drawer = { drawer_open = !drawer_open },
                            drawer_fraction = { drawer_progress.value },
                            on_build = on_build,
                            on_run = on_run,
                            is_xml_file = is_xml_file,
                            is_markdown_file = is_markdown_file,
                            markdown_preview_enabled = markdown_preview_enabled,
                            on_toggle_markdown_mode = {
                                markdown_preview_enabled = !markdown_preview_enabled
                            },
                            on_open_designer = on_open_designer,
                            on_test = on_test,
                            on_pack = on_pack,
                            on_tidy = on_tidy,
                            build_running = output_panel_state.task_running,
                            build_stopping = output_panel_state.task_stopping,
                            on_toggle_read_only = on_toggle_read_only,
                            on_open_ai = { show_ai_page = true },
                            tools = plugin_tools,
                            on_open_tool = { active_tool = it },
                            on_open_project_config = { show_project_config = true }
                        )
                    }
                    // 文件标签栏保持在顶部
                    if (has_open_file) {
                        editor_tabs_bar(
                            tabs = tabs,
                            selected_tab_path = selected_tab_path,
                            toolbar_visible = toolbar_visible,
                            on_toggle_toolbar = on_toggle_toolbar,
                            on_select_tab = on_select_tab,
                            on_pin_tab = on_pin_tab,
                            on_close_tab = on_close_tab,
                            on_close_other_tabs = on_close_other_tabs,
                            on_close_all_tabs = on_close_all_tabs
                        )
                    }
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                ) {
                    if (show_markdown_preview) {
                        markdown_preview_panel(
                            text = editor.text.toString(),
                            modifier = Modifier.fillMaxSize()
                        )
                    } else if (has_open_file) {
                        code_editor_panel(
                            editor = editor,
                            modifier = Modifier.fillMaxSize(),
                            on_focus_change = { focused -> editor_focused = focused }
                        )

                        long_press_menu?.let { menu ->
                            Box(
                                modifier = Modifier
                                    .offset {
                                        IntOffset(
                                            menu.position.x.roundToInt(),
                                            menu.position.y.roundToInt()
                                        )
                                    }
                                    .size(0.dp)
                            ) {
                                DropdownMenu(
                                    expanded = true,
                                    onDismissRequest = { long_press_menu = null }
                                ) {
                                    DropdownMenuItem(
                                        text = { Text("跳转定义") },
                                        onClick = {
                                            val column_count = editor.text?.getColumnCount(menu.line) ?: 0
                                            editor.setSelection(menu.line, (menu.column + 1).coerceAtMost(column_count))
                                            long_press_menu = null
                                            on_goto_definition()
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("复制") },
                                        onClick = {
                                            editor.copyText(false)
                                            long_press_menu = null
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("粘贴") },
                                        enabled = !read_only,
                                        onClick = {
                                            editor.pasteText()
                                            long_press_menu = null
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("全选") },
                                        onClick = {
                                            editor.selectAll()
                                            long_press_menu = null
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("搜索") },
                                        onClick = {
                                            long_press_menu = null
                                            search_visible = true
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("格式化") },
                                        enabled = !read_only,
                                        onClick = {
                                            long_press_menu = null
                                            on_format()
                                        }
                                    )
                                }
                            }
                        }

                        // 左下角行列号（独立叶子：光标移动只重组这一小块）
                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomStart)
                                .padding(start = 8.dp, bottom = 6.dp)
                        ) {
                            editor_cursor_position_chip(
                                cursor_line_provider = cursor_line_provider,
                                cursor_column_provider = cursor_column_provider
                            )
                        }

                        Column(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(6.dp),
                            horizontalAlignment = Alignment.End,
                            verticalArrangement = Arrangement.spacedBy(7.dp)
                        ) {
                            // 光标相关状态全部在这个叶子组合里读取：
                            // 光标移动只重组这一小块，不会带动整个 editor_screen
                            editor_cursor_overlays(
                                cursor_selected_provider = cursor_selected_provider,
                                can_undo = can_undo,
                                can_redo = can_redo,
                                has_changes = has_changes,
                                can_format = !read_only,
                                show_format = true,
                                show_save = true,
                                show_search = true,
                                search_active = search_visible,
                                on_undo = on_undo,
                                on_redo = on_redo,
                                on_format = on_format,
                                on_save = on_save,
                                on_toggle_search = { search_visible = !search_visible }
                            )
                        }

                        androidx.compose.animation.AnimatedVisibility(
                            visible = search_visible,
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .padding(top = 8.dp)
                                .offset {
                                    IntOffset(
                                        search_panel_offset.x.roundToInt(),
                                        search_panel_offset.y.roundToInt()
                                    )
                                }
                        ) {
                            editor_search_panel(
                                query = search_query,
                                replacement = search_replace_text,
                                expanded = search_expanded,
                                match_case = search_match_case,
                                whole_word = search_whole_word,
                                regex = search_regex,
                                has_match = search_has_match,
                                replace_enabled = !read_only,
                                on_query_change = { search_query = it },
                                on_replacement_change = { search_replace_text = it },
                                on_expanded_change = { search_expanded = it },
                                on_match_case_change = { search_match_case = it },
                                on_whole_word_change = { enabled ->
                                    search_whole_word = enabled
                                    if (enabled) search_regex = false
                                },
                                on_regex_change = { enabled ->
                                    search_regex = enabled
                                    if (enabled) search_whole_word = false
                                },
                                on_previous = on_search_previous,
                                on_next = on_search_next,
                                on_replace_current = { on_replace_current(search_replace_text) },
                                on_replace_all = { on_replace_all(search_replace_text) },
                                on_close = { search_visible = false },
                                on_drag = { drag_amount -> search_panel_offset += drag_amount }
                            )
                        }
                    } else {
                        empty_editor_placeholder(
                            text = if (loading) "正在加载..." else "未打开任何文件",
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }

                // 符号栏作为编辑器布局的一部分，让 CodeEditor 的可用高度同步收缩。
                // 这样自动补全弹窗只会出现在编辑器区域内，不会压到键盘上方的符号输入区。
                if (show_symbol_bar) {
                    editor_symbol_bar(
                        on_insert = on_insert_symbol,
                        on_toggle_comment = on_toggle_comment
                    )
                }

                // 底部只留导航条安全距离
                Spacer(modifier = Modifier.navigationBarsPadding())
            }

            editor_definition_overlay(
                can_goto_definition_flow = can_goto_definition_flow,
                goto_definition_running = goto_definition_running,
                on_goto_definition = on_goto_definition,
                bottom_padding = if (show_symbol_bar) 54.dp else 10.dp
            )
        }

        // 左缘右滑：跟手拉出左侧抽屉
        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .fillMaxHeight()
                .width(20.dp)
                .pointerInput(Unit) {
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            if (drawer_edge_dragging) {
                                drawer_edge_dragging = false
                                if (drawer_progress.value > 0.35f) {
                                    drawer_open = true
                                } else if (!drawer_open) {
                                    coroutine_scope.launch {
                                        drawer_progress.animateTo(0f, tween(motion.SLOW, easing = motion.quiet))
                                    }
                                }
                            }
                        },
                        onDragCancel = {
                            if (drawer_edge_dragging) {
                                drawer_edge_dragging = false
                                if (drawer_progress.value > 0.35f) {
                                    drawer_open = true
                                } else if (!drawer_open) {
                                    coroutine_scope.launch {
                                        drawer_progress.animateTo(0f, tween(motion.SLOW, easing = motion.quiet))
                                    }
                                }
                            }
                        }
                    ) { change, drag_amount ->
                        change.consume()
                        if (!drawer_open && !show_ai_page) {
                            drawer_edge_dragging = true
                            val next = (drawer_progress.value + drag_amount / drawer_width_px)
                                .coerceIn(0f, 1f)
                            coroutine_scope.launch { drawer_progress.snapTo(next) }
                        }
                    }
                }
        )

        // 右缘左滑：打开 AI 聊天窗口
        Box(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight()
                .width(20.dp)
                .pointerInput(Unit) {
                    detectHorizontalDragGestures(
                        onDragStart = { ai_edge_swipe_distance = 0f }
                    ) { change, drag_amount ->
                        change.consume()
                        if (!show_ai_page && !drawer_open) {
                            ai_edge_swipe_distance += drag_amount
                            if (ai_edge_swipe_distance <= -ai_open_threshold_px) {
                                ai_edge_swipe_distance = 0f
                                show_ai_page = true
                            }
                        }
                    }
                }
        )

        if (drawer_open || drawer_progress_value > 0f) {
            editor_sidebar(
                drawer_width = drawer_width,
                drawer_offset_px = sidebar_offset_px,
                selected_tool = selected_tool,
                output_panel_state = output_panel_state,
                terminal_state = terminal_state,
                terminal_cwd = terminal_cwd,
                terminal_extra_environment = terminal_extra_environment,
                project_root_path = project_root_path,
                file_nodes = file_nodes,
                expanded_paths = expanded_paths,
                file_tree_loading = file_tree_loading,
                project_exists = project_exists,
                structure_file_name = structure_file_name,
                structure_symbols = structure_symbols,
                on_structure_navigate = { line ->
                    drawer_open = false
                    on_structure_navigate(line)
                },
                on_project_search = on_project_search,
                file_diagnostics = file_diagnostics,
                on_diagnostic_click = on_diagnostic_click,
                on_tool_selected = { selected_tool = it },
            on_new_file = { parent_path ->
                create_dialog_request = editor_create_dialog_request(editor_create_dialog_type.FILE, parent_path)
            },
            on_new_folder = { parent_path ->
                create_dialog_request = editor_create_dialog_request(editor_create_dialog_type.FOLDER, parent_path)
            },
            on_refresh = on_refresh_files,
            on_rename_node = on_rename_file_tree_node,
            on_delete_node = on_delete_file_tree_node,
            on_directory_click = on_directory_click,
            on_file_click = { path ->
                drawer_open = false
                on_file_click(path)
            },
            on_file_position_click = { path, line, column ->
                drawer_open = false
                on_file_position_click(path, line, column)
            },
            on_drag = { drag_amount ->
                drawer_width = (drawer_width.value + drag_amount.x).coerceIn(300f, 480f).dp
            }
        )
        }

        if (drawer_open) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(start = drawer_width)
                    .pointerInput(Unit) {
                        detectTapGestures(onTap = { drawer_open = false })
                    }
            )
        }


        create_dialog_request?.let { request ->
            editor_create_entry_dialog(
                is_folder = request.type == editor_create_dialog_type.FOLDER,
                on_confirm = { name, template ->
                    create_dialog_request = null
                    if (request.type == editor_create_dialog_type.FOLDER) {
                        on_create_folder(request.parent_path, name)
                    } else {
                        on_create_file(request.parent_path, name, template)
                    }
                },
                on_dismiss = { create_dialog_request = null }
            )
        }

        active_tool?.let { tool ->
            editor_tool_dialog(
                tool_def = tool,
                on_dismiss = { active_tool = null }
            )
        }

        // AI 助手全屏页面（点击顶栏 AI 图标进入）
        AnimatedVisibility(
            visible = show_ai_page,
            enter = slideInHorizontally(
                animationSpec = tween(motion.BASE, easing = motion.quiet),
                initialOffsetX = { it }
            ) + androidx.compose.animation.fadeIn(),
            exit = slideOutHorizontally(
                animationSpec = tween(motion.BASE, easing = motion.quiet),
                targetOffsetX = { it }
            ) + androidx.compose.animation.fadeOut()
        ) {
            val agent = ai_agent
            if (agent != null) {
                com.jmwl.gostudio.ui.screens.ai.ai_chat_page(
                    agent = agent,
                    on_close = { show_ai_page = false },
                    on_open_settings = {
                        // 保持 AI 页面在下层：设置页关闭后返回这里，而不是退出到工作区
                        on_open_ai_settings()
                    },
                    current_choice = ai_current_choice,
                    instances = ai_instances,
                    on_model_choice = on_ai_model_choice,
                    project_dir = java.io.File(project_root_path),
                    global_prompts_dir = ai_global_prompts_dir,
                    project_prompts_dir = ai_project_prompts_dir,
                    back_handler_enabled = !ai_settings_visible,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                show_ai_page = false
            }
        }

        // 项目配置全屏页（顶栏「更多」菜单进入；原为左侧抽屉工具页签）
        AnimatedVisibility(
            visible = show_project_config,
            enter = slideInHorizontally(
                animationSpec = tween(motion.BASE, easing = motion.quiet),
                initialOffsetX = { it }
            ) + androidx.compose.animation.fadeIn(),
            exit = slideOutHorizontally(
                animationSpec = tween(motion.BASE, easing = motion.quiet),
                targetOffsetX = { it }
            ) + androidx.compose.animation.fadeOut()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(colors.editor_bg)
                    .statusBarsPadding()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "项目配置",
                        color = colors.editor_text,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier
                            .weight(1f)
                            .padding(start = 18.dp)
                    )
                    IconButton(onClick = { show_project_config = false }) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "关闭",
                            tint = colors.editor_toolbar_icon
                        )
                    }
                }
                editor_project_config_panel(
                    project_root_path = project_root_path,
                    is_app_project = is_app_project,
                    on_apply = on_project_config_apply,
                    on_compile = on_project_config_compile,
                    on_compile_cancel = on_compile_cancel,
                    compile_state = compile_dialog_state,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }
}

/**
 * Markdown 工作区预览：复用 AI 会话的轻量 Markdown 渲染器。
 */
@Composable
private fun markdown_preview_panel(
    text: String,
    modifier: Modifier = Modifier
) {
    val colors = app_theme_provider.colors

    Box(
        modifier = modifier
            .background(colors.editor_bg)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        if (text.isBlank()) {
            Text(
                text = "空白 Markdown 文件",
                color = colors.editor_hint,
                fontSize = 14.sp
            )
        } else {
            ai_markdown_text(
                text = text,
                color = colors.editor_text
            )
        }
    }
}

/**
 * 光标区叶子组合：悬浮按钮（含搜索入口）。
 * 光标选区/可跳转状态只在这里读取，光标每次移动仅重组此组合，
 * 避免整个 editor_screen（顶栏/标签栏）跟着重新执行。
 * 行列号 chip 在别处（左下角）单独作为叶子读取。
 */
@Composable
private fun editor_cursor_overlays(
    cursor_selected_provider: () -> Boolean,
    can_undo: Boolean,
    can_redo: Boolean,
    has_changes: Boolean,
    can_format: Boolean,
    show_format: Boolean,
    show_save: Boolean,
    show_search: Boolean,
    search_active: Boolean,
    on_undo: () -> Unit,
    on_redo: () -> Unit,
    on_format: () -> Unit,
    on_save: () -> Unit,
    on_toggle_search: () -> Unit
) {
    editor_floating_actions(
        can_undo = can_undo,
        can_redo = can_redo,
        has_changes = has_changes,
        can_format = can_format,
        format_selection = cursor_selected_provider(),
        show_format = show_format,
        show_save = show_save,
        show_search = show_search,
        search_active = search_active,
        on_undo = on_undo,
        on_redo = on_redo,
        on_format = on_format,
        on_save = on_save,
        on_toggle_search = on_toggle_search
    )
}

@Composable
private fun BoxScope.editor_definition_overlay(
    can_goto_definition_flow: kotlinx.coroutines.flow.StateFlow<Boolean>,
    goto_definition_running: Boolean,
    on_goto_definition: () -> Unit,
    bottom_padding: androidx.compose.ui.unit.Dp
) {
    val can_goto by can_goto_definition_flow.collectAsState()
    if (can_goto) {
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .imePadding()
                .navigationBarsPadding()
                .padding(bottom = bottom_padding)
        ) {
            goto_definition_chip(
                running = goto_definition_running,
                on_click = on_goto_definition
            )
        }
    }
}

/**
 * 左下角行列号 chip：独立的叶子组合，光标移动只重组它。
 */
@Composable
private fun editor_cursor_position_chip(
    cursor_line_provider: () -> Int,
    cursor_column_provider: () -> Int
) {
    cursor_chip(
        line = cursor_line_provider(),
        column = cursor_column_provider()
    )
}
