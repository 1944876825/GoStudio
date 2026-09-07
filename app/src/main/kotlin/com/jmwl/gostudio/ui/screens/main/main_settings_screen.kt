package com.jmwl.gostudio.ui.screens.main

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jmwl.gostudio.ui.components.sub_page_top_bar
import com.jmwl.gostudio.ui.theme.*
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun main_settings_screen(
    on_back: () -> Unit,
    on_theme_click: () -> Unit = {},
    on_editor_theme_click: () -> Unit = {},
    on_editor_click: () -> Unit = {},
    on_ai_click: () -> Unit = {},
    on_git_click: () -> Unit = {},
    on_about_click: () -> Unit = {},
    on_feedback_click: () -> Unit = {},
    on_tools_click: () -> Unit = {},
    on_plugins_click: () -> Unit = {}
) {
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = Color.Transparent,
        topBar = { sub_page_top_bar("设置", on_back) }
    ) { padding_values ->
        main_settings_content(
            on_theme_click = on_theme_click,
            on_editor_theme_click = on_editor_theme_click,
            on_editor_click = on_editor_click,
            on_ai_click = on_ai_click,
            on_git_click = on_git_click,
            on_about_click = on_about_click,
            on_feedback_click = on_feedback_click,
            on_tools_click = on_tools_click,
            on_plugins_click = on_plugins_click,
            show_title = false,
            modifier = Modifier.padding(padding_values)
        )
    }
}

/**
 * 设置页内容主体（无返回按钮）：路由版 [main_settings_screen] 与
 * 首页底部导航的「设置」页签共用。
 * [show_title] = false 时不渲染页内大标题（由顶栏承担标题）。
 */
@Composable
fun main_settings_content(
    on_theme_click: () -> Unit = {},
    on_editor_theme_click: () -> Unit = {},
    on_editor_click: () -> Unit = {},
    on_ai_click: () -> Unit = {},
    on_git_click: () -> Unit = {},
    on_about_click: () -> Unit = {},
    on_feedback_click: () -> Unit = {},
    on_tools_click: () -> Unit = {},
    on_plugins_click: () -> Unit = {},
    show_title: Boolean = true,
    modifier: Modifier = Modifier
) {
    val colors = app_theme_provider.colors
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val update_controller = remember { com.jmwl.gostudio.update.app_update_controller(context) }
    var update_checking by remember { mutableStateOf(false) }

    // 模块代理源切换弹窗（应用后刷新副标题显示）
    var show_goproxy_dialog by remember { mutableStateOf(false) }
    var goproxy_display_name by remember { mutableStateOf(com.jmwl.gostudio.toolchain.goproxy_store.current_display_name()) }
    if (show_goproxy_dialog) {
        com.jmwl.gostudio.ui.dialogs.main.goproxy_settings_dialog(
            on_dismiss = {
                show_goproxy_dialog = false
                goproxy_display_name = com.jmwl.gostudio.toolchain.goproxy_store.current_display_name()
            }
        )
    }

    // 手动检查更新
    fun start_update_check() {
        if (update_checking) return
        update_checking = true
        scope.launch {
            val result = update_controller.check()
            update_checking = false
            when (result) {
                is com.jmwl.gostudio.update.app_update_check_result.UpToDate ->
                    android.widget.Toast.makeText(
                        context,
                        "当前已是最新版本 v${com.jmwl.gostudio.update.app_update_controller.current_version_name(context)}",
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                is com.jmwl.gostudio.update.app_update_check_result.Error ->
                    android.widget.Toast.makeText(
                        context,
                        "检查更新失败：${result.message}",
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                is com.jmwl.gostudio.update.app_update_check_result.UpdateAvailable -> Unit // 弹窗自动弹出
            }
        }
    }

    if (update_checking) {
        com.jmwl.gostudio.ui.dialogs.main.app_update_checking_dialog()
    }
    com.jmwl.gostudio.ui.dialogs.main.app_update_dialog(
        controller = update_controller,
        on_dismiss = { update_controller.reset() }
    )

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        Spacer(modifier = Modifier.height(30.dp))

        if (show_title) {
            Column(
                horizontalAlignment = Alignment.Start,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
            ) {
                Text(
                    text = "设置",
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold,
                    color = colors.title_large
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "应用设置与偏好",
                    fontSize = 13.sp,
                    color = colors.subtitle
                )
            }

            Spacer(modifier = Modifier.height(30.dp))
        }

        settings_group(colors = colors, title = "外观") {
            main_settings_card_item(
                icon = Icons.Default.BrightnessMedium,
                title = "主题模式",
                subtitle = "深色/浅色、字号缩放",
                colors = colors,
                onClick = on_theme_click,
                is_top = true,
                is_bottom = false
            )
            Spacer(modifier = Modifier.height(1.dp))
            main_settings_card_item(
                icon = Icons.Default.Palette,
                title = "编辑器主题",
                subtitle = "代码高亮配色",
                colors = colors,
                onClick = on_editor_theme_click,
                is_top = false,
                is_bottom = true
            )
        }

        Spacer(modifier = Modifier.height(22.dp))

        settings_group(colors = colors, title = "编辑器") {
            main_settings_card_item(
                icon = Icons.Default.Code,
                title = "编辑器",
                subtitle = "字体、缩进、自动保存、gopls",
                colors = colors,
                onClick = on_editor_click,
                is_top = true,
                is_bottom = true
            )
        }

        Spacer(modifier = Modifier.height(22.dp))

        settings_group(colors = colors, title = "工具") {
            main_settings_card_item(
                icon = Icons.Default.Build,
                title = "开发工具",
                subtitle = "Go 工具链、gopls、git 安装与管理",
                colors = colors,
                onClick = on_tools_click,
                is_top = true,
                is_bottom = false
            )
            Spacer(modifier = Modifier.height(1.dp))
            main_settings_card_item(
                icon = Icons.Default.Public,
                title = "模块代理 (GOPROXY)",
                subtitle = "当前：$goproxy_display_name · 内置国内源，支持自定义与测速",
                colors = colors,
                onClick = { show_goproxy_dialog = true },
                is_top = false,
                is_bottom = false
            )
            Spacer(modifier = Modifier.height(1.dp))
            main_settings_card_item(
                icon = Icons.Default.Key,
                title = "Git 登录",
                subtitle = "Token、账号密码、SSH Key、OAuth",
                colors = colors,
                onClick = on_git_click,
                is_top = false,
                is_bottom = false
            )
            Spacer(modifier = Modifier.height(1.dp))
            main_settings_card_item(
                icon = Icons.Default.Extension,
                title = "插件",
                subtitle = "管理 GoStudio 插件",
                colors = colors,
                onClick = on_plugins_click,
                is_top = false,
                is_bottom = true
            )
        }

        Spacer(modifier = Modifier.height(22.dp))

        settings_group(colors = colors, title = "AI") {
            main_settings_card_item(
                icon = Icons.Default.AutoAwesome,
                title = "AI 助手",
                subtitle = "提供商、API Key、工具与 MCP",
                colors = colors,
                onClick = on_ai_click,
                is_top = true,
                is_bottom = true
            )
        }

        Spacer(modifier = Modifier.height(22.dp))

        settings_group(colors = colors, title = "关于") {
            main_settings_card_item(
                icon = Icons.Default.SystemUpdateAlt,
                title = "检查更新",
                subtitle = "当前版本 v${com.jmwl.gostudio.update.app_update_controller.current_version_name(context)} · 通过 GitHub 检查新版本",
                colors = colors,
                onClick = { start_update_check() },
                is_top = true,
                is_bottom = false
            )
            Spacer(modifier = Modifier.height(1.dp))
            main_settings_card_item(
                icon = Icons.Default.BugReport,
                title = "问题反馈",
                subtitle = "查看本设备的错误上报记录与处理状态",
                colors = colors,
                onClick = on_feedback_click,
                is_top = false,
                is_bottom = false
            )
            Spacer(modifier = Modifier.height(1.dp))
            main_settings_card_item(
                icon = Icons.Default.Info,
                title = "关于",
                subtitle = "版本信息、开源许可",
                colors = colors,
                onClick = on_about_click,
                is_top = false,
                is_bottom = true
            )
        }

        Spacer(modifier = Modifier.height(40.dp))
    }
}

@Composable
private fun settings_group(
    colors: app_colors,
    title: String,
    content: @Composable () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp)
    ) {
        Text(
            text = title,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            color = colors.title_highlight,
            modifier = Modifier.padding(start = 4.dp, bottom = 12.dp)
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
        ) {
            content()
        }
    }
}

@Composable
private fun main_settings_card_item(
    icon: ImageVector,
    title: String,
    subtitle: String,
    colors: app_colors,
    onClick: () -> Unit,
    is_top: Boolean = false,
    is_bottom: Boolean = false
) {
    val corner_radius = 12.dp
    val shape = when {
        is_top && is_bottom -> RoundedCornerShape(corner_radius)
        is_top -> RoundedCornerShape(topStart = corner_radius, topEnd = corner_radius, bottomStart = 0.dp, bottomEnd = 0.dp)
        is_bottom -> RoundedCornerShape(topStart = 0.dp, topEnd = 0.dp, bottomStart = corner_radius, bottomEnd = corner_radius)
        else -> RoundedCornerShape(0.dp)
    }
    
    val interaction_source = remember { MutableInteractionSource() }
    val is_pressed by interaction_source.collectIsPressedAsState()
    
    val background_color = when {
        is_pressed -> colors.card_pressed
        else -> colors.card_bg
    }
    
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(background_color)
            .clickable(
                interactionSource = interaction_source,
                indication = ripple(bounded = true)
            ) { onClick() }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(colors.card_icon_bg.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    icon,
                    contentDescription = title,
                    tint = colors.card_icon_bg,
                    modifier = Modifier.size(16.dp)
                )
            }
            
            Column(
                verticalArrangement = Arrangement.spacedBy(2.dp),
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = title,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.card_text_title
                )
                Text(
                    text = subtitle,
                    fontSize = 10.sp,
                    lineHeight = 10.sp,
                    fontWeight = FontWeight.Light,
                    color = colors.card_text_subtitle
                )
            }
            
            Icon(
                Icons.Default.ChevronRight,
                contentDescription = "进入",
                tint = colors.card_chevron,
                modifier = Modifier.size(16.dp)
            )
        }
    }
}