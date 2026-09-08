package com.jmwl.gostudio.ui.screens.editor

import android.view.ViewGroup
import android.widget.FrameLayout
import com.jmwl.gostudio.editor.core.R
import com.jmwl.gostudio.editor.model.editor_tab_item

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.jmwl.gostudio.ui.theme.app_theme_provider
import io.github.rosemoe.sora.widget.CodeEditor

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun editor_tabs_bar(
    tabs: List<editor_tab_item>,
    selected_tab_path: String?,
    toolbar_visible: Boolean,
    on_toggle_toolbar: () -> Unit,
    on_select_tab: (String) -> Unit,
    on_pin_tab: (String) -> Unit,
    on_close_tab: (String) -> Unit,
    on_close_other_tabs: (String) -> Unit,
    on_close_all_tabs: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = app_theme_provider.colors
    val outline = MaterialTheme.colorScheme.outlineVariant

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(colors.editor_bg)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(40.dp)
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(38.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = on_toggle_toolbar
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (toolbar_visible) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = if (toolbar_visible) "隐藏工具栏" else "显示工具栏",
                    tint = colors.editor_tab_add_icon,
                    modifier = Modifier.size(20.dp)
                )
            }

            Row(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (tabs.isEmpty()) {
                    Text(
                        text = "未打开任何文件",
                        color = colors.editor_tab_unselected_content,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(horizontal = 4.dp)
                    )
                }

                tabs.forEach { tab ->
                    val selected = tab.path == selected_tab_path
                    val text_color = if (selected) colors.editor_tab_selected_text else colors.editor_tab_unselected_content
                    val card_shape = RoundedCornerShape(11.dp)
                    val card_border = if (selected) {
                        BorderStroke(1.dp, colors.title_highlight.copy(alpha = 0.48f))
                    } else {
                        BorderStroke(1.dp, outline.copy(alpha = 0.38f))
                    }
                    var menu_expanded by remember(tab.path) { mutableStateOf(false) }

                    Box {
                        Row(
                            modifier = Modifier
                                .widthIn(min = 112.dp, max = 230.dp)
                                .height(32.dp)
                                .background(
                                    color = if (selected) colors.editor_tab_selected_bg else Color.Transparent,
                                    shape = card_shape
                                )
                                .border(card_border, card_shape)
                                .clip(card_shape)
                                .combinedClickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                    onClick = {
                                        if (!selected) on_select_tab(tab.path)
                                    },
                                    onLongClick = { menu_expanded = true }
                                )
                                .padding(start = 10.dp, end = 5.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (tab.pinned) {
                                Icon(
                                    imageVector = Icons.Default.PushPin,
                                    contentDescription = "已置顶",
                                    tint = colors.editor_icon,
                                    modifier = Modifier.size(12.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                            }

                            val tab_icon = editor_file_icon(tab.title)
                            Icon(
                                imageVector = tab_icon.icon,
                                contentDescription = null,
                                tint = tab_icon.tint,
                                modifier = Modifier.size(14.dp)
                            )

                            Text(
                                text = tab.title,
                                fontSize = 12.sp,
                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                color = text_color,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier
                                    .padding(start = 6.dp)
                                    .weight(1f, fill = false)
                                    .widthIn(max = 128.dp)
                            )

                            if (tab.has_changes) {
                                Box(
                                    modifier = Modifier
                                        .padding(start = 7.dp)
                                        .size(6.dp)
                                        .clip(CircleShape)
                                        .background(colors.editor_tab_selected_icon)
                                )
                            }

                            Box(
                                modifier = Modifier
                                    .padding(start = 3.dp)
                                    .size(24.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null,
                                        enabled = !tab.pinned
                                    ) { on_close_tab(tab.path) },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "关闭 ${tab.title}",
                                    tint = if (tab.pinned) colors.editor_tab_unselected_content.copy(alpha = 0.35f) else colors.editor_tab_unselected_content,
                                    modifier = Modifier.size(14.dp)
                                )
                            }
                        }

                        editor_dropdown_menu(
                            expanded = menu_expanded,
                            on_dismiss_request = { menu_expanded = false }
                        ) {
                            editor_menu_item(
                                icon = Icons.Default.PushPin,
                                label = if (tab.pinned) "取消置顶" else "置顶"
                            ) {
                                menu_expanded = false
                                on_pin_tab(tab.path)
                            }
                            editor_menu_divider()
                            editor_menu_item(
                                icon = Icons.Default.Close,
                                label = "关闭当前",
                                enabled = !tab.pinned
                            ) {
                                menu_expanded = false
                                on_close_tab(tab.path)
                            }
                            editor_menu_item(
                                icon = Icons.Default.Close,
                                label = "关闭其他",
                                enabled = tabs.any { it.path != tab.path && !it.pinned }
                            ) {
                                menu_expanded = false
                                on_close_other_tabs(tab.path)
                            }
                            editor_menu_item(
                                icon = Icons.Default.Close,
                                label = "关闭全部",
                                enabled = tabs.any { !it.pinned }
                            ) {
                                menu_expanded = false
                                on_close_all_tabs()
                            }
                        }
                    }
                }
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(outline.copy(alpha = 0.45f))
        )
    }
}

/**
 * 工具图标白名单：tool.json 只声明名字，宿主解析成 Material 图标（不加载任意资源）。
 */
fun plugin_tool_icon(name: String): ImageVector = when (name.lowercase()) {
    "dataobject" -> Icons.Default.DataObject
    "code" -> Icons.Default.Code
    "build" -> Icons.Default.Build
    "extension" -> Icons.Default.Extension
    "translate" -> Icons.Default.Translate
    else -> Icons.Default.Construction
}

/**
 * 工作区顶栏：抽屉切换 · 项目名 · 保存 · 直接运行 · AI · 更多菜单。
 * 构建/测试等次级任务收纳在更多菜单；菜单视觉对齐 CodeAssist 的圆角浮层。
 */
@Composable
fun editor_top_bar(
    project_name: String,
    has_changes: Boolean,
    on_save: () -> Unit,
    read_only: Boolean,
    has_open_file: Boolean,
    on_toggle_drawer: () -> Unit,
    drawer_fraction: () -> Float = { 0f },
    on_build: () -> Unit,
    on_run: () -> Unit,
    is_xml_file: Boolean = false,
    is_markdown_file: Boolean = false,
    markdown_preview_enabled: Boolean = false,
    on_toggle_markdown_mode: () -> Unit = {},
    on_open_designer: () -> Unit = {},
    on_test: () -> Unit = {},
    on_pack: () -> Unit = {},
    on_tidy: () -> Unit = {},
    build_running: Boolean,
    build_stopping: Boolean,
    on_toggle_read_only: () -> Unit = {},
    on_open_ai: () -> Unit = {},
    /** 已启用的插件工具：每个在「更多」菜单独立一项 */
    tools: List<com.jmwl.gostudio.plugins.plugin_tool_def> = emptyList(),
    on_open_tool: (com.jmwl.gostudio.plugins.plugin_tool_def) -> Unit = {},
    on_open_project_config: () -> Unit = {}
) {
    val colors = app_theme_provider.colors
    val accent = MaterialTheme.colorScheme.primary
    var more_menu_open by remember { mutableStateOf(false) }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 2.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
                .padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            editor_sidebar_toggle_button(
                fraction = drawer_fraction,
                on_click = on_toggle_drawer
            )

            Text(
                text = project_name,
                color = colors.editor_text,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 6.dp)
            )

            if (is_markdown_file) {
                editor_top_bar_icon_button(
                    icon = if (markdown_preview_enabled) Icons.Default.Edit else Icons.Default.Visibility,
                    content_description = if (markdown_preview_enabled) "编辑 Markdown" else "预览 Markdown",
                    tint = if (markdown_preview_enabled) colors.editor_toolbar_icon else accent,
                    on_click = on_toggle_markdown_mode
                )
            }

            editor_top_bar_icon_button(
                icon = Icons.Default.Save,
                content_description = "保存",
                tint = if (has_changes) accent else colors.editor_toolbar_icon,
                on_click = on_save
            )

            if (build_running) {
                editor_top_bar_icon_button(
                    icon = Icons.Default.Stop,
                    content_description = if (build_stopping) "正在停止" else "停止",
                    tint = colors.danger,
                    on_click = on_build
                )
            } else {
                editor_top_bar_icon_button(
                    icon = Icons.Default.PlayArrow,
                    content_description = "运行",
                    tint = accent,
                    on_click = on_run
                )
            }

            if (is_xml_file) {
                editor_top_bar_icon_button(
                    icon = Icons.Default.DesignServices,
                    content_description = "可视化编辑",
                    tint = colors.editor_toolbar_icon,
                    on_click = on_open_designer
                )
            }

            editor_top_bar_icon_button(
                icon = Icons.Default.AutoAwesome,
                content_description = "AI 助手",
                tint = colors.editor_toolbar_icon,
                on_click = on_open_ai
            )

            // 只读切换：眼睛图标（开启时高亮），原在「更多」菜单里
            editor_top_bar_icon_button(
                icon = if (read_only) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                content_description = if (read_only) "退出只读模式" else "切换只读模式",
                tint = if (read_only) accent else colors.editor_toolbar_icon,
                on_click = on_toggle_read_only
            )

            Box {
                editor_top_bar_icon_button(
                    icon = Icons.Default.MoreVert,
                    content_description = "更多",
                    tint = colors.editor_toolbar_icon,
                    on_click = { more_menu_open = true }
                )
                editor_dropdown_menu(
                    expanded = more_menu_open,
                    on_dismiss_request = { more_menu_open = false }
                ) {
                    if (!build_running) {
                        editor_menu_item(
                            icon = Icons.Default.Build,
                            label = "构建 (go build)"
                        ) {
                            more_menu_open = false
                            on_build()
                        }
                        editor_menu_item(
                            icon = Icons.Default.Science,
                            label = "测试 (go test ./...)"
                        ) {
                            more_menu_open = false
                            on_test()
                        }
                        editor_menu_item(
                            icon = Icons.Default.CleaningServices,
                            label = "tidy"
                        ) {
                            more_menu_open = false
                            on_tidy()
                        }
                        editor_menu_item(
                            icon = Icons.Default.Archive,
                            label = "打包 APK"
                        ) {
                            more_menu_open = false
                            on_pack()
                        }
                        editor_menu_divider()
                    }
                    editor_menu_item(
                        icon = Icons.Default.Settings,
                        label = "项目配置"
                    ) {
                        more_menu_open = false
                        on_open_project_config()
                    }
                    // 插件工具：每个已启用的工具独立一项，直接打开各自弹窗
                    tools.forEach { tool ->
                        editor_menu_item(
                            icon = plugin_tool_icon(tool.icon),
                            label = tool.name
                        ) {
                            more_menu_open = false
                            on_open_tool(tool)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun editor_dropdown_menu(
    expanded: Boolean,
    on_dismiss_request: () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = on_dismiss_request,
        offset = DpOffset(0.dp, 6.dp),
        shape = RoundedCornerShape(16.dp),
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 0.dp,
        shadowElevation = 14.dp,
        modifier = Modifier.border(
            BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)),
            RoundedCornerShape(16.dp)
        ),
        content = content
    )
}

@Composable
private fun editor_menu_item(
    icon: ImageVector,
    label: String,
    active: Boolean = false,
    enabled: Boolean = true,
    on_click: () -> Unit
) {
    val menu_text = MaterialTheme.colorScheme.onSurface
    val text_color = when {
        !enabled -> MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)
        active -> MaterialTheme.colorScheme.primary
        else -> menu_text
    }
    val icon_tint = when {
        !enabled -> MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)
        active -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    DropdownMenuItem(
        text = {
            Text(
                text = label,
                color = text_color,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium
            )
        },
        leadingIcon = {
            Icon(icon, null, Modifier.size(16.dp), tint = icon_tint)
        },
        enabled = enabled,
        onClick = on_click
    )
}

@Composable
private fun editor_menu_divider() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .height(1.dp)
            .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f))
    )
}

@Composable
private fun editor_top_bar_icon_button(
    icon: ImageVector,
    content_description: String,
    tint: Color,
    on_click: () -> Unit,
    enabled: Boolean = true
) {
    Box(
        modifier = Modifier
            .size(38.dp)
            .clip(RoundedCornerShape(10.dp))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                enabled = enabled,
                onClick = on_click
            ),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = content_description,
            tint = if (enabled) tint else tint.copy(alpha = 0.4f),
            modifier = Modifier.size(21.dp)
        )
    }
}

/**
 * 抽屉切换按钮：一枚「迷你屏幕」图标，线框内的分隔线即抽屉边缘——
 * 随抽屉开度 0→1 向右滑动并染上主色（手势拖动抽屉时图标实时跟随）。
 * 移植自 CodeAssist SidebarToggleButton；fraction 以 lambda 传入、
 * 仅在绘制阶段读取，抽屉动画不会触发顶栏重组。
 */
@Composable
private fun editor_sidebar_toggle_button(
    fraction: () -> Float,
    on_click: () -> Unit
) {
    val outline = MaterialTheme.colorScheme.onSurfaceVariant
    val accent = MaterialTheme.colorScheme.primary
    Box(
        modifier = Modifier
            .size(38.dp)
            .clip(RoundedCornerShape(10.dp))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = on_click
            ),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.size(width = 19.dp, height = 15.dp)) {
            val f = fraction().coerceIn(0f, 1f)
            val stroke = 1.5.dp.toPx()
            val inset = stroke / 2f
            val corner = CornerRadius(3.5.dp.toPx())
            // 分隔线 = 抽屉边缘：随开度右移（风格化的行程，保证 19dp 内可读）
            val divider = inset + (size.width - 2 * inset) * (0.34f + 0.30f * f)
            val frame = Path().apply {
                addRoundRect(
                    RoundRect(
                        inset,
                        inset,
                        size.width - inset,
                        size.height - inset,
                        corner
                    )
                )
            }
            clipPath(frame) {
                drawRect(
                    color = lerp(outline.copy(alpha = 0.32f), accent, f),
                    topLeft = Offset(inset, inset),
                    size = Size(divider - inset, size.height - 2 * inset)
                )
            }
            drawRoundRect(
                color = outline,
                topLeft = Offset(inset, inset),
                size = Size(size.width - 2 * inset, size.height - 2 * inset),
                cornerRadius = corner,
                style = Stroke(stroke)
            )
            drawLine(outline, Offset(divider, inset), Offset(divider, size.height - inset), stroke)
        }
    }
}

@Composable
fun code_editor_panel(
    editor: CodeEditor,
    modifier: Modifier = Modifier,
    on_focus_change: (Boolean) -> Unit = {}
) {
    val current_on_focus_change by rememberUpdatedState(on_focus_change)

    DisposableEffect(editor) {
        editor.setOnFocusChangeListener { _, has_focus ->
            current_on_focus_change(has_focus)
        }
        current_on_focus_change(editor.isFocused)
        onDispose {
            editor.setOnFocusChangeListener(null)
        }
    }

    AndroidView(
        factory = { context ->
            FrameLayout(context).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            }
        },
        modifier = modifier,
        update = { container ->
            if (editor.parent !== container) {
                (editor.parent as? ViewGroup)?.removeView(editor)
                container.removeAllViews()
                container.addView(
                    editor,
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                )
            }
            current_on_focus_change(editor.isFocused)
        }
    )
}
