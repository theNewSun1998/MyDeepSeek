import { http } from '@kit.NetworkKit';
import { hilog } from '@kit.PerformanceAnalysisKit';
import { Attachment, ChatMessage, Citation, Conversation, SearchHit, SearchStep } from '../model/Types';
import { DeepSeekApi, StreamHandler } from './DeepSeekApi';
import { Json } from './Json';
import { Store } from './Store';
import { Util } from '../common/Util';

const DOMAIN: number = 0x0000;
const TAG: string = 'hmds';

/** 流式过程中累积的一个 content block */
class BlockAcc {
  type: string = '';
  text: string = '';
  thinking: string = '';
  signature: string = '';
  /** 完整到达的块（server_tool_use / 检索结果）原样保存，回传时不许改 */
  raw: string = '';
  citations: Array<Citation> = [];
}

export class SendOptions {
  search: boolean = true;
  thinking: boolean = true;
}

/**
 * 一轮对话的状态机：拼请求 → 解 SSE → 更新消息 → 落盘。
 * UI 只订阅 onUpdate()，不关心协议细节。
 */
export class ChatEngine {
  conv: Conversation;
  onUpdate: (() => void) | null = null;

  private store: Store = Store.get();
  private req: http.HttpRequest | null = null;
  private blockMap: Map<number, BlockAcc> = new Map();
  private steps: Array<SearchStep> = [];
  private stepOfTool: Map<string, number> = new Map();
  private assistant: ChatMessage | null = null;
  private streaming: boolean = false;
  private lastNotify: number = 0;
  private firstTextAt: number = 0;
  private opts: SendOptions = new SendOptions();

  constructor(conv: Conversation) {
    this.conv = conv;
  }

  isStreaming(): boolean {
    return this.streaming;
  }

  /** 发一条用户消息并开始流式回答 */
  send(text: string, attachments: Array<Attachment>, opts: SendOptions): void {
    if (this.streaming) {
      return;
    }
    const user = new ChatMessage();
    user.id = Util.uuid();
    user.role = 'user';
    user.text = text;
    user.attachments = attachments;
    user.createdAt = Util.now();
    user.status = 'done';
    this.conv.messages.push(user);

    this.store.autoTitle(this.conv, text);
    this.start(opts);
  }

  /** 重新生成：丢掉最后一条助手消息，用同样的历史再来一次 */
  regenerate(opts: SendOptions): void {
    if (this.streaming) {
      return;
    }
    const msgs = this.conv.messages;
    while (msgs.length > 0 && msgs[msgs.length - 1].role === 'assistant') {
      msgs.pop();
    }
    if (msgs.length === 0) {
      return;
    }
    this.start(opts);
  }

  stop(): void {
    if (this.req !== null) {
      try {
        this.req.destroy();
      } catch (err) {
        hilog.warn(DOMAIN, TAG, 'destroy request failed');
      }
      this.req = null;
    }
    if (!this.streaming) {
      return;
    }
    this.streaming = false;
    if (this.assistant !== null) {
      this.assistant.status = 'stopped';
      this.assistant = null;
    }
    this.store.touch(this.conv);
    this.notify(true);
  }

  // ---------------------------------------------------------------- 内部实现

  private start(opts: SendOptions): void {
    this.opts = opts;
    this.blockMap = new Map();
    this.steps = [];
    this.stepOfTool = new Map();
    this.firstTextAt = 0;

    const assistant = new ChatMessage();
    assistant.id = Util.uuid();
    assistant.role = 'assistant';
    assistant.status = 'streaming';
    assistant.createdAt = Util.now();
    this.assistant = assistant;
    this.conv.messages.push(assistant);

    this.streaming = true;
    this.notify(true);

    const body = this.buildBody(opts);
    const hasDocs = this.hasDocuments();
    hilog.info(DOMAIN, TAG, 'send request, %{public}d bytes body', body.length);

    const handler: StreamHandler = {
      onEvent: (eventName: string, data: string): void => {
        this.handleEvent(eventName, data);
      },
      onDone: (): void => {
        this.finish('done');
      },
      onError: (message: string): void => {
        this.fail(message);
      }
    };

    try {
      this.req = DeepSeekApi.streamMessages(this.store.settings, body, hasDocs, handler);
    } catch (err) {
      this.fail(`请求失败：${JSON.stringify(err)}`);
    }
  }

  private hasDocuments(): boolean {
    for (let i = 0; i < this.conv.messages.length; i++) {
      const atts = this.conv.messages[i].attachments;
      for (let j = 0; j < atts.length; j++) {
        if (atts[j].fileId.length > 0) {
          return true;
        }
      }
    }
    return false;
  }

  private buildBody(opts: SendOptions): string {
    const s = this.store.settings;
    const parts: Array<string> = [];
    parts.push(`"model":${JSON.stringify(s.model)}`);
    parts.push('"max_tokens":8192');
    parts.push('"stream":true');
    if (opts.thinking) {
      parts.push('"thinking":{"type":"enabled"}');
    }
    const tools: Array<string> = [];
    if (opts.search) {
      tools.push('{"type":"deepseek_web_search","name":"deepseek_web_search"}');
    }
    tools.push('{"type":"deepseek_read_url","name":"deepseek_read_url"}');
    parts.push(`"tools":[${tools.join(',')}]`);
    if (s.safetyLevel !== 'off') {
      parts.push(`"safety_check":{"level":${JSON.stringify(s.safetyLevel)}}`);
    }
    parts.push('"title":{"enabled":true}');
    if (s.systemPrompt.trim().length > 0) {
      parts.push(`"system":${JSON.stringify(s.systemPrompt)}`);
    }
    parts.push(`"messages":[${this.messagesJson()}]`);
    return '{' + parts.join(',') + '}';
  }

  private messagesJson(): string {
    const out: Array<string> = [];
    for (let i = 0; i < this.conv.messages.length; i++) {
      const m = this.conv.messages[i];
      if (m.role === 'user') {
        out.push(`{"role":"user","content":${this.userContentJson(m)}}`);
      } else {
        // 助手消息必须是服务端原始块（含 signature），一个字都不能动
        if (m.rawContentJson.length > 2) {
          out.push(`{"role":"assistant","content":${m.rawContentJson}}`);
        }
      }
    }
    return out.join(',');
  }

  private userContentJson(m: ChatMessage): string {
    let hasDoc = false;
    for (let i = 0; i < m.attachments.length; i++) {
      if (m.attachments[i].fileId.length > 0) {
        hasDoc = true;
      }
    }
    if (!hasDoc) {
      return JSON.stringify(m.text);
    }
    const blocks: Array<string> = [];
    for (let i = 0; i < m.attachments.length; i++) {
      const a = m.attachments[i];
      if (a.fileId.length > 0) {
        blocks.push(`{"type":"document","source":{"type":"file","file_id":${JSON.stringify(a.fileId)}}}`);
      }
    }
    blocks.push(`{"type":"text","text":${JSON.stringify(m.text)}}`);
    return '[' + blocks.join(',') + ']';
  }

  private handleEvent(eventName: string, data: string): void {
    const o = Json.obj(data);
    const type = Json.str(o, 'type');
    if (type === 'content_block_start') {
      this.onBlockStart(o);
    } else if (type === 'content_block_delta') {
      this.onBlockDelta(o);
    } else if (type === 'message_delta') {
      this.onMessageDelta(o);
    } else if (type === 'message_stop') {
      this.finish('done');
    } else if (type === 'error') {
      const errObj = Json.sub(o, 'error');
      this.fail(Json.str(errObj, 'message'));
    }
    this.rebuild();
    this.notify(false);
  }

  private onBlockStart(o: Record<string, Object>): void {
    const index = Json.num(o, 'index');
    const block = Json.sub(o, 'content_block');
    const type = Json.str(block, 'type');
    const acc = new BlockAcc();
    acc.type = type;

    if (type === 'text') {
      acc.text = Json.str(block, 'text');
    } else if (type === 'thinking') {
      acc.thinking = Json.str(block, 'thinking');
      acc.signature = Json.str(block, 'signature');
    } else {
      acc.raw = JSON.stringify(block);
    }
    this.blockMap.set(index, acc);

    if (type === 'server_tool_use') {
      this.addStep(block);
    } else if (type === 'web_search_tool_result') {
      this.fillSearchResult(block);
    } else if (type === 'web_fetch_tool_result') {
      this.fillFetchResult(block);
    }
  }

  private addStep(block: Record<string, Object>): void {
    const input = Json.sub(block, 'input');
    const step = new SearchStep();
    step.kind = Json.str(block, 'kind');
    step.action = Json.str(input, 'action');
    step.queries = Json.strs(input, 'queries');
    step.url = Json.str(input, 'url');
    step.pattern = Json.str(input, 'pattern');
    this.steps.push(step);
    const id = Json.str(block, 'id');
    if (id.length > 0) {
      this.stepOfTool.set(id, this.steps.length - 1);
    }
  }

  private stepIndex(toolUseId: string): number {
    const idx = this.stepOfTool.get(toolUseId);
    return idx === undefined ? -1 : idx;
  }

  private fillSearchResult(block: Record<string, Object>): void {
    const idx = this.stepIndex(Json.str(block, 'tool_use_id'));
    if (idx < 0) {
      return;
    }
    const step = this.steps[idx];
    const content = Json.arr(block, 'content');
    for (let i = 0; i < content.length; i++) {
      const item = content[i] as Record<string, Object>;
      const itemType = Json.str(item, 'type');
      if (itemType === 'web_search_tool_result_error') {
        step.errorCode = Json.str(item, 'error_code');
        continue;
      }
      const hit = new SearchHit();
      hit.title = Json.str(item, 'title');
      hit.url = Json.str(item, 'url');
      hit.siteName = Json.str(item, 'site_name');
      hit.snippet = Json.str(item, 'snippet');
      hit.pageAge = Json.str(item, 'page_age');
      hit.imageUrl = Json.str(item, 'image_url');
      step.hits.push(hit);
    }
  }

  private fillFetchResult(block: Record<string, Object>): void {
    const idx = this.stepIndex(Json.str(block, 'tool_use_id'));
    if (idx < 0) {
      return;
    }
    const step = this.steps[idx];
    const content = Json.sub(block, 'content');
    const contentType = Json.str(content, 'type');
    if (contentType === 'web_fetch_tool_result_error') {
      step.errorCode = Json.str(content, 'error_code');
      return;
    }
    step.fetchUrl = Json.str(content, 'url');
    const doc = Json.sub(content, 'content');
    step.fetchTitle = Json.str(doc, 'title');
    if (step.fetchTitle.length === 0) {
      step.fetchTitle = step.fetchUrl;
    }
  }

  private onBlockDelta(o: Record<string, Object>): void {
    const index = Json.num(o, 'index');
    const acc = this.blockMap.get(index);
    if (acc === undefined) {
      return;
    }
    const delta = Json.sub(o, 'delta');
    const dt = Json.str(delta, 'type');
    if (dt === 'text_delta') {
      if (this.firstTextAt === 0) {
        this.firstTextAt = Util.now();
        if (this.assistant !== null) {
          this.assistant.thinkingMs = this.firstTextAt - this.assistant.createdAt;
        }
      }
      acc.text += Json.str(delta, 'text');
    } else if (dt === 'thinking_delta') {
      acc.thinking += Json.str(delta, 'thinking');
    } else if (dt === 'signature_delta') {
      acc.signature += Json.str(delta, 'signature');
    } else if (dt === 'citations_delta') {
      const c = Json.sub(delta, 'citation');
      const cit = new Citation();
      cit.url = Json.str(c, 'url');
      cit.title = Json.str(c, 'title');
      cit.citedText = Json.str(c, 'cited_text');
      acc.citations.push(cit);
    }
  }

  private onMessageDelta(o: Record<string, Object>): void {
    const delta = Json.sub(o, 'delta');
    const stopReason = Json.str(delta, 'stop_reason');
    const title = Json.str(delta, 'title');
    if (title.length > 0) {
      this.conv.title = title;
    }
    if (stopReason === 'refusal') {
      this.finish('refused');
    }
  }

  /** 把 block 累积结果摊平成 UI 要的字段（text / thinking / steps / citations / 原始 JSON） */
  private rebuild(): void {
    const msg = this.assistant;
    if (msg === null) {
      return;
    }
    const keys: Array<number> = [];
    this.blockMap.forEach((_v: BlockAcc, k: number) => {
      keys.push(k);
    });
    keys.sort((a: number, b: number) => a - b);

    let text = '';
    let thinking = '';
    const cites: Array<Citation> = [];
    const parts: Array<string> = [];

    for (let i = 0; i < keys.length; i++) {
      const acc = this.blockMap.get(keys[i]);
      if (acc === undefined) {
        continue;
      }
      if (acc.type === 'text') {
        text += acc.text;
        parts.push(`{"type":"text","text":${JSON.stringify(acc.text)}}`);
        for (let j = 0; j < acc.citations.length; j++) {
          cites.push(acc.citations[j]);
        }
      } else if (acc.type === 'thinking') {
        thinking += acc.thinking;
        parts.push(`{"type":"thinking","thinking":${JSON.stringify(acc.thinking)},` +
          `"signature":${JSON.stringify(acc.signature)}}`);
      } else if (acc.raw.length > 0) {
        parts.push(acc.raw);
      }
    }

    msg.text = text;
    msg.thinking = thinking;
    msg.citations = cites;
    msg.steps = this.steps.slice();
    msg.rawContentJson = '[' + parts.join(',') + ']';
  }

  private finish(status: string): void {
    if (!this.streaming) {
      return;
    }
    this.streaming = false;
    this.req = null;
    if (this.assistant !== null) {
      this.assistant.status = status;
      if (this.assistant.text.length === 0 && this.assistant.thinking.length === 0 &&
        this.assistant.steps.length === 0) {
        this.assistant.status = 'error';
        this.assistant.errorText = '没有收到内容，换个说法或关掉联网再试。';
      }
      this.assistant = null;
    }
    this.store.touch(this.conv);
    this.notify(true);
  }

  private fail(message: string): void {
    if (this.assistant !== null) {
      this.assistant.status = 'error';
      this.assistant.errorText = message;
      this.assistant = null;
    }
    this.streaming = false;
    if (this.req !== null) {
      try {
        this.req.destroy();
      } catch (err) {
        hilog.warn(DOMAIN, TAG, 'destroy on fail failed');
      }
      this.req = null;
    }
    this.store.touch(this.conv);
    this.notify(true);
  }

  /** 高频 delta 会把 UI 刷爆，这里做 80ms 节流 */
  private notify(force: boolean): void {
    if (this.onUpdate === null) {
      return;
    }
    const now = Util.now();
    if (!force) {
      if (now - this.lastNotify < 80) {
        return;
      }
    }
    this.lastNotify = now;
    this.onUpdate();
  }
}
