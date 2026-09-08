package com.jmwl.gostudio.activity

import android.content.Intent
import android.os.Bundle
import com.jmwl.gostudio.ui.toast.app_toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import com.jmwl.gostudio.project.project_manager
import com.jmwl.gostudio.project.recent_project_info
import com.jmwl.gostudio.toolchain.git_manager
import com.jmwl.gostudio.toolchain.install_garble
import com.jmwl.gostudio.toolchain.install_gcc
import com.jmwl.gostudio.toolchain.install_git
import com.jmwl.gostudio.toolchain.install_go
import com.jmwl.gostudio.toolchain.install_gopls
import com.jmwl.gostudio.toolchain.install_go_toolchain
import com.jmwl.gostudio.toolchain.toolchain_manager
import com.jmwl.gostudio.ui.screens.main.main_navigation
import com.jmwl.gostudio.ui.screens.main.main_tools_install_status
import com.jmwl.gostudio.ui.screens.main.recent_project
import com.jmwl.gostudio.ui.screens.main.toolchain_action
import com.jmwl.gostudio.ui.screens.main.toolchain_custom_install_request
import com.jmwl.gostudio.ui.screens.main.toolchain_trigger
import com.jmwl.gostudio.ui.theme.app_theme_preset
import com.jmwl.gostudio.ui.theme.app_theme_provider
import com.jmwl.gostudio.ui.theme.app_theme_type
import com.jmwl.gostudio.ui.theme.theme_manager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class main_activity : ComponentActivity() {
    private var recent_projects by mutableStateOf<List<recent_project>>(emptyList())
    private var toolchain_status by mutableStateOf(main_tools_install_status())
    private var current_theme by mutableStateOf(app_theme_type.SYSTEM)
    private var current_theme_preset by mutableStateOf(app_theme_preset.CLASSIC_TEAL)
    private var custom_theme_accent by mutableStateOf(0xFF7C9EFF.toInt())
    private var scale_value by mutableStateOf(1f)
    private var toolchain_tasks by mutableStateOf<List<toolchain_trigger>>(emptyList())
    private var custom_toolchain_dialog by mutableStateOf<toolchain_custom_install_request?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        enableEdgeToEdge()
        setContent {
            app_theme_provider {
                // 启动后静默检查更新（仅发现新版本时弹窗）
                val context = androidx.compose.ui.platform.LocalContext.current
                val update_controller = androidx.compose.runtime.remember {
                    com.jmwl.gostudio.update.app_update_controller(context)
                }
                androidx.compose.runtime.LaunchedEffect(Unit) {
                    kotlinx.coroutines.delay(2500)
                    update_controller.check()
                }
                com.jmwl.gostudio.ui.dialogs.main.app_update_dialog(
                    controller = update_controller,
                    on_dismiss = { update_controller.reset() }
                )

                main_navigation(
                    recent_projects = recent_projects,
                    toolchain_status = toolchain_status,
                    current_theme = current_theme,
                    current_theme_preset = current_theme_preset,
                    custom_theme_accent = custom_theme_accent,
                    scale_value = scale_value,
                    toolchain_tasks = toolchain_tasks,
                    custom_toolchain_dialog = custom_toolchain_dialog,
                    on_back_to_background = { moveTaskToBack(true) },
                    on_terminal = ::open_terminal,
                    on_project_click = ::open_recent_project,
                    on_project_copy = ::copy_project,
                    on_project_delete = ::delete_project,
                    on_project_export = ::export_project,
                    on_create_project = ::create_project,
                    on_import_project = ::import_project,
                    on_clone_project = ::clone_github_project,
                    on_toolchain_trigger_change = { trigger ->
                        toolchain_tasks = if (trigger != null) {
                            toolchain_tasks + trigger
                        } else {
                            toolchain_tasks.drop(1)
                        }
                    },
                    on_custom_toolchain_dialog_change = { custom_toolchain_dialog = it },
                    on_theme_change = ::set_theme,
                    on_theme_preset_change = ::set_theme_preset,
                    on_custom_theme_accent_change = ::set_custom_theme_accent,
                    on_scale_change = ::set_scale,
                    on_run_toolchain_task = ::run_toolchain_task,
                    on_toolchain_task_success = ::on_toolchain_task_success
                )
            }
        }

        load_initial_data()
    }

    override fun onResume() {
        super.onResume()
        // 从编辑器返回时刷新最近项目（项目图标等配置可能已变化）
        lifecycleScope.launch { reload_recent_projects() }
    }

    private fun load_initial_data() {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                toolchain_manager.cleanup_removed_toolchain_environment()
            }
            reload_recent_projects()
            refresh_toolchain_status()
            current_theme = theme_manager.theme.value
            current_theme_preset = theme_manager.preset.value
            custom_theme_accent = theme_manager.custom_accent.value
            scale_value = theme_manager.scale.value
        }
    }

    private suspend fun reload_recent_projects() {
        recent_projects = project_manager.get_recent_projects().map { it.to_ui_recent_project() }
    }

    private suspend fun refresh_toolchain_status() {
        toolchain_status = withContext(Dispatchers.IO) {
            val go = toolchain_manager.installed_go()
            main_tools_install_status(
                go_installed = go != null,
                gopls_installed = toolchain_manager.is_gopls_installed(),
                git_installed = toolchain_manager.is_git_installed(),
                installed_go_version = go?.version.orEmpty()
            )
        }
    }

    private fun open_terminal() {
        startActivity(Intent(this, terminal_activity::class.java))
    }

    private fun set_theme(theme: app_theme_type) {
        theme_manager.set_theme(this, theme)
        current_theme = theme
    }


    private fun set_theme_preset(preset: app_theme_preset) {
        theme_manager.set_preset(this, preset)
        current_theme_preset = preset
    }

    private fun set_custom_theme_accent(argb: Int) {
        theme_manager.set_custom_accent(this, argb)
        current_theme_preset = app_theme_preset.CUSTOM
        custom_theme_accent = argb or 0xFF000000.toInt()
    }

    private fun set_scale(scale: Float) {
        theme_manager.set_scale(this, scale)
        scale_value = scale
    }

    private fun open_recent_project(project: recent_project) {
        lifecycleScope.launch {
            val project_dir = File(project.path)
            if (!project_dir.exists() || !project_dir.isDirectory) {
                project_manager.remove_recent_project(project.path)
                reload_recent_projects()
                app_toast.show(this@main_activity, "项目不存在，已从最近项目移除", app_toast.LENGTH_LONG)
                return@launch
            }

            project_manager.ensure_project_config(project.path)
            val result = project_manager.add_recent_project(project.path)
            result.onSuccess { info ->
                reload_recent_projects()
                open_editor(info.name, info.path)
            }.onFailure { error ->
                app_toast.show(this@main_activity, "打开失败: ${error.message}", app_toast.LENGTH_LONG)
            }
        }
    }

    /** 复制项目到同目录副本（name-copy、name-copy-2 递增），成功后加入最近列表。 */
    private fun copy_project(project: recent_project) {
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val source = File(project.path)
                    if (!source.isDirectory) throw IllegalArgumentException("项目目录不存在")
                    val parent = source.parentFile ?: throw IllegalArgumentException("无法定位父目录")
                    var target = File(parent, source.name + "-copy")
                    var index = 2
                    while (target.exists()) {
                        target = File(parent, source.name + "-copy-$index")
                        index++
                    }
                    source.copyRecursively(target, overwrite = false)
                    target
                }
            }
            result.fold(
                onSuccess = { target ->
                    project_manager.add_recent_project(target.absolutePath)
                    reload_recent_projects()
                    app_toast.show(this@main_activity, "已复制到 ${target.name}", app_toast.LENGTH_SHORT)
                },
                onFailure = { error ->
                    app_toast.show(this@main_activity, "复制失败：${error.message}", app_toast.LENGTH_LONG)
                }
            )
        }
    }

    /** 删除项目目录并移出最近列表。 */
    private fun delete_project(project: recent_project) {
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val dir = File(project.path)
                    if (dir.isDirectory) {
                        if (!dir.deleteRecursively()) throw IllegalStateException("目录删除失败")
                    }
                }
            }
            project_manager.remove_recent_project(project.path)
            reload_recent_projects()
            result.fold(
                onSuccess = {
                    app_toast.show(this@main_activity, "已删除 ${project.name}", app_toast.LENGTH_SHORT)
                },
                onFailure = { error ->
                    app_toast.show(this@main_activity, "目录删除失败：${error.message}（已移出最近列表）", app_toast.LENGTH_LONG)
                }
            )
        }
    }

    /** 导出项目为 ZIP 到用户选择的 SAF 目标。 */
    private fun export_project(project: recent_project, uri: Uri) {
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val root = File(project.path)
                    if (!root.isDirectory) throw IllegalArgumentException("项目目录不存在")
                    contentResolver.openOutputStream(uri)?.use { output ->
                        ZipOutputStream(output.buffered()).use { zip ->
                            root.walkTopDown().filter { it.isFile }.forEach { file ->
                                val entry_name = file.relativeTo(root).invariantSeparatorsPath
                                zip.putNextEntry(ZipEntry(entry_name))
                                file.inputStream().use { it.copyTo(zip) }
                                zip.closeEntry()
                            }
                        }
                    } ?: throw IllegalStateException("无法打开导出目标")
                }
            }
            result.fold(
                onSuccess = {
                    app_toast.show(this@main_activity, "已导出 ${project.name}.zip", app_toast.LENGTH_SHORT)
                },
                onFailure = { error ->
                    app_toast.show(this@main_activity, "导出失败：${error.message}", app_toast.LENGTH_LONG)
                }
            )
        }
    }

    private fun create_project(
        project_name: String,
        template_id: String,
        app_name: String,
        app_package: String
    ) {
        lifecycleScope.launch {
            val result = project_manager.create_project(
                name = project_name,
                template_id = template_id,
                app_name = app_name,
                package_name = app_package
            )
            result.onSuccess { project_dir ->
                project_manager.add_recent_project(project_dir.absolutePath)
                reload_recent_projects()
                app_toast.show(this@main_activity, "项目已创建: ${project_dir.name}", app_toast.LENGTH_SHORT)
                open_editor(project_dir.name, project_dir.absolutePath)
            }.onFailure { error ->
                app_toast.show(this@main_activity, "创建失败: ${error.message}", app_toast.LENGTH_LONG)
            }
        }
    }

    private suspend fun clone_github_project(
        repository_url: String,
        on_log: (String) -> Unit,
        on_progress: (Int) -> Unit
    ): Boolean {
        if (!toolchain_status.git_installed) {
            on_log("Git 未安装，请先到「工具」页安装 Git")
            return false
        }

        val url = repository_url.trim()
        val https_pattern = Regex(
            """^https?://([\w.-]+)(?::\d+)?/(?:[\w.-]+/)*([\w.-]+?)(?:\.git)?/?$""",
            RegexOption.IGNORE_CASE
        )
        val ssh_pattern = Regex(
            """^(?:ssh://)?git@([\w.-]+)(?::\d+)?[/:](?:[\w.-]+/)*([\w.-]+?)(?:\.git)?$""",
            RegexOption.IGNORE_CASE
        )
        val match = https_pattern.find(url) ?: ssh_pattern.find(url)
        if (match == null) {
            on_log("仓库地址无效：$url")
            return false
        }

        val raw_name = match.groupValues[2].ifBlank { "github-project" }
        val base_name = raw_name.replace(Regex("[^A-Za-z0-9._-]"), "_")
            .replace(Regex("^[.]*"), "")
            .ifBlank { "github-project" }
        val projects_root = project_manager.default_projects_dir()
        val project_dir = withContext(Dispatchers.IO) {
            var candidate = File(projects_root, base_name)
            var index = 1
            while (candidate.exists()) {
                candidate = File(projects_root, "${base_name}-${index++}")
            }
            candidate
        }

        on_log("仓库：$url")
        on_log("目标：${project_dir.name}")
        on_progress(8)
        val (clone_ok, clone_output) = git_manager.clone_repository(
            projects_root = projects_root.absolutePath,
            repository_url = url,
            destination_name = project_dir.name,
            on_command_log = on_log
        )
        if (!clone_ok) {
            on_progress(100)
            on_log(clone_output.ifBlank { "克隆失败" })
            withContext(Dispatchers.IO) { project_dir.deleteRecursively() }
            return false
        }

        on_progress(70)
        val configured = withContext(Dispatchers.IO) {
            project_manager.ensure_project_config(project_dir.absolutePath)
        }
        configured.onFailure { error ->
            on_progress(100)
            on_log(error.message ?: "仓库根目录缺少 go.mod，不是可打开的 Go 项目")
            withContext(Dispatchers.IO) { project_dir.deleteRecursively() }
            return false
        }

        on_progress(88)
        val recent = project_manager.add_recent_project(project_dir.absolutePath)
        recent.onFailure { error ->
            on_progress(100)
            on_log("项目加入最近列表失败: ${error.message}")
            return false
        }

        on_progress(100)
        reload_recent_projects()
        on_log("克隆完成：${project_dir.name}")
        app_toast.show(this, "项目已克隆: ${project_dir.name}", app_toast.LENGTH_SHORT)
        open_editor(project_dir.name, project_dir.absolutePath, auto_tidy = true)
        return true
    }

    /**
     * 从文件管理器（SAF OpenDocumentTree）选中的目录导入项目：
     * 整目录复制到内部 projects 根（proot 需要内部存储路径），再走与克隆一致的打开流程。
     */
    private suspend fun import_project(
        uri: Uri,
        on_log: (String) -> Unit,
        on_progress: (Int) -> Unit
    ): Boolean {
        val projects_root = project_manager.default_projects_dir()

        on_progress(3)
        val root = withContext(Dispatchers.IO) {
            runCatching { DocumentFile.fromTreeUri(this@main_activity, uri) }.getOrNull()
        } ?: run {
            on_log("无法读取所选目录")
            on_progress(100)
            return false
        }

        val base_name = withContext(Dispatchers.IO) {
            root.name.orEmpty()
                .replace(Regex("[^A-Za-z0-9._-]"), "_")
                .replace(Regex("^[.]*"), "")
                .ifBlank { "imported-project" }
        }
        // 提前校验 go.mod，避免复制完才发现不是 Go 项目
        val has_go_mod = withContext(Dispatchers.IO) {
            root.listFiles().any { !it.isDirectory && it.name == "go.mod" }
        }
        if (!has_go_mod) {
            on_log("所选目录根下没有 go.mod，请选择 Go 项目的根目录")
            on_progress(100)
            return false
        }

        val project_dir = withContext(Dispatchers.IO) {
            var candidate = File(projects_root, base_name)
            var index = 1
            while (candidate.exists()) {
                candidate = File(projects_root, "${base_name}-${index++}")
            }
            candidate
        }
        on_log("导入：$base_name")
        on_log("目标：${project_dir.name}")
        on_progress(8)

        val copy_result = withContext(Dispatchers.IO) {
            runCatching {
                var total = 0
                fun count_files(dir: DocumentFile) {
                    dir.listFiles().forEach { child ->
                        if (child.isDirectory) count_files(child) else total++
                    }
                }
                count_files(root)
                on_log("共 $total 个文件")

                var copied = 0
                fun copy_tree(source: DocumentFile, target: File) {
                    if (!target.exists() && !target.mkdirs()) {
                        throw IllegalStateException("无法创建目录 ${target.name}")
                    }
                    source.listFiles().forEach { child ->
                        val child_name = child.name ?: return@forEach
                        if (child.isDirectory) {
                            copy_tree(child, File(target, child_name))
                        } else {
                            val output_file = File(target, child_name)
                            contentResolver.openInputStream(child.uri)?.use { input ->
                                output_file.outputStream().use { output -> input.copyTo(output) }
                            } ?: throw IllegalStateException("无法读取文件 $child_name")
                            copied++
                            on_progress(10 + (85.0 * copied / total.coerceAtLeast(1)).toInt())
                        }
                    }
                }
                copy_tree(root, project_dir)
            }
        }
        if (copy_result.isFailure) {
            on_progress(100)
            on_log("复制失败：${copy_result.exceptionOrNull()?.message ?: "未知错误"}")
            withContext(Dispatchers.IO) { project_dir.deleteRecursively() }
            return false
        }

        on_progress(97)
        val configured = withContext(Dispatchers.IO) {
            project_manager.ensure_project_config(project_dir.absolutePath)
        }
        configured.onFailure { error ->
            on_progress(100)
            on_log(error.message ?: "项目配置初始化失败")
            withContext(Dispatchers.IO) { project_dir.deleteRecursively() }
            return false
        }

        val recent = project_manager.add_recent_project(project_dir.absolutePath)
        recent.onFailure { error ->
            on_progress(100)
            on_log("项目加入最近列表失败: ${error.message}")
            return false
        }

        on_progress(100)
        reload_recent_projects()
        on_log("导入完成：${project_dir.name}")
        app_toast.show(this, "项目已导入: ${project_dir.name}", app_toast.LENGTH_SHORT)
        // 导入的项目依赖未缓存，打开后自动 go mod tidy（与克隆一致）
        open_editor(project_dir.name, project_dir.absolutePath, auto_tidy = true)
        return true
    }

    private fun open_editor(project_name: String, project_path: String, auto_tidy: Boolean = false) {
        project_manager.ensure_project_clang_format(project_path)
        val intent = Intent(this, editor_activity::class.java).apply {
            putExtra("project_name", project_name)
            putExtra("project_path", project_path)
            // 克隆的项目依赖未缓存，打开后自动 go mod tidy
            if (auto_tidy) putExtra("auto_tidy", true)
        }
        startActivity(intent)
    }

    private suspend fun run_toolchain_task(
        trigger: toolchain_trigger,
        on_log: (String) -> Unit,
        on_progress: (Int) -> Unit
    ): Boolean {
        return when (trigger.action) {
            toolchain_action.INSTALL_GO -> install_go_toolchain(on_log, on_progress)
            toolchain_action.INSTALL_GOPLS -> install_gopls(on_log, on_progress)
            toolchain_action.INSTALL_GIT -> install_git(on_log, on_progress)
            toolchain_action.INSTALL_GCC -> install_gcc(on_log, on_progress)
            toolchain_action.INSTALL_GARBLE -> install_garble(on_log, on_progress)
        }
    }

    private fun on_toolchain_task_success(trigger: toolchain_trigger) {
        toolchain_tasks = toolchain_tasks.drop(1)
        // 工具链已变化，让 installed_go() 探测缓存失效（编辑器重组热路径依赖它）
        toolchain_manager.invalidate_go_probe()
        app_toast.show(this, "${trigger.title} 完成", app_toast.LENGTH_SHORT)
        lifecycleScope.launch { refresh_toolchain_status() }
    }

    private fun recent_project_info.to_ui_recent_project(): recent_project {
        return recent_project(
            name = name,
            path = path,
            go_version = go_version,
            last_opened = last_opened,
            template = template,
            icon_path = app_icon_path
        )
    }
}
