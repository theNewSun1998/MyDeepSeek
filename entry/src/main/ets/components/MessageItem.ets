import { common } from '@kit.AbilityKit';
import { pasteboard } from '@kit.BasicServicesKit';
import { promptAction } from '@kit.ArkUI';
import { hilog } from '@kit.PerformanceAnalysisKit';
import { Attachment, ChatMessage, Citation, SearchHit, SearchStep } from '../model/Types';
import { Theme } from '../common/Theme';
import { MarkdownView } from './MarkdownView';
import { Linker } from '../common/Linker';
import { Util } from '../common/Util';

const DOMAIN: number = 0x0000;
const TAG: string = 'hmds';

/**
 * 一条消息。用户消息是右侧蓝气泡；助手消息按 DeepSeek App 的顺序堆：
 * 深度思考（可折叠）→ 搜索/读页步骤（可折叠）→ 正文（Markdown）→ 参考来源 → 操作条。
 */
@Component
export struct MessageItem {
  @ObjectLink msg: ChatMessage;
  @Prop isLast: boolean = false;
  @Prop streaming: boolean = false;
  onRegenerate: () => void = () => {};

  @State thinkingOpen: boolean = false;
  @State stepsOpen: boolean = false;

  build() {
    Column() {
      if (this.msg.role === 'user') {
        this.userView()
      } else {
        this.assistantView()
      }
    }
    .width('100%')
    .padding({ left: 16, right: 16, top: 8, bottom: 8 })
  }

  @Builder
  userView() {
    Row() {
      Column({ space: 8 }) {
        if (this.msg.attachments.length > 0) {
          ForEach(this.msg.attachments, (att: Attachment, i: number) => {
            Row({ space: 6 }) {
              Text(att.name)
                .fontSize(12)
                .fontColor('#E8EDFF')
                .maxLines(1)
                .textOverflow({ overflow: TextOverflow.Ellipsis })
                .layoutWeight(1)
              Text(Util.humanSize(att.sizeBytes))
                .fontSize(11)
                .fontColor('#C7D2FE')
            }
            .width('100%')
            .padding({ top: 4, bottom: 4, left: 8, right: 8 })
            .backgroundColor('#3355D6')
            .borderRadius(8)
          }, (att: Attachment, i: number) => att.name + i)
        }
        if (this.msg.text.length > 0) {
          Text(this.msg.text)
            .fontSize(16)
            .fontColor(Theme.USER_TEXT)
            .lineHeight(24)
            .copyOption(CopyOptions.LocalDevice)
        }
      }
      .alignItems(HorizontalAlign.Start)
      .padding({ left: 14, right: 14, top: 10, bottom: 10 })
      .backgroundColor(Theme.USER_BUBBLE)
      .borderRadius(18)
      .constraintSize({ maxWidth: '82%' })
    }
    .width('100%')
    .justifyContent(FlexAlign.End)
  }

  @Builder
  assistantView() {
    Column({ space: 10 }) {
      if (this.msg.thinking.length > 0 || (this.streaming && this.msg.text.length === 0)) {
        this.thinkingCard()
      }

      if (this.msg.steps.length > 0) {
        this.stepsCard()
      }

      if (this.msg.text.length > 0) {
        MarkdownView({ content: this.msg.text })
      } else if (this.msg.status === 'streaming' && this.msg.steps.length === 0 &&
        this.msg.thinking.length === 0) {
        Row({ space: 8 }) {
          LoadingProgress().width(18).height(18).color(Theme.BRAND)
          Text('正在回答…').fontSize(14).fontColor(Theme.TEXT_SUB)
        }
      }

      if (this.msg.status === 'refused') {
        this.notice('这条回答被模型的内容安全策略拒绝了，换个问法试试。', Theme.DANGER)
      }
      if (this.msg.status === 'stopped') {
        this.notice('已停止生成。', Theme.TEXT_FAINT)
      }
      if (this.msg.status === 'error') {
        Column({ space: 8 }) {
          Text(this.msg.errorText.length > 0 ? this.msg.errorText : '请求失败')
            .fontSize(13)
            .fontColor(Theme.DANGER)
            .width('100%')
          Row({ space: 10 }) {
            Text('重试')
              .fontSize(13)
              .fontColor(Theme.BRAND)
              .padding({ left: 12, right: 12, top: 6, bottom: 6 })
              .backgroundColor(Theme.BRAND_SOFT)
              .borderRadius(14)
              .onClick(() => {
                this.onRegenerate();
              })
          }
          .width('100%')
        }
        .width('100%')
        .padding(12)
        .backgroundColor('#FEF2F2')
        .borderRadius(12)
        .alignItems(HorizontalAlign.Start)
      }

      if (this.msg.citations.length > 0) {
        this.citationsView()
      }

      if (this.msg.status !== 'streaming' && this.msg.text.length > 0) {
        this.actionsView()
      }
    }
    .width('100%')
    .alignItems(HorizontalAlign.Start)
  }

  @Builder
  thinkingCard() {
    Column({ space: 6 }) {
      Row({ space: 6 }) {
        Text('∨')
          .fontSize(11)
          .fontColor(Theme.TEXT_FAINT)
          .rotate({ angle: this.thinkingOpen ? 0 : -90 })
        Text(this.thinkingOpen ? '深度思考' : '已深度思考')
          .fontSize(13)
          .fontColor(Theme.TEXT_SUB)
        if (this.msg.thinkingMs > 1000) {
          Text(`用时 ${(this.msg.thinkingMs / 1000).toFixed(1)} 秒`)
            .fontSize(12)
            .fontColor(Theme.TEXT_FAINT)
        }
        Blank()
      }
      .width('100%')
      .onClick(() => {
        this.thinkingOpen = !this.thinkingOpen;
      })

      if (this.thinkingOpen) {
        Text(this.msg.thinking.length > 0 ? this.msg.thinking : '思考中…')
          .fontSize(13)
          .fontColor(Theme.TEXT_SUB)
          .lineHeight(20)
          .width('100%')
      }
    }
    .width('100%')
    .padding(12)
    .backgroundColor(Theme.BG_CARD)
    .borderRadius(12)
    .alignItems(HorizontalAlign.Start)
  }

  @Builder
  stepsCard() {
    Column({ space: 6 }) {
      Row({ space: 6 }) {
        Text(this.stepsTitle())
          .fontSize(13)
          .fontColor(Theme.TEXT_SUB)
        Blank()
        Text(this.stepsOpen ? '收起' : '展开')
          .fontSize(12)
          .fontColor(Theme.BRAND)
      }
      .width('100%')
      .onClick(() => {
        this.stepsOpen = !this.stepsOpen;
      })

      if (this.stepsOpen) {
        ForEach(this.msg.steps, (step: SearchStep, si: number) => {
          Column({ space: 4 }) {
            if (step.action === 'search' && step.queries.length > 0) {
              Text(`搜索：${step.queries.join(' / ')}`)
                .fontSize(12)
                .fontColor(Theme.TEXT_FAINT)
                .width('100%')
            }
            if (step.action === 'open_page' || step.action === 'find_in_page') {
              Text(step.action === 'open_page' ? `打开：${step.url}` : `页内查找：${step.pattern}`)
                .fontSize(12)
                .fontColor(Theme.TEXT_FAINT)
                .maxLines(2)
                .textOverflow({ overflow: TextOverflow.Ellipsis })
                .width('100%')
            }
            if (step.action === 'read_link') {
              Text(`读取链接：${step.fetchTitle.length > 0 ? step.fetchTitle : step.url}`)
                .fontSize(12)
                .fontColor(Theme.TEXT_FAINT)
                .maxLines(2)
                .textOverflow({ overflow: TextOverflow.Ellipsis })
                .width('100%')
            }
            if (step.errorCode.length > 0) {
              Text(`失败：${step.errorCode}`)
                .fontSize(12)
                .fontColor(Theme.DANGER)
                .width('100%')
            }
            ForEach(step.hits, (hit: SearchHit, hi: number) => {
              this.hitRow(hit.title, hit.siteName, hit.url, hit.snippet)
            }, (hit: SearchHit, hi: number) => `${si}-${hi}-${hit.url.length}`)
          }
          .width('100%')
          .alignItems(HorizontalAlign.Start)
          .margin({ top: 4 })
        }, (step: SearchStep, si: number) => `${si}-${step.action}-${step.hits.length}`)
      }
    }
    .width('100%')
    .padding(12)
    .backgroundColor(Theme.BG_CARD)
    .borderRadius(12)
    .alignItems(HorizontalAlign.Start)
  }

  @Builder
  hitRow(title: string, siteName: string, url: string, snippet: string) {
    Column({ space: 2 }) {
      Text(title.length > 0 ? title : url)
        .fontSize(13)
        .fontColor(Theme.TEXT)
        .maxLines(2)
        .textOverflow({ overflow: TextOverflow.Ellipsis })
        .width('100%')
      if (snippet.length > 0) {
        Text(snippet)
          .fontSize(11)
          .fontColor(Theme.TEXT_FAINT)
          .maxLines(2)
          .textOverflow({ overflow: TextOverflow.Ellipsis })
          .width('100%')
      }
      if (siteName.length > 0) {
        Text(siteName)
          .fontSize(11)
          .fontColor(Theme.BRAND)
      }
    }
    .width('100%')
    .padding({ top: 6, bottom: 6 })
    .onClick(() => {
      Linker.open(getContext(this) as common.UIAbilityContext, url);
    })
    .alignItems(HorizontalAlign.Start)
  }

  @Builder
  citationsView() {
    Column({ space: 4 }) {
      Text('参考来源')
        .fontSize(12)
        .fontColor(Theme.TEXT_FAINT)
        .width('100%')
      ForEach(this.msg.citations, (c: Citation, i: number) => {
        Row({ space: 6 }) {
          Text(`${i + 1}`)
            .fontSize(10)
            .fontColor(Theme.BRAND)
            .width(16)
            .height(16)
            .textAlign(TextAlign.Center)
            .backgroundColor(Theme.BRAND_SOFT)
            .borderRadius(8)
          Text(c.title.length > 0 ? c.title : c.url)
            .fontSize(12)
            .fontColor(Theme.TEXT_SUB)
            .maxLines(1)
            .textOverflow({ overflow: TextOverflow.Ellipsis })
            .layoutWeight(1)
        }
        .width('100%')
        .padding({ top: 4, bottom: 4 })
        .onClick(() => {
          Linker.open(getContext(this) as common.UIAbilityContext, c.url);
        })
      }, (c: Citation, i: number) => `${i}-${c.url.length}`)
    }
    .width('100%')
    .padding({ top: 8 })
    .alignItems(HorizontalAlign.Start)
  }

  @Builder
  actionsView() {
    Row({ space: 18 }) {
      Text('复制')
        .fontSize(12)
        .fontColor(Theme.TEXT_FAINT)
        .onClick(() => {
          this.copy();
        })
      if (this.isLast) {
        Text('重新生成')
          .fontSize(12)
          .fontColor(Theme.TEXT_FAINT)
          .onClick(() => {
            this.onRegenerate();
          })
      }
      Blank()
      if (this.msg.createdAt > 0) {
        Text(Util.clock(this.msg.createdAt))
          .fontSize(11)
          .fontColor(Theme.TEXT_FAINT)
      }
    }
    .width('100%')
    .padding({ top: 4 })
  }

  @Builder
  notice(text: string, color: string) {
    Text(text)
      .fontSize(13)
      .fontColor(color)
      .width('100%')
      .padding(10)
      .backgroundColor(Theme.BG_GRAY)
      .borderRadius(10)
  }

  private stepsTitle(): string {
    let hits = 0;
    let fetches = 0;
    let searches = 0;
    for (let i = 0; i < this.msg.steps.length; i++) {
      const s = this.msg.steps[i];
      if (s.action === 'search') {
        searches++;
      }
      if (s.action === 'read_link' || s.action === 'open_page') {
        fetches++;
      }
      hits += s.hits.length;
    }
    if (hits > 0) {
      return `已搜索 ${hits} 个网页`;
    }
    if (fetches > 0) {
      return `已阅读 ${fetches} 个网页`;
    }
    if (searches > 0) {
      return '已完成联网搜索';
    }
    return '工具调用';
  }

  private copy(): void {
    const text = this.msg.text;
    if (text.length === 0) {
      return;
    }
    const data = pasteboard.createData(pasteboard.MIMETYPE_TEXT_PLAIN, text);
    pasteboard.getSystemPasteboard().setData(data).then(() => {
      promptAction.showToast({ message: '已复制', duration: 1500 });
    }).catch((err: Error) => {
      hilog.warn(DOMAIN, TAG, 'copy failed: %{public}s', err.message);
    });
  }
}
