import { webview } from '@kit.ArkWeb';
import { abilityAccessCtrl, common, Permissions } from '@kit.AbilityKit';
import { BusinessError, request } from '@kit.BasicServicesKit';
import { picker } from '@kit.CoreFileKit';
import { promptAction } from '@kit.ArkUI';
import { hilog } from '@kit.PerformanceAnalysisKit';
import { Config } from '../common/Config';

const DOMAIN: number = 0x0000;
const TAG: string = 'hmds';

/**
 * DeepSeek 鸿蒙客户端外壳（ArkWeb 套壳方案）。
 *
 * 页面本体是官方开源的 chat-demo（MIT）：联网搜索、深度搜索、读链接、传文件、
 * 自动起标题、语音输入/朗读、导入官网聊天记录都在网页里实现。这里只负责把它
 * 变成一台鸿蒙 App 该有的样子：
 *   - rawfile 里有本地前端就离线加载，失败/超时自动回退在线版
 *   - 网页里点上传 → 拉起系统文件选择器
 *   - 网页里要录音 → 先申请系统麦克风权限再授予 Web
 *   - 网页触发下载 → 交给系统下载器
 *   - 返回键 → 网页后退，退无可退才退出 App
 */
@Entry
@Component
struct Index {
  private controller: webview.WebviewController = new webview.WebviewController();
  @State showSplash: boolean = true;
  private initialUrl: string = Config.REMOTE_URL;
  private fellBack: boolean = false;

  aboutToAppear(): void {
    if (Config.PREFER_LOCAL && this.detectLocalBundle()) {
      this.initialUrl = Config.LOCAL_URL;
      // resource:// 加载本地前端属于实验路径，超时没渲染出来就切在线版，
      // 保证 App 不会白屏。
      setTimeout(() => {
        if (this.showSplash) {
          hilog.warn(DOMAIN, TAG, 'local bundle not rendered in time, fallback');
          this.switchToRemote();
        }
      }, Config.FALLBACK_TIMEOUT_MS);
    } else {
      this.initialUrl = Config.REMOTE_URL;
    }
  }

  /** 判断 rawfile/web 里是否已经打进官方前端产物。 */
  private detectLocalBundle(): boolean {
    try {
      const ctx = getContext(this) as common.UIAbilityContext;
      ctx.resourceManager.getRawFileContentSync(Config.LOCAL_ENTRY);
      return true;
    } catch (err) {
      return false;
    }
  }

  /** 本地版没起来 → 换在线版（只做一次，避免来回跳）。 */
  private switchToRemote(): void {
    if (this.fellBack) {
      return;
    }
    this.fellBack = true;
    try {
      this.controller.loadUrl(Config.REMOTE_URL);
    } catch (err) {
      const e = err as BusinessError;
      hilog.error(DOMAIN, TAG, 'loadUrl remote failed: %{public}d', e.code);
      this.showSplash = false;
    }
  }

  /** 网页里点了「语音输入」时，先把系统麦克风权限要到手。 */
  private async ensureMicPermission(): Promise<void> {
    const ctx = getContext(this) as common.UIAbilityContext;
    const atManager = abilityAccessCtrl.createAtManager();
    const perms: Array<Permissions> = ['ohos.permission.MICROPHONE'];
    const result = await atManager.requestPermissionsFromUser(ctx, perms);
    if (result.authResults.length === 0 || result.authResults[0] !== 0) {
      throw new Error('microphone permission denied');
    }
  }

  /** 网页触发下载（比如导出聊天记录）→ 交给系统下载器存到应用缓存目录。 */
  private handleDownload(url: string): void {
    const ctx = getContext(this) as common.UIAbilityContext;
    let name: string = url.substring(url.lastIndexOf('/') + 1);
    const q: number = name.indexOf('?');
    if (q > 0) {
      name = name.substring(0, q);
    }
    if (name.length === 0) {
      name = 'deepseek-download.bin';
    }
    const filePath: string = `${ctx.cacheDir}/${name}`;
    request.downloadFile(ctx, { url: url, filePath: filePath })
      .then((task) => {
        task.on('complete', () => {
          promptAction.showToast({ message: `已保存：${name}`, duration: 3000 });
        });
        task.on('fail', () => {
          promptAction.showToast({ message: '下载失败', duration: 3000 });
        });
      })
      .catch((err: BusinessError) => {
        hilog.error(DOMAIN, TAG, 'downloadFile failed: %{public}d', err.code);
        promptAction.showToast({ message: '下载失败', duration: 3000 });
      });
  }

  build() {
    Stack() {
      Web({ src: this.initialUrl, controller: this.controller })
        .width('100%')
        .height('100%')
        .domStorageAccess(true)
        .databaseAccess(true)
        .fileAccess(true)
        .imageAccess(true)
        .onlineImageAccess(true)
        .javaScriptAccess(true)
        .mixedMode(MixedMode.All)
        .zoomAccess(false)
        .horizontalScrollBarAccess(false)
        .verticalScrollBarAccess(false)
        .onPageBegin((event) => {
          if (!event) {
            return;
          }
          this.showSplash = true;
          hilog.info(DOMAIN, TAG, 'page begin: %{public}s', event.url);
        })
        .onPageEnd((event) => {
          if (!event) {
            return;
          }
          this.showSplash = false;
          hilog.info(DOMAIN, TAG, 'page end: %{public}s', event.url);
        })
        .onErrorReceive((event) => {
          if (!event) {
            return;
          }
          if (!event.request.isMainFrame()) {
            return;
          }
          hilog.error(DOMAIN, TAG, 'load error %{public}d: %{public}s',
            event.error.getErrorCode(), event.error.getErrorInfo());
          if (this.initialUrl === Config.LOCAL_URL && !this.fellBack) {
            this.switchToRemote();
          } else {
            this.showSplash = false;
          }
        })
        .onConsole((event) => {
          if (!event) {
            return false;
          }
          hilog.debug(DOMAIN, TAG, 'console: %{public}s', event.message.getMessage());
          return false;
        })
        .onPermissionRequest((event) => {
          if (!event) {
            return;
          }
          const resources: Array<string> = event.request.getAccessibleResource();
          if (resources.indexOf('ohos.permission.MICROPHONE') < 0) {
            event.request.grant(resources);
            return;
          }
          this.ensureMicPermission()
            .then(() => {
              event.request.grant(resources);
            })
            .catch((err: Error) => {
              hilog.warn(DOMAIN, TAG, 'mic denied: %{public}s', err.message);
              event.request.deny();
              promptAction.showToast({ message: '需要麦克风权限才能语音输入', duration: 3000 });
            });
        })
        .onShowFileSelector((event) => {
          if (!event) {
            return false;
          }
          const ctx = getContext(this) as common.UIAbilityContext;
          const options = new picker.DocumentSelectOptions();
          options.maxSelectNumber = 10;
          const documentPicker = new picker.DocumentViewPicker(ctx);
          documentPicker.select(options)
            .then((uris: Array<string>) => {
              event.result.handleFileList(uris);
            })
            .catch((err: BusinessError) => {
              hilog.error(DOMAIN, TAG, 'file picker failed: %{public}s', JSON.stringify(err));
              event.result.handleFileList([]);
            });
          return true;
        })
        .onDownloadStart((event) => {
          if (!event) {
            return;
          }
          this.handleDownload(event.url);
        })
    }
    .width('100%')
    .height('100%')
    .backgroundColor('#FFFFFF')
    .alignContent(Alignment.Center)

    if (this.showSplash) {
      Column({ space: 12 }) {
        LoadingProgress()
          .width(48)
          .height(48)
          .color('#4D6BFE')
        Text('DeepSeek')
          .fontSize(14)
          .fontColor('#8A8F98')
      }
      .width('100%')
      .height('100%')
      .justifyContent(FlexAlign.Center)
      .backgroundColor('#FFFFFF')
    }
  }

  onBackPress(): boolean {
    if (this.controller.accessBackward()) {
      this.controller.backward();
      return true;
    }
    return false;
  }
}
