package com.jmwl.gostudio.ui.screens.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * parse_markdown 解析行为回归测试。
 * 重点：曾经存在的主线程死循环（以 ``` 开头但无法识别为开栏的行）必须永远终止。
 */
class ai_markdown_parser_test {

    @Test
    fun `plain fenced go block`() {
        val blocks = parse_markdown("```go\nfmt.Println(\"hi\")\n```")
        assertEquals(1, blocks.size)
        val code = blocks[0] as ai_md_block.Code
        assertEquals("go", code.lang)
        assertEquals("fmt.Println(\"hi\")", code.code)
    }

    @Test
    fun `fence with non-word language like c-plus-plus`() {
        // 曾经：```c++ 不被识别为开栏 → 也不进段落 → 死循环 ANR
        val blocks = parse_markdown("```c++\nint main(){}\n```")
        val code = blocks[0] as ai_md_block.Code
        assertEquals("c++", code.lang)
        assertEquals("int main(){}", code.code)
    }

    @Test
    fun `fence with info string takes first word as lang`() {
        val blocks = parse_markdown("```go 标题示例\nx := 1\n```")
        val code = blocks[0] as ai_md_block.Code
        assertEquals("go", code.lang)
    }

    @Test
    fun `unclosed fence during streaming swallows rest as code`() {
        val blocks = parse_markdown("```go\nfunc main() {")
        val code = blocks[0] as ai_md_block.Code
        assertEquals("go", code.lang)
        assertEquals("func main() {", code.code)
    }

    @Test(timeout = 5000)
    fun `inline fence on its own line terminates instead of hanging`() {
        // 曾经：```code``` 行首内联围栏 → 死循环；现在按普通文本消费（独立成段）
        val blocks = parse_markdown("说明如下\n```code```\n结束")
        assertEquals(3, blocks.size)
        assertEquals("说明如下", (blocks[0] as ai_md_block.Paragraph).text)
        assertEquals("```code```", (blocks[1] as ai_md_block.Paragraph).text)
        assertEquals("结束", (blocks[2] as ai_md_block.Paragraph).text)
    }

    @Test(timeout = 5000)
    fun `fence with backtick in info string terminates`() {
        val blocks = parse_markdown("```a`b\n内容")
        assertEquals(2, blocks.size)
        assertEquals("```a`b", (blocks[0] as ai_md_block.Paragraph).text)
        assertEquals("内容", (blocks[1] as ai_md_block.Paragraph).text)
    }

    @Test
    fun `paragraph keeps single newlines`() {
        // 曾经：连续非空行合并成空格，目录列表被压成一坨
        val blocks = parse_markdown("./\n[目录] bin\nmain.go (75B)")
        val para = blocks[0] as ai_md_block.Paragraph
        assertEquals("./\n[目录] bin\nmain.go (75B)", para.text)
    }

    @Test
    fun `paragraph stops at fence boundary`() {
        val blocks = parse_markdown("看代码\n```go\nx\n```\n后面")
        assertEquals(3, blocks.size)
        assertTrue(blocks[0] is ai_md_block.Paragraph)
        assertTrue(blocks[1] is ai_md_block.Code)
        assertTrue(blocks[2] is ai_md_block.Paragraph)
    }

    @Test
    fun `four-backtick outer fence not closed by inner three`() {
        val blocks = parse_markdown("````\n```go\ninner\n```\n````")
        val code = blocks[0] as ai_md_block.Code
        assertEquals("```go\ninner\n```", code.code)
    }

    @Test
    fun `list still parses after changes`() {
        // 现有行为：空行容忍会把有序/无序列表并进同一个 ListBlock（渲染时按 order 显示符号）
        val blocks = parse_markdown("- a\n- b\n\n1. one\n2. two")
        assertEquals(1, blocks.size)
        val list = blocks[0] as ai_md_block.ListBlock
        assertEquals(4, list.items.size)
        assertEquals(null, list.items[0].order)
        assertEquals(1, list.items[2].order)
    }

    @Test
    fun `heading quote hrule table unaffected`() {
        val blocks = parse_markdown("## 标题\n> 引用\n---\n| a | b |\n|---|---|\n| 1 | 2 |")
        assertEquals(4, blocks.size)
        assertTrue(blocks[0] is ai_md_block.Heading)
        assertTrue(blocks[1] is ai_md_block.Quote)
        assertTrue(blocks[2] is ai_md_block.HRule)
        val table = blocks[3] as ai_md_block.Table
        assertEquals(listOf("a", "b"), table.headers)
        assertEquals(listOf("1", "2"), table.rows[0])
    }

    @Test
    fun `blank line separates paragraphs`() {
        val blocks = parse_markdown("第一段\n第二行\n\n第二段")
        assertEquals(2, blocks.size)
        assertEquals("第一段\n第二行", (blocks[0] as ai_md_block.Paragraph).text)
    }
}
