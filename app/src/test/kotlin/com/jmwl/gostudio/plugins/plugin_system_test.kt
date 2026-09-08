package com.jmwl.gostudio.plugins

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 插件系统核心逻辑回归：manifest/tool.json 解析校验、工具去重、
 * 启用状态迁移（旧数组格式 → 新对象格式）、ZIP 安装的路径穿越防护。
 */
class plugin_system_test {

    @get:Rule
    val tmp = TemporaryFolder()

    // ---------- manifest ----------

    @Test
    fun `manifest accepts hyphenated ids and parses category`() {
        val manifest = plugin_manifest.from_json(
            """{"id": "com.example.json-to-go", "name": "J2G", "version": "1.2.3", """ +
                """"description": "d", "author": "a", "category": "builtin", "min_app_version": 109}"""
        )
        assertThat(manifest).isNotNull()
        assertThat(manifest!!.id).isEqualTo("com.example.json-to-go")
        assertThat(manifest.category).isEqualTo("builtin")
        assertThat(manifest.min_app_version).isEqualTo(109)
    }

    @Test
    fun `manifest rejects malformed ids and versions`() {
        // 单段 id / 缺 name / 非语义化版本 都应拒绝
        assertThat(plugin_manifest.from_json("""{"id": "json2go", "name": "X", "version": "1.0.0"}""")).isNull()
        assertThat(plugin_manifest.from_json("""{"id": "a.b", "name": "", "version": "1.0.0"}""")).isNull()
        assertThat(plugin_manifest.from_json("""{"id": "a.b", "name": "X", "version": "1.0"}""")).isNull()
        assertThat(plugin_manifest.from_json("not json")).isNull()
    }

    // ---------- tool.json ----------

    @Test
    fun `tool def parses and validates`() {
        val dir = tmp.newFolder("json-to-go")
        java.io.File(dir, "tool.json").writeText(
            """{"id": "json-to-go", "name": "json2code", "kind": "json-to-go", """ +
                """"icon": "DataObject", "description": "desc"}"""
        )
        val tool = plugin_tool_def.from_file(java.io.File(dir, "tool.json"), "com.a.b")
        assertThat(tool).isNotNull()
        assertThat(tool!!.kind).isEqualTo("json-to-go")
        assertThat(tool.key).isEqualTo("com.a.b/json-to-go")

        // 缺 kind 拒绝
        java.io.File(dir, "tool.json").writeText("""{"id": "x", "name": "X"}""")
        assertThat(plugin_tool_def.from_file(java.io.File(dir, "tool.json"), "com.a.b")).isNull()
        // 非法 id（大写）拒绝
        java.io.File(dir, "tool.json").writeText("""{"id": "Bad_ID", "name": "X", "kind": "k"}""")
        assertThat(plugin_tool_def.from_file(java.io.File(dir, "tool.json"), "com.a.b")).isNull()
    }

    @Test
    fun `duplicate tool keys keep first occurrence`() {
        val first = plugin_tool_def("com.a.b", "t", "一", "json-to-go")
        val second = plugin_tool_def("com.a.b", "t", "二", "json-to-go")
        val third = plugin_tool_def("com.a.c", "t", "三", "json-to-go")
        val result = dedup_tools(listOf(first, second, third))
        assertThat(result).containsExactly(first, third).inOrder()
    }

    // ---------- 启用状态 ----------

    @Test
    fun `enabled state migrates legacy disabled array`() {
        // 旧格式：纯数组 = 被禁用的 id 列表 → enabled 为空（builtin 之外的默认停用）
        val (enabled, disabled) = parse_enabled_state("""["a", "b"]""")
        assertThat(enabled).isEmpty()
        assertThat(disabled).containsExactly("a", "b")
    }

    @Test
    fun `enabled state reads object format`() {
        val (enabled, disabled) = parse_enabled_state(
            """{"enabled": ["x"], "disabled": ["y"]}"""
        )
        assertThat(enabled).containsExactly("x")
        assertThat(disabled).containsExactly("y")
    }

    @Test
    fun `enabled state tolerates garbage and blank`() {
        assertThat(parse_enabled_state("")).isEqualTo(emptySet<String>() to emptySet<String>())
        assertThat(parse_enabled_state("{broken"))
            .isEqualTo(emptySet<String>() to emptySet<String>())
    }

    // ---------- ZIP 解压安全 ----------

    private fun zip_of(vararg entries: Pair<String, String>): ByteArrayInputStream {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zip ->
            entries.forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray())
                zip.closeEntry()
            }
        }
        return ByteArrayInputStream(bytes.toByteArray())
    }

    @Test
    fun `unzip extracts normal entries`() {
        val target = tmp.newFolder("out")
        plugin_manager.unzip(zip_of("manifest.json" to "{}", "skills/s/SKILL.md" to "---\n"), target)
        assertThat(java.io.File(target, "manifest.json").readText()).isEqualTo("{}")
        assertThat(java.io.File(target, "skills/s/SKILL.md").isFile).isTrue()
    }

    @Test
    fun `unzip blocks zip-slip path traversal`() {
        val target = tmp.newFolder("out")
        val evil = zip_of("../evil.txt" to "boom")
        assertThrows(SecurityException::class.java) {
            plugin_manager.unzip(evil, target)
        }
        assertThat(java.io.File(target.parentFile, "evil.txt").exists()).isFalse()
    }
}
