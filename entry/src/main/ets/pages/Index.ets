import { router, promptAction } from '@kit.ArkUI';
import { common } from '@kit.AbilityKit';
import { Conversation } from '../model/Types';
import { Store } from '../service/Store';
import { Theme } from '../common/Theme';
import { Util } from '../common/Util';

/** 首页：会话列表 + 新建对话 + 设置入口（对应 App 里的历史会话） */
@Entry
@Component
struct Index {
  @State convs: Array<Conversation> = [];
  @State hasKey: boolean = false;
  @State ready: boolean = false;

  aboutToAppear(): void {
    this.boot();
  }

  onPageShow(): void {
    if (this.ready) {
      this.refresh();
    }
  }

  build() {
    Column() {
      Row({ space: 10 }) {
        Text('DeepSeek')
          .fontSize(24)
          .fontWeight(FontWeight.Bold)
          .fontColor(Theme.TEXT)
        Blank()
        Text('⚙')
          .fontSize(20)
          .fontColor(Theme.TEXT_SUB)
          .width(38)
          .height(38)
          .textAlign(TextAlign.Center)
          .onClick(() => {
            router.pushUrl({ url: 'pages/SettingsPage' });
          })
      }
      .width('100%')
      .height(56)
      .padding({ left: 20, right: 12 })

      if (!this.hasKey) {
        Row({ space: 8 }) {
          Text('还没填 API Key，点这里去设置')
            .fontSize(13)
            .fontColor(Theme.BRAND)
            .layoutWeight(1)
          Text('去设置')
            .fontSize(13)
            .fontColor('#FFFFFF')
            .padding({ left: 12, right: 12, top: 6, bottom: 6 })
            .backgroundColor(Theme.BRAND)
            .borderRadius(12)
        }
        .width('100%')
        .padding({ left: 16, right: 16, top: 12, bottom: 12 })
        .backgroundColor(Theme.BRAND_SOFT)
        .onClick(() => {
          router.pushUrl({ url: 'pages/SettingsPage' });
        })
      }

      if (!this.ready) {
        Column() {
          LoadingProgress().width(30).height(30).color(Theme.BRAND)
        }
        .layoutWeight(1)
        .width('100%')
        .justifyContent(FlexAlign.Center)
      } else if (this.convs.length === 0) {
        Column({ space: 10 }) {
          Text('🐳')
            .fontSize(44)
          Text('还没有对话')
            .fontSize(15)
            .fontColor(Theme.TEXT_SUB)
          Text('点下面的输入框开始第一次对话')
            .fontSize(12)
            .fontColor(Theme.TEXT_FAINT)
        }
        .layoutWeight(1)
        .width('100%')
        .justifyContent(FlexAlign.Center)
      } else {
        List({ space: 2 }) {
          ForEach(this.convs, (conv: Conversation, index: number) => {
            ListItem() {
              Row({ space: 10 }) {
                Text(conv.title)
                  .fontSize(15)
                  .fontColor(Theme.TEXT)
                  .maxLines(1)
                  .textOverflow({ overflow: TextOverflow.Ellipsis })
                  .layoutWeight(1)
                Text(Util.listTime(conv.updatedAt))
                  .fontSize(11)
                  .fontColor(Theme.TEXT_FAINT)
              }
              .width('100%')
              .padding({ left: 20, right: 20, top: 14, bottom: 14 })
              .onClick(() => {
                router.pushUrl({ url: 'pages/ChatPage', params: { id: conv.id } });
              })
              .bindMenu([
                {
                  value: '删除对话',
                  action: () => {
                    Store.get().remove(conv.id);
                    this.refresh();
                  }
                }
              ])
            }
          }, (conv: Conversation, index: number) => `${conv.id}-${conv.updatedAt}`)
        }
        .width('100%')
        .layoutWeight(1)
        .scrollBar(BarState.Off)

        Divider().color(Theme.DIVIDER)
      }

      Row({ space: 10 }) {
        Text('给 DeepSeek 发送消息')
          .fontSize(15)
          .fontColor(Theme.TEXT_FAINT)
          .layoutWeight(1)
        Text('↑')
          .fontSize(18)
          .fontColor('#FFFFFF')
          .width(38)
          .height(38)
          .textAlign(TextAlign.Center)
          .backgroundColor(Theme.BRAND)
          .borderRadius(19)
      }
      .width('100%')
      .padding({ left: 16, right: 10, top: 10, bottom: 10 })
      .backgroundColor(Theme.BG_GRAY)
      .borderRadius(24)
      .margin({ left: 12, right: 12, top: 8, bottom: 8 })
      .onClick(() => {
        this.newChat();
      })
    }
    .width('100%')
    .height('100%')
    .backgroundColor(Theme.BG)
    .padding({ top: 8 })
  }

  private async boot(): Promise<void> {
    const ctx = getContext(this) as common.UIAbilityContext;
    const store = Store.get();
    await store.init(ctx);
    this.refresh();
    this.ready = true;
  }

  private refresh(): void {
    const store = Store.get();
    this.convs = store.sorted();
    this.hasKey = store.hasKey();
  }

  private newChat(): void {
    if (!Store.get().hasKey()) {
      promptAction.showToast({ message: '先在设置里填 API Key', duration: 2000 });
      router.pushUrl({ url: 'pages/SettingsPage' });
      return;
    }
    const conv = Store.get().create();
    router.pushUrl({ url: 'pages/ChatPage', params: { id: conv.id } });
  }
}
