/**
 * 数据模型。
 *
 * 设计要点：助手消息除了给人看的字段（text / thinking / steps / citations），
 * 还存了 rawContentJson —— 服务端返回的 content 块数组的原样 JSON。
 * 官方明确要求「下一轮必须把思考、搜索、抓页结果块原样回传」，
 * 少一个 signature 模型就会忘记刚看过的网页，所以这里不做任何裁剪。
 */

export class Citation {
  url: string = '';
  title: string = '';
  citedText: string = '';
}

/** 搜索结果里的一条网页 */
export class SearchHit {
  title: string = '';
  url: string = '';
  siteName: string = '';
  snippet: string = '';
  pageAge: string = '';
  imageUrl: string = '';
}

/** 一个工具调用步骤（联网搜索 / 打开网页 / 页内查找 / 读用户给的链接） */
export class SearchStep {
  kind: string = 'search';       // search | deep_search | read_link | image_search | deep_image_search
  action: string = 'search';     // search | open_page | find_in_page | read_link
  queries: Array<string> = [];
  url: string = '';
  pattern: string = '';
  hits: Array<SearchHit> = [];
  fetchTitle: string = '';
  fetchUrl: string = '';
  errorCode: string = '';
}

/** 用户上传的附件（拿到 file_id 后每轮都带着走） */
export class Attachment {
  fileId: string = '';
  name: string = '';
  sizeBytes: number = 0;
  isImage: boolean = false;
  uploading: boolean = false;
  errorText: string = '';
}

@Observed
export class ChatMessage {
  id: string = '';
  role: string = 'user';           // user | assistant
  text: string = '';
  thinking: string = '';
  thinkingMs: number = 0;
  steps: Array<SearchStep> = [];
  citations: Array<Citation> = [];
  attachments: Array<Attachment> = [];
  rawContentJson: string = '';
  status: string = 'done';         // streaming | done | stopped | refused | error
  errorText: string = '';
  createdAt: number = 0;
}

export class Conversation {
  id: string = '';
  title: string = '新的对话';
  messages: Array<ChatMessage> = [];
  createdAt: number = 0;
  updatedAt: number = 0;
}

export class AppSettings {
  apiKey: string = '';
  baseUrl: string = 'https://api.deepseek.com';
  model: string = 'deepseek-flash';
  /** off | low | medium | high —— 对外服务必须开，自己用建议 high */
  safetyLevel: string = 'high';
  /** 新对话默认打开联网搜索 */
  searchDefault: boolean = true;
  /** 新对话默认打开深度思考 */
  thinkingDefault: boolean = true;
  /** text_recognition（快、便宜）| rich_layout_recognition（能看到图表） */
  parsePurpose: string = 'text_recognition';
  /** 额外系统提示词，可空 */
  systemPrompt: string = '';
}
