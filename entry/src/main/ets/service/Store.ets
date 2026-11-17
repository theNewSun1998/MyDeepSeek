import { preferences } from '@kit.ArkData';
import { common } from '@kit.AbilityKit';
import { hilog } from '@kit.PerformanceAnalysisKit';
import { AppSettings, ChatMessage, Conversation } from '../model/Types';
import { Util } from '../common/Util';

const DOMAIN: number = 0x0000;
const TAG: string = 'hmds';
const STORE_NAME: string = 'deepseek_app';
const KEY_SETTINGS: string = 'settings';
const KEY_CONVS: string = 'conversations';

/** 本地持久化：设置 + 全部会话（JSON 存在 preferences 里，够简单也够快） */
export class Store {
  private static inst: Store = new Store();
  private prefs: preferences.Preferences | null = null;
  private ready: boolean = false;

  settings: AppSettings = new AppSettings();
  conversations: Array<Conversation> = [];

  static get(): Store {
    return Store.inst;
  }

  async init(ctx: common.UIAbilityContext): Promise<void> {
    if (this.ready) {
      return;
    }
    try {
      this.prefs = await preferences.getPreferences(ctx, STORE_NAME);
      const rawSettings = await this.prefs.get(KEY_SETTINGS, '');
      if (typeof rawSettings === 'string' && rawSettings.length > 0) {
        this.settings = JSON.parse(rawSettings) as AppSettings;
      }
      const rawConvs = await this.prefs.get(KEY_CONVS, '');
      if (typeof rawConvs === 'string' && rawConvs.length > 0) {
        this.conversations = JSON.parse(rawConvs) as Array<Conversation>;
      }
      this.ready = true;
      hilog.info(DOMAIN, TAG, 'store loaded, %{public}d conversations', this.conversations.length);
    } catch (err) {
      hilog.error(DOMAIN, TAG, 'store init failed: %{public}s', JSON.stringify(err));
      this.ready = true;
    }
  }

  async save(): Promise<void> {
    if (!this.prefs) {
      return;
    }
    try {
      await this.prefs.put(KEY_SETTINGS, JSON.stringify(this.settings));
      await this.prefs.put(KEY_CONVS, JSON.stringify(this.conversations));
      await this.prefs.flush();
    } catch (err) {
      hilog.error(DOMAIN, TAG, 'store save failed: %{public}s', JSON.stringify(err));
    }
  }

  hasKey(): boolean {
    return this.settings.apiKey.trim().length > 0;
  }

  sorted(): Array<Conversation> {
    const list = this.conversations.slice();
    list.sort((a: Conversation, b: Conversation) => b.updatedAt - a.updatedAt);
    return list;
  }

  find(id: string): Conversation | null {
    for (let i = 0; i < this.conversations.length; i++) {
      if (this.conversations[i].id === id) {
        return this.conversations[i];
      }
    }
    return null;
  }

  create(): Conversation {
    const conv = new Conversation();
    conv.id = Util.uuid();
    conv.title = '新的对话';
    conv.messages = [];
    conv.createdAt = Util.now();
    conv.updatedAt = conv.createdAt;
    this.conversations.push(conv);
    this.save();
    return conv;
  }

  remove(id: string): void {
    const next: Array<Conversation> = [];
    for (let i = 0; i < this.conversations.length; i++) {
      if (this.conversations[i].id !== id) {
        next.push(this.conversations[i]);
      }
    }
    this.conversations = next;
    this.save();
  }

  touch(conv: Conversation): void {
    conv.updatedAt = Util.now();
    this.save();
  }

  /** 第一次发消息时用用户的话生成标题（服务端也会给 title，收到再覆盖） */
  autoTitle(conv: Conversation, firstUserText: string): void {
    if (conv.title === '新的对话' || conv.title.length === 0) {
      conv.title = Util.titleFromText(firstUserText);
    }
  }

  clearAll(): void {
    this.conversations = [];
    this.save();
  }

  /** 统计用：某个会话里最早的用户消息 */
  firstUserText(conv: Conversation): string {
    for (let i = 0; i < conv.messages.length; i++) {
      const m: ChatMessage = conv.messages[i];
      if (m.role === 'user') {
        return m.text;
      }
    }
    return '';
  }
}
