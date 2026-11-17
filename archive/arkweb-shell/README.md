# 归档：ArkWeb 网页套壳版（v0.1）

第一版实现：用 ArkWeb 加载官方 `chat-demo` 网页，套一个鸿蒙壳。**已经不是主线**，
主线是仓库根的 ArkUI 原生实现（见根目录 README）。

留在这里的原因：如果你只想尽快有个能用的壳、或者想对照当时怎么处理
文件选择 / 麦克风授权 / 下载接管 / 失败回退，这些代码还有参考价值。

注意：`Index.ets` 里 `import { Config } from '../common/Config'` 指向的
`entry/src/main/ets/common/Config.ets` 已经被删除（改为原生后不再需要），
要把这份代码跑起来需要自己补回那个文件，其中应有：

```ts
export class Config {
  static readonly REMOTE_URL: string = 'https://cdn.deepseek.com/chat-demo';
  static readonly LOCAL_ENTRY: string = 'web/index.html';
  static readonly LOCAL_URL: string = 'resource://rawfile/web/index.html';
  static readonly PREFER_LOCAL: boolean = true;
  static readonly FALLBACK_TIMEOUT_MS: number = 12000;
  static readonly WEB_DEBUG: boolean = true;
}
```

`prepare-demo.ps1` 是当时用来把官方前端构建进 `rawfile/web` 的脚本。
