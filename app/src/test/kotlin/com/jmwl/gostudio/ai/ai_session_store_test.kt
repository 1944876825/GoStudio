package com.jmwl.gostudio.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.junit.Test
import java.io.File

/**
 * 会话存储 scope 隔离 + .current 标记 + 旧版平铺文件迁移的单元测试。
 */
class ai_session_store_test {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun msg(text: String) = ai_message(role = ai_message_role.USER, text = text)

    @Test
    fun `不同项目的会话互相隔离`() {
        val root = tmp.newFolder("sessions")
        val store_a = ai_session_store(root, scope = "p-aa-11111111", legacy_ids = listOf("aa"))
        val store_b = ai_session_store(root, scope = "p-bb-22222222", legacy_ids = listOf("bb"))

        store_a.save_session("aa", listOf(msg("项目A的问题")))
        store_b.save_session("bb", listOf(msg("项目B的问题")))

        assertEquals(listOf("aa"), store_a.list_sessions().map { it.id })
        assertEquals(listOf("bb"), store_b.list_sessions().map { it.id })
        assertTrue(store_a.load_session("aa").any { it.text == "项目A的问题" })
        assertTrue(store_b.load_session("bb").none { it.text == "项目A的问题" })
    }

    @Test
    fun `全局与项目会话隔离`() {
        val root = tmp.newFolder("sessions")
        val global = ai_session_store(root) // 默认 _global
        val project = ai_session_store(root, scope = "p-aa-11111111", legacy_ids = listOf("aa"))

        global.save_session("global", listOf(msg("全局问答")))
        project.save_session("aa", listOf(msg("项目问答")))

        assertEquals(listOf("global"), global.list_sessions().map { it.id })
        assertEquals(listOf("aa"), project.list_sessions().map { it.id })
    }

    @Test
    fun `current 标记记录上次激活会话`() {
        val root = tmp.newFolder("sessions")
        val store = ai_session_store(root, scope = "p-aa-11111111")

        assertNull(store.read_current_session_id()) // 尚无任何会话

        store.save_session("aa", listOf(msg("hi")))
        assertEquals("aa", store.read_current_session_id()) // 保存时自动记录

        store.set_current_session_id("chat-42")
        store.save_session("chat-42", listOf(msg("new chat")))
        assertEquals("chat-42", store.read_current_session_id())

        store.delete_session("chat-42")
        assertNull(store.read_current_session_id()) // 标记指向已删除的会话时视为无
    }

    @Test
    fun `旧版平铺项目会话迁移进项目 scope`() {
        val root = tmp.newFolder("sessions")
        // 模拟旧版写入的平铺文件：sessions/aa.jsonl
        File(root, "aa.jsonl").writeText("""{"role":"USER","text":"旧记录"}""")
        File(root, "aa.meta.json").writeText("""{"title":"旧标题","message_count":1}""")

        val scope = ai_session_store.project_scope(File("/data/projects/aa"))
        val store = ai_session_store(root, scope = scope, legacy_ids = listOf("aa"))

        assertFalse(File(root, "aa.jsonl").exists()) // 平铺文件被移走
        assertTrue(File(File(root, scope), "aa.jsonl").isFile)
        val meta = store.list_sessions().first { it.id == "aa" }
        assertEquals("旧标题", meta.title)
        assertTrue(store.load_session("aa").any { it.text == "旧记录" })
    }

    @Test
    fun `全局 scope 收编 global 与 chat 会话且不动其他项目文件`() {
        val root = tmp.newFolder("sessions")
        File(root, "global.jsonl").writeText("""{"role":"USER","text":"g"}""")
        File(root, "chat-123.jsonl").writeText("""{"role":"USER","text":"c"}""")
        File(root, "chat-123.meta.json").writeText("""{"title":"c","message_count":1}""")
        File(root, "bb.jsonl").writeText("""{"role":"USER","text":"b"}""") // 属于项目 bb

        val global = ai_session_store(root)

        assertEquals(listOf("chat-123", "global"), global.list_sessions().map { it.id }.sorted())
        assertFalse(File(root, "global.jsonl").exists())
        assertFalse(File(root, "chat-123.jsonl").exists())
        assertTrue(File(root, "bb.jsonl").exists()) // 项目的留给项目 scope 自己迁移
    }

    @Test
    fun `同名不同路径的项目 scope 不同`() {
        val s1 = ai_session_store.project_scope(File("/a/hello"))
        val s2 = ai_session_store.project_scope(File("/b/hello"))
        assertNotEquals(s1, s2)
        assertTrue(s1.startsWith("p-hello-"))
    }
}
