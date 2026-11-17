import { hilog } from '@kit.PerformanceAnalysisKit';

const DOMAIN: number = 0x0000;
const TAG: string = 'hmds';

/**
 * 解析服务端 JSON 的小工具。
 * 服务端的块结构（thinking / server_tool_use / web_search_tool_result / citations）
 * 层级深且带扩展字段，这里统一按 Record 取值，避免为每种块写一堆 interface。
 */
export class Json {
  static obj(text: string): Record<string, Object> {
    try {
      if (text.length === 0) {
        return {};
      }
      const parsed = JSON.parse(text) as Record<string, Object>;
      return parsed === null ? {} : parsed;
    } catch (err) {
      hilog.warn(DOMAIN, TAG, 'json parse failed: %{public}s', text.substring(0, 120));
      return {};
    }
  }

  static str(o: Record<string, Object>, key: string): string {
    const v = o[key];
    if (v === undefined || v === null) {
      return '';
    }
    return `${v}`;
  }

  static num(o: Record<string, Object>, key: string): number {
    const v = o[key];
    if (v === undefined || v === null) {
      return 0;
    }
    const n = Number(`${v}`);
    return Number.isNaN(n) ? 0 : n;
  }

  static arr(o: Record<string, Object>, key: string): Array<Object> {
    const v = o[key];
    if (v === undefined || v === null) {
      return [];
    }
    if (Array.isArray(v)) {
      return v as Array<Object>;
    }
    return [];
  }

  static sub(o: Record<string, Object>, key: string): Record<string, Object> {
    const v = o[key];
    if (v === undefined || v === null) {
      return {};
    }
    if (typeof v === 'object' && !Array.isArray(v)) {
      return v as Record<string, Object>;
    }
    return {};
  }

  static strs(o: Record<string, Object>, key: string): Array<string> {
    const raw = Json.arr(o, key);
    const out: Array<string> = [];
    for (let i = 0; i < raw.length; i++) {
      out.push(`${raw[i]}`);
    }
    return out;
  }
}
