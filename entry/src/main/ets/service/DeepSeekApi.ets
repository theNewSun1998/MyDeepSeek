import { http } from '@kit.NetworkKit';
import { util } from '@kit.ArkTS';
import { BusinessError } from '@kit.BasicServicesKit';
import { fs } from '@kit.CoreFileKit';
import { hilog } from '@kit.PerformanceAnalysisKit';
import { AppSettings } from '../model/Types';
import { Util } from '../common/Util';
import { Json } from './Json';

const DOMAIN: number = 0x0000;
const TAG: string = 'hmds';
const MAX_UPLOAD_BYTES: number = 100 * 1024 * 1024;

export class UploadResult {
  ok: boolean = false;
  fileId: string = '';
  fileName: string = '';
  bytes: number = 0;
  errorText: string = '';
}

export class FormField {
  name: string = '';
  value: string = '';

  constructor(name: string, value: string) {
    this.name = name;
    this.value = value;
  }
}

export interface StreamHandler {
  onEvent: (eventName: string, data: string) => void;
  onDone: () => void;
  onError: (message: string) => void;
}

/** server-sent events 增量解析：官方流里混着 `: keep-alive` 注释和 event/data 两行一组 */
class SseParser {
  private buffer: string = '';
  private eventName: string = '';
  private dataLines: string = '';
  private cb: (eventName: string, data: string) => void;

  constructor(cb: (eventName: string, data: string) => void) {
    this.cb = cb;
  }

  feed(chunk: string): void {
    this.buffer += chunk;
    let idx = this.buffer.indexOf('\n');
    while (idx >= 0) {
      let line = this.buffer.substring(0, idx);
      this.buffer = this.buffer.substring(idx + 1);
      if (line.endsWith('\r')) {
        line = line.substring(0, line.length - 1);
      }
      this.handleLine(line);
      idx = this.buffer.indexOf('\n');
    }
  }

  private handleLine(line: string): void {
    if (line.length === 0) {
      this.flush();
      return;
    }
    if (line.startsWith(':')) {
      return;
    }
    if (line.startsWith('event:')) {
      this.eventName = line.substring(6).trim();
      return;
    }
    if (line.startsWith('data:')) {
      const d = line.substring(5).trim();
      this.dataLines = this.dataLines.length === 0 ? d : this.dataLines + '\n' + d;
    }
  }

  private flush(): void {
    if (this.dataLines.length === 0) {
      this.eventName = '';
      return;
    }
    const name = this.eventName;
    const data = this.dataLines;
    this.eventName = '';
    this.dataLines = '';
    this.cb(name, data);
  }
}

/**
 * DeepSeek API 客户端。
 * 走 Messages 协议（/anthropic/v1/messages）：只有它会把思考、搜索步骤、抓页结果、
 * 引用角标都以结构化 content block 返回，正好对应 App 里那套 UI。
 */
export class DeepSeekApi {
  static base(settings: AppSettings): string {
    let b = settings.baseUrl.trim();
    if (b.length === 0) {
      b = 'https://api.deepseek.com';
    }
    while (b.endsWith('/')) {
      b = b.substring(0, b.length - 1);
    }
    return b;
  }

  static headers(settings: AppSettings, json: boolean, withDocs: boolean): Record<string, string> {
    const h: Record<string, string> = {
      'Authorization': 'Bearer ' + settings.apiKey.trim(),
      'x-api-key': settings.apiKey.trim()
    };
    if (json) {
      h['Content-Type'] = 'application/json';
    }
    if (withDocs) {
      h['anthropic-beta'] = 'files-api-2025-04-14';
    }
    return h;
  }

  /** 发起流式对话，返回的 HttpRequest 用于随时中断 */
  static streamMessages(settings: AppSettings, body: string, withDocs: boolean,
    handler: StreamHandler): http.HttpRequest {
    const req = http.createHttp();
    const decoder = util.TextDecoder.create('utf-8');
    const parser = new SseParser((eventName: string, data: string) => {
      handler.onEvent(eventName, data);
    });
    let settled: boolean = false;

    req.on('dataReceive', (chunk: ArrayBuffer) => {
      try {
        const text = decoder.decodeWithStream(new Uint8Array(chunk), { stream: true });
        parser.feed(text);
      } catch (err) {
        hilog.warn(DOMAIN, TAG, 'decode chunk failed');
      }
    });

    req.on('dataEnd', () => {
      if (!settled) {
        settled = true;
        handler.onDone();
      }
    });

    const url = DeepSeekApi.base(settings) + '/anthropic/v1/messages';
    const options: http.HttpRequestOptions = {
      method: http.RequestMethod.POST,
      header: DeepSeekApi.headers(settings, true, withDocs),
      extraData: body,
      expectDataType: http.HttpDataType.STRING,
      usingCache: false,
      connectTimeout: 30000,
      readTimeout: 300000
    };

    req.request(url, options).then((res: http.HttpResponse) => {
      if (res.responseCode !== 200 && !settled) {
        settled = true;
        const raw = `${res.result}`;
        handler.onError(DeepSeekApi.friendlyError(res.responseCode, raw));
      }
    }).catch((err: BusinessError) => {
      if (!settled) {
        settled = true;
        handler.onError(`网络错误（${err.code}）：${err.message}`);
      }
    });

    return req;
  }

  static friendlyError(code: number, raw: string): string {
    const o = Json.obj(raw);
    const errObj = Json.sub(o, 'error');
    let msg = Json.str(errObj, 'message');
    if (msg.length === 0) {
      msg = raw.length > 300 ? raw.substring(0, 300) : raw;
    }
    if (code === 401) {
      return 'API Key 无效或已过期（401）。去设置里检查一下。';
    }
    if (code === 402) {
      return '账户余额不足（402）。';
    }
    if (code === 429) {
      return '请求太频繁或并发超限（429），稍等再试。';
    }
    return `HTTP ${code}：${msg}`;
  }

  /** 上传并解析文件，拿到 file_id 才能放进对话 */
  static async uploadFile(settings: AppSettings, uri: string, fileName: string,
    purpose: string): Promise<UploadResult> {
    const result = new UploadResult();
    result.fileName = fileName;
    try {
      const bytes = DeepSeekApi.readBytes(uri);
      result.bytes = bytes.length;
      const fields: Array<FormField> = [];
      fields.push(new FormField('purpose', purpose));
      const text = await DeepSeekApi.postMultipart(settings,
        DeepSeekApi.base(settings) + '/files', fields, fileName, bytes, 200000);
      const o = Json.obj(text);
      const id = Json.str(o, 'id');
      if (id.length === 0) {
        const errObj = Json.sub(o, 'error');
        result.errorText = Json.str(errObj, 'message');
        if (result.errorText.length === 0) {
          result.errorText = '上传失败：服务端没有返回 file_id';
        }
        return result;
      }
      result.fileId = id;
      result.ok = true;
      return result;
    } catch (err) {
      const e = err as BusinessError;
      result.errorText = `上传失败：${e.message}`;
      return result;
    }
  }

  /** 语音听写：整段 WAV 上传，返回识别文本 */
  static async transcribe(settings: AppSettings, uri: string): Promise<string> {
    try {
      const bytes = DeepSeekApi.readBytes(uri);
      const fields: Array<FormField> = [];
      const text = await DeepSeekApi.postMultipart(settings,
        DeepSeekApi.base(settings) + '/v1/tools/asr', fields, 'speech.wav', bytes, 300000);
      const o = Json.obj(text);
      return Json.str(o, 'text');
    } catch (err) {
      const e = err as BusinessError;
      hilog.error(DOMAIN, TAG, 'asr failed: %{public}s', e.message);
      return '';
    }
  }

  /** 查余额，设置页里用来"测试连接" */
  static async fetchBalance(settings: AppSettings): Promise<string> {
    const req = http.createHttp();
    try {
      const res = await req.request(DeepSeekApi.base(settings) + '/user/balance', {
        method: http.RequestMethod.GET,
        header: DeepSeekApi.headers(settings, false, false),
        expectDataType: http.HttpDataType.STRING,
        connectTimeout: 15000,
        readTimeout: 20000
      });
      if (res.responseCode !== 200) {
        return DeepSeekApi.friendlyError(res.responseCode, `${res.result}`);
      }
      const o = Json.obj(`${res.result}`);
      const infos = Json.arr(o, 'balance_infos');
      if (infos.length === 0) {
        return '连接成功（未取到余额字段）';
      }
      const first = infos[0] as Record<string, Object>;
      const total = Json.str(first, 'total_balance');
      const currency = Json.str(first, 'currency');
      return `连接成功，余额 ${total} ${currency}`;
    } catch (err) {
      const e = err as BusinessError;
      return `连接失败：${e.message}`;
    } finally {
      req.destroy();
    }
  }

  static readBytes(uri: string): Uint8Array {
    const file = fs.openSync(uri, fs.OpenMode.READ_ONLY);
    try {
      const stat = fs.statSync(file.fd);
      if (stat.size > MAX_UPLOAD_BYTES) {
        throw new Error(`文件超过 ${Util.humanSize(MAX_UPLOAD_BYTES)}，服务端上限 100 MiB`);
      }
      const buf = new ArrayBuffer(stat.size);
      const read = fs.readSync(file.fd, buf);
      if (read <= 0) {
        throw new Error('文件读取失败');
      }
      return new Uint8Array(buf.slice(0, read));
    } finally {
      fs.closeSync(file);
    }
  }

  private static async postMultipart(settings: AppSettings, url: string, fields: Array<FormField>,
    fileName: string, fileBytes: Uint8Array, timeoutMs: number): Promise<string> {
    const boundary = '----dshm' + Util.uuid().replace(/-/g, '');
    const enc = new util.TextEncoder();
    const parts: Array<Uint8Array> = [];

    for (let i = 0; i < fields.length; i++) {
      parts.push(enc.encodeInto(`--${boundary}\r\n`));
      parts.push(enc.encodeInto(`Content-Disposition: form-data; name="${fields[i].name}"\r\n\r\n`));
      parts.push(enc.encodeInto(`${fields[i].value}\r\n`));
    }
    parts.push(enc.encodeInto(`--${boundary}\r\n`));
    parts.push(enc.encodeInto(`Content-Disposition: form-data; name="file"; filename="${fileName}"\r\n`));
    parts.push(enc.encodeInto('Content-Type: application/octet-stream\r\n\r\n'));
    parts.push(fileBytes);
    parts.push(enc.encodeInto(`\r\n--${boundary}--\r\n`));

    let total = 0;
    for (let i = 0; i < parts.length; i++) {
      total += parts[i].length;
    }
    const body = new Uint8Array(total);
    let offset = 0;
    for (let i = 0; i < parts.length; i++) {
      body.set(parts[i], offset);
      offset += parts[i].length;
    }

    const header: Record<string, string> = {
      'Authorization': 'Bearer ' + settings.apiKey.trim(),
      'Content-Type': `multipart/form-data; boundary=${boundary}`
    };

    const req = http.createHttp();
    try {
      const res = await req.request(url, {
        method: http.RequestMethod.POST,
        header: header,
        extraData: body.buffer as ArrayBuffer,
        expectDataType: http.HttpDataType.STRING,
        connectTimeout: 30000,
        readTimeout: timeoutMs
      });
      if (res.responseCode !== 200) {
        throw new Error(DeepSeekApi.friendlyError(res.responseCode, `${res.result}`));
      }
      return `${res.result}`;
    } finally {
      req.destroy();
    }
  }
}
