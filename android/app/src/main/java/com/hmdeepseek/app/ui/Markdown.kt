package com.hmdeepseek.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

sealed class MdBlock {
    data class Heading(val level: Int, val text: String) : MdBlock()
    data class Paragraph(val text: String) : MdBlock()
    data class Code(val lang: String, val text: String) : MdBlock()
    data class Bullets(val ordered: Boolean, val items: List<String>) : MdBlock()
    data class Quote(val text: String) : MdBlock()
    data object Divider : MdBlock()
}

private val ORDERED = Regex("^(\\d+)[.)]\\s+")

/** 轻量 Markdown：标题 / 代码块 / 列表 / 引用 / 分割线 + 行内粗斜体、行内代码、链接 */
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
                    !ordered && curBullet -> {
                        items.add(cur.drop(2).trim())
                    }
                    ordered && curOrdered -> {
                        items.add(cur.replaceFirst(ORDERED, ""))
                    }
                    cur.isNotEmpty() && items.isNotEmpty() &&
                        (lines[i].startsWith("  ") || lines[i].startsWith("\t")) -> {
                        items[items.size - 1] = items[items.size - 1] + " " + cur
                    }
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

/** 行内样式（粗体、斜体、行内代码、链接） */
fun inlineAnnotated(
    text: String,
    color: Color = AppColor.text,
    size: TextUnit = 16.sp,
    weight: FontWeight = FontWeight.Normal
): AnnotatedString = buildAnnotatedString {
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

        if (c == '[') {
            val closeBracket = text.indexOf(']', i + 1)
            if (closeBracket > i && closeBracket + 1 < text.length && text[closeBracket + 1] == '(') {
                val closeParen = text.indexOf(')', closeBracket + 2)
                if (closeParen > closeBracket) {
                    flushPlain()
                    withStyle(
                        SpanStyle(
                            color = AppColor.brand,
                            fontSize = size,
                            textDecoration = TextDecoration.Underline
                        )
                    ) {
                        append(text.substring(i + 1, closeBracket))
                    }
                    i = closeParen + 1
                    continue
                }
            }
        }

        if (c == '*') {
            val end = text.indexOf('*', i + 1)
            if (end > i) {
                flushPlain()
                withStyle(
                    SpanStyle(color = color, fontSize = size, fontWeight = weight, fontStyle = FontStyle.Italic)
                ) {
                    append(text.substring(i + 1, end))
                }
                i = end + 1
                continue
            }
        }

        plain.append(c)
        i++
    }
    flushPlain()
}

private fun headingSize(level: Int): TextUnit = when {
    level <= 1 -> 21.sp
    level == 2 -> 19.sp
    else -> 17.sp
}

@Composable
fun MarkdownView(content: String, modifier: Modifier = Modifier) {
    val blocks = remember(content) { parseMarkdown(content) }
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        blocks.forEach { block ->
            when (block) {
                is MdBlock.Heading -> Text(
                    text = inlineAnnotated(block.text, AppColor.text, headingSize(block.level), FontWeight.Bold),
                    lineHeight = (headingSize(block.level).value + 8).sp
                )

                is MdBlock.Paragraph -> Text(
                    text = inlineAnnotated(block.text),
                    style = TextStyle(lineHeight = 26.sp)
                )

                is MdBlock.Code -> Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(AppColor.codeBg)
                        .padding(12.dp)
                ) {
                    if (block.lang.isNotEmpty()) {
                        Text(
                            text = block.lang,
                            fontSize = 11.sp,
                            color = AppColor.textFaint,
                            modifier = Modifier.padding(bottom = 6.dp)
                        )
                    }
                    Text(
                        text = block.text,
                        fontSize = 13.sp,
                        lineHeight = 20.sp,
                        fontFamily = FontFamily.Monospace,
                        color = Color(0xFF111827)
                    )
                }

                is MdBlock.Bullets -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    block.items.forEachIndexed { index, item ->
                        Row(modifier = Modifier.fillMaxWidth()) {
                            Text(
                                text = if (block.ordered) "${index + 1}." else "•",
                                fontSize = 15.sp,
                                color = AppColor.textSub,
                                modifier = Modifier.width(20.dp)
                            )
                            Text(
                                text = inlineAnnotated(item),
                                style = TextStyle(lineHeight = 24.sp),
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }

                is MdBlock.Quote -> Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(IntrinsicSize.Min)
                ) {
                    Spacer(
                        modifier = Modifier
                            .width(3.dp)
                            .fillMaxHeight()
                            .background(AppColor.divider)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = inlineAnnotated(block.text, AppColor.textSub, 15.sp),
                        style = TextStyle(lineHeight = 23.sp),
                        modifier = Modifier.weight(1f)
                    )
                }

                MdBlock.Divider -> HorizontalDivider(color = AppColor.divider)
            }
        }
    }
}
