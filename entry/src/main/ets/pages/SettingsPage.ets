import { router, promptAction } from '@kit.ArkUI';
import { common } from '@kit.AbilityKit';
import { Store } from '../service/Store';
import { DeepSeekApi } from '../service/DeepSeekApi';
import { Theme } from '../common/Theme';
import { AppSettings } from '../model/Types';
import { SettingField, SettingSectionTitle, SettingSelect, SettingSwitch } from '../components/SettingRows';

/** 设置：Key、模型、安全等级、默认开关、附件解析方式 */
@Entry
@Component
struct SettingsPage {
  @State apiKey: string = '';
  @State baseUrl: string = '';
  @State model: string = '';
  @State safetyLevel: string = 'high';
  @State searchDefault: boolean = true;
  @State thinkingDefault: boolean = true;
  @State parsePurpose: string = 'text_recognition';
  @State systemPrompt: string = '';
  @State testResult: string = '';
  @State testing: boolean = false;
  @State ready: boolean = false;

  aboutToAppear(): void {
    this.boot();
  }

  build() {
    Column() {
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
        Text('设置')
          .fontSize(17)
          .fontWeight(FontWeight.Medium)
          .fontColor(Theme.TEXT)
          .layoutWeight(1)
          .textAlign(TextAlign.Center)
        Text('')
          .width(36)
      }
      .width('100%')
      .height(52)
      .padding({ left: 6, right: 6 })

      Scroll() {
        Column({ space: 16 }) {
          SettingSectionTitle({ title: '账号' })
          SettingField({
            label: 'DeepSeek API Key',
            value: this.apiKey,
            placeholder: 'sk-...',
            secret: true,
            onChange: (v: string) => {
              this.apiKey = v;
              this.persist();
            }
          })

          Row({ space: 10 }) {
            Button(this.testing ? '测试中…' : '测试连接 / 查余额')
              .fontSize(13)
              .height(38)
              .backgroundColor(Theme.BRAND_SOFT)
              .fontColor(Theme.BRAND)
              .layoutWeight(1)
              .enabled(!this.testing)
              .onClick(() => {
                this.test();
              })
            Button('清空所有对话')
              .fontSize(13)
              .height(38)
              .backgroundColor(Theme.BG_GRAY)
              .fontColor(Theme.DANGER)
              .layoutWeight(1)
              .onClick(() => {
                Store.get().clearAll();
                promptAction.showToast({ message: '已清空', duration: 1500 });
              })
          }
          .width('100%')

          if (this.testResult.length > 0) {
            Text(this.testResult)
              .fontSize(12)
              .fontColor(this.testResult.startsWith('连接成功') ? Theme.OK : Theme.DANGER)
              .width('100%')
              .padding(10)
              .backgroundColor(Theme.BG_GRAY)
              .borderRadius(10)
          }

          SettingSectionTitle({ title: '模型' })
          SettingField({
            label: '模型名',
            value: this.model,
            placeholder: 'deepseek-flash',
            secret: false,
            onChange: (v: string) => {
              this.model = v;
              this.persist();
            }
          })
          SettingField({
            label: '接口地址',
            value: this.baseUrl,
            placeholder: 'https://api.deepseek.com',
            secret: false,
            onChange: (v: string) => {
              this.baseUrl = v;
              this.persist();
            }
          })

          SettingSectionTitle({ title: '回答设置' })
          SettingSwitch({
            label: '新对话默认联网搜索',
            isOn: this.searchDefault,
            onChange: (v: boolean) => {
              this.searchDefault = v;
              this.persist();
            }
          })
          SettingSwitch({
            label: '新对话默认深度思考',
            isOn: this.thinkingDefault,
            onChange: (v: boolean) => {
              this.thinkingDefault = v;
              this.persist();
            }
          })
          SettingSelect({
            label: '内容安全等级',
            options: this.safetyOptions(),
            selected: this.safetyIndex(),
            onSelect: (i: number, v: string) => {
              this.safetyLevel = this.safetyValue(i);
              this.persist();
            }
          })

          SettingSectionTitle({ title: '附件解析' })
          SettingSelect({
            label: '解析方式',
            options: ['纯文本（快、便宜）', '带版面（能看到图表）'],
            selected: this.parsePurpose === 'rich_layout_recognition' ? 1 : 0,
            onSelect: (i: number, v: string) => {
              this.parsePurpose = i === 1 ? 'rich_layout_recognition' : 'text_recognition';
              this.persist();
            }
          })

          SettingSectionTitle({ title: '高级' })
          SettingField({
            label: '系统提示词（可空）',
            value: this.systemPrompt,
            placeholder: '例如：回答尽量简洁',
            secret: false,
            onChange: (v: string) => {
              this.systemPrompt = v;
              this.persist();
            }
          })

          Text('这个客户端用你自己的 API Key 直连 DeepSeek 官方接口，密钥只存在本机。\n' +
            '全套工具（联网搜索 / 深度搜索 / 读链接 / 文件解析 / 语音）目前只有 deepseek-flash 支持，' +
            'V4-Pro 还没跟上，所以模型名别乱改。')
            .fontSize(11)
            .fontColor(Theme.TEXT_FAINT)
            .width('100%')
            .lineHeight(17)
            .padding({ top: 6, bottom: 40 })
        }
        .width('100%')
        .padding({ left: 16, right: 16 })
      }
      .layoutWeight(1)
      .width('100%')
      .scrollBar(BarState.Off)
    }
    .width('100%')
    .height('100%')
    .backgroundColor(Theme.BG)
  }

  private async boot(): Promise<void> {
    const ctx = getContext(this) as common.UIAbilityContext;
    const store = Store.get();
    await store.init(ctx);
    const s = store.settings;
    this.apiKey = s.apiKey;
    this.baseUrl = s.baseUrl;
    this.model = s.model;
    this.safetyLevel = s.safetyLevel;
    this.searchDefault = s.searchDefault;
    this.thinkingDefault = s.thinkingDefault;
    this.parsePurpose = s.parsePurpose;
    this.systemPrompt = s.systemPrompt;
    this.ready = true;
  }

  private persist(): void {
    const s = Store.get().settings;
    s.apiKey = this.apiKey.trim();
    s.baseUrl = this.baseUrl.trim().length > 0 ? this.baseUrl.trim() : 'https://api.deepseek.com';
    s.model = this.model.trim().length > 0 ? this.model.trim() : 'deepseek-flash';
    s.safetyLevel = this.safetyLevel;
    s.searchDefault = this.searchDefault;
    s.thinkingDefault = this.thinkingDefault;
    s.parsePurpose = this.parsePurpose;
    s.systemPrompt = this.systemPrompt;
    Store.get().save();
  }

  private async test(): Promise<void> {
    if (this.apiKey.trim().length === 0) {
      promptAction.showToast({ message: '先填 API Key', duration: 1500 });
      return;
    }
    this.testing = true;
    this.testResult = '';
    const settings = new AppSettings();
    settings.apiKey = this.apiKey.trim();
    settings.baseUrl = this.baseUrl.trim().length > 0 ? this.baseUrl.trim() : 'https://api.deepseek.com';
    const result = await DeepSeekApi.fetchBalance(settings);
    this.testResult = result;
    this.testing = false;
  }

  private safetyOptions(): Array<string> {
    return ['关闭（仅自己用）', '低', '中', '高（推荐）'];
  }

  private safetyIndex(): number {
    if (this.safetyLevel === 'off') {
      return 0;
    }
    if (this.safetyLevel === 'low') {
      return 1;
    }
    if (this.safetyLevel === 'medium') {
      return 2;
    }
    return 3;
  }

  private safetyValue(index: number): string {
    if (index === 0) {
      return 'off';
    }
    if (index === 1) {
      return 'low';
    }
    if (index === 2) {
      return 'medium';
    }
    return 'high';
  }
}
