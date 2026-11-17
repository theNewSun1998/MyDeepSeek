/** Markdown 的一个块 */
export class MdBlock {
  kind: string = 'p';       // h1 | h2 | h3 | p | code | ul | ol | quote | hr
  text: string = '';
  lang: string = '';
  items: Array<string> = [];
}

/** 行内片段 */
export class Inline {
  text: string = '';
  bold: boolean = false;
  italic: boolean = false;
  code: boolean = false;
  link: boolean = false;
  url: string = '';
}

/**
 * 轻量 Markdown 解析：够渲染模型回答就行，不追求完整语法。
 * 支持 # 标题、``` 代码块、- / 1. 列表、> 引用、--- 分割线，
 * 行内 **粗体**、`代码`、*斜体*、[文字](链接)。
 */
export class MarkdownParser {
  static parse(source: string): Array<MdBlock> {
    const lines = source.split('\n');
    const blocks: Array<MdBlock> = [];
    let i = 0;
    let paragraph: Array<string> = [];

    const flushParagraph = (): void => {
      if (paragraph.length > 0) {
        const b = new MdBlock();
        b.kind = 'p';
        b.text = paragraph.join('\n');
        blocks.push(b);
        paragraph = [];
      }
    };

    while (i < lines.length) {
      const line = lines[i];
      const trimmed = line.trim();

      if (trimmed.startsWith('```')) {
        flushParagraph();
        const b = new MdBlock();
        b.kind = 'code';
        b.lang = trimmed.substring(3).trim();
        const code: Array<string> = [];
        i++;
        while (i < lines.length && !lines[i].trim().startsWith('```')) {
          code.push(lines[i]);
          i++;
        }
        b.text = code.join('\n');
        blocks.push(b);
        i++;
        continue;
      }

      if (trimmed.length === 0) {
        flushParagraph();
        i++;
        continue;
      }

      if (trimmed === '---' || trimmed === '***') {
        flushParagraph();
        const b = new MdBlock();
        b.kind = 'hr';
        blocks.push(b);
        i++;
        continue;
      }

      if (trimmed.startsWith('#')) {
        flushParagraph();
        let level = 0;
        while (level < trimmed.length && trimmed.charAt(level) === '#') {
          level++;
        }
        const b = new MdBlock();
        b.kind = level <= 1 ? 'h1' : (level === 2 ? 'h2' : 'h3');
        b.text = trimmed.substring(level).trim();
        blocks.push(b);
        i++;
        continue;
      }

      if (trimmed.startsWith('>')) {
        flushParagraph();
        const quote: Array<string> = [];
        while (i < lines.length && lines[i].trim().startsWith('>')) {
          quote.push(lines[i].trim().substring(1).trim());
          i++;
        }
        const b = new MdBlock();
        b.kind = 'quote';
        b.text = quote.join('\n');
        blocks.push(b);
        continue;
      }

      const isBullet = trimmed.startsWith('- ') || trimmed.startsWith('* ') || trimmed.startsWith('+ ');
      const isOrdered = MarkdownParser.orderedIndex(trimmed) > 0;

      if (isBullet || isOrdered) {
        flushParagraph();
        const b = new MdBlock();
        b.kind = isBullet ? 'ul' : 'ol';
        while (i < lines.length) {
          const cur = lines[i].trim();
          const curBullet = cur.startsWith('- ') || cur.startsWith('* ') || cur.startsWith('+ ');
          const curOrdered = MarkdownParser.orderedIndex(cur) > 0;
          if (isBullet && curBullet) {
            b.items.push(cur.substring(2).trim());
          } else if (isOrdered && curOrdered) {
            b.items.push(cur.substring(cur.indexOf('.') + 1).trim());
          } else if (cur.length > 0 && b.items.length > 0 && (cur.startsWith('  ') || lines[i].startsWith('  '))) {
            b.items[b.items.length - 1] = b.items[b.items.length - 1] + ' ' + cur;
          } else {
            break;
          }
          i++;
        }
        blocks.push(b);
        continue;
      }

      paragraph.push(trimmed);
      i++;
    }
    flushParagraph();
    return blocks;
  }

  private static orderedIndex(trimmed: string): number {
    let d = 0;
    while (d < trimmed.length && trimmed.charAt(d) >= '0' && trimmed.charAt(d) <= '9') {
      d++;
    }
    if (d === 0 || d >= trimmed.length) {
      return 0;
    }
    if (trimmed.charAt(d) !== '.' && trimmed.charAt(d) !== ')') {
      return 0;
    }
    return d;
  }

  /** 行内标记拆成片段 */
  static inline(text: string): Array<Inline> {
    const out: Array<Inline> = [];
    let buf = '';
    let i = 0;

    const pushPlain = (): void => {
      if (buf.length > 0) {
        const t = new Inline();
        t.text = buf;
        out.push(t);
        buf = '';
      }
    };

    while (i < text.length) {
      const c = text.charAt(i);

      if (c === '`') {
        const end = text.indexOf('`', i + 1);
        if (end > i) {
          pushPlain();
          const t = new Inline();
          t.code = true;
          t.text = text.substring(i + 1, end);
          out.push(t);
          i = end + 1;
          continue;
        }
      }

      if (c === '*' && i + 1 < text.length && text.charAt(i + 1) === '*') {
        const end = text.indexOf('**', i + 2);
        if (end > i) {
          pushPlain();
          const t = new Inline();
          t.bold = true;
          t.text = text.substring(i + 2, end);
          out.push(t);
          i = end + 2;
          continue;
        }
      }

      if (c === '[') {
        const closeBracket = text.indexOf(']', i + 1);
        if (closeBracket > i && closeBracket + 1 < text.length && text.charAt(closeBracket + 1) === '(') {
          const closeParen = text.indexOf(')', closeBracket + 2);
          if (closeParen > closeBracket) {
            pushPlain();
            const t = new Inline();
            t.link = true;
            t.text = text.substring(i + 1, closeBracket);
            t.url = text.substring(closeBracket + 2, closeParen);
            out.push(t);
            i = closeParen + 1;
            continue;
          }
        }
      }

      if (c === '*') {
        const end = text.indexOf('*', i + 1);
        if (end > i) {
          pushPlain();
          const t = new Inline();
          t.italic = true;
          t.text = text.substring(i + 1, end);
          out.push(t);
          i = end + 1;
          continue;
        }
      }

      buf += c;
      i++;
    }
    pushPlain();
    return out;
  }

  /** 从答案里捞出所有 http(s) 链接，用来在消息底部做可点跳转 */
  static extractUrls(text: string): Array<string> {
    const out: Array<string> = [];
    const lower = text.toLowerCase();
    let from = 0;
    while (from < text.length) {
      const start = lower.indexOf('http', from);
      if (start < 0) {
        break;
      }
      let end = start;
      while (end < text.length) {
        const ch = lower.charAt(end);
        const stop = ch === ' ' || ch === '\n' || ch === ')' || ch === ']' || ch === '>' ||
          ch === '"' || ch === '」' || ch === '，' || ch === '。';
        if (stop) {
          break;
        }
        end++;
      }
      const url = text.substring(start, end);
      if (url.length > 10 && out.indexOf(url) < 0) {
        out.push(url);
      }
      from = end + 1;
    }
    return out;
  }
}
