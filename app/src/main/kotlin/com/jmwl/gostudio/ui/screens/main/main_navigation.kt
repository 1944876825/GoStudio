package com.jmwl.gostudio.ui.screens.main

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.core.tween
import androidx.navigation.NavBackStackEntry
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import com.jmwl.gostudio.ai.ai_environment_context
import com.jmwl.gostudio.ai.ai_agent_loop
import com.jmwl.gostudio.ai.ai_provider
import com.jmwl.gostudio.ai.tools.ai_tool_registry
import com.jmwl.gostudio.ai.load_ai_settings
import com.jmwl.gostudio.ai.save_ai_settings
import com.jmwl.gostudio.ui.dialogs.common.install_progress_dialog
import com.jmwl.gostudio.ui.dialogs.main.clone_project_dialog
import com.jmwl.gostudio.ui.dialogs.main.new_project_dialog
import com.jmwl.gostudio.ui.dialogs.main.open_project_dialog
import com.jmwl.gostudio.ui.dialogs.main.toolchain_custom_install_dialog
import com.jmwl.gostudio.ui.screens.ai.ai_chat_screen
import com.jmwl.gostudio.ui.screens.ai.ai_settings_screen
import com.jmwl.gostudio.ui.screens.editor.editor_settings_screen
import com.jmwl.gostudio.ui.screens.editor.editor_theme_settings_screen
import com.jmwl.gostudio.ui.screens.main.git_settings_screen
import com.jmwl.gostudio.ui.theme.app_theme_preset
import com.jmwl.gostudio.ui.theme.app_theme_provider
import com.jmwl.gostudio.ui.theme.app_theme_type
import com.jmwl.gostudio.ui.theme.motion
import kotlinx.coroutines.launch

/** 路由层级表：数字越大越「深」。页面过渡按层级判断前进/后退方向。 */
private val route_levels = mapOf(
    "main" to 0,
    "tools" to 1,
    "plugins" to 1,
    "plugin_market" to 2,
    "settings" to 1,
    "agent" to 1,
    "learn" to 1,
    "about" to 2,
    "theme_settings" to 2,
    "editor_settings" to 2,
    "ai_settings" to 2,
    "git_settings" to 2,
    "editor_theme_settings" to 3,
    "learn_track" to 2,
    "learn_lesson" to 3
)

private fun route_level(route: String?): Int = route_levels[route] ?: 1

/**
 * 方向感知 shared-axis X 过渡（移植自 CodeAssist ScreenTransition.mobileSharedAxis）：
 * 前进 = 新页从右侧 1/4 屏宽滑入 + 淡入、旧页左滑淡出；后退反向。
 * 空间感明确，符合手机原生导航直觉。
 */
private fun AnimatedContentTransitionScope<NavBackStackEntry>.shared_axis_forward(): Boolean =
    route_level(targetState.destination.route) >= route_level(initialState.destination.route)

private fun shared_axis_enter(forward: Boolean): EnterTransition {
    val dir = if (forward) 1 else -1
    return slideInHorizontally(tween(motion.BASE, easing = motion.quiet)) { dir * it / 4 } +
        fadeIn(tween(motion.BASE, easing = motion.soft))
}

private fun shared_axis_exit(forward: Boolean): ExitTransition {
    val dir = if (forward) 1 else -1
    return slideOutHorizontally(tween(motion.BASE, easing = motion.quiet)) { -dir * it / 4 } +
        fadeOut(tween(motion.BASE, easing = motion.soft))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun main_navigation(
    recent_projects: List<recent_project>,
    toolchain_status: main_tools_install_status,
    current_theme: app_theme_type,
    current_theme_preset: app_theme_preset,
    custom_theme_accent: Int,
    scale_value: Float,
    toolchain_tasks: List<toolchain_trigger>,
    custom_toolchain_dialog: toolchain_custom_install_request?,
    on_back_to_background: () -> Unit,
    on_terminal: () -> Unit,
    on_project_click: (recent_project) -> Unit,
    on_project_copy: (recent_project) -> Unit,
    on_project_delete: (recent_project) -> Unit,
    on_project_export: (recent_project, android.net.Uri) -> Unit,
    on_create_project: (String, String, String, String) -> Unit,
    on_open_project: (String) -> Unit,
    on_clone_project: suspend (String, (String) -> Unit, (Int) -> Unit) -> Boolean,
    on_toolchain_trigger_change: (toolchain_trigger?) -> Unit,
    on_custom_toolchain_dialog_change: (toolchain_custom_install_request?) -> Unit,
    on_theme_change: (app_theme_type) -> Unit,
    on_theme_preset_change: (app_theme_preset) -> Unit,
    on_custom_theme_accent_change: (Int) -> Unit,
    on_scale_change: (Float) -> Unit,
    on_run_toolchain_task: suspend (toolchain_trigger, (String) -> Unit, (Int) -> Unit) -> Boolean,
    on_toolchain_task_success: (toolchain_trigger) -> Unit
) {
    val colors = app_theme_provider.colors
    val nav_controller = rememberNavController()
    val current_back_stack by nav_controller.currentBackStackEntryAsState()
    val context = LocalContext.current
    val coroutine_scope = rememberCoroutineScope()

    // 会话级模型选择 override（null=跟随全局设置）
    var ai_session_choice by remember { mutableStateOf<com.jmwl.gostudio.ai.ai_model_choice?>(null) }

    // AI agent：主界面用通用问答（无项目上下文，不带文件工具）
    val ai_agent = remember {
        val home_dir = com.jmwl.gostudio.toolchain.toolchain_runtime_provider.paths().home_dir
        val ai_root = java.io.File(home_dir, ".ai").apply { mkdirs() }
        val global_skills_dir = java.io.File(ai_root, "skills")
        val global_prompts_dir = java.io.File(ai_root, "prompts")
        runCatching { com.jmwl.gostudio.ai.skills.release_builtin_skills(context, global_skills_dir) }
        val skill_mgr = com.jmwl.gostudio.ai.skills.ai_skill_manager(global_skills_dir, null, com.jmwl.gostudio.plugins.plugin_manager.skill_dirs())
        ai_agent_loop(
            settings_provider = {
                // 会话选择优先（快照含 base_url/key）；用内存缓存避免每轮 Keystore I/O
                val base = com.jmwl.gostudio.ai.cached_ai_settings(context)
                ai_session_choice?.let { c ->
                    base.copy(
                        provider = c.provider,
                        model = c.model,
                        base_url = c.base_url.ifBlank { base.base_url },
                        api_key = c.api_key.ifBlank { base.api_key }
                    )
                } ?: base
            },
            env_provider = { ai_environment_context() },
            tool_registry = ai_tool_registry(),
            scope_launcher = { block -> coroutine_scope.launch { block() } },
            input_processor = com.jmwl.gostudio.ai.ai_input_processor(
                project_dir = null,
                skill_manager = skill_mgr,
                global_prompts_dir = global_prompts_dir,
                project_prompts_dir = null
            ),
            session_store = com.jmwl.gostudio.ai.ai_session_store(java.io.File(ai_root, "sessions")),
            session_id = "global",
            skill_manager = skill_mgr
        )
    }
    var ai_settings_state by remember { mutableStateOf(load_ai_settings(context)) }

    var show_new_project_dialog by remember { mutableStateOf(false) }
    var show_open_project_dialog by remember { mutableStateOf(false) }
    var github_clone_url by remember { mutableStateOf<String?>(null) }
    var show_clone_project_dialog by remember { mutableStateOf(false) }
    val active_toolchain_trigger = toolchain_tasks.firstOrNull()
    var toolchain_dialog_visible by remember(active_toolchain_trigger) { mutableStateOf(true) }

    // 首页双击返回退出：第一次提示，2 秒内再按才退到后台
    var last_back_press_at by remember { mutableStateOf(0L) }

    BackHandler(enabled = true) {
        if (current_back_stack?.destination?.route != "main") {
            nav_controller.popBackStack()
        } else {
            val now = System.currentTimeMillis()
            if (now - last_back_press_at < 2000) {
                on_back_to_background()
            } else {
                last_back_press_at = now
                android.widget.Toast.makeText(context, "再按一次返回键退出", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.gradient_start)
    ) {
        NavHost(
            navController = nav_controller,
            startDestination = "main",
            enterTransition = { shared_axis_enter(shared_axis_forward()) },
            exitTransition = { shared_axis_exit(shared_axis_forward()) },
            popEnterTransition = { shared_axis_enter(forward = false) },
            popExitTransition = { shared_axis_exit(forward = false) }
        ) {
            composable("main") {
                main_screen(
                    on_new_project = { show_new_project_dialog = true },
                    on_open_project = { show_open_project_dialog = true },
                    on_clone_project = { show_clone_project_dialog = true },
                    recent_projects = recent_projects,
                    on_tools = { nav_controller.navigate("tools") },
                    on_plugins = { nav_controller.navigate("plugins") },
                    on_terminal = on_terminal,
                    on_ai = { nav_controller.navigate("agent") },
                    on_learn_open_track = { track_id -> nav_controller.navigate("learn_track/$track_id") },
                    on_learn_resume = { lesson_id, step_index ->
                        nav_controller.navigate("learn_lesson/$lesson_id?step=$step_index")
                    },
                    on_theme_click = { nav_controller.navigate("theme_settings") },
                    on_editor_theme_click = { nav_controller.navigate("editor_theme_settings") },
                    on_editor_click = { nav_controller.navigate("editor_settings") },
                    on_ai_settings_click = { nav_controller.navigate("ai_settings") },
                    on_git_settings_click = { nav_controller.navigate("git_settings") },
                    on_about_click = { nav_controller.navigate("about") },
                    on_project_click = on_project_click,
                    on_project_copy = on_project_copy,
                    on_project_delete = on_project_delete,
                    on_project_export = on_project_export
                )
            }

            composable("tools") {
                main_tools_screen(
                    on_back = { nav_controller.popBackStack() },
                    on_install_go = {
                        on_toolchain_trigger_change(
                            toolchain_trigger(
                                title = "安装 Go 工具链",
                                action = toolchain_action.INSTALL_GO
                            )
                        )
                    },
                    on_install_gopls = {
                        on_toolchain_trigger_change(
                            toolchain_trigger(
                                title = "安装 gopls",
                                action = toolchain_action.INSTALL_GOPLS
                            )
                        )
                    },
                    on_install_git = {
                        on_toolchain_trigger_change(
                            toolchain_trigger(
                                title = "安装 git",
                                action = toolchain_action.INSTALL_GIT
                            )
                        )
                    },
                    on_install_gcc = {
                        on_toolchain_trigger_change(
                            toolchain_trigger(
                                title = "安装 gcc",
                                action = toolchain_action.INSTALL_GCC
                            )
                        )
                    },
                    on_install_garble = {
                        on_toolchain_trigger_change(
                            toolchain_trigger(
                                title = "安装 garble",
                                action = toolchain_action.INSTALL_GARBLE
                            )
                        )
                    },
                    install_status = toolchain_status
                )
            }

            composable("plugins") {
                plugins_screen(
                    on_back = { nav_controller.popBackStack() },
                    on_browse = { nav_controller.navigate("plugin_market") }
                )
            }
            composable("plugin_market") { plugin_market_screen(on_back = { nav_controller.popBackStack() }) }
            composable("settings") {
                main_settings_screen(
                    on_back = { nav_controller.popBackStack() },
                    on_theme_click = { nav_controller.navigate("theme_settings") },
                    on_editor_theme_click = { nav_controller.navigate("editor_theme_settings") },
                    on_editor_click = { nav_controller.navigate("editor_settings") },
                    on_ai_click = { nav_controller.navigate("ai_settings") },
                    on_git_click = { nav_controller.navigate("git_settings") },
                    on_about_click = { nav_controller.navigate("about") }
                )
            }
            composable("about") {
                main_about_screen(
                    on_back = { nav_controller.popBackStack() }
                )
            }
            composable("theme_settings") {
                main_theme_settings_screen(
                    current_theme = current_theme,
                    current_preset = current_theme_preset,
                    custom_accent = custom_theme_accent,
                    scale_value = scale_value,
                    on_theme_change = on_theme_change,
                    on_preset_change = on_theme_preset_change,
                    on_custom_accent_change = on_custom_theme_accent_change,
                    on_scale_change = on_scale_change,
                    on_back = { nav_controller.popBackStack() }
                )
            }
            composable("editor_settings") {
                editor_settings_screen(
                    on_back = { nav_controller.popBackStack() },
                    on_theme_click = { nav_controller.navigate("editor_theme_settings") }
                )
            }
            composable("editor_theme_settings") { editor_theme_settings_screen(on_back = { nav_controller.popBackStack() }) }
            composable("agent") {
                androidx.compose.runtime.LaunchedEffect(Unit) { ai_agent.initialize() }
                ai_chat_screen(
                    agent = ai_agent,
                    on_back = { nav_controller.popBackStack() },
                    on_open_settings = { nav_controller.navigate("ai_settings") },
                    current_choice = ai_session_choice ?: com.jmwl.gostudio.ai.ai_model_choice.from_settings(ai_settings_state),
                    instances = ai_settings_state.instances,
                    on_model_choice = { choice -> ai_session_choice = choice }
                )
            }
            composable("learn") {
                com.jmwl.gostudio.ui.screens.learn.learn_home_screen(
                    on_back = { nav_controller.popBackStack() },
                    on_open_track = { track_id -> nav_controller.navigate("learn_track/$track_id") },
                    on_resume = { lesson_id, step_index ->
                        nav_controller.navigate("learn_lesson/$lesson_id?step=$step_index")
                    }
                )
            }
            composable(
                route = "learn_track/{track_id}",
                arguments = listOf(androidx.navigation.navArgument("track_id") { type = androidx.navigation.NavType.StringType })
            ) { entry ->
                val track_id = entry.arguments?.getString("track_id").orEmpty()
                com.jmwl.gostudio.ui.screens.learn.learn_track_screen(
                    track_id = track_id,
                    on_back = { nav_controller.popBackStack() },
                    on_open_lesson = { lesson_id, step_index ->
                        nav_controller.navigate("learn_lesson/$lesson_id?step=$step_index")
                    }
                )
            }
            composable(
                route = "learn_lesson/{lesson_id}?step={step}",
                arguments = listOf(
                    androidx.navigation.navArgument("lesson_id") { type = androidx.navigation.NavType.StringType },
                    androidx.navigation.navArgument("step") {
                        type = androidx.navigation.NavType.IntType
                        defaultValue = 0
                    }
                )
            ) { entry ->
                val lesson_id = entry.arguments?.getString("lesson_id").orEmpty()
                val step = entry.arguments?.getInt("step") ?: 0
                com.jmwl.gostudio.ui.screens.learn.learn_player_screen(
                    lesson_id = lesson_id,
                    initial_step = step,
                    on_exit = { nav_controller.popBackStack() }
                )
            }
            composable("git_settings") {
                git_settings_screen(on_back = { nav_controller.popBackStack() })
            }

            composable("ai_settings") {
                ai_settings_screen(
                    initial = ai_settings_state,
                    on_back = { nav_controller.popBackStack() },
                    on_save = { new_settings ->
                        save_ai_settings(context, new_settings)
                        ai_settings_state = new_settings
                        nav_controller.popBackStack()
                    },
                    on_change = { new_settings ->
                        save_ai_settings(context, new_settings)
                        ai_settings_state = new_settings
                    }
                )
            }
        }

        if (active_toolchain_trigger != null && !toolchain_dialog_visible) {
            Button(
                onClick = { toolchain_dialog_visible = true },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(16.dp)
                    .wrapContentSize(),
                colors = ButtonDefaults.buttonColors(containerColor = colors.card_bg)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Download, contentDescription = null, tint = colors.card_text_title, modifier = Modifier.size(16.dp))
                    Text(text = if (toolchain_tasks.size > 1) "后台任务 ${toolchain_tasks.size}" else "后台任务", color = colors.card_text_title)
                }
            }
        }
    }

    if (show_new_project_dialog) {
        new_project_dialog(
            on_dismiss = { show_new_project_dialog = false },
            on_create = { project_name, template_id, app_name, app_package ->
                show_new_project_dialog = false
                on_create_project(project_name, template_id, app_name, app_package)
            }
        )
    }

    if (show_open_project_dialog) {
        open_project_dialog(
            on_dismiss = { show_open_project_dialog = false },
            on_open = { project_path ->
                show_open_project_dialog = false
                on_open_project(project_path)
            }
        )
    }

    if (show_clone_project_dialog) {
        clone_project_dialog(
            on_dismiss = { show_clone_project_dialog = false },
            on_clone = { repository_url ->
                show_clone_project_dialog = false
                github_clone_url = repository_url
            }
        )
    }

    github_clone_url?.let { repository_url ->
        install_progress_dialog(
            title = "克隆 Git 项目",
            task = { on_log, on_progress ->
                on_clone_project(repository_url, on_log, on_progress)
            },
            on_dismiss = { github_clone_url = null },
            on_success = { github_clone_url = null },
        )
    }

    custom_toolchain_dialog?.let { request ->
        toolchain_custom_install_dialog(
            title = request.title,
            on_dismiss = { on_custom_toolchain_dialog_change(null) },
            on_install = { archive_path ->
                on_custom_toolchain_dialog_change(null)
                request.on_install(archive_path)
            }
        )
    }

    active_toolchain_trigger?.let { trigger ->
        install_progress_dialog(
            title = trigger.title,
            task = { on_log, on_progress -> on_run_toolchain_task(trigger, on_log, on_progress) },
            on_dismiss = {
                toolchain_dialog_visible = true
                on_toolchain_trigger_change(null)
            },
            on_success = {
                toolchain_dialog_visible = true
                on_toolchain_task_success(trigger)
            },
            on_minimize = { toolchain_dialog_visible = false },
            visible = toolchain_dialog_visible
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun placeholder_screen(title: String, on_back: () -> Unit) {
    val colors = app_theme_provider.colors

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title, color = colors.title_large) },
                navigationIcon = {
                    IconButton(onClick = on_back) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回",
                            tint = colors.top_button_icon
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
            )
        },
        containerColor = Color.Transparent
    ) { padding_values ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding_values),
            contentAlignment = Alignment.Center
        ) {
            Text(text = "$title 页面开发中...", color = colors.subtitle)
        }
    }
}
