package com.hmdeepseek.app.ui

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hmdeepseek.app.model.Citation

sealed class MdBlock {
    data class Heading(val level: Int, val text: String) : MdBlock()
    data class Paragraph(val text: String) : MdBlock()
    data class Code(val lang: String, val text: String) : MdBlock()
    data class Bullets(val ordered: Boolean, val items: List<String>) : MdBlock()
    data class Quote(val text: String) : MdBlock()
    data class Table(val header: List<String>, val rows: List<List<String>>) : MdBlock()
    data class Math(val latex: String) : MdBlock()
    data object Divider : MdBlock()
}

/** 一段可点文本里的可点区域 */
data class TapRange(val start: Int, val end: Int, val url: String)

private val ORDERED = Regex("^(\\d+)[.)]\\s+")
private val TABLE_DIVIDER = Regex("^\\|?\\s*:?-{2,}:?\\s*(\\|\\s*:?-{2,}:?\\s*)*\\|?$")

/** 轻量 Markdown：标题 / 代码块 / 列表 / 引用 / 分割线 / 表格 / 公式 + 行内样式 */
fun parseMarkdown(source: String): List<MdBlock> {
    val lines = source.split('\n')
    val blocks = mutableListOf<MdBlock>()
    var paragraph = mutableListOf<String>()

    fun flushParagraph() {
        if (paragraph.isNotEmpty()) {
            blocks.add(MdBlock.Paragraph(paragraph.joinToString("\n")))
            paragraph = mutableListOf()
        }
    }

    var i = 0
    while (i < lines.size) {
        val raw = lines[i]
        val t = raw.trim()

        if (t.startsWith("```")) {
            flushParagraph()
            val lang = t.removePrefix("```").trim()
            val code = mutableListOf<String>()
            i++
            while (i < lines.size && !lines[i].trim().startsWith("```")) {
                code.add(lines[i])
                i++
            }
            blocks.add(MdBlock.Code(lang, code.joinToString("\n")))
            i++
            continue
        }
        if (t == "$$") {
            flushParagraph()
            val math = mutableListOf<String>()
            i++
            while (i < lines.size && lines[i].trim() != "$$") {
                math.add(lines[i])
                i++
            }
            blocks.add(MdBlock.Math(math.joinToString("\n")))
            i++
            continue
        }
        if (t.isEmpty()) {
            flushParagraph()
            i++
            continue
        }
        if (t == "---" || t == "***" || t == "___") {
            flushParagraph()
            blocks.add(MdBlock.Divider)
            i++
            continue
        }
        if (t.startsWith("#")) {
            flushParagraph()
            val level = t.takeWhile { it == '#' }.length
            blocks.add(MdBlock.Heading(level, t.drop(level).trim()))
            i++
            continue
        }
        if (t.startsWith(">")) {
            flushParagraph()
            val quote = mutableListOf<String>()
            while (i < lines.size && lines[i].trim().startsWith(">")) {
                quote.add(lines[i].trim().removePrefix(">").trim())
                i++
            }
            blocks.add(MdBlock.Quote(quote.joinToString("\n")))
            continue
        }
        // 表格：当前行是 | a | b |，下一行是 |---|---|
        if (t.startsWith("|") && i + 1 < lines.size && TABLE_DIVIDER.matches(lines[i + 1].trim())) {
            flushParagraph()
            val header = splitTableRow(t)
            i += 2
            val rows = mutableListOf<List<String>>()
            while (i < lines.size && lines[i].trim().startsWith("|")) {
                rows.add(splitTableRow(lines[i].trim()))
                i++
            }
            blocks.add(MdBlock.Table(header, rows))
            continue
        }

        val isBullet = t.startsWith("- ") || t.startsWith("* ") || t.startsWith("+ ")
        val isOrdered = ORDERED.containsMatchIn(t)
        if (isBullet || isOrdered) {
            flushParagraph()
            val ordered = !isBullet
            val items = mutableListOf<String>()
            while (i < lines.size) {
                val cur = lines[i].trim()
                val curBullet = cur.startsWith("- ") || cur.startsWith("* ") || cur.startsWith("+ ")
                val curOrdered = ORDERED.containsMatchIn(cur)
                when {
                    !ordered && curBullet -> items.add(cur.drop(2).trim())
                    ordered && curOrdered -> items.add(cur.replaceFirst(ORDERED, ""))
                    cur.isNotEmpty() && items.isNotEmpty() &&
                        (lines[i].startsWith("  ") || lines[i].startsWith("\t")) ->
                        items[items.size - 1] = items[items.size - 1] + " " + cur
                    else -> break
                }
                i++
            }
            blocks.add(MdBlock.Bullets(ordered, items))
            continue
        }

        paragraph.add(t)
        i++
    }
    flushParagraph()
    return blocks
}

private fun splitTableRow(line: String): List<String> {
    val trimmed = line.removePrefix("|").removeSuffix("|")
    return trimmed.split("|").map { it.trim() }
}

// ---------------------------------------------------------------- 行内渲染

/** 把答案里的引用角标 [n] 按 citations 的 citedText 定位插进去 */
fun injectCitationMarkers(text: String, citations: List<Citation>): String {
    if (citations.isEmpty() || text.isEmpty()) return text
    val inserts = mutableListOf<Pair<Int, Int>>()
    var searchFrom = 0
    citations.forEachIndexed { idx, c ->
        val cited = c.citedText
        if (cited.isNotBlank()) {
            val probe = if (cited.length > 80) cited.takeLast(80) else cited
            val pos = text.indexOf(probe, searchFrom)
            if (pos >= 0) {
                val at = pos + probe.length
                inserts.add(at to (idx + 1))
                searchFrom = at
            }
        }
    }
    if (inserts.isEmpty()) return text
    val sb = StringBuilder()
    var cursor = 0
    inserts.sortedBy { it.first }.forEach { pair ->
        val at = pair.first.coerceIn(cursor, text.length)
        sb.append(text, cursor, at).append("[${pair.second}]")
        cursor = at
    }
    sb.append(text, cursor, text.length)
    return sb.toString()
}

class RichInline(val text: AnnotatedString, val taps: List<TapRange>)

fun inlineAnnotated(
    text: String,
    citations: List<Citation> = emptyList(),
    color: androidx.compose.ui.graphics.Color = AppColor.text,
    size: TextUnit = 16.sp,
    weight: FontWeight = FontWeight.Normal
): RichInline {
    val taps = mutableListOf<TapRange>()
    val annotated = buildAnnotatedString {
        var i = 0
        val plain = StringBuilder()

        fun flushPlain() {
            if (plain.isNotEmpty()) {
                withStyle(SpanStyle(color = color, fontSize = size, fontWeight = weight)) {
                    append(plain.toString())
                }
                plain.clear()
            }
        }

        while (i < text.length) {
            val c = text[i]

            // 行内代码
            if (c == '`') {
                val end = text.indexOf('`', i + 1)
                if (end > i) {
                    flushPlain()
                    withStyle(SpanStyle(color = AppColor.inlineCode, fontSize = size, fontFamily = FontFamily.Monospace)) {
                        append(text.substring(i + 1, end))
                    }
                    i = end + 1
                    continue
                }
            }

            // 粗体
            if (c == '*' && i + 1 < text.length && text[i + 1] == '*') {
                val end = text.indexOf("**", i + 2)
                if (end > i) {
                    flushPlain()
                    withStyle(SpanStyle(color = color, fontSize = size, fontWeight = FontWeight.Bold)) {
                        append(text.substring(i + 2, end))
                    }
                    i = end + 2
                    continue
                }
            }

            // 行内公式 $...$
            if (c == '$' && (i + 1 >= text.length || text[i + 1] != '$')) {
                val end = text.indexOf('$', i + 1)
                if (end > i) {
                    flushPlain()
                    withStyle(SpanStyle(color = color, fontSize = size, fontFamily = FontFamily.Serif, fontStyle = FontStyle.Italic)) {
                        append(Latex.toUnicode(text.substring(i + 1, end)))
                    }
                    i = end + 1
                    continue
                }
            }

            // 引用角标 [n]
            if (c == '[') {
                val close = text.indexOf(']', i + 1)
                if (close in (i + 2)..(i + 6)) {
                    val digits = text.substring(i + 1, close)
                    if (digits.all { it.isDigit() }) {
                        val n = digits.toIntOrNull() ?: 0
                        val cite = citations.getOrNull(n - 1)
                        if (cite != null && cite.url.isNotEmpty()) {
                            flushPlain()
                            val start = length
                            withStyle(
                                SpanStyle(
                                    color = AppColor.brand,
                                    fontSize = size * 0.72f,
                                    fontWeight = FontWeight.Medium,
                                    baselineShift = BaselineShift.Superscript
                                )
                            ) { append("[$n]") }
                            taps.add(TapRange(start, length, cite.url))
                            i = close + 1
                            continue
                        }
                    }
                }
                // 普通链接 [文字](url)
                if (close > i && close + 1 < text.length && text[close + 1] == '(') {
                    val closeParen = text.indexOf(')', close + 2)
                    if (closeParen > close) {
                        flushPlain()
                        val start = length
                        withStyle(
                            SpanStyle(
                                color = AppColor.brand,
                                fontSize = size,
                                textDecoration = TextDecoration.Underline
                            )
                        ) { append(text.substring(i + 1, close)) }
                        taps.add(TapRange(start, length, text.substring(close + 2, closeParen)))
                        i = closeParen + 1
                        continue
                    }
                }
            }

            // 斜体
            if (c == '*') {
                val end = text.indexOf('*', i + 1)
                if (end > i) {
                    flushPlain()
                    withStyle(
                        SpanStyle(color = color, fontSize = size, fontWeight = weight, fontStyle = FontStyle.Italic)
                    ) { append(text.substring(i + 1, end)) }
                    i = end + 1
                    continue
                }
            }

            plain.append(c)
            i++
        }
        flushPlain()
    }
    return RichInline(annotated, taps)
}

/** 可点文本：点击落在某个 TapRange 里就打开对应链接 */
@Composable
fun TappableText(
    rich: RichInline,
    onOpenUrl: (String) -> Unit,
    modifier: Modifier = Modifier,
    lineHeight: TextUnit = 26.sp,
    maxLines: Int = Int.MAX_VALUE
) {
    var layout by remember(rich) { mutableStateOf<TextLayoutResult?>(null) }
    Text(
        text = rich.text,
        modifier = modifier
            .fillMaxWidth()
            .pointerInput(rich) {
                detectTapGestures { offset ->
                    val l = layout ?: return@detectTapGestures
                    val pos = l.getOffsetForPosition(offset)
                    rich.taps.firstOrNull { pos >= it.start && pos < it.end }?.let { onOpenUrl(it.url) }
                }
            },
        style = TextStyle(lineHeight = lineHeight),
        maxLines = maxLines,
        onTextLayout = { layout = it }
    )
}

// ---------------------------------------------------------------- 代码高亮

private val KEYWORDS = setOf(
    "fun", "val", "var", "if", "else", "when", "for", "while", "return", "class", "object", "interface",
    "import", "package", "try", "catch", "finally", "throw", "null", "true", "false", "suspend",
    "public", "private", "internal", "override", "data", "sealed", "enum", "const", "let", "def",
    "function", "const", "new", "this", "async", "await", "await", "export", "default", "from",
    "int", "long", "double", "float", "boolean", "void", "static", "final", "struct", "type", "in", "is"
)

fun highlightCode(code: String): AnnotatedString = buildAnnotatedString {
    var i = 0
    val plain = StringBuilder()

    fun flushPlain() {
        if (plain.isNotEmpty()) {
            withStyle(SpanStyle(color = AppColor.codeText)) { append(plain.toString()) }
            plain.clear()
        }
    }

    while (i < code.length) {
        val c = code[i]

        // 行注释
        if (c == '/' && i + 1 < code.length && (code[i + 1] == '/' || code[i + 1] == '*')) {
            flushPlain()
            val end = if (code[i + 1] == '/') {
                code.indexOf('\n', i).let { if (it < 0) code.length else it }
            } else {
                code.indexOf("*/", i + 2).let { if (it < 0) code.length else it + 2 }
            }
            withStyle(SpanStyle(color = AppColor.codeComment)) { append(code.substring(i, end)) }
            i = end
            continue
        }

        // 字符串
        if (c == '"' || c == '\'' || c == '`') {
            flushPlain()
            val quote = c
            var j = i + 1
            while (j < code.length) {
                if (code[j] == '\\') {
                    j += 2
                    continue
                }
                if (code[j] == quote) {
                    j++
                    break
                }
                j++
            }
            withStyle(SpanStyle(color = AppColor.codeString)) { append(code.substring(i, j.coerceAtMost(code.length))) }
            i = j.coerceAtMost(code.length)
            continue
        }

        // 数字
        if (c.isDigit()) {
            flushPlain()
            var j = i
            while (j < code.length && (code[j].isDigit() || code[j] == '.' || code[j] == 'x' || code[j] == '_')) j++
            withStyle(SpanStyle(color = AppColor.codeNumber)) { append(code.substring(i, j)) }
            i = j
            continue
        }

        // 标识符 / 关键字
        if (c.isLetter() || c == '_') {
            var j = i
            while (j < code.length && (code[j].isLetterOrDigit() || code[j] == '_')) j++
            val word = code.substring(i, j)
            if (word in KEYWORDS) {
                flushPlain()
                withStyle(SpanStyle(color = AppColor.codeKeyword, fontWeight = FontWeight.Medium)) { append(word) }
            } else {
                plain.append(word)
            }
            i = j
            continue
        }

        plain.append(c)
        i++
    }
    flushPlain()
}

// ---------------------------------------------------------------- LaTeX 简化

/** 只做常见符号与结构的 Unicode 化，不是完整排版引擎 */
object Latex {
    private val SYMBOLS = mapOf(
        "\\alpha" to "α", "\\beta" to "β", "\\gamma" to "γ", "\\delta" to "δ", "\\epsilon" to "ε",
        "\\zeta" to "ζ", "\\eta" to "η", "\\theta" to "θ", "\\iota" to "ι", "\\kappa" to "κ",
        "\\lambda" to "λ", "\\mu" to "μ", "\\nu" to "ν", "\\xi" to "ξ", "\\pi" to "π", "\\rho" to "ρ",
        "\\sigma" to "σ", "\\tau" to "τ", "\\upsilon" to "υ", "\\phi" to "φ", "\\chi" to "χ",
        "\\psi" to "ψ", "\\omega" to "ω",
        "\\Gamma" to "Γ", "\\Delta" to "Δ", "\\Theta" to "Θ", "\\Lambda" to "Λ", "\\Xi" to "Ξ",
        "\\Pi" to "Π", "\\Sigma" to "Σ", "\\Phi" to "Φ", "\\Psi" to "Ψ", "\\Omega" to "Ω",
        "\\times" to "×", "\\cdot" to "·", "\\div" to "÷", "\\pm" to "±", "\\mp" to "∓",
        "\\le" to "≤", "\\leq" to "≤", "\\ge" to "≥", "\\geq" to "≥", "\\ne" to "≠", "\\neq" to "≠",
        "\\approx" to "≈", "\\equiv" to "≡", "\\propto" to "∝", "\\infty" to "∞",
        "\\sum" to "∑", "\\prod" to "∏", "\\int" to "∫", "\\partial" to "∂", "\\nabla" to "∇",
        "\\rightarrow" to "→", "\\to" to "→", "\\leftarrow" to "←", "\\Rightarrow" to "⇒",
        "\\in" to "∈", "\\notin" to "∉", "\\subset" to "⊂", "\\supset" to "⊃", "\\cup" to "∪",
        "\\cap" to "∩", "\\forall" to "∀", "\\exists" to "∃", "\\emptyset" to "∅",
        "\\angle" to "∠", "\\degree" to "°", "\\circ" to "∘", "\\ldots" to "…", "\\cdots" to "…",
        "\\quad" to "  ", "\\qquad" to "    ", "\\," to " ", "\\;" to " ", "\\!" to ""
    )

    fun toUnicode(latex: String): String {
        var s = latex.trim()
        s = s.replace("\\\\", "\n")

        // \frac{a}{b} -> (a)/(b)
        val frac = Regex("\\\\frac\\{([^{}]*)\\}\\{([^{}]*)\\}")
        while (frac.containsMatchIn(s)) {
            s = frac.replace(s) { "(${it.groupValues[1]})/(${it.groupValues[2]})" }
        }
        // \sqrt{x} -> √(x)
        s = Regex("\\\\sqrt\\{([^{}]*)\\}").replace(s) { "√(${it.groupValues[1]})" }
        s = s.replace("\\sqrt", "√")

        // 上标 ^{...} / ^x
        s = Regex("\\^\\{([^{}]*)\\}").replace(s) { superscript(it.groupValues[1]) }
        s = Regex("\\^([0-9a-zA-Z+\\-])").replace(s) { superscript(it.groupValues[1]) }
        // 下标 _{...} / _x
        s = Regex("_\\{([^{}]*)\\}").replace(s) { subscript(it.groupValues[1]) }
        s = Regex("_([0-9a-zA-Z+\\-])").replace(s) { subscript(it.groupValues[1]) }

        for ((k, v) in SYMBOLS) s = s.replace(k, v)
        // 去掉剩余的花括号/反斜杠
        s = s.replace("{", "").replace("}", "")
        s = s.replace(Regex("\\\\[a-zA-Z]+"), "")
        return s.trim()
    }

    private fun superscript(text: String): String {
        val map = mapOf(
            '0' to '⁰', '1' to '¹', '2' to '²', '3' to '³', '4' to '⁴',
            '5' to '⁵', '6' to '⁶', '7' to '⁷', '8' to '⁸', '9' to '⁹',
            '+' to '⁺', '-' to '⁻', 'n' to 'ⁿ', 'i' to 'ⁱ', '(' to '⁽', ')' to '⁾'
        )
        val sb = StringBuilder()
        for (ch in text) {
            val mapped = map[ch]
            if (mapped == null) return "($text)"
            sb.append(mapped)
        }
        return sb.toString()
    }

    private fun subscript(text: String): String {
        val map = mapOf(
            '0' to '₀', '1' to '₁', '2' to '₂', '3' to '₃', '4' to '₄',
            '5' to '₅', '6' to '₆', '7' to '₇', '8' to '₈', '9' to '₉',
            '+' to '₊', '-' to '₋', 'n' to 'ₙ', 'i' to 'ᵢ', '(' to '₍', ')' to '₎'
        )
        val sb = StringBuilder()
        for (ch in text) {
            val mapped = map[ch]
            if (mapped == null) return "_$text"
            sb.append(mapped)
        }
        return sb.toString()
    }
}

// ---------------------------------------------------------------- 组件

private fun headingSize(level: Int): TextUnit = when {
    level <= 1 -> 21.sp
    level == 2 -> 19.sp
    else -> 17.sp
}

@Composable
fun MarkdownView(
    content: String,
    modifier: Modifier = Modifier,
    citations: List<Citation> = emptyList(),
    onOpenUrl: (String) -> Unit = {}
) {
    val blocks = remember(content) { parseMarkdown(content) }
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        blocks.forEach { block ->
            when (block) {
                is MdBlock.Heading -> TappableText(
                    rich = inlineAnnotated(block.text, citations, AppColor.text, headingSize(block.level), FontWeight.Bold),
                    onOpenUrl = onOpenUrl,
                    lineHeight = (headingSize(block.level).value + 8).sp
                )

                is MdBlock.Paragraph -> TappableText(
                    rich = inlineAnnotated(block.text, citations),
                    onOpenUrl = onOpenUrl
                )

                is MdBlock.Code -> CodeBlock(block.lang, block.text)

                is MdBlock.Bullets -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    block.items.forEachIndexed { index, item ->
                        Row(modifier = Modifier.fillMaxWidth()) {
                            Text(
                                text = if (block.ordered) "${index + 1}." else "•",
                                fontSize = 15.sp,
                                color = AppColor.textSub,
                                modifier = Modifier.width(20.dp)
                            )
                            TappableText(
                                rich = inlineAnnotated(item, citations),
                                onOpenUrl = onOpenUrl,
                                modifier = Modifier.weight(1f),
                                lineHeight = 24.sp
                            )
                        }
                    }
                }

                is MdBlock.Quote -> Row(
                    modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min)
                ) {
                    Spacer(
                        modifier = Modifier
                            .width(3.dp)
                            .fillMaxHeight()
                            .background(AppColor.divider)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    TappableText(
                        rich = inlineAnnotated(block.text, citations, AppColor.textSub, 15.sp),
                        onOpenUrl = onOpenUrl,
                        modifier = Modifier.weight(1f),
                        lineHeight = 23.sp
                    )
                }

                is MdBlock.Table -> TableView(block, citations, onOpenUrl)

                is MdBlock.Math -> Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(AppColor.codeBg)
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = Latex.toUnicode(block.latex),
                        fontSize = 16.sp,
                        fontFamily = FontFamily.Serif,
                        color = AppColor.codeText
                    )
                }

                MdBlock.Divider -> HorizontalDivider(color = AppColor.divider)
            }
        }
    }
}

@Composable
private fun CodeBlock(lang: String, code: String) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(AppColor.codeBg)
            .padding(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = lang.ifEmpty { "code" },
                fontSize = 11.sp,
                color = AppColor.textFaint,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = "复制",
                fontSize = 11.sp,
                color = AppColor.brand,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable {
                        clipboard.setText(AnnotatedString(code))
                        Toast.makeText(context, "代码已复制", Toast.LENGTH_SHORT).show()
                    }
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = highlightCode(code),
            fontSize = 13.sp,
            lineHeight = 20.sp,
            fontFamily = FontFamily.Monospace,
            color = AppColor.codeText
        )
    }
}

@Composable
private fun TableView(table: MdBlock.Table, citations: List<Citation>, onOpenUrl: (String) -> Unit) {
    val columns = maxOf(table.header.size, table.rows.maxOfOrNull { it.size } ?: 0).coerceAtLeast(1)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(AppColor.bgGray)
            .padding(vertical = 6.dp)
    ) {
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp)) {
            for (c in 0 until columns) {
                val cell = table.header.getOrNull(c) ?: ""
                Text(
                    text = Latex.toUnicode(cell),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = AppColor.text,
                    modifier = Modifier.weight(1f)
                )
            }
        }
        HorizontalDivider(color = AppColor.divider)
        table.rows.forEach { row ->
            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp)) {
                for (c in 0 until columns) {
                    val cell = row.getOrNull(c) ?: ""
                    TappableText(
                        rich = inlineAnnotated(cell, citations, AppColor.textSub, 12.sp),
                        onOpenUrl = onOpenUrl,
                        modifier = Modifier.weight(1f),
                        lineHeight = 16.sp
                    )
                }
            }
            HorizontalDivider(color = AppColor.divider)
        }
    }
}
