package com.jmwl.gostudio.ai.skills

import android.content.Context
import java.io.File

/**
 * Skill 系统（参考 earendil-works/pi 的 Agent Skills 标准）。
 *
 * 一个 skill 是含 SKILL.md 的目录。SKILL.md 带 YAML frontmatter（name + description）。
 *
 * 渐进式披露：
 * - 启动时只把每个 skill 的 name + description 放进 system prompt（省 token）
 * - 模型判断需要时，用 read 工具读完整 SKILL.md 加载详细指令
 * - 用户也可用 /skill:名称 手动激活（把全文直接注入对话）
 *
 * 发现路径：
 * - 全局：`<app home>/.ai/skills/`
 * - 项目：`<project>/.ai/skills/`
 * - 内置 assets：随 APK 发布的 Go 开发常用 skill（首次运行释放到全局目录）
 */

/** 单个 skill 的元数据（用于 system prompt 索引） */
data class ai_skill(
    val name: String,           // 唯一名（小写字母数字连字符）
    val description: String,    // 给模型看的用途说明
    val skill_dir: File,        // skill 目录（含 SKILL.md）
    val skill_md_path: String,  // SKILL.md 路径（相对项目根，供 read 工具用；全局的用绝对路径）
    val source: skill_source    // 来源
)

enum class skill_source { BUILT_IN, GLOBAL, PROJECT, PLUGIN }

class ai_skill_manager(
    private val global_skills_dir: File,   // <app home>/.ai/skills
    private val project_skills_dir: File?, // <project>/.ai/skills（可能无项目）
    // 插件 skill 目录以 provider 传入：启用状态在会话中途可能变化，每次 discover 时现取
    private val plugin_skill_dirs_provider: () -> List<File> = { emptyList() }
) {
    /** 已发现的所有 skill（按 name 去重，项目级覆盖同名全局） */
    private var skills: List<ai_skill> = emptyList()

    /** 上次扫描时的插件变更代数；不一致说明插件开关变了，需要重扫 */
    private var seen_plugin_generation: Long = Long.MIN_VALUE

    /** 扫描并加载 skill 索引（在 IO 线程调用） */
    fun discover() {
        val result = linkedMapOf<String, ai_skill>()
        // 优先级：插件 < 全局 < 项目（后加载覆盖同名）
        plugin_skill_dirs_provider().forEach { dir ->
            scan_dir(dir, skill_source.PLUGIN, is_project_relative = false, result)
        }
        scan_dir(global_skills_dir, skill_source.GLOBAL, is_project_relative = false, result)
        project_skills_dir?.let {
            scan_dir(it, skill_source.PROJECT, is_project_relative = true, result)
        }
        skills = result.values.toList()
        seen_plugin_generation = com.jmwl.gostudio.plugins.plugin_manager.generation
    }

    /**
     * 插件启用状态可能在会话中途变化：查询前对齐一次。
     * 代数没变就不重扫，平时开销为零。
     */
    fun ensure_current() {
        if (seen_plugin_generation != com.jmwl.gostudio.plugins.plugin_manager.generation) {
            discover()
        }
    }

    private fun scan_dir(dir: File, source: skill_source, is_project_relative: Boolean, out: MutableMap<String, ai_skill>) {
        if (!dir.isDirectory) return
        dir.listFiles()?.forEach { sub ->
            if (sub.isDirectory) {
                val md = File(sub, "SKILL.md")
                if (md.isFile) parse_skill(md, source, is_project_relative)?.let { out[it.name] = it }
            }
        }
    }

    /** 解析 SKILL.md 的 frontmatter（简单 YAML，只取 name/description） */
    private fun parse_skill(md: File, source: skill_source, is_project_relative: Boolean): ai_skill? {
        return runCatching {
            val text = md.readText()
            val (frontmatter, _) = extract_frontmatter(text)
            val name = frontmatter["name"]?.trim()?.lowercase()
                ?: md.parentFile?.name?.lowercase() ?: return null
            val desc = frontmatter["description"]?.trim() ?: ""
            if (name.isBlank()) return null
            ai_skill(
                name = name,
                description = desc,
                skill_dir = md.parentFile!!,
                skill_md_path = if (is_project_relative) ".ai/skills/$name/SKILL.md" else md.absolutePath,
                source = source
            )
        }.getOrNull()
    }

    /** 提取 YAML frontmatter（--- 包裹的部分），返回字段 map + 正文 */
    private fun extract_frontmatter(text: String): Pair<Map<String, String>, String> {
        if (!text.startsWith("---")) return emptyMap<String, String>() to text
        val end = text.indexOf("\n---", 3)
        if (end < 0) return emptyMap<String, String>() to text
        val yaml = text.substring(3, end).trim()
        val body = text.substring(end + 4).trim()
        val map = mutableMapOf<String, String>()
        // 简单解析 key: value（不支持嵌套/多行，skill frontmatter 够用）
        for (line in yaml.lines()) {
            val idx = line.indexOf(':')
            if (idx > 0) {
                val k = line.substring(0, idx).trim()
                val v = line.substring(idx + 1).trim().trim('"').trim('\'')
                if (k.isNotEmpty()) map[k] = v
            }
        }
        return map to body
    }

    /** 给 system prompt 用的 skill 索引（name + description 列表） */
    fun skill_index_text(): String {
        ensure_current()
        if (skills.isEmpty()) return ""
        val sb = StringBuilder()
        sb.appendLine("可用技能（Skills）。需要时用 read 工具读取对应 SKILL.md 获取详细指令：")
        for (s in skills) {
            sb.appendLine("- `${s.name}`: ${s.description}（路径: ${s.skill_md_path}）")
        }
        return sb.toString()
    }

    /** 按 name 查 skill */
    fun find(name: String): ai_skill? {
        ensure_current()
        return skills.firstOrNull { it.name == name }
    }

    /** 手动激活某 skill：返回其 SKILL.md 全文（供注入对话） */
    fun activate(name: String): String? {
        val s = find(name) ?: return null
        return runCatching { File(s.skill_dir, "SKILL.md").readText() }.getOrNull()
    }

    /** 列出所有 skill（供管理 UI） */
    fun all(): List<ai_skill> {
        ensure_current()
        return skills.sortedBy { it.name }
    }

    /** 创建新 skill（写到全局目录），返回是否成功 */
    fun create_skill(name: String, description: String, content: String): Boolean {
        if (name.isBlank()) return false
        val safe_name = name.trim().replace(Regex("[^a-zA-Z0-9_-]"), "-")
        val dir = File(global_skills_dir, safe_name)
        dir.mkdirs()
        val md = File(dir, "SKILL.md")
        val full = buildString {
            appendLine("---")
            appendLine("name: $safe_name")
            appendLine("description: $description")
            appendLine("---")
            appendLine()
            appendLine(content.trim())
        }
        return runCatching { md.writeText(full); discover(); true }.getOrDefault(false)
    }

    /** 删除 skill（仅允许删全局/项目的，内置不删） */
    fun delete_skill(name: String): Boolean {
        val s = find(name) ?: return false
        if (s.source == skill_source.BUILT_IN || s.source == skill_source.PLUGIN) return false
        return runCatching {
            s.skill_dir.deleteRecursively()
            discover()
            true
        }.getOrDefault(false)
    }

    /** 读取 skill 的 SKILL.md 全文 */
    fun read_skill_content(name: String): String? {
        val s = find(name) ?: return null
        return runCatching { File(s.skill_dir, "SKILL.md").readText() }.getOrNull()
    }
}

/**
 * 首次运行时把内置 skill 从 assets 释放到全局 skill 目录。
 * 内置 skill 在 app/src/main/assets/skills/ 下。
 */
fun release_builtin_skills(context: Context, global_skills_dir: File) {
    if (!global_skills_dir.isDirectory) global_skills_dir.mkdirs()
    val assetSkills = runCatching { context.assets.list("skills") }.getOrNull() ?: return
    for (skillName in assetSkills) {
        val target = File(global_skills_dir, skillName)
        if (File(target, "SKILL.md").isFile) continue // 已释放过
        target.mkdirs()
        runCatching {
            context.assets.open("skills/$skillName/SKILL.md").use { input ->
                File(target, "SKILL.md").outputStream().use { input.copyTo(it) }
            }
        }
    }
}
