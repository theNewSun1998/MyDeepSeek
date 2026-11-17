import { util } from '@kit.ArkTS';

export class Util {
  static uuid(): string {
    return util.generateRandomUUID(true);
  }

  static now(): number {
    return new Date().getTime();
  }

  /** 会话列表里的时间：今天显示时:分，昨天显示"昨天"，更早显示月/日 */
  static listTime(ts: number): string {
    const d = new Date(ts);
    const now = new Date();
    const sameDay = d.getFullYear() === now.getFullYear() && d.getMonth() === now.getMonth() &&
      d.getDate() === now.getDate();
    if (sameDay) {
      return Util.pad(d.getHours()) + ':' + Util.pad(d.getMinutes());
    }
    const yesterday = new Date(now.getTime() - 86400000);
    if (d.getFullYear() === yesterday.getFullYear() && d.getMonth() === yesterday.getMonth() &&
      d.getDate() === yesterday.getDate()) {
      return '昨天';
    }
    if (d.getFullYear() === now.getFullYear()) {
      return (d.getMonth() + 1) + '月' + d.getDate() + '日';
    }
    return d.getFullYear() + '/' + (d.getMonth() + 1) + '/' + d.getDate();
  }

  static clock(ts: number): string {
    const d = new Date(ts);
    return Util.pad(d.getHours()) + ':' + Util.pad(d.getMinutes());
  }

  static pad(n: number): string {
    return n < 10 ? '0' + n : `${n}`;
  }

  static humanSize(bytes: number): string {
    if (bytes < 1024) {
      return `${bytes} B`;
    }
    if (bytes < 1024 * 1024) {
      return `${(bytes / 1024).toFixed(1)} KB`;
    }
    return `${(bytes / 1024 / 1024).toFixed(1)} MB`;
  }

  /** 从文件路径或 URL 猜个文件名 */
  static fileNameFromPath(path: string): string {
    let name = path;
    const slash = name.lastIndexOf('/');
    if (slash >= 0) {
      name = name.substring(slash + 1);
    }
    const q = name.indexOf('?');
    if (q > 0) {
      name = name.substring(0, q);
    }
    return name.length > 0 ? name : 'file';
  }

  static isImageName(name: string): boolean {
    const lower = name.toLowerCase();
    return lower.endsWith('.png') || lower.endsWith('.jpg') || lower.endsWith('.jpeg') ||
      lower.endsWith('.webp') || lower.endsWith('.gif') || lower.endsWith('.bmp') ||
      lower.endsWith('.tif') || lower.endsWith('.tiff') || lower.endsWith('.heic');
  }

  /** 会话标题：取第一句话，太长就截断 */
  static titleFromText(text: string): string {
    const cleaned = text.replace(/\s+/g, ' ').trim();
    if (cleaned.length === 0) {
      return '新的对话';
    }
    return cleaned.length > 24 ? cleaned.substring(0, 24) + '…' : cleaned;
  }
}
