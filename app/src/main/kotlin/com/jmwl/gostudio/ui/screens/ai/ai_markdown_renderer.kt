package com.jmwl.gostudio.ui.screens.ai

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jmwl.gostudio.ui.toast.app_toast
import com.jmwl.gostudio.ui.theme.app_theme_provider

// ==================== Markdown 数据模型 ====================

/** 一个块级元素 */
sealed class ai_md_block {
    data class Code(val lang: String, val code: String) : ai_md_block()
    data class Heading(val level: Int, val text: String) : ai_md_block()
    data class Paragraph(val text: String) : ai_md_block()
    data class Quote(val text: String) : ai_md_block()
    data class HRule(val placeholder: String = "") : ai_md_block()
    /** 有序列表（items：每项 (序号, 内容)）；无序列表 order=null */
    data class ListBlock(val items: List<ListItem>) : ai_md_block()
    data class ListItem(val order: Int?, val text: String)
    data class Table(val headers: List<String>, val rows: List<List<String>>) : ai_md_block()
}

/**
 * Markdown 解析器（轻量、自研，覆盖 AI 回复常见语法）。
 * 支持的语法：代码围栏、标题 #、有序/无序列表、引用 >、分隔线 ---、表格 |、段落。
 * 行内：**粗** *斜* `代码` [text](url) ~~删除线~~。
 */
fun parse_markdown(text: String): List<ai_md_block> {
    if (text.isBlank()) return emptyList()
    val lines = text.lines()
    val blocks = mutableListOf<ai_md_block>()
    var i = 0

    while (i < lines.size) {
        val line = lines[i]

        // 1. 代码围栏 ```lang（info string 允许任意非反引号字符：
        //    兼容 ```c++ / ```c# / ```go 标题 等写法，语言取首词）
        val fenceMatch = Regex("^(`{3,})([^`]*)$").find(line.trim())
        if (fenceMatch != null) {
            val fence = fenceMatch.groupValues[1]
            val lang = fenceMatch.groupValues[2].trim().substringBefore(' ').trim().take(16)
            val codeLines = mutableListOf<String>()
            i++
            while (i < lines.size) {
                val closeMatch = Regex("^(`{3,})\\s*$").find(lines[i].trim())
                // 闭合围栏：只含反引号且长度不小于开栏（嵌套 4 反引栏不会误闭合）
                if (closeMatch != null && closeMatch.groupValues[1].length >= fence.length) {
                    i++
                    break
                }
                codeLines.add(lines[i])
                i++
            }
            // 流式中可能围栏未闭合：把剩下的都当代码
            blocks.add(ai_md_block.Code(lang, codeLines.joinToString("\n").trimEnd('\n')))
            continue
        }

        // 2. 分隔线 --- / *** / ___
        if (Regex("^([-*_])\\1{2,}\\s*$").matches(line.trim())) {
            blocks.add(ai_md_block.HRule())
            i++
            continue
        }

        // 3. 标题 # ## ###
        val headingMatch = Regex("^(#{1,6})\\s+(.+?)\\s*#*\\s*$").find(line)
        if (headingMatch != null) {
            blocks.add(ai_md_block.Heading(headingMatch.groupValues[1].length, headingMatch.groupValues[2]))
            i++
            continue
        }

        // 4. 引用 >
        if (line.trimStart().startsWith(">")) {
            val quoteLines = mutableListOf<String>()
            while (i < lines.size && lines[i].trimStart().startsWith(">")) {
                quoteLines.add(lines[i].trimStart().removePrefix(">").trim())
                i++
            }
            blocks.add(ai_md_block.Quote(quoteLines.joinToString("\n")))
            continue
        }

        // 5. 表格 |header|header|
        if (line.trimStart().startsWith("|") && i + 1 < lines.size &&
            Regex("^\\|?\\s*:?-{2,}:?\\s*\\|").containsMatchIn(lines[i + 1])
        ) {
            val headers = split_table_row(line)
            i += 2 // 跳过分隔行
            val rows = mutableListOf<List<String>>()
            while (i < lines.size && lines[i].trimStart().startsWith("|")) {
                rows.add(split_table_row(lines[i]))
                i++
            }
            blocks.add(ai_md_block.Table(headers, rows))
            continue
        }

        // 6. 列表（- /* / + 或 1.）
        val listStart = list_item_match(line)
        if (listStart != null) {
            val items = mutableListOf<ai_md_block.ListItem>()
            while (i < lines.size) {
                val m = list_item_match(lines[i])
                if (m != null) {
                    val (order, content) = m
                    items.add(ai_md_block.ListItem(order, content.trim()))
                    i++
                } else if (lines[i].isBlank()) {
                    // 空行允许列表延续（容忍单空行）
                    if (i + 1 < lines.size && list_item_match(lines[i + 1]) != null) i++ else break
                } else {
                    break
                }
            }
            blocks.add(ai_md_block.ListBlock(items))
            continue
        }

        // 7. 空行跳过
        if (line.isBlank()) {
            i++
            continue
        }

        // 8. 段落：连续非空行合并（保留换行：聊天场景下模型的硬换行有意义，
        //    合并成空格会把目录列表/松散多行文本压成一坨）
        val paraLines = mutableListOf<String>()
        while (i < lines.size && lines[i].isNotBlank() && !is_paragraph_boundary(lines[i])) {
            paraLines.add(lines[i])
            i++
        }
        if (paraLines.isEmpty()) {
            // 兜底：以 ``` 开头但没被识别为开栏的行（如行内围栏 ```code```），
            // 不消费会导致主循环死循环（主线程 ANR），按普通文本吃掉
            paraLines.add(lines[i])
            i++
        }
        blocks.add(ai_md_block.Paragraph(paraLines.joinToString("\n")))
    }
    return blocks
}

/** 段落收集的终止边界：代码围栏/标题/引用/列表/分隔线 */
private fun is_paragraph_boundary(line: String): Boolean {
    val trimmed = line.trim()
    return Regex("^`{3,}").containsMatchIn(trimmed) ||
        Regex("^#{1,6}\\s").containsMatchIn(line) ||
        line.trimStart().startsWith(">") ||
        list_item_match(line) != null ||
        Regex("^([-*_])\\1{2,}\\s*$").matches(trimmed)
}

/** 匹配列表项，返回 (序号 or null, 内容) */
private fun list_item_match(line: String): Pair<Int?, String>? {
    val trimmed = line.trimStart()
    // 有序：1. / 1)
    val ordered = Regex("^(\\d+)[.)]\\s+(.+)").find(trimmed)
    if (ordered != null) return ordered.groupValues[1].toIntOrNull() to ordered.groupValues[2]
    // 无序：- / * / +
    val unordered = Regex("^[-*+]\\s+(.+)").find(trimmed)
    if (unordered != null) return null to unordered.groupValues[1]
    return null
}

private fun split_table_row(line: String): List<String> {
    return line.trim().trim('|').split("|").map { it.trim() }
}

// ==================== 行内解析 ====================

/**
 * 把一段行内文本（可能含 **粗** *斜* `code` [t](u) ~~del~~）渲染成 AnnotatedString。
 * 链接用 [androidx.compose.ui.text.LinkAnnotation.Url] 标注，Text 自动处理点击打开。
 */
fun render_inline(text: String, base: SpanStyle, link_color: Color = Color.Unspecified): AnnotatedString = buildAnnotatedString {
    var pos = 0
    while (pos < text.length) {
        // 行内代码 `...`
        if (text[pos] == '`') {
            val end = text.indexOf('`', pos + 1)
            if (end > pos) {
                withStyle(base.copy(fontFamily = FontFamily.Monospace, background = base.color.copy(alpha = 0.12f))) {
                    append(text.substring(pos + 1, end))
                }
                pos = end + 1
                continue
            }
        }
        // 粗体 **...**
        if (pos + 1 < text.length && text[pos] == '*' && text[pos + 1] == '*') {
            val end = text.indexOf("**", pos + 2)
            if (end > pos) {
                withStyle(base.copy(fontWeight = FontWeight.Bold)) { append(text.substring(pos + 2, end)) }
                pos = end + 2
                continue
            }
        }
        // 删除线 ~~...~~
        if (pos + 1 < text.length && text[pos] == '~' && text[pos + 1] == '~') {
            val end = text.indexOf("~~", pos + 2)
            if (end > pos) {
                withStyle(base.copy(textDecoration = TextDecoration.LineThrough)) { append(text.substring(pos + 2, end)) }
                pos = end + 2
                continue
            }
        }
        // 斜体 *...*
        if (text[pos] == '*') {
            val end = text.indexOf('*', pos + 1)
            if (end > pos && text.getOrNull(end + 1) != '*') {
                withStyle(base.copy(fontStyle = FontStyle.Italic)) { append(text.substring(pos + 1, end)) }
                pos = end + 1
                continue
            }
        }
        // 链接 [text](url)：只认 http(s)/mailto，防 javascript: 等伪协议
        if (text[pos] == '[') {
            val textEnd = text.indexOf(']', pos + 1)
            if (textEnd > pos && text.getOrNull(textEnd + 1) == '(') {
                val urlEnd = text.indexOf(')', textEnd + 2)
                if (urlEnd > textEnd) {
                    val url = text.substring(textEnd + 2, urlEnd)
                    if (url.startsWith("http://") || url.startsWith("https://") || url.startsWith("mailto:")) {
                        val linkText = text.substring(pos + 1, textEnd)
                        withLink(
                            LinkAnnotation.Url(
                                url = url,
                                styles = TextLinkStyles(
                                    style = base.copy(
                                        color = if (link_color == Color.Unspecified) base.color else link_color,
                                        textDecoration = TextDecoration.Underline
                                    )
                                )
                            )
                        ) {
                            append(linkText)
                        }
                        pos = urlEnd + 1
                        continue
                    }
                }
            }
        }
        // 普通字符
        append(text[pos])
        pos++
    }
}

// ==================== 渲染入口 ====================

/**
 * 完整的 Markdown 文本渲染（含代码围栏、列表、表格、引用、标题、行内格式）。
 * 流式时末尾追加闪烁光标。
 */
@Composable
fun ai_markdown_text(
    text: String,
    color: Color,
    streaming: Boolean = false,
    on_insert_code: ((String) -> Unit)? = null,
    on_apply_code: ((String, String) -> Unit)? = null,
    current_file_suffix: String? = null
) {
    val colors = app_theme_provider.colors
    // 流式中代码块可能未闭合：解析器已容忍
    val blocks = remember(text) { parse_markdown(text) }

    Column {
        for (block in blocks) {
            when (block) {
                is ai_md_block.Code -> ai_code_block(
                    lang = block.lang,
                    code = block.code,
                    on_insert_code = on_insert_code,
                    on_apply_code = on_apply_code,
                    can_apply = current_file_suffix != null && lang_matches_suffix(block.lang, current_file_suffix)
                )
                is ai_md_block.Heading -> {
                    val (size, weight) = when (block.level) {
                        1 -> 18.sp to FontWeight.Bold
                        2 -> 16.sp to FontWeight.Bold
                        3 -> 14.sp to FontWeight.SemiBold
                        else -> 13.sp to FontWeight.Medium
                    }
                    Text(
                        text = render_inline(block.text, SpanStyle(color = color, fontSize = size, fontWeight = weight), colors.title_highlight),
                        fontSize = size,
                        modifier = Modifier.padding(vertical = 3.dp)
                    )
                }
                is ai_md_block.Paragraph -> {
                    Text(
                        text = render_inline(block.text, SpanStyle(color = color, fontSize = 13.sp), colors.title_highlight),
                        fontSize = 13.sp,
                        lineHeight = 19.sp,
                        modifier = Modifier.padding(vertical = 2.dp)
                    )
                }
                is ai_md_block.Quote -> {
                    Surface(
                        color = colors.editor_bg.copy(alpha = 0.35f),
                        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)
                    ) {
                        Row(modifier = Modifier.padding(top = 4.dp, bottom = 4.dp, start = 8.dp, end = 4.dp)) {
                            Box(
                                modifier = Modifier
                                    .width(3.dp)
                                    .height(20.dp)
                                    .background(colors.title_highlight.copy(alpha = 0.5f))
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = render_inline(block.text, SpanStyle(color = colors.subtitle, fontSize = 12.sp), colors.title_highlight),
                                fontSize = 12.sp,
                                lineHeight = 17.sp
                            )
                        }
                    }
                }
                is ai_md_block.HRule -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp)
                            .height(1.dp)
                            .background(colors.input_border.copy(alpha = 0.4f))
                    )
                }
                is ai_md_block.ListBlock -> {
                    Column(modifier = Modifier.padding(start = 4.dp).padding(vertical = 2.dp)) {
                        for ((idx, item) in block.items.withIndex()) {
                            Row(modifier = Modifier.padding(vertical = 1.dp)) {
                                Text(
                                    text = item.order?.let { "$it. " } ?: "•  ",
                                    color = colors.title_highlight,
                                    fontSize = 13.sp
                                )
                                Text(
                                    text = render_inline(item.text, SpanStyle(color = color, fontSize = 13.sp), colors.title_highlight),
                                    fontSize = 13.sp,
                                    lineHeight = 18.sp,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }
                }
                is ai_md_block.Table -> ai_table_block(block, color)
            }
        }
        // 流式光标
        if (streaming) {
            StreamingCursor()
        }
    }
}

/** 闪烁光标 */
@Composable
private fun StreamingCursor() {
    val transition = rememberInfiniteTransition(label = "cursor")
    val alpha by transition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(animation = tween(500), repeatMode = RepeatMode.Reverse),
        label = "cursor_alpha"
    )
    Text("▌", fontSize = 13.sp, color = app_theme_provider.colors.title_highlight, modifier = Modifier.alpha(alpha))
}

/** 表格渲染 */
@Composable
private fun ai_table_block(block: ai_md_block.Table, color: Color) {
    val colors = app_theme_provider.colors
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = colors.editor_bg.copy(alpha = 0.3f),
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)
    ) {
        Column {
            // 表头
            Row(modifier = Modifier.fillMaxWidth().background(colors.title_highlight.copy(alpha = 0.08f))) {
                for (h in block.headers) {
                    Text(
                        text = h,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = color,
                        modifier = Modifier.weight(1f).padding(6.dp)
                    )
                }
            }
            // 行
            for (row in block.rows) {
                Row(modifier = Modifier.fillMaxWidth()) {
                    for (cell in row) {
                        Text(
                            text = cell,
                            fontSize = 11.sp,
                            color = colors.card_text_subtitle,
                            modifier = Modifier.weight(1f).padding(6.dp)
                        )
                    }
                }
            }
        }
    }
}

/** 代码块：语言标签 + 复制 + 横向滚动 + 浅着色 */
@Composable
private fun ai_code_block(
    lang: String,
    code: String,
    on_insert_code: ((String) -> Unit)?,
    on_apply_code: ((String, String) -> Unit)?,
    can_apply: Boolean
) {
    val colors = app_theme_provider.colors
    val context = LocalContext.current
    var copied by remember { mutableStateOf(false) }

    Surface(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        shape = RoundedCornerShape(8.dp),
        color = colors.editor_bg.copy(alpha = 0.65f)
    ) {
        Column {
            // 顶部工具条：语言标签 + 操作按钮
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(colors.editor_hint.copy(alpha = 0.15f))
                    .padding(horizontal = 8.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (lang.isNotBlank()) {
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = colors.title_highlight.copy(alpha = 0.18f)
                    ) {
                        Text(
                            text = lang,
                            fontSize = 9.sp,
                            fontFamily = FontFamily.Monospace,
                            color = colors.title_highlight,
                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                        )
                    }
                } else {
                    Spacer(Modifier.size(0.dp))
                }
                Spacer(Modifier.weight(1f))
                // 复制
                IconButton(
                    onClick = {
                        copy_to_clipboard(context, code)
                        copied = true
                        app_toast.show(context, "已复制", app_toast.LENGTH_SHORT)
                    },
                    modifier = Modifier.size(24.dp)
                ) {
                    Icon(
                        imageVector = if (copied) Icons.Default.Check else Icons.Default.ContentCopy,
                        contentDescription = "复制",
                        tint = if (copied) colors.success else colors.subtitle,
                        modifier = Modifier.size(14.dp)
                    )
                }
                // 插入到光标
                if (on_insert_code != null) {
                    IconButton(
                        onClick = { on_insert_code(code) },
                        modifier = Modifier.size(24.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Edit,
                            contentDescription = "插入到光标",
                            tint = colors.subtitle,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }
                // 应用（替换当前文件）
                if (on_apply_code != null && can_apply) {
                    IconButton(
                        onClick = { on_apply_code(lang, code) },
                        modifier = Modifier.size(24.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = "应用到当前文件",
                            tint = colors.success,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }
            }
            // 代码内容（横向滚动 + 简单着色）
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(8.dp)
            ) {
                Text(
                    text = highlight_code(code, lang, colors),
                    fontSize = 11.5.sp,
                    fontFamily = FontFamily.Monospace,
                    lineHeight = 16.sp
                )
            }
        }
    }
}

/** 判断代码块语言是否与文件后缀匹配（用于决定是否显示"应用"按钮） */
fun lang_matches_suffix(lang: String, suffix: String): Boolean {
    val l = lang.lowercase().trim()
    val s = suffix.lowercase().trim()
    return when (l) {
        "go", "golang" -> s == "go"
        "kotlin", "kt" -> s == "kt" || s == "kts"
        "java" -> s == "java"
        "python", "py" -> s == "py"
        "javascript", "js" -> s == "js"
        "typescript", "ts" -> s == "ts"
        "json" -> s == "json"
        "yaml", "yml" -> s == "yaml" || s == "yml"
        "sh", "bash", "shell" -> s == "sh" || s == "bash"
        "xml", "html" -> s == "xml" || s == "html"
        "css" -> s == "css"
        "sql" -> s == "sql"
        "c" -> s == "c" || s == "h"
        "cpp", "c++" -> s == "cpp" || s == "cc" || s == "cxx" || s == "hpp"
        "rust", "rs" -> s == "rs"
        "markdown", "md" -> s == "md"
        else -> l == s
    }
}

/**
 * 轻量代码着色：用正则识别关键字/字符串/注释/数字。
 * 不做完整 tokenizer，AI 回复里的代码段够用。
 */
fun highlight_code(code: String, lang: String, colors: com.jmwl.gostudio.ui.theme.app_colors): AnnotatedString {
    return buildAnnotatedString {
        val keywordColor = colors.title_highlight
        val stringColor = colors.success
        val commentColor = colors.subtitle
        val numberColor = colors.warning
        val defaultColor = colors.editor_text

        val keywords = lang_keywords(lang)

        // 简化策略：先按行处理，每行用正则切 token
        for ((lineIdx, line) in code.lines().withIndex()) {
            if (lineIdx > 0) append("\n")
            var pos = 0
            // 整行注释
            val lineCommentPrefix = when {
                lang.lowercase() in listOf("python", "py", "sh", "bash", "shell", "yaml", "yml", "toml") -> "#"
                lang.lowercase() in listOf("sql") -> "--"
                else -> "//"
            }
            val trimmedLine = line.trimStart()
            if (trimmedLine.startsWith(lineCommentPrefix)) {
                withStyle(SpanStyle(color = commentColor, fontStyle = FontStyle.Italic)) { append(line) }
                continue
            }
            // token 切分
            val tokenRegex = Regex("\"(?:\\\\.|[^\"\\\\])*\"|'(?:\\\\.|[^'\\\\])*'|`[^`]*`|\\b\\d+\\.?\\d*\\b|\\b[A-Za-z_]\\w*\\b|[^A-Za-z0-9_\\s\"'`]+|\\s+")
            for (m in tokenRegex.findAll(line)) {
                val tok = m.value
                when {
                    tok.startsWith("\"") || tok.startsWith("'") || tok.startsWith("`") ->
                        withStyle(SpanStyle(color = stringColor)) { append(tok) }
                    tok.firstOrNull()?.isDigit() == true ->
                        withStyle(SpanStyle(color = numberColor)) { append(tok) }
                    tok in keywords ->
                        withStyle(SpanStyle(color = keywordColor, fontWeight = FontWeight.Bold)) { append(tok) }
                    else -> withStyle(SpanStyle(color = defaultColor)) { append(tok) }
                }
            }
        }
    }
}

private fun lang_keywords(lang: String): Set<String> = when (lang.lowercase().trim()) {
    "go", "golang" -> setOf(
        "break", "case", "chan", "const", "continue", "default", "defer", "else", "fallthrough", "for",
        "func", "go", "goto", "if", "import", "interface", "map", "package", "range", "return", "select",
        "struct", "switch", "type", "var", "nil", "true", "false", "iota", "make", "new", "len", "cap",
        "append", "copy", "delete", "panic", "recover", "print", "println"
    )
    "kotlin", "kt" -> setOf(
        "fun", "val", "var", "class", "object", "interface", "if", "else", "when", "for", "while", "do",
        "return", "break", "continue", "package", "import", "as", "is", "in", "typealias", "data",
        "sealed", "enum", "companion", "override", "private", "public", "protected", "internal",
        "suspend", "null", "true", "false", "this", "super", "by", "get", "set", "init"
    )
    "java" -> setOf(
        "public", "private", "protected", "class", "interface", "extends", "implements", "static", "final",
        "void", "int", "long", "double", "float", "boolean", "char", "byte", "short", "String",
        "if", "else", "for", "while", "do", "switch", "case", "break", "continue", "return", "new",
        "try", "catch", "finally", "throw", "throws", "import", "package", "this", "super",
        "null", "true", "false", "instanceof", "abstract", "synchronized", "volatile", "transient"
    )
    "python", "py" -> setOf(
        "def", "class", "if", "elif", "else", "for", "while", "return", "import", "from", "as", "try",
        "except", "finally", "with", "lambda", "yield", "global", "nonlocal", "pass", "break",
        "continue", "and", "or", "not", "in", "is", "None", "True", "False", "self", "raise", "assert", "del"
    )
    else -> emptySet()
}

/** 复制到系统剪贴板 */
private fun copy_to_clipboard(context: android.content.Context, text: String) {
    val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
    clipboard.setPrimaryClip(android.content.ClipData.newPlainText("AI 回复", text))
}
