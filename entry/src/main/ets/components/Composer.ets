import { Theme } from '../common/Theme';

/** 底部输入区：联网/深度思考开关 + 附件 + 输入框 + 按住说话 + 发送/停止 */
@Component
export struct Composer {
  @Link draft: string;
  @Prop streaming: boolean = false;
  @Prop searchOn: boolean = false;
  @Prop thinkingOn: boolean = false;
  @Prop busy: boolean = false;
  @Prop recording: boolean = false;
  @Prop canSend: boolean = false;

  onSend: () => void = () => {};
  onStop: () => void = () => {};
  onToggleSearch: () => void = () => {};
  onToggleThinking: () => void = () => {};
  onAttach: () => void = () => {};
  onVoice: (pressed: boolean) => void = () => {};

  build() {
    Column({ space: 8 }) {
      Row({ space: 8 }) {
        Row({ space: 4 }) {
          Text(this.searchOn ? '●' : '○')
            .fontSize(9)
            .fontColor(this.searchOn ? Theme.BRAND : Theme.TEXT_FAINT)
          Text('联网搜索')
            .fontSize(12)
            .fontColor(this.searchOn ? Theme.BRAND : Theme.TEXT_SUB)
        }
        .padding({ left: 10, right: 10, top: 6, bottom: 6 })
        .backgroundColor(this.searchOn ? Theme.BRAND_SOFT : Theme.BG_GRAY)
        .borderRadius(14)
        .onClick(() => {
          this.onToggleSearch();
        })

        Row({ space: 4 }) {
          Text(this.thinkingOn ? '●' : '○')
            .fontSize(9)
            .fontColor(this.thinkingOn ? Theme.BRAND : Theme.TEXT_FAINT)
          Text('深度思考')
            .fontSize(12)
            .fontColor(this.thinkingOn ? Theme.BRAND : Theme.TEXT_SUB)
        }
        .padding({ left: 10, right: 10, top: 6, bottom: 6 })
        .backgroundColor(this.thinkingOn ? Theme.BRAND_SOFT : Theme.BG_GRAY)
        .borderRadius(14)
        .onClick(() => {
          this.onToggleThinking();
        })

        Blank()
        Text('＋')
          .fontSize(20)
          .fontColor(Theme.TEXT_SUB)
          .width(34)
          .height(34)
          .textAlign(TextAlign.Center)
          .backgroundColor(Theme.BG_CARD)
          .borderRadius(17)
          .onClick(() => {
            this.onAttach();
          })
      }
      .width('100%')

      Row({ space: 8 }) {
        TextArea({ text: this.draft, placeholder: '给 DeepSeek 发送消息' })
          .placeholderColor(Theme.TEXT_FAINT)
          .fontSize(16)
          .fontColor(Theme.TEXT)
          .backgroundColor(Theme.BG_GRAY)
          .borderRadius(20)
          .padding({ left: 14, right: 14, top: 10, bottom: 10 })
          .constraintSize({ minHeight: 44, maxHeight: 132 })
          .layoutWeight(1)
          .onChange((value: string) => {
            this.draft = value;
          })

        if (this.busy) {
          LoadingProgress()
            .width(26)
            .height(26)
            .color(Theme.BRAND)
        }

        Text(this.recording ? '松开 结束' : '按住 说话')
          .fontSize(12)
          .fontColor(this.recording ? '#FFFFFF' : Theme.TEXT_SUB)
          .width(this.recording ? 84 : 64)
          .height(40)
          .textAlign(TextAlign.Center)
          .backgroundColor(this.recording ? Theme.BRAND : Theme.BG_CARD)
          .borderRadius(20)
          .onTouch((event: TouchEvent) => {
            if (event.type === TouchType.Down) {
              this.onVoice(true);
            } else if (event.type === TouchType.Up || event.type === TouchType.Cancel) {
              this.onVoice(false);
            }
          })

        if (this.streaming) {
          Text('■')
            .fontSize(14)
            .fontColor('#FFFFFF')
            .width(40)
            .height(40)
            .textAlign(TextAlign.Center)
            .backgroundColor(Theme.TEXT)
            .borderRadius(20)
            .onClick(() => {
              this.onStop();
            })
        } else {
          Text('↑')
            .fontSize(20)
            .fontColor(this.canSend ? '#FFFFFF' : Theme.TEXT_FAINT)
            .width(40)
            .height(40)
            .textAlign(TextAlign.Center)
            .backgroundColor(this.canSend ? Theme.BRAND : Theme.BG_CARD)
            .borderRadius(20)
            .onClick(() => {
              if (this.canSend) {
                this.onSend();
              }
            })
        }
      }
      .width('100%')
      .alignItems(VerticalAlign.Bottom)
    }
    .width('100%')
    .padding({ left: 12, right: 12, top: 8, bottom: 8 })
    .backgroundColor(Theme.BG)
  }
}
