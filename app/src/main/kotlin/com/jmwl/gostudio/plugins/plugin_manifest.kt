package com.jmwl.gostudio.plugins

import org.json.JSONObject
import java.io.File

/**
 * 插件 manifest 数据模型。
 *
 * 一个插件是 plugins/<id>/ 目录，目录内必须含 manifest.json。
 * 其余目录按能力可选存在：
 * - skills/    AI 技能（每个子目录一个 SKILL.md）
 * - tools/     工作区小工具（每个子目录一个 tool.json，逻辑由宿主内置引擎实现）
 * - templates/ 项目模板（预留）
 * - themes/    编辑器主题（预留）
 */
data class plugin_manifest(
    val id: String,                 // 唯一 id，形如 com.example.my-plugin
    val name: String,               // 显示名
    val version: String,            // 语义化版本
    val description: String = "",   // 可选描述
    val author: String = "",        // 可选作者
    val min_app_version: Int = 0,   // 最低宿主版本（预留）
    val category: String = ""       // 分类：builtin = 内置插件（随 APK 发布，重启自动恢复）
) {
    companion object {
        // id 段允许连字符（与 dist 包名/常见生态习惯一致），至少两段：com.example.my-plugin
        private val id_pattern = Regex("^[a-zA-Z][a-zA-Z0-9_-]*(\\.[a-zA-Z][a-zA-Z0-9_-]*)+$")
        private val version_pattern = Regex("^\\d+\\.\\d+\\.\\d+$")

        /** 从 manifest.json 文件解析；失败返回 null */
        fun from_file(file: File): plugin_manifest? {
            val text = runCatching { file.readText() }.getOrNull() ?: return null
            return from_json(text)
        }

        /** 从 JSON 文本解析并校验 */
        fun from_json(text: String): plugin_manifest? {
            return runCatching {
                val json = JSONObject(text)
                val id = json.optString("id").trim()
                val name = json.optString("name").trim()
                val version = json.optString("version", "1.0.0").trim()
                if (id.isEmpty() || name.isEmpty()) return null
                if (!id_pattern.matches(id)) return null
                if (!version_pattern.matches(version)) return null
                plugin_manifest(
                    id = id,
                    name = name,
                    version = version,
                    description = json.optString("description").trim(),
                    author = json.optString("author").trim(),
                    min_app_version = json.optInt("min_app_version", 0),
                    category = json.optString("category").trim()
                )
            }.getOrNull()
        }
    }
}

/** 扫描到的插件实例：manifest + 目录 + 状态标记 */
data class plugin_instance(
    val manifest: plugin_manifest,
    val dir: File,
    val enabled: Boolean,
    val compatible: Boolean = true
) {
    val id: String get() = manifest.id

    /** 内置插件（category = builtin） */
    val builtin: Boolean get() = manifest.category == "builtin"

    /** 插件提供的能力列表（用于 UI 展示；校验到有效条目才显示，目录空壳不算） */
    fun capabilities(): List<String> {
        val caps = mutableListOf<String>()
        val skills_dir = File(dir, "skills")
        if (skills_dir.isDirectory &&
            skills_dir.listFiles()?.any { File(it, "SKILL.md").isFile } == true
        ) {
            caps.add("AI 技能")
        }
        val tools_dir = File(dir, "tools")
        if (tools_dir.isDirectory &&
            tools_dir.listFiles()?.any { plugin_tool_def.from_file(File(it, "tool.json"), manifest.id) != null } == true
        ) {
            caps.add("工具")
        }
        if (File(dir, "templates").isDirectory) caps.add("项目模板")
        if (File(dir, "themes").isDirectory) caps.add("主题")
        return caps
    }

    /** 该插件的 skills 目录（存在才返回） */
    fun skill_dir(): File? = File(dir, "skills").takeIf { it.isDirectory }
}
