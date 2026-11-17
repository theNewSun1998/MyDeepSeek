import { Theme } from '../common/Theme';
import { Inline, MarkdownParser, MdBlock } from '../service/Markdown';

/** 渲染模型回答的 Markdown（标题/代码块/列表/引用/行内样式） */
@Component
export struct MarkdownView {
  @Prop content: string = '';
  @Prop textColor: string = Theme.TEXT;

  build() {
    Column({ space: 8 }) {
      ForEach(MarkdownParser.parse(this.content), (block: MdBlock, index: number) => {
        this.blockView(block, index)
      }, (block: MdBlock, index: number) => `${index}-${block.kind}-${block.text.length}`)
    }
    .width('100%')
    .alignItems(HorizontalAlign.Start)
  }

  @Builder
  blockView(block: MdBlock, index: number) {
    if (block.kind === 'code') {
      Column({ space: 6 }) {
        Text(block.lang.length > 0 ? block.lang : 'code')
          .fontSize(11)
          .fontColor(Theme.TEXT_FAINT)
          .width('100%')
        Text(block.text)
          .fontSize(13)
          .fontColor('#111827')
          .fontFamily('monospace')
          .lineHeight(20)
          .width('100%')
          .copyOption(CopyOptions.LocalDevice)
      }
      .width('100%')
      .padding(12)
      .backgroundColor(Theme.BG_CODE)
      .borderRadius(12)
      .alignItems(HorizontalAlign.Start)
    } else if (block.kind === 'hr') {
      Divider()
        .color(Theme.DIVIDER)
        .margin({ top: 4, bottom: 4 })
    } else if (block.kind === 'ul' || block.kind === 'ol') {
      Column({ space: 4 }) {
        ForEach(block.items, (item: string, i: number) => {
          Row({ space: 6 }) {
            Text(block.kind === 'ul' ? '•' : `${i + 1}.`)
              .fontSize(15)
              .fontColor(Theme.TEXT_SUB)
              .width(18)
              .textAlign(TextAlign.Start)
            this.inlineRow(item, 16, Theme.TEXT, FontWeight.Normal)
          }
          .width('100%')
          .alignItems(VerticalAlign.Top)
        }, (item: string, i: number) => `${i}`)
      }
      .width('100%')
      .alignItems(HorizontalAlign.Start)
    } else if (block.kind === 'quote') {
      Row() {
        Divider()
          .vertical(true)
          .strokeWidth(3)
          .color(Theme.DIVIDER)
          .height('100%')
          .margin({ right: 10 })
        this.inlineRow(block.text, 15, Theme.TEXT_SUB, FontWeight.Normal)
      }
      .width('100%')
      .alignItems(VerticalAlign.Top)
      .padding({ top: 2, bottom: 2 })
    } else {
      this.inlineRow(block.text, this.sizeOf(block.kind), this.colorOf(block.kind), this.weightOf(block.kind))
    }
  }

  @Builder
  inlineRow(text: string, size: number, color: string, weight: FontWeight) {
    Text() {
      ForEach(MarkdownParser.inline(text), (tok: Inline, i: number) => {
        Span(tok.text)
          .fontSize(size)
          .fontColor(tok.code ? '#B4261B' : (tok.link ? Theme.BRAND : color))
          .fontWeight(tok.bold ? FontWeight.Bold : weight)
          .fontStyle(tok.italic ? FontStyle.Italic : FontStyle.Normal)
          .fontFamily(tok.code ? 'monospace' : 'HarmonyOS Sans')
          .textDecoration(tok.link ? TextDecorationType.Underline : TextDecorationType.None)
      }, (tok: Inline, i: number) => `${i}-${tok.text.length}`)
    }
    .width('100%')
    .lineHeight(size + 10)
    .textAlign(TextAlign.Start)
  }

  private sizeOf(kind: string): number {
    if (kind === 'h1') {
      return 21;
    }
    if (kind === 'h2') {
      return 19;
    }
    if (kind === 'h3') {
      return 17;
    }
    return 16;
  }

  private colorOf(kind: string): string {
    return kind.startsWith('h') ? Theme.TEXT : this.textColor;
  }

  private weightOf(kind: string): FontWeight {
    return kind.startsWith('h') ? FontWeight.Bold : FontWeight.Normal;
  }
}
