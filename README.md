# hmdeepseek — DeepSeek 第三方客户端

用 DeepSeek 官方「App 全套能力 API」自建的客户端：**用你自己的 API Key，不被官方 App 限额**。
功能对着手机版 App 做——流式回答、深度思考折叠、联网搜索卡片、引用来源、传文件、按住说话、会话管理。

- 📱 **Android**（Kotlin + Jetpack Compose）：主线，APK 直接装，也能在鸿蒙手机里用卓易通跑
- 🐳 **HarmonyOS NEXT**（ArkTS + ArkUI 原生）：备选，需 DevEco Studio + 华为账号签名
- 🗄️ **archive/**：早期 ArkWeb 网页套壳实现，已弃用，仅留档

> 不是官方客户端，也不分发任何密钥。所有请求都从你的设备直连 `api.deepseek.com`。

---

## 下载安装


**现成 APK：[`dist/hmdeepseek-debug.apk`](dist/hmdeepseek-debug.apk)**（9.62 MB）

| 项 | 值 |
| --- | --- |
| 包名 | `com.hmdeepseek.app` |
| 应用名 | DeepSeek |
| 版本 | 0.1.0 |
| 适用 | Android 8.0+（`minSdk 26`），`targetSdk 34` |
| 签名 | debug（`CN=Android Debug`） |
| SHA256 | `82B0756F2370A82E1459BF5DACAD013C8FFAE07C53D46EFFB39BC292F32B68DD` |

安装方式任选：

1. 手机浏览器打开本仓库的 `dist/hmdeepseek-debug.apk` → 下载 → 允许「未知来源安装」；
2. 数据线 `adb install hmdeepseek-debug.apk`；
3. 鸿蒙手机：把 APK 导入**卓易通**再安装（见下方注意事项）。

和官方 DeepSeek App 包名不同，**可以共存**，不会互相覆盖。

---

## 功能

| DeepSeek App 里的功能 | 实现 |
| --- | --- |
| 流式回答 | `POST /anthropic/v1/messages`，自己解 SSE（含 `: keep-alive`） |
| 深度思考折叠卡 | `thinking` block，「已深度思考 · 用时 N 秒」 |
| 「已搜索 N 个网页」卡片 | `server_tool_use` / `web_search_tool_result`，展开可见查询词、站点、摘要，点条目开浏览器 |
| 参考来源 | `citations` → 折叠列表，默认收起，展开后逐条可点 |
| 读用户给的链接 | `deepseek_read_url` |
| 传文件（PDF/Word/PPT/图） | `POST /files` 拿 `file_id` → `document` block（自动带 beta 头） |
| 语音输入 | 麦克风圆形按钮按住说话，`AudioRecord` 录 16k PCM → 封 WAV → `POST /v1/tools/asr`，转写结果插入光标末尾 |
| 会话列表 / 删除 / 自动标题 | 本地 SharedPreferences + 服务端 `title` |
| 停止 / 重新生成 / 复制 | 断连接 / 丢弃末条助手消息 / 剪贴板 |
| 拒答提示 | `stop_reason: refusal` |
| 设置 | Key、模型名、接口地址、安全等级、默认开关、带版面解析、测试连接查余额 |

---

## 用它

1. 首页顶部提示「还没填 API Key」→ 点进去 → 粘贴 <https://platform.deepseek.com/api_keys> 生成的 `sk-...`。
2. 点 **测试连接 / 查余额**：显示 `连接成功，余额 xx CNY` 就通了（一步区分是 Key 问题还是网络问题）。
3. 回首页 → 点底部「给 DeepSeek 发送消息」→ 问一个必须联网的问题，比如「今天有什么 AI 新闻？给我来源」。
   正常会依次出现：灰色「已深度思考」→「已搜索 N 个网页」→ 流式正文 → 底部「参考来源」。

| 想干什么 | 怎么做 |
| --- | --- |
| 联网搜索 / 深度思考开关 | 输入框上方两个胶囊，点亮=开（关了只保留「读链接」能力） |
| 传文件 | 输入框上方 **＋** → 选文件 → 等小标签从「解析中…」变成文件名再发送（PDF 要 1~2 分钟） |
| 语音输入 | 按住麦克风圆形按钮说话，松手自动转文字（≤60 秒） |
| 看思考 / 看搜索明细 / 看来源 | 分别点「已深度思考」「已搜索 N 个网页」「参考来源」展开 |
| 停止 / 重新生成 / 复制 | 回答过程中右侧 ■；回答下方「重新生成」「复制」 |
| 历史会话 | 左上 ‹ 返回首页；条目右侧 ⋯ 可删除 |
| 设置 | 首页右上 ⚙ |

---

## 项目结构

```
hmdeepseek/
├── android/                        # Android 客户端（主线）
│   ├── app/src/main/java/com/hmdeepseek/app/
│   │   ├── MainActivity.kt         # 极简导航：Home / Chat / Settings
│   │   ├── ChatViewModel.kt        # 状态 + 录音 + 上传 + 持久化入口
│   │   ├── model/Types.kt          # 数据模型 + JSON 编解码
│   │   ├── data/Store.kt           # SharedPreferences 存设置与会话
│   │   ├── net/DeepSeekApi.kt      # HTTP：SSE 流、multipart 上传、ASR、查余额
│   │   ├── net/ChatSession.kt      # 一轮对话状态机：拼请求 → 解块 → 回传原样 JSON
│   │   ├── audio/WavRecorder.kt    # AudioRecord → 16bit PCM WAV
│   │   └── ui/                     # Compose：聊天页、消息卡、Markdown、输入区、设置
│   ├── res/…                       # 图标（麦克风矢量图）等资源
│   └── build.ps1                   # 本机一键打包（用 .build/ 里的工具链）
├── entry/ AppScope/ …              # HarmonyOS NEXT 客户端（ArkTS）
├── archive/arkweb-shell/           # 早期 ArkWeb 套壳实现（仅留档）
├── dist/hmdeepseek-debug.apk       # 预编译安装包
└── .github/workflows/build-apk.yml # 云端打包（Actions）
```

---

## 构建

### 已经打好包（推荐）

直接下 [`dist/hmdeepseek-debug.apk`](dist/hmdeepseek-debug.apk)。

### 本机重新打包

需要 JDK 17 + Android SDK 34 + Gradle 8.7。仓库里提供了一个本地工具链目录 `.build/`（不进版本库，约 1.2 GB），准备好后：

```powershell
pwsh -File android/build.ps1
```

产物：`android/app/build/outputs/apk/debug/app-debug.apk`。

<details>
<summary>手动等价命令（注入式签名，绕开 <code>~/.android</code> 无写权限的环境）</summary>

```powershell
$env:JAVA_HOME = "$PWD\.build\jdk\jdk-17.0.21+9"
$env:ANDROID_HOME = "$PWD\.build\sdk"
cd android
..\.build\gradle\gradle-8.7\bin\gradle.bat assembleDebug `
  -Pandroid.injected.signing.store.file="$PWD\..\.build\keystore\debug.jks" `
  -Pandroid.injected.signing.store.password=android `
  -Pandroid.injected.signing.key.alias=androiddebugkey `
  -Pandroid.injected.signing.key.password=android
```
</details>

### Android Studio

`File → Open` 选 `android/` 目录 → Sync → Run。首次会下载 Gradle 8.7 与依赖（约 1~2 GB）。
> 仓库里只有 `gradle-wrapper.properties`，没有 `gradle-wrapper.jar`；Android Studio 按 properties 里的地址自己下 Gradle，不受影响。想在命令行跑 `./gradlew` 才需要补一次 `gradle wrapper`。

### 云端打包（不装任何东西）

推送到 GitHub 后，`Actions → build-apk → Run workflow` 即可；给仓库打 `v*` tag 会把 APK 自动挂到 Releases。

### 鸿蒙版

装 DevEco Studio → 打开仓库根目录 → `Project Structure` 勾自动生成签名（需华为开发者账号）→ Run。
`compatibleSdkVersion` 为 `5.0.0(12)`，需要 HarmonyOS NEXT（5.x/6.x）设备。

---

## 协议要点（踩过的坑）

- **走 Messages 协议**（`POST /anthropic/v1/messages`，头 `x-api-key`）：只有它把思考、检索步骤、抓页结果、引用返回成结构化 block，正好对应 App 的 UI。
- **历史必须原样回传**：助手消息 `content` 里的 `signature` 压着读过的网页内容。客户端把服务端返回的块原样保存（`rawContentJson`），下一轮直接拼回去；任何「顺手过滤一下」都会让模型失忆。
- **开了工具就别指望调温度**：声明 `deepseek_web_search` / `deepseek_read_url`，或开 `safety_check`、`title`，服务端接管采样，`temperature`、`top_p` 一律忽略。
- **只有 `deepseek-flash` 支持全套工具**，V4-Pro 还没跟上，默认锁死模型名。
- **附件两步走**：`POST /files`（multipart，字段顺序 `purpose` 再 `file`）拿 `file_id` → 作为 `document` block 进对话；Messages 需要 `anthropic-beta: files-api-2025-04-14`。
- **ASR 限制**：16-bit PCM WAV 或 Ogg Opus，≤120 秒、≤8 MiB，整段上传、不支持边说边出字。
- **省钱**：搜索与抓页各约 \$0.75/千次，文件解析 \$0.225/千页，深度搜索一轮可能调 20 次工具。自用很便宜，公开服务务必给每轮搜索次数设上限。

---

## 已知缺口

- **TTS 朗读**：官方 TTS 支持 WebSocket 边生成边合成，两个平台都还没接。
- **相册选图**：现在图片走文件接口当文档解析，不是真正的 `image` block。
- **Markdown 边界**：标题/代码块/列表/引用/粗斜体/行内代码/链接都有，表格、Mermaid、LaTeX 没做。
- **暗色主题**：目前只有浅色。
- **长会话性能**：每条 delta 刷新最后一条消息，几千条消息的长会话需要懒加载/分页。
- **多端同步**：会话只在本地。

## 卓易通 / 兼容层注意

- **麦克风**：容器常拦录音 → 按住说话可能无反应或识别为空，不影响其它功能。
- **文件选择器**：部分容器对 `ACTION_OPEN_DOCUMENT` 支持不完整。
- **后台流式**：切后台久了容器可能挂起进程，正在生成的回答会中断（回来点「重新生成」）。

## 排错

| 现象 | 处理 |
| --- | --- |
| 401 | Key 错或过期，设置页点「测试连接」确认 |
| 402 / 429 | 余额不足 / 并发超限，等几秒重发 |
| 400 且提到 documents | 附件太多太长（单请求 ≤20 个文档、文本合计 8 MiB） |
| 只有文字没有搜索卡 | 联网开关关了，或问题本身不需要联网（模型自己决定搜不搜） |
| 语音识别为空 | 环境吵、录得太短，或容器拦了麦克风 |
| 装机报 `INSTALL_FAILED_UPDATE_INCOMPATIBLE` | 手机上先卸载旧包（签名不同） |
| Gradle 报 `native-platform.dll` 加载失败 | 用独立 `GRADLE_USER_HOME` 与 `-Djava.io.tmpdir`（`build.ps1` 已处理） |
| Gradle 报 `~/.android/debug.keystore.lock` 拒绝访问 | 用注入式签名参数指定自己的 keystore（`build.ps1` 已处理） |

## 许可

[MIT](LICENSE)。代码可自由使用，但请注意：

- 「DeepSeek」商标与官方服务归杭州深度求索（DeepSeek）所有，本项目与官方无关；
- 对外发布或提供服务时，需自行完成内容安全审核、大模型服务备案与 AI 生成内容标识，并开启官方要求的 `safety_check`；
- 不要把 API Key 打进分发包。
