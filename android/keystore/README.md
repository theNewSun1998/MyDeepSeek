# 共用 debug 签名

`debug.jks` 是一个**调试用**密钥库（storePassword / keyPassword 均为 `android`，alias 为 `androiddebugkey`）。

放进来是为了让「本机 build.ps1」「GitHub Actions」「dist/ 里的预编译 APK」三者使用同一张证书，
这样它们互相覆盖安装时不会出现 `INSTALL_FAILED_UPDATE_INCOMPATIBLE`。

- 它**不是**发布签名，不能用于上架；
- 真要发布到应用市场，请另建 release keystore，并把密码放在 CI secrets 里，别提交进来。
