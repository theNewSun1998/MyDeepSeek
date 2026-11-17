import { common, Want } from '@kit.AbilityKit';
import { hilog } from '@kit.PerformanceAnalysisKit';

const DOMAIN: number = 0x0000;
const TAG: string = 'hmds';

/** 用系统浏览器打开链接（引用来源、答案里的 URL 都走这里） */
export class Linker {
  static open(ctx: common.UIAbilityContext, url: string): void {
    if (url.length === 0) {
      return;
    }
    const want: Want = {
      action: 'ohos.want.action.viewData',
      entities: ['entity.system.browsable'],
      uri: url
    };
    ctx.startAbility(want).catch((err: Error) => {
      hilog.warn(DOMAIN, TAG, 'open url failed: %{public}s', err.message);
    });
  }
}
