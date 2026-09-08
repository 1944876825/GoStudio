package com.jmwl.gostudio.plugins

import android.content.Context
import android.os.Build
import com.jmwl.gostudio.core.logging.logger_manager
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * 插件管理器。
 *
 * 插件安装目录：`<filesDir>/home/gostudio/plugins/`
 * 目录结构（数据包插件，不执行代码）：
 * ```
 * plugins/
 * └── com.example.myskills/
 *     ├── manifest.json    # 必需
 *     ├── skills/          # 可选能力目录（AI 技能）
 *     │   └── my-skill/SKILL.md
 *     ├── tools/           # 可选能力目录（工作区小工具，逻辑由宿主内置引擎实现）
 *     │   └── my-tool/tool.json
 *     └── config.json      # 可选：插件私有配置（宿主代存，内置插件升级覆盖目录时保留）
 * ```
 *
 * 启用状态持久化在 `plugins/enabled.json`（enabled + disabled 两个列表）：
 * - builtin 分类的插件默认启用（被显式禁用的除外）
 * - 第三方插件默认禁用，装完由用户审查后手动启用
 * - 旧版格式（纯数组 = 被禁用 id 列表）自动迁移
 *
 * 兼容性：manifest 的 min_app_version 大于宿主 versionCode 的插件标记为不兼容，
 * 能力（skills/tools）不生效、开关不可用，插件页提示更新 App。
 */
object plugin_manager {

    private const val LOG_TAG = "plugin_manager"
    private const val ENABLED_FILE = "enabled.json"
    private const val BUILTIN_MARKER = ".builtin"
    private const val CONFIG_FILE = "config.json"

    private lateinit var plugins_dir: File
    private var disabled_ids: MutableSet<String> = mutableSetOf()
    private var enabled_ids: MutableSet<String> = mutableSetOf()
    private var plugins: List<plugin_instance> = emptyList()
    private var host_version_code: Int = 0

    /** 变更代数：安装/卸载/开关都会 +1；消费方（如 AI skill 索引）据此判断要不要重扫 */
    var generation: Long = 0
        private set

    /** 版本监听（数据变更通知，供 UI 刷新） */
    private val listeners = mutableListOf<() -> Unit>()

    fun init(context: Context) {
        plugins_dir = File(context.filesDir, "home/gostudio/plugins")
        if (!plugins_dir.exists()) plugins_dir.mkdirs()
        host_version_code = read_host_version(context)
        load_enabled_state()
        discover()
    }

    /** 确保 lazy 初始化（未 init 时兜底，比如测试/预览） */
    private fun ensure_dir(context: Context? = null): File {
        if (!::plugins_dir.isInitialized) {
            val ctx = context ?: com.jmwl.gostudio.gostudio_application.instance
            plugins_dir = File(ctx.filesDir, "home/gostudio/plugins")
            plugins_dir.mkdirs()
            host_version_code = read_host_version(ctx)
            load_enabled_state()
        }
        return plugins_dir
    }

    private fun read_host_version(context: Context): Int = runCatching {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode.toInt()
        else @Suppress("DEPRECATION") info.versionCode
    }.getOrDefault(0)

    /** 当前宿主 versionCode（install 等带 context 的入口用，顺带缓存） */
    private fun host_version(context: Context): Int {
        if (host_version_code <= 0) host_version_code = read_host_version(context)
        return host_version_code
    }

    /** 重新扫描插件目录 */
    fun discover() {
        val dir = ensure_dir()
        val result = mutableListOf<plugin_instance>()
        dir.listFiles()?.filter { it.isDirectory }?.forEach { sub ->
            val mf = File(sub, "manifest.json")
            if (mf.isFile) {
                plugin_manifest.from_file(mf)?.let { manifest ->
                    // manifest.id 必须与目录名一致，防止目录伪装
                    if (manifest.id == sub.name) {
                        result.add(
                            plugin_instance(
                                manifest = manifest,
                                dir = sub,
                                enabled = compute_enabled(manifest),
                                compatible = manifest.min_app_version <= host_version_code
                            )
                        )
                    } else {
                        logger_manager.w(LOG_TAG, "插件目录名 ${sub.name} 与 manifest id ${manifest.id} 不一致，跳过")
                    }
                }
            }
        }
        plugins = result.sortedBy { it.manifest.name.lowercase() }
    }

    /** builtin 默认启用；第三方默认禁用（装完由用户审查后手动启用） */
    private fun compute_enabled(manifest: plugin_manifest): Boolean =
        if (manifest.category == "builtin") manifest.id !in disabled_ids
        else manifest.id in enabled_ids

    /** 当前插件列表（已排序） */
    fun all(): List<plugin_instance> = plugins

    /** 启用且兼容宿主版本的插件 skills 目录列表（skills 扩展点消费） */
    fun skill_dirs(context: Context? = null): List<File> {
        if (plugins.isEmpty()) discover()
        return plugins.filter { it.enabled && it.compatible }.mapNotNull { it.skill_dir() }
    }

    /** 启用/禁用插件（不兼容宿主版本的插件不允许开启） */
    fun set_enabled(id: String, enabled: Boolean): Boolean {
        if (plugins.none { it.id == id }) return false
        if (enabled) {
            enabled_ids.add(id)
            disabled_ids.remove(id)
        } else {
            enabled_ids.remove(id)
            disabled_ids.add(id)
        }
        save_enabled_state()
        discover()
        notify_changed()
        return true
    }

    /**
     * 从 ZIP 安装插件。
     *
     * ZIP 内可以是：
     * - 直接含 manifest.json 的根结构
     * - 或外层包一个目录（常见于 GitHub 下载的 zip）
     *
     * 校验通过后安装到 plugins/<id>/。已存在同 id 插件则覆盖更新（config.json 保留）。
     */
    fun install(context: Context, zip_stream: InputStream): Result<plugin_manifest> {
        return runCatching {
            val dir = ensure_dir(context)

            // 1. 解压到临时目录
            val staging = File(dir, ".staging-${System.currentTimeMillis()}")
            staging.mkdirs()
            try {
                unzip(zip_stream, staging)

                // 2. 定位 manifest.json（根目录或唯一子目录）
                val source_dir = locate_plugin_root(staging)
                    ?: throw IllegalArgumentException("ZIP 中未找到有效的 manifest.json")

                val manifest = plugin_manifest.from_file(File(source_dir, "manifest.json"))
                    ?: throw IllegalArgumentException("manifest.json 无效（缺 id/name 或格式错误）")
                check_version_compatible(manifest, host_version(context))

                // 3. 移入正式目录（同 id 覆盖）
                move_plugin_into_place(dir, source_dir, manifest)

                discover()
                notify_changed()
                logger_manager.i(LOG_TAG, "插件安装成功: ${manifest.id} v${manifest.version}")
                manifest
            } finally {
                staging.deleteRecursively()
            }
        }
    }

    /**
     * 从已解包的本地目录安装插件（开发模式：SAF 选中的目录由 UI 层先拷到临时目录再调用）。
     * 目录可以是插件根，或外层包一层目录（与 ZIP 安装同规则）。
     */
    fun install_from_directory(source: File): Result<plugin_manifest> = runCatching {
        val dir = ensure_dir()
        val plugin_root = locate_plugin_root(source)
            ?: throw IllegalArgumentException("目录中未找到有效的 manifest.json")
        val manifest = plugin_manifest.from_file(File(plugin_root, "manifest.json"))
            ?: throw IllegalArgumentException("manifest.json 无效（缺 id/name 或格式错误）")
        check_version_compatible(manifest, host_version_code)

        move_plugin_into_place(dir, plugin_root, manifest)

        discover()
        notify_changed()
        logger_manager.i(LOG_TAG, "插件安装成功: ${manifest.id} v${manifest.version}")
        manifest
    }

    /** 版本门控：插件要求的最低宿主版本高于当前版本时拒绝 */
    private fun check_version_compatible(manifest: plugin_manifest, current_version: Int) {
        if (manifest.min_app_version > current_version) {
            throw IllegalArgumentException(
                "插件需要更新版本的 App（要求版本号 ≥ ${manifest.min_app_version}）"
            )
        }
    }

    /** 覆盖安装：保留旧目录里的 config.json（插件私有配置不能因升级丢失） */
    private fun move_plugin_into_place(plugins_root: File, source_dir: File, manifest: plugin_manifest) {
        val target = File(plugins_root, manifest.id)
        val config_text = File(target, CONFIG_FILE).takeIf { it.isFile }
            ?.let { runCatching { it.readText() }.getOrNull() }
        if (target.exists()) target.deleteRecursively()
        if (!source_dir.renameTo(target)) {
            // 跨设备 rename 失败时复制
            source_dir.copyRecursively(target, overwrite = true)
            source_dir.deleteRecursively()
        }
        config_text?.let { text ->
            runCatching { File(target, CONFIG_FILE).writeText(text) }
        }
    }

    /** 卸载插件（删除目录） */
    fun uninstall(id: String): Boolean {
        val dir = ensure_dir()
        val target = File(dir, id)
        if (!target.isDirectory) return false
        val ok = runCatching { target.deleteRecursively() }.getOrDefault(false)
        if (ok) {
            disabled_ids.remove(id)
            enabled_ids.remove(id)
            save_enabled_state()
            discover()
            notify_changed()
            logger_manager.i(LOG_TAG, "插件已卸载: $id")
        }
        return ok
    }

    /** 插件目录（已安装才返回）；工具配置等插件私有数据存在目录内 config.json */
    fun dir_of(id: String): File? = File(ensure_dir(), id).takeIf { it.isDirectory }

    /**
     * 释放随 APK 发布的内置插件（assets/plugins/<id>/）到插件目录：
     * - 版本升级时整目录覆盖，config.json（用户配置）保留
     * - 每个内置插件目录写入 .builtin 标记；带标记但 assets 已不再发布的内置插件自动清理
     * - enabled.json 不动，用户启用/禁用状态保持；卸载后下次启动自动恢复（禁用则不会）
     */
    fun release_builtin_plugins(context: Context) {
        val dir = ensure_dir(context)
        host_version_code = read_host_version(context)
        var changed = false
        val shipped_ids = mutableSetOf<String>()
        val ids = runCatching { context.assets.list("plugins") }.getOrNull()
        if (ids != null) {
            for (id in ids) {
                runCatching {
                    val manifest_text = context.assets.open("plugins/$id/manifest.json")
                        .bufferedReader().use { it.readText() }
                    val manifest = plugin_manifest.from_json(manifest_text) ?: return@runCatching
                    // 目录名必须与 manifest id 一致（与 discover 同规则），防止 assets 目录伪装
                    if (manifest.id != id) {
                        logger_manager.w(LOG_TAG, "内置插件目录名 $id 与 manifest id 不一致，跳过")
                        return@runCatching
                    }
                    shipped_ids.add(id)
                    val target = File(dir, id)
                    val installed_version = plugin_manifest.from_file(File(target, "manifest.json"))?.version
                    if (installed_version == manifest.version) {
                        // 同版本也要补 .builtin 标记（标记机制上线前装的目录没有）
                        if (!File(target, BUILTIN_MARKER).isFile) {
                            runCatching { File(target, BUILTIN_MARKER).writeText(manifest.version) }
                            changed = true
                        }
                        return@runCatching
                    }
                    val config_text = File(target, CONFIG_FILE).takeIf { it.isFile }
                        ?.let { runCatching { it.readText() }.getOrNull() }
                    if (target.exists()) target.deleteRecursively()
                    copy_asset_dir(context, "plugins/$id", target)
                    config_text?.let { text ->
                        runCatching { File(target, CONFIG_FILE).writeText(text) }
                    }
                    runCatching { File(target, BUILTIN_MARKER).writeText(manifest.version) }
                    changed = true
                    logger_manager.i(LOG_TAG, "内置插件已释放: $id v${manifest.version}")
                }.onFailure { error ->
                    logger_manager.e(LOG_TAG, "内置插件 $id 释放失败: ${error.message}", error)
                }
            }
            // 清理：带内置标记但已不再随 APK 发布的插件目录（内置插件下架）
            dir.listFiles()?.filter { it.isDirectory }?.forEach { sub ->
                if (File(sub, BUILTIN_MARKER).isFile && sub.name !in shipped_ids) {
                    runCatching { sub.deleteRecursively() }
                    changed = true
                    logger_manager.i(LOG_TAG, "已下架的内置插件已清理: ${sub.name}")
                }
            }
        }
        // 一次性迁移：标记机制上线前（1.0.9 开发版）的内置插件目录没有 .builtin，按旧 id 手工清理
        val legacy = File(dir, "com.gostudio.tools")
        if (legacy.isDirectory) {
            runCatching { legacy.deleteRecursively() }
            changed = true
        }
        if (changed) {
            discover()
            notify_changed()
        }
    }

    /** 递归复制 assets 子目录到文件系统 */
    private fun copy_asset_dir(context: Context, path: String, target: File) {
        target.mkdirs()
        val children = context.assets.list(path) ?: return
        for (child in children) {
            val child_path = "$path/$child"
            val child_target = File(target, child)
            val sub_entries = context.assets.list(child_path)
            if (!sub_entries.isNullOrEmpty()) {
                copy_asset_dir(context, child_path, child_target)
            } else {
                context.assets.open(child_path).use { input ->
                    child_target.outputStream().use { input.copyTo(it) }
                }
            }
        }
    }

    /** 注册插件列表变更监听 */
    fun add_listener(listener: () -> Unit) {
        listeners.add(listener)
    }

    fun remove_listener(listener: () -> Unit) {
        listeners.remove(listener)
    }

    private fun notify_changed() {
        generation++
        listeners.toList().forEach { runCatching(it) }
    }

    /** 解压 ZIP，带 zip-slip 路径穿越防护 */
    internal fun unzip(stream: InputStream, target_dir: File) {
        ZipInputStream(stream.buffered()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                val out_file = File(target_dir, entry.name)
                val canonical = out_file.canonicalFile
                if (!canonical.path.startsWith(target_dir.canonicalPath + File.separator)) {
                    throw SecurityException("ZIP 条目路径非法: ${entry.name}")
                }
                if (entry.isDirectory) {
                    canonical.mkdirs()
                } else {
                    canonical.parentFile?.mkdirs()
                    canonical.outputStream().use { zip.copyTo(it) }
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
    }

    /** 定位插件根：目录本身有 manifest.json，或其下唯一目录有 */
    private fun locate_plugin_root(staging: File): File? {
        if (File(staging, "manifest.json").isFile) return staging
        val dirs = staging.listFiles()?.filter { it.isDirectory } ?: return null
        val candidates = dirs.filter { File(it, "manifest.json").isFile }
        return when {
            candidates.size == 1 -> candidates.first()
            // 允许外层目录 + 隐藏文件（如 macOS 的 __MACOSX）
            dirs.size >= 1 && candidates.size == 1 -> candidates.first()
            else -> null
        }
    }

    /** 启用状态：enabled.json 记录显式启用/禁用两个列表 */
    private fun enabled_file(): File = File(ensure_dir(), ENABLED_FILE)

    private fun load_enabled_state() {
        val text = runCatching { enabled_file().readText() }.getOrDefault("")
        val (enabled, disabled) = parse_enabled_state(text)
        enabled_ids = enabled.toMutableSet()
        disabled_ids = disabled.toMutableSet()
    }

    private fun save_enabled_state() {
        runCatching {
            enabled_file().writeText(
                JSONObject()
                    .put("enabled", JSONArray(enabled_ids))
                    .put("disabled", JSONArray(disabled_ids))
                    .toString()
            )
        }.onFailure {
            logger_manager.e(LOG_TAG, "保存插件启用状态失败: ${it.message}", it)
        }
    }
}

/**
 * 解析 enabled.json：
 * - 旧格式（纯字符串数组）= 被禁用的 id 列表 → (空集, 该列表)
 * - 新格式 {"enabled": [...], "disabled": [...]}
 * - 解析失败 → 两个空集（所有插件按默认规则处理）
 */
internal fun parse_enabled_state(text: String): Pair<Set<String>, Set<String>> {
    if (text.isBlank()) return emptySet<String>() to emptySet()
    return runCatching {
        val trimmed = text.trim()
        if (trimmed.startsWith("[")) {
            val array = JSONArray(trimmed)
            val disabled = buildSet {
                for (i in 0 until array.length()) add(array.optString(i))
            }
            emptySet<String>() to disabled
        } else {
            val json = JSONObject(trimmed)
            fun read_names(key: String): Set<String> {
                val array = json.optJSONArray(key) ?: return emptySet()
                return buildSet {
                    for (i in 0 until array.length()) add(array.optString(i))
                }
            }
            read_names("enabled") to read_names("disabled")
        }
    }.getOrDefault(emptySet<String>() to emptySet())
}
