package com.jmwl.gostudio.plugins

import org.json.JSONObject
import java.io.File

/**
 * 插件「工具」能力定义（tools 能力目录）。
 *
 * 一个插件可在 tools/ 下声明多个工具，每个工具一个子目录，内含 tool.json：
 * ```
 * plugins/<plugin-id>/
 * └── tools/
 *     └── json-to-go/
 *         └── tool.json
 * ```
 *
 * tool.json 字段：
 * - id          必需，插件内唯一（小写字母开头，小写字母数字连字符）
 * - name        必需，显示名
 * - kind        必需，工具类型：宿主内置引擎的注册键（当前支持 json-to-go）。
 *               插件只声明类型不携带代码，逻辑由宿主实现（数据包插件原则）。
 * - icon        可选，Material 图标名（宿主白名单解析，缺省 Construction）
 * - description 可选，工具说明
 *
 * kind 宿主不认识的工具会被忽略（老版本 App 装新插件时的兼容行为）。
 */
data class plugin_tool_def(
    val plugin_id: String,
    val tool_id: String,
    val name: String,
    val kind: String,
    val icon: String = "Construction",
    val description: String = ""
) {
    /** 全局唯一键 */
    val key: String get() = "$plugin_id/$tool_id"

    companion object {
        private val id_pattern = Regex("^[a-z][a-z0-9-]*$")

        /** 解析 tool.json；失败返回 null */
        fun from_file(file: File, plugin_id: String): plugin_tool_def? {
            return runCatching {
                val json = JSONObject(file.readText())
                val tool_id = json.optString("id").trim()
                val name = json.optString("name").trim()
                val kind = json.optString("kind").trim()
                if (tool_id.isEmpty() || name.isEmpty() || kind.isEmpty()) return null
                if (!id_pattern.matches(tool_id)) return null
                plugin_tool_def(
                    plugin_id = plugin_id,
                    tool_id = tool_id,
                    name = name,
                    kind = kind,
                    icon = json.optString("icon").trim().ifEmpty { "Construction" },
                    description = json.optString("description").trim()
                )
            }.getOrNull()
        }
    }
}

/** 插件实例声明的全部工具（不过滤 kind） */
fun plugin_instance.tool_defs(): List<plugin_tool_def> {
    val tools_dir = File(dir, "tools")
    if (!tools_dir.isDirectory) return emptyList()
    return tools_dir.listFiles()
        ?.filter { it.isDirectory }
        ?.mapNotNull { sub -> plugin_tool_def.from_file(File(sub, "tool.json"), id) }
        ?: emptyList()
}

/** 已启用且兼容宿主版本的插件提供的工具；kind 不在宿主支持集合内的不返回 */
fun plugin_manager.enabled_tools(supported_kinds: Set<String>): List<plugin_tool_def> {
    return dedup_tools(
        all()
            .filter { it.enabled && it.compatible }
            .flatMap { it.tool_defs() }
            .filter { it.kind in supported_kinds }
    )
}

/** 同 key（plugin_id/tool_id）去重，保留先出现的；插件声明重复工具 id 时行为可预期 */
internal fun dedup_tools(tools: List<plugin_tool_def>): List<plugin_tool_def> {
    val seen = mutableSetOf<String>()
    return tools.filter { seen.add(it.key) }
}
