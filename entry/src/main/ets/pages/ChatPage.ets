import { router, promptAction } from '@kit.ArkUI';
import { abilityAccessCtrl, common, Permissions } from '@kit.AbilityKit';
import { picker } from '@kit.CoreFileKit';
import { BusinessError } from '@kit.BasicServicesKit';
import { hilog } from '@kit.PerformanceAnalysisKit';
import { Attachment, ChatMessage, Conversation } from '../model/Types';
import { ChatEngine, SendOptions } from '../service/ChatEngine';
import { DeepSeekApi } from '../service/DeepSeekApi';
import { Recorder } from '../service/Recorder';
import { Store } from '../service/Store';
import { Composer } from '../components/Composer';
import { MessageItem } from '../components/MessageItem';
import { Theme } from '../common/Theme';
import { Util } from '../common/Util';

const DOMAIN: number = 0x0000;
const TAG: string = 'hmds';

@Entry
@Component
struct ChatPage {
  @State messages: Array<ChatMessage> = [];
  @State draft: string = '';
  @State streaming: boolean = false;
  @State uploading: boolean = false;
  @State recording: boolean = false;
  @State searchOn: boolean = true;
  @State thinkingOn: boolean = true;
  @State title: string = '新的对话';
  @State attachments: Array<Attachment> = [];
  @State ready: boolean = false;

  private conv: Conversation | null = null;
  private engine: ChatEngine | null = null;
  private recorder: Recorder = new Recorder();
  private scroller: Scroller = new Scroller();

  aboutToAppear(): void {
    this.boot();
  }

  build() {
    Column() {
      this.topBar()

      if (!this.ready) {
        Column() {
          LoadingProgress().width(32).height(32).color(Theme.BRAND)
        }
        .width('100%')
        .layoutWeight(1)
        .justifyContent(FlexAlign.Center)
      } else if (this.messages.length === 0) {
        this.welcomeView()
        this.bottomArea()
      } else {
        List({ scroller: this.scroller, space: 2 }) {
          ForEach(this.messages, (item: ChatMessage, index: number) => {
            ListItem() {
              MessageItem({
                msg: item,
                isLast: index === this.messages.length - 1,
                streaming: this.streaming,
                onRegenerate: () => {
                  this.regenerate();
                }
              })
            }
          }, (item: ChatMessage, index: number) => item.id)
        }
        .width('100%')
        .layoutWeight(1)
        .scrollBar(BarState.Off)
        .edgeEffect(EdgeEffect.Spring)
        .padding({ top: 4, bottom: 8 })

        this.bottomArea()
      }
    }
    .width('100%')
    .height('100%')
    .backgroundColor(Theme.BG)
  }

  @Builder
  topBar() {
    Row({ space: 8 }) {
      Text('‹')
        .fontSize(26)
        .fontColor(Theme.TEXT)
        .width(36)
        .height(36)
        .textAlign(TextAlign.Center)
        .onClick(() => {
          router.back();
        })
      Text(this.title)
        .fontSize(17)
        .fontWeight(FontWeight.Medium)
        .fontColor(Theme.TEXT)
        .maxLines(1)
        .textOverflow({ overflow: TextOverflow.Ellipsis })
        .layoutWeight(1)
        .textAlign(TextAlign.Center)
      Text('⋯')
        .fontSize(20)
        .fontColor(Theme.TEXT)
        .width(36)
        .height(36)
        .textAlign(TextAlign.Center)
        .bindMenu([
          {
            value: '新建对话',
            action: () => {
              this.newChat();
            }
          },
          {
            value: '删除这个对话',
            action: () => {
              this.deleteChat();
            }
          }
        ])
    }
    .width('100%')
    .height(52)
    .padding({ left: 6, right: 6 })
    .backgroundColor(Theme.BG)
  }

  @Builder
  welcomeView() {
    Column({ space: 14 }) {
      Blank()
      Text('DeepSeek')
        .fontSize(28)
        .fontWeight(FontWeight.Bold)
        .fontColor(Theme.BRAND)
      Text('今天有什么可以帮到你？')
        .fontSize(15)
        .fontColor(Theme.TEXT_SUB)
      Column({ space: 8 }) {
        this.suggestion('帮我搜一下今天关于 DeepSeek 的新闻')
        this.suggestion('把这个链接里的要点总结成 5 条')
        this.suggestion('写一段鸿蒙 ArkTS 的流式请求示例')
      }
      .width('100%')
      .padding({ left: 24, right: 24, top: 10 })
      Blank()
    }
    .width('100%')
    .layoutWeight(1)
    .justifyContent(FlexAlign.Center)
  }

  @Builder
  suggestion(text: string) {
    Text(text)
      .fontSize(13)
      .fontColor(Theme.TEXT_SUB)
      .width('100%')
      .padding({ left: 14, right: 14, top: 10, bottom: 10 })
      .backgroundColor(Theme.BG_GRAY)
      .borderRadius(14)
      .maxLines(1)
      .textOverflow({ overflow: TextOverflow.Ellipsis })
      .onClick(() => {
        this.draft = text;
      })
  }

  @Builder
  bottomArea() {
    Column({ space: 6 }) {
      if (this.attachments.length > 0) {
        Row({ space: 6 }) {
          ForEach(this.attachments, (att: Attachment, i: number) => {
            Row({ space: 4 }) {
              Text(att.uploading ? '解析中…' : (att.errorText.length > 0 ? '解析失败' : att.name))
                .fontSize(11)
                .fontColor(att.errorText.length > 0 ? Theme.DANGER : Theme.TEXT_SUB)
                .maxLines(1)
                .textOverflow({ overflow: TextOverflow.Ellipsis })
                .constraintSize({ maxWidth: 150 })
              Text('✕')
                .fontSize(11)
                .fontColor(Theme.TEXT_FAINT)
                .onClick(() => {
                  this.removeAttachment(i);
                })
            }
            .padding({ left: 10, right: 8, top: 6, bottom: 6 })
            .backgroundColor(Theme.BG_GRAY)
            .borderRadius(12)
          }, (att: Attachment, i: number) => `${att.name}-${i}`)
          Blank()
        }
        .width('100%')
        .padding({ left: 12, right: 12 })
      }

      Composer({
        draft: $draft,
        streaming: this.streaming,
        searchOn: this.searchOn,
        thinkingOn: this.thinkingOn,
        busy: this.uploading,
        recording: this.recording,
        canSend: this.canSend(),
        onSend: () => {
          this.send();
        },
        onStop: () => {
          this.stop();
        },
        onToggleSearch: () => {
          this.searchOn = !this.searchOn;
        },
        onToggleThinking: () => {
          this.thinkingOn = !this.thinkingOn;
        },
        onAttach: () => {
          this.attach();
        },
        onVoice: (pressed: boolean) => {
          this.voice(pressed);
        }
      })
    }
    .width('100%')
  }

  // ------------------------------------------------------------- 逻辑

  private async boot(): Promise<void> {
    const ctx = getContext(this) as common.UIAbilityContext;
    const store = Store.get();
    await store.init(ctx);

    let id = '';
    const params = router.getParams();
    if (params !== undefined && params !== null) {
      const rec = params as Record<string, string>;
      const v = rec['id'];
      if (v !== undefined) {
        id = v;
      }
    }
    let conv = store.find(id);
    if (conv === null) {
      conv = store.create();
    }
    this.conv = conv;
    this.messages = conv.messages.slice();
    this.title = conv.title;
    this.searchOn = store.settings.searchDefault;
    this.thinkingOn = store.settings.thinkingDefault;

    const engine = new ChatEngine(conv);
    engine.onUpdate = () => {
      this.onEngineUpdate();
    };
    this.engine = engine;
    this.ready = true;
    this.scrollToBottom();
  }

  private onEngineUpdate(): void {
    const conv = this.conv;
    if (conv === null) {
      return;
    }
    if (conv.messages.length !== this.messages.length) {
      this.messages = conv.messages.slice();
    }
    const engine = this.engine;
    this.streaming = engine !== null && engine.isStreaming();
    this.title = conv.title;
    this.scrollToBottom();
  }

  private canSend(): boolean {
    return this.draft.trim().length > 0 || this.attachments.length > 0;
  }

  private send(): void {
    const store = Store.get();
    if (!store.hasKey()) {
      promptAction.showToast({ message: '先去设置里填 DeepSeek API Key', duration: 2500 });
      this.openSettings();
      return;
    }
    const engine = this.engine;
    if (engine === null || engine.isStreaming()) {
      return;
    }
    if (this.uploading) {
      promptAction.showToast({ message: '文件还在解析，稍等一下', duration: 2000 });
      return;
    }
    const text = this.draft.trim();
    if (text.length === 0 && this.attachments.length === 0) {
      return;
    }
    const opts = new SendOptions();
    opts.search = this.searchOn;
    opts.thinking = this.thinkingOn;
    engine.send(text, this.attachments, opts);
    this.draft = '';
    this.attachments = [];
    this.scrollToBottom();
  }

  private regenerate(): void {
    const engine = this.engine;
    if (engine === null || engine.isStreaming()) {
      return;
    }
    const opts = new SendOptions();
    opts.search = this.searchOn;
    opts.thinking = this.thinkingOn;
    engine.regenerate(opts);
  }

  private stop(): void {
    const engine = this.engine;
    if (engine !== null) {
      engine.stop();
    }
  }

  private async attach(): Promise<void> {
    const ctx = getContext(this) as common.UIAbilityContext;
    const store = Store.get();
    let uris: Array<string> = [];
    try {
      const options = new picker.DocumentSelectOptions();
      options.maxSelectNumber = 5;
      const documentPicker = new picker.DocumentViewPicker(ctx);
      uris = await documentPicker.select(options);
    } catch (err) {
      const e = err as BusinessError;
      hilog.warn(DOMAIN, TAG, 'picker failed: %{public}s', e.message);
      return;
    }
    if (uris.length === 0) {
      return;
    }
    if (!store.hasKey()) {
      promptAction.showToast({ message: '先去设置里填 DeepSeek API Key', duration: 2500 });
      return;
    }
    this.uploading = true;
    for (let i = 0; i < uris.length; i++) {
      const uri = uris[i];
      const name = Util.fileNameFromPath(uri);
      const att = new Attachment();
      att.name = name;
      att.isImage = Util.isImageName(name);
      att.uploading = true;
      this.attachments.push(att);
      this.attachments = this.attachments.slice();

      const res = await DeepSeekApi.uploadFile(store.settings, uri, name, store.settings.parsePurpose);
      att.uploading = false;
      if (res.ok) {
        att.fileId = res.fileId;
        att.sizeBytes = res.bytes;
      } else {
        att.errorText = res.errorText;
        promptAction.showToast({ message: res.errorText, duration: 3000 });
      }
      this.attachments = this.attachments.slice();
    }
    this.uploading = false;
  }

  private removeAttachment(index: number): void {
    const next: Array<Attachment> = [];
    for (let i = 0; i < this.attachments.length; i++) {
      if (i !== index) {
        next.push(this.attachments[i]);
      }
    }
    this.attachments = next;
  }

  private async voice(pressed: boolean): Promise<void> {
    const ctx = getContext(this) as common.UIAbilityContext;
    if (pressed) {
      const granted = await this.ensureMic();
      if (!granted) {
        return;
      }
      const ok = await this.recorder.start(ctx);
      this.recording = ok;
      if (!ok) {
        promptAction.showToast({ message: '麦克风启动失败', duration: 2000 });
      }
      return;
    }
    if (!this.recorder.isRecording()) {
      return;
    }
    this.recording = false;
    const path = await this.recorder.stopAndSave(ctx);
    if (path.length === 0) {
      return;
    }
    promptAction.showToast({ message: '正在识别…', duration: 1500 });
    const text = await DeepSeekApi.transcribe(Store.get().settings, path);
    if (text.length > 0) {
      this.draft = this.draft.length > 0 ? `${this.draft}${text}` : text;
    } else {
      promptAction.showToast({ message: '没听清，再说一次', duration: 2000 });
    }
  }

  private async ensureMic(): Promise<boolean> {
    const ctx = getContext(this) as common.UIAbilityContext;
    const atManager = abilityAccessCtrl.createAtManager();
    const perms: Array<Permissions> = ['ohos.permission.MICROPHONE'];
    try {
      const result = await atManager.requestPermissionsFromUser(ctx, perms);
      return result.authResults.length > 0 && result.authResults[0] === 0;
    } catch (err) {
      return false;
    }
  }

  private scrollToBottom(): void {
    if (this.messages.length === 0) {
      return;
    }
    setTimeout(() => {
      try {
        this.scroller.scrollEdge(Edge.Bottom);
      } catch (err) {
        hilog.debug(DOMAIN, TAG, 'scroll failed');
      }
    }, 40);
  }

  private openSettings(): void {
    router.pushUrl({ url: 'pages/SettingsPage' });
  }

  private newChat(): void {
    const conv = Store.get().create();
    router.replaceUrl({ url: 'pages/ChatPage', params: { id: conv.id } });
  }

  private deleteChat(): void {
    const conv = this.conv;
    if (conv === null) {
      return;
    }
    Store.get().remove(conv.id);
    router.back();
  }

  onBackPress(): boolean {
    router.back();
    return true;
  }
}
