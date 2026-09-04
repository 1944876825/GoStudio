package com.jmwl.gostudio.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 上下文压缩核心逻辑测试（阈值判断 + 切点边界规则）。
 */
class ai_compaction_test {

    private fun user(text: String) = ai_message(role = ai_message_role.USER, text = text)
    private fun assistant(text: String) = ai_message(role = ai_message_role.ASSISTANT, text = text)
    private fun tool(id: String, text: String = "x") =
        ai_message(role = ai_message_role.TOOL, text = text, tool_call_id = id)

    @Test
    fun `should_compact 在阈值以下不触发`() {
        // 80% 阈值：20 万上限的阈值是 16 万，严格大于才触发
        assertFalse(ai_compaction.should_compact(used_chars = 160_000L, max_chars = 200_000, threshold_percent = 80))
        assertTrue(ai_compaction.should_compact(used_chars = 160_001L, max_chars = 200_000, threshold_percent = 80))
        // 阈值百分比生效
        assertTrue(ai_compaction.should_compact(used_chars = 145_000L, max_chars = 200_000, threshold_percent = 70))
        // 上限非法时不触发
        assertFalse(ai_compaction.should_compact(used_chars = 1_000_000L, max_chars = 0))
    }

    @Test
    fun `find_cut_point 切点只落在 user 边界`() {
        val msgs = listOf(
            user("第一问".repeat(50)),          // 0
            assistant("答一".repeat(50)),        // 1
            user("第二问".repeat(50)),          // 2
            assistant("答二".repeat(50)),        // 3
            tool("t1"),                          // 4
            user("第三问".repeat(50))            // 5
        )
        // 保留预算恰好覆盖到 index 2 的 user：切点必须是 2（不是 3/4）
        val keep = msgs.drop(2).sumOf { it.estimated_chars().toLong() }
        val cut = ai_compaction.find_cut_point(msgs, keep.toInt())
        assertEquals(2, cut)
    }

    @Test
    fun `find_cut_point 丢弃区不拆散 assistant 与 tool 配对`() {
        val msgs = listOf(
            user("q1"),                          // 0
            assistant("a1"),                     // 1
            user("q2"),                          // 2
            assistant("a2"),                     // 3（带 tool_calls 的 assistant）
            tool("t1"),                          // 4（属于 3）
            user("q3")                           // 5
        )
        // 预算只够保留最后一条 user：切点 5，丢弃 [0,5) 含完整 (3,4) 配对
        val keep = msgs[5].estimated_chars()
        val cut = ai_compaction.find_cut_point(msgs, keep)
        assertEquals(5, cut)
    }

    @Test
    fun `find_cut_point 历史太短返回负一`() {
        assertEquals(-1, ai_compaction.find_cut_point(listOf(user("只有一条")), 100))
        assertEquals(-1, ai_compaction.find_cut_point(emptyList(), 100))
    }

    @Test
    fun `find_cut_point 预算覆盖全部历史时返回负一`() {
        val msgs = listOf(user("q1"), assistant("a"), user("q2"))
        // 预算巨大：累计永远达不到 → 无需切，返回 -1
        val cut = ai_compaction.find_cut_point(msgs, keep_recent_chars = 1_000_000)
        assertEquals(-1, cut)
    }

    @Test
    fun `find_cut_point 摘要消息也是合法切点`() {
        val summary = ai_message(
            role = ai_message_role.USER,
            text = "旧摘要",
            is_summary = true, summary_origin_count = 3, summary_tokens_before = 100
        )
        val msgs = listOf(summary, user("新问题"), assistant("回答"), user("再问"))
        val keep = msgs.drop(2).sumOf { it.estimated_chars().toLong() }
        // 预算累计到 assistant(2) 时达标，但 assistant 不是合法切点，
        // 回退到 user(1)：保留区从 user 边界开始（宁可多保留一点）
        val cut = ai_compaction.find_cut_point(msgs, keep.toInt())
        assertEquals(1, cut)
    }

    @Test
    fun `compact 启发式兜底生成带元数据的摘要`() {
        val msgs = listOf(
            user("帮我写个服务器"),
            assistant("好的，用 http.ListenAndServe"),
            user("再加个健康检查"),
            assistant("已添加 /healthz 端点")
        )
        // keep=0 → 尽量多压缩（切在最后一个满足预算的 user）
        val result = kotlinx.coroutines.runBlocking {
            ai_compaction.compact(msgs, keep_recent_chars = 0, client = null, previous_summary = null)
        }
        // 至少压缩掉最前面一对
        assertTrue(result != null && result.compacted_count >= 2)
        val summary = result!!.summary_message
        assertTrue(summary.is_summary)
        assertEquals(result.compacted_count, summary.summary_origin_count)
        assertTrue(summary.text.contains("帮我写个服务器"))
        // 摘要角色是 USER，可作为请求首条对话消息（满足 Anthropic 交替要求）
        assertEquals(ai_message_role.USER, summary.role)
    }

    @Test
    fun `消息字符估算包含工具调用参数`() {
        val with_tools = ai_message(
            role = ai_message_role.ASSISTANT,
            text = "abc",
            tool_calls = listOf(ai_tool_call(id = "1", name = "read", arguments_json = "{\"path\":\"main.go\"}"))
        )
        val without = assistant("abc")
        assertTrue(with_tools.estimated_chars() > without.estimated_chars())
        assertEquals(3, without.estimated_chars())
    }
}
