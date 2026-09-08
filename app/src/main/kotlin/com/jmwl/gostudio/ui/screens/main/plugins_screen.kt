package com.jmwl.gostudio.ui.screens.main

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.InstallMobile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.documentfile.provider.DocumentFile
import com.jmwl.gostudio.plugins.plugin_instance
import com.jmwl.gostudio.plugins.plugin_manager
import com.jmwl.gostudio.plugins.tool_defs
import com.jmwl.gostudio.tools.tool_engine
import com.jmwl.gostudio.ui.theme.app_theme_provider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 插件管理页。
 *
 * 数据包插件（不执行代码）：一个含 manifest.json 的目录，
 * 通过 ZIP 导入安装。当前支持 skills 能力目录。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun plugins_screen(on_back: () -> Unit, on_browse: () -> Unit) {
    val colors = app_theme_provider.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    var plugins by remember { mutableStateOf(plugin_manager.all()) }
    var pending_delete by remember { mutableStateOf<plugin_instance?>(null) }
    var detail_plugin by remember { mutableStateOf<plugin_instance?>(null) }

    // 插件列表变更时刷新（安装/卸载/开关），离开页面注销
    DisposableEffect(Unit) {
        val listener = { plugins = plugin_manager.all() }
        plugin_manager.add_listener(listener)
        onDispose { plugin_manager.remove_listener(listener) }
    }

    fun refresh() {
        plugins = plugin_manager.all()
    }

    // SAF 选择 ZIP 安装
    val zip_picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(uri)?.use { stream ->
                        plugin_manager.install(context, stream).getOrThrow()
                    } ?: throw IllegalStateException("无法读取所选文件")
                }
            }
            result.fold(
                onSuccess = { manifest ->
                    snackbar.showSnackbar("已安装 ${manifest.name} v${manifest.version}")
                },
                onFailure = { error ->
                    snackbar.showSnackbar("安装失败：${error.message ?: "未知错误"}")
                }
            )
        }
    }

    // 开发模式：SAF 选插件目录直接安装（免去先打成 zip）
    val dir_picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val root = DocumentFile.fromTreeUri(context, uri)
                        ?: throw IllegalStateException("无法读取所选目录")
                    val staging = java.io.File(context.cacheDir, "plugin-import-${System.currentTimeMillis()}")
                    staging.mkdirs()
                    try {
                        copy_document_tree(context, root, staging)
                        plugin_manager.install_from_directory(staging).getOrThrow()
                    } finally {
                        staging.deleteRecursively()
                    }
                }
            }
            result.fold(
                onSuccess = { manifest ->
                    snackbar.showSnackbar("已安装 ${manifest.name} v${manifest.version}（默认停用，可在详情审查后启用）")
                },
                onFailure = { error ->
                    snackbar.showSnackbar("安装失败：${error.message ?: "未知错误"}")
                }
            )
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("插件", color = colors.title_large) },
                navigationIcon = {
                    IconButton(onClick = on_back) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回",
                            tint = colors.top_button_icon
                        )
                    }
                },
                actions = {
                    TextButton(onClick = on_browse) {
                        Text("浏览", color = colors.title_highlight, fontSize = 14.sp)
                    }
                    IconButton(onClick = { dir_picker.launch(null) }) {
                        Icon(
                            Icons.Default.FolderOpen,
                            contentDescription = "从文件夹导入（开发模式）",
                            tint = colors.title_highlight,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    TextButton(onClick = { zip_picker.launch(arrayOf("application/zip", "application/octet-stream")) }) {
                        Icon(
                            Icons.Default.InstallMobile,
                            contentDescription = null,
                            tint = colors.title_highlight,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text("安装", color = colors.title_highlight, fontSize = 14.sp)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
            )
        },
        containerColor = Color.Transparent
    ) { padding ->
        if (plugins.isEmpty()) {
            plugins_empty_state(Modifier.padding(padding))
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 18.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Spacer(Modifier.height(4.dp))
                // 内置插件（随 APK 发布）排前面单独分组，其余为用户安装的
                val builtin_plugins = plugins.filter { it.builtin }
                val installed_plugins = plugins.filter { !it.builtin }
                if (builtin_plugins.isNotEmpty()) {
                    plugin_section_header("内置插件")
                    builtin_plugins.forEach { plugin ->
                        plugin_card(
                            plugin = plugin,
                            on_toggle = { enabled ->
                                plugin_manager.set_enabled(plugin.id, enabled)
                                refresh()
                            },
                            on_delete = { pending_delete = plugin },
                            on_open_detail = { detail_plugin = plugin }
                        )
                    }
                }
                if (installed_plugins.isNotEmpty()) {
                    plugin_section_header("已安装插件")
                    installed_plugins.forEach { plugin ->
                        plugin_card(
                            plugin = plugin,
                            on_toggle = { enabled ->
                                plugin_manager.set_enabled(plugin.id, enabled)
                                refresh()
                            },
                            on_delete = { pending_delete = plugin },
                            on_open_detail = { detail_plugin = plugin }
                        )
                    }
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }

    // 插件详情/审查（点卡片信息区打开）
    detail_plugin?.let { plugin ->
        plugin_detail_dialog(
            plugin = plugin,
            on_dismiss = { detail_plugin = null }
        )
    }

    // 删除确认
    pending_delete?.let { plugin ->
        AlertDialog(
            onDismissRequest = { pending_delete = null },
            title = { Text("卸载插件") },
            text = { Text("确定卸载「${plugin.manifest.name}」？插件目录将被删除。") },
            confirmButton = {
                TextButton(onClick = {
                    if (plugin_manager.uninstall(plugin.id)) {
                        scope.launch { snackbar.showSnackbar("已卸载 ${plugin.manifest.name}") }
                    }
                    pending_delete = null
                    refresh()
                }) { Text("卸载", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { pending_delete = null }) { Text("取消") }
            }
        )
    }
}

@Composable
private fun plugin_section_header(title: String) {
    val colors = app_theme_provider.colors
    Text(
        text = title,
        fontSize = 12.sp,
        fontWeight = FontWeight.Medium,
        color = colors.card_text_subtitle,
        modifier = Modifier.padding(start = 4.dp)
    )
}

@Composable
private fun plugin_card(
    plugin: plugin_instance,
    on_toggle: (Boolean) -> Unit,
    on_delete: () -> Unit,
    on_open_detail: () -> Unit
) {
    val colors = app_theme_provider.colors
    val caps = plugin.capabilities()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(colors.card_bg)
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 点信息区进详情（审查 skills/工具声明后再决定是否启用）
            Row(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { on_open_detail() },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(colors.card_icon_bg.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.Extension,
                        contentDescription = null,
                        tint = colors.title_highlight,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        plugin.manifest.name,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.card_text_title
                    )
                    Text(
                        "v${plugin.manifest.version}" +
                            (if (plugin.manifest.author.isNotBlank()) " · ${plugin.manifest.author}" else "") +
                            (if (plugin.builtin) " · 内置" else ""),
                        fontSize = 11.sp,
                        color = colors.card_text_subtitle
                    )
                    if (!plugin.compatible) {
                        Text(
                            "需要更新 App 后使用",
                            fontSize = 11.sp,
                            color = colors.danger
                        )
                    }
                }
                Icon(
                    Icons.Default.ChevronRight,
                    contentDescription = "详情",
                    tint = colors.card_text_subtitle.copy(alpha = 0.6f),
                    modifier = Modifier.size(16.dp)
                )
            }
            Switch(
                checked = plugin.enabled,
                onCheckedChange = on_toggle,
                enabled = plugin.compatible,
                colors = SwitchDefaults.colors(
                    checkedTrackColor = colors.title_highlight,
                    checkedThumbColor = Color.White
                )
            )
        }

        if (plugin.manifest.description.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(
                plugin.manifest.description,
                fontSize = 12.sp,
                lineHeight = 16.sp,
                color = colors.card_text_subtitle
            )
        }

        if (caps.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                caps.forEach { cap ->
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(5.dp))
                            .background(colors.card_icon_bg.copy(alpha = 0.12f))
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                    ) {
                        Text(cap, fontSize = 10.sp, color = colors.card_text_subtitle)
                    }
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End
        ) {
            IconButton(onClick = on_delete) {
                Icon(
                    Icons.Default.DeleteOutline,
                    contentDescription = "卸载",
                    tint = colors.card_text_subtitle.copy(alpha = 0.7f),
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

@Composable
private fun plugins_empty_state(modifier: Modifier = Modifier) {
    val colors = app_theme_provider.colors
    Box(
        modifier = modifier.fillMaxSize().padding(horizontal = 32.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                Icons.Default.Extension,
                contentDescription = null,
                tint = colors.card_text_subtitle.copy(alpha = 0.5f),
                modifier = Modifier.size(44.dp)
            )
            Spacer(Modifier.height(12.dp))
            Text("暂无插件", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = colors.subtitle)
            Spacer(Modifier.height(6.dp))
            Text(
                "点击右上角「安装」导入插件 ZIP。\n\n插件是一个含 manifest.json 的目录，\n可包含 skills/ 等 capability 目录。",
                fontSize = 12.sp,
                lineHeight = 18.sp,
                color = colors.card_text_subtitle,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}

/** 插件 skill 摘要（详情审查用） */
private data class plugin_skill_summary(
    val name: String,
    val description: String,
    val content: String
)

/** 读取插件 skills/ 下每个 SKILL.md 的 frontmatter 摘要 + 全文（供审查） */
private fun read_plugin_skills(plugin: plugin_instance): List<plugin_skill_summary> {
    val skills_dir = java.io.File(plugin.dir, "skills")
    if (!skills_dir.isDirectory) return emptyList()
    return skills_dir.listFiles()
        ?.filter { it.isDirectory }
        .orEmpty()
        .mapNotNull { sub ->
            val md = java.io.File(sub, "SKILL.md")
            if (!md.isFile) return@mapNotNull null
            runCatching {
                val text = md.readText()
                var name = sub.name.lowercase()
                var description = ""
                if (text.startsWith("---")) {
                    val end = text.indexOf("\n---", 3)
                    if (end > 0) {
                        for (line in text.substring(3, end).lines()) {
                            val idx = line.indexOf(':')
                            if (idx <= 0) continue
                            val key = line.substring(0, idx).trim()
                            val value = line.substring(idx + 1).trim().trim('"').trim('\'')
                            if (key == "name" && value.isNotBlank()) name = value.lowercase()
                            if (key == "description") description = value
                        }
                    }
                }
                plugin_skill_summary(name, description, text)
            }.getOrNull()
        }
}

/**
 * 插件详情/审查弹窗：manifest 信息 + 工具声明（含宿主支持状态）+ skills 列表（可看全文）。
 * 第三方插件装完默认停用，用户在这里审查后再回列表打开开关。
 */
@Composable
private fun plugin_detail_dialog(
    plugin: plugin_instance,
    on_dismiss: () -> Unit
) {
    val colors = app_theme_provider.colors
    val tools = remember(plugin.id) { plugin.tool_defs() }
    val skills = remember(plugin.id) { read_plugin_skills(plugin) }
    var viewing_skill by remember { mutableStateOf<plugin_skill_summary?>(null) }

    AlertDialog(
        onDismissRequest = on_dismiss,
        containerColor = colors.dialog_bg,
        shape = RoundedCornerShape(14.dp),
        title = {
            Text(
                plugin.manifest.name,
                color = colors.dialog_text,
                fontSize = 17.sp,
                fontWeight = FontWeight.Medium
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 430.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    "v${plugin.manifest.version}" +
                        (if (plugin.manifest.author.isNotBlank()) " · ${plugin.manifest.author}" else "") +
                        " · " + (if (plugin.builtin) "内置插件" else "第三方插件"),
                    fontSize = 11.sp,
                    color = colors.dialog_hint
                )
                if (plugin.manifest.description.isNotBlank()) {
                    Text(plugin.manifest.description, fontSize = 12.sp, color = colors.dialog_text, lineHeight = 17.sp)
                }
                if (!plugin.compatible) {
                    Text(
                        "当前 App 版本过低，需要版本号 ≥ ${plugin.manifest.min_app_version} 才能使用该插件",
                        fontSize = 12.sp,
                        color = colors.danger
                    )
                }

                if (tools.isNotEmpty()) {
                    Text("工具", fontSize = 12.sp, fontWeight = FontWeight.Medium, color = colors.dialog_hint)
                    tools.forEach { tool ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(colors.dialog_input_bg)
                                .padding(horizontal = 10.dp, vertical = 8.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(tool.name, fontSize = 13.sp, color = colors.dialog_text)
                                val supported = tool.kind in tool_engine.supported_kinds
                                Text(
                                    if (supported) "可用" else "当前版本不支持",
                                    fontSize = 10.sp,
                                    color = if (supported) colors.dialog_clone_bg else colors.dialog_hint
                                )
                            }
                            if (tool.description.isNotBlank()) {
                                Text(tool.description, fontSize = 11.sp, color = colors.dialog_hint)
                            }
                        }
                    }
                }

                if (skills.isNotEmpty()) {
                    Text("AI 技能（${skills.size}）", fontSize = 12.sp, fontWeight = FontWeight.Medium, color = colors.dialog_hint)
                    skills.forEach { skill ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(colors.dialog_input_bg)
                                .clickable { viewing_skill = skill }
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(skill.name, fontSize = 13.sp, color = colors.dialog_text)
                                if (skill.description.isNotBlank()) {
                                    Text(skill.description, fontSize = 11.sp, color = colors.dialog_hint)
                                }
                            }
                            Icon(
                                Icons.Default.ChevronRight,
                                contentDescription = "查看全文",
                                tint = colors.dialog_hint,
                                modifier = Modifier.size(14.dp)
                            )
                        }
                    }
                }

                if (tools.isEmpty() && skills.isEmpty()) {
                    Text("该插件没有声明任何能力。", fontSize = 12.sp, color = colors.dialog_hint)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = on_dismiss) {
                Text("关闭", color = colors.dialog_hint)
            }
        }
    )

    // skill 全文审查（会注入 AI system prompt 的内容，用户应能看到原文）
    viewing_skill?.let { skill ->
        AlertDialog(
            onDismissRequest = { viewing_skill = null },
            containerColor = colors.dialog_bg,
            shape = RoundedCornerShape(14.dp),
            title = {
                Text(
                    skill.name,
                    color = colors.dialog_text,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium
                )
            },
            text = {
                Column(
                    modifier = Modifier
                        .heightIn(max = 460.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    Text(
                        skill.content,
                        fontSize = 11.sp,
                        lineHeight = 16.sp,
                        fontFamily = FontFamily.Monospace,
                        color = colors.dialog_text
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { viewing_skill = null }) {
                    Text("关闭", color = colors.dialog_hint)
                }
            }
        )
    }
}

/** 递归拷贝 SAF 文档树到本地目录（开发模式的插件目录导入） */
private fun copy_document_tree(
    context: android.content.Context,
    source: DocumentFile,
    target: java.io.File
) {
    target.mkdirs()
    source.listFiles().forEach { child ->
        val name = child.name ?: return@forEach
        if (child.isDirectory) {
            copy_document_tree(context, child, java.io.File(target, name))
        } else {
            context.contentResolver.openInputStream(child.uri)?.use { input ->
                java.io.File(target, name).outputStream().use { input.copyTo(it) }
            } ?: throw IllegalStateException("无法读取文件 $name")
        }
    }
}
