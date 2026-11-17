import { audio } from '@kit.AudioKit';
import { fs } from '@kit.CoreFileKit';
import { common } from '@kit.AbilityKit';
import { hilog } from '@kit.PerformanceAnalysisKit';
import { Util } from '../common/Util';

const DOMAIN: number = 0x0000;
const TAG: string = 'hmds';
const SAMPLE_RATE: number = 16000;
const MAX_RECORD_MS: number = 60000;

/**
 * 录音：16kHz 单声道 16bit PCM，录完自己封 WAV 头。
 * 这不是随便选的参数 —— 官方 ASR 只收 16-bit PCM WAV / Ogg Opus，
 * 时长 ≤ 120 秒、体积 ≤ 8 MiB，整段上传，不支持边说边出字。
 */
export class Recorder {
  private capturer: audio.AudioCapturer | null = null;
  private chunks: Array<Uint8Array> = [];
  private recording: boolean = false;
  private startAt: number = 0;

  isRecording(): boolean {
    return this.recording;
  }

  elapsedMs(): number {
    return this.recording ? Util.now() - this.startAt : 0;
  }

  reachedLimit(): boolean {
    return this.recording && Util.now() - this.startAt > MAX_RECORD_MS;
  }

  async start(ctx: common.UIAbilityContext): Promise<boolean> {
    if (this.recording) {
      return true;
    }
    this.chunks = [];
    const streamInfo: audio.AudioStreamInfo = {
      samplingRate: audio.AudioSamplingRate.SAMPLE_RATE_16000,
      channels: audio.AudioChannel.CHANNEL_1,
      sampleFormat: audio.AudioSampleFormat.SAMPLE_FORMAT_S16LE,
      encodingType: audio.AudioEncodingType.ENCODING_TYPE_RAW
    };
    const capturerInfo: audio.AudioCapturerInfo = {
      source: audio.SourceType.SOURCE_TYPE_MIC,
      capturerFlags: 0
    };
    try {
      const capturer = await audio.createAudioCapturer({
        streamInfo: streamInfo,
        capturerInfo: capturerInfo
      });
      capturer.on('readData', (buffer: ArrayBuffer) => {
        if (this.recording) {
          this.chunks.push(new Uint8Array(buffer.slice(0)));
        }
      });
      await capturer.start();
      this.capturer = capturer;
      this.recording = true;
      this.startAt = Util.now();
      return true;
    } catch (err) {
      hilog.error(DOMAIN, TAG, 'start recorder failed: %{public}s', JSON.stringify(err));
      return false;
    }
  }

  /** 停止录音，写 WAV 到应用缓存目录，返回 uri */
  async stopAndSave(ctx: common.UIAbilityContext): Promise<string> {
    if (!this.recording) {
      return '';
    }
    this.recording = false;
    if (this.capturer !== null) {
      try {
        await this.capturer.stop();
        await this.capturer.release();
      } catch (err) {
        hilog.warn(DOMAIN, TAG, 'stop capturer failed');
      }
      this.capturer = null;
    }

    let dataLen = 0;
    for (let i = 0; i < this.chunks.length; i++) {
      dataLen += this.chunks[i].length;
    }
    if (dataLen === 0) {
      return '';
    }

    const wav = this.buildWav(dataLen);
    const path = `${ctx.cacheDir}/voice-${Util.now()}.wav`;
    try {
      const file = fs.openSync(path, fs.OpenMode.CREATE | fs.OpenMode.READ_WRITE);
      fs.writeSync(file.fd, wav);
      fs.closeSync(file);
      return path;
    } catch (err) {
      hilog.error(DOMAIN, TAG, 'write wav failed: %{public}s', JSON.stringify(err));
      return '';
    }
  }

  private buildWav(dataLen: number): ArrayBuffer {
    const buf = new ArrayBuffer(44 + dataLen);
    const view = new DataView(buf);
    this.writeAscii(view, 0, 'RIFF');
    view.setUint32(4, 36 + dataLen, true);
    this.writeAscii(view, 8, 'WAVE');
    this.writeAscii(view, 12, 'fmt ');
    view.setUint32(16, 16, true);
    view.setUint16(20, 1, true);
    view.setUint16(22, 1, true);
    view.setUint32(24, SAMPLE_RATE, true);
    view.setUint32(28, SAMPLE_RATE * 2, true);
    view.setUint16(32, 2, true);
    view.setUint16(34, 16, true);
    this.writeAscii(view, 36, 'data');
    view.setUint32(40, dataLen, true);

    const bytes = new Uint8Array(buf);
    let offset = 44;
    for (let i = 0; i < this.chunks.length; i++) {
      bytes.set(this.chunks[i], offset);
      offset += this.chunks[i].length;
    }
    return buf;
  }

  private writeAscii(view: DataView, offset: number, text: string): void {
    for (let i = 0; i < text.length; i++) {
      view.setUint8(offset + i, text.charCodeAt(i));
    }
  }

  async cancel(): Promise<void> {
    this.recording = false;
    this.chunks = [];
    if (this.capturer !== null) {
      try {
        await this.capturer.stop();
        await this.capturer.release();
      } catch (err) {
        hilog.warn(DOMAIN, TAG, 'cancel capturer failed');
      }
      this.capturer = null;
    }
  }
}
