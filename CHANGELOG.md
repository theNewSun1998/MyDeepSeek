# 更新记录

## v0.1.0 — 2026-11-17

首个可安装版本（Android）。

**新增**

- Android 客户端（Kotlin + Jetpack Compose），复刻 DeepSeek 手机 App 的对话体验
  - 流式回答、深度思考折叠卡、「已搜索 N 个网页」卡片、参考来源
  - 传文件（PDF / Word / PPT / 图片）走 Files API + `document` block
  - 按住说话的语音输入（AudioRecord → WAV → ASR）
  - 会话列表 / 删除 / 自动标题 / 停止 / 重新生成 / 复制
  - 设置：API Key、模型名、接口地址、安全等级、默认开关、带版面解析、测试连接查余额
- HarmonyOS NEXT 客户端（ArkTS + ArkUI 原生），功能对齐
- 预编译 APK：`dist/hmdeepseek-debug.apk`
- 云构建工作流：`.github/workflows/build-apk.yml`（打 `v*` tag 自动发 Release）
- 本机一键打包脚本：`android/build.ps1`

**修复（相对最初内部版本）**

- 按住说话按钮改为麦克风圆形图标，录音时变品牌蓝、输入框提示「正在录音，松开结束…」
- 语音转写 / 点击建议词后光标停在文字末尾（输入框改为托管 `TextFieldValue`）
- 参考来源改为折叠展示，与「已搜索 N 个网页」一致
- `build.ps1` 加 UTF-8 BOM，避免被 GBK 解析导致语法错误

**已知问题**

- 未做真机回归测试的项：TTS 朗读（未实现）、相册选图（当前按文档解析）
- 卓易通等兼容层里麦克风可能不可用
