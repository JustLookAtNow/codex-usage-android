# WSL 开发接续说明

- 项目位置：`/home/lee/projectSpace/codex-usage-android`，已从 Windows 项目的 android 子目录迁移成独立工程，使用独立 Git 仓库及 `main` 主分支。
- Android 8.0+；Kotlin 2.0.21、AGP 8.7.3、Gradle Wrapper 8.9、JDK 17、compile/target SDK 35、Build Tools 默认 34.0.0。
- 2026-10-24 已在 WSL 完成编译、22 项单元测试/Robolectric 测试、Lint 和调试 APK 构建；没有安卓手机连接，尚未做真机验证，不能视为真机已验证可用。
- 当前目录已通过 `mise use java@temurin-17` 配置 `mise.toml`，实际使用 Temurin 17.0.16+8。后续执行 `mise exec -- ./gradlew testDebugUnitTest lintDebug assembleDebug`；最终结果 `BUILD SUCCESSFUL`，22 项测试零失败，Kotlin 编译无弃用警告。
- Lint 为 0 错误、4 条版本提示：target SDK 35、Core 1.15.0、WorkManager runtime/testing 2.9.1 存在更新版本。未为消除版本提示擅自升级现有 SDK/构建工具链。报告：`app/build/reports/lint-results-debug.html`；测试报告：`app/build/reports/tests/testDebugUnitTest/index.html`。
- APK：`app/build/outputs/apk/debug/app-debug.apk`，包名 `com.codexusage.android`，版本 0.1.0，最低 Android 8.0/API 26；已通过 APK v2 签名验证和 zipalign 检查。
- APK SHA-256：`0a084e3272144f48e8d22ec4a95f3587ec337a9155ef0f3b945b6cf35740ced6`。
- 已按用户要求参考 Flutter 项目的 `upload_apks.sh`，上传到同一个 WebDAV 的 `资料/apks/codex-usage-android-0.1.0-debug-20261024-0a084e32.apk`，HTTP 201；回读远端文件 4,948,474 字节，SHA-256 与本地完全一致。未复制 WebDAV 真实凭据到本项目。
- 本轮修复：窗口边距改用 AndroidX WindowInsetsCompat；Root 超时清理避免被继承的输出管道阻塞，自定义路径只允许普通文件；失败保留重置点数量；HTTP 日期限流等待设上限；小部件额度分行、刷新触控区域 48dp、失败仍显示更新时间；清除配置与调度/小部件更新同步；文件选择器状态跨界面重建保存；凭据清除时尝试删除 Keystore 密钥；缓存及限流同步写入检查磁盘错误；修正字符串资源和 API 31 小部件元数据兼容性。
- 测试仅使用虚构凭据、假 HTTP 响应和测试 AES 密钥，不读取真实 auth.json。加密测试验证 AES-GCM 和私有存储的加解密、随机 IV 和篡改检测；真实 Android Keystore 的密钥持久性仍需真机验收。保留使用 Gradle Wrapper 的 CI 配置，目前按用户要求停用 GitHub Actions；本次只推送源码，不创建 GitHub Release 或上传 APK。
- Root 只读取手机终端中的 Codex auth.json（例如 Termux），不会从 ChatGPT App 数据库提取凭据；查找范围见 README。导入模式不自动续期，Root 模式每次重读源文件。均只支持 ChatGPT 账号登录，API Key 不支持额度查询。
- 两个请求端点：`https://chatgpt.com/backend-api/wham/usage` 和 `https://chatgpt.com/backend-api/wham/rate-limit-reset-credits`；Bearer access_token 与 ChatGPT-Account-Id 请求头。禁止日志输出真实凭据或原始接口响应。
- 真机重点验证：su 授权/拒绝/超时、多路径选择、源文件更新、SAF 文件导入及取消、Keystore 重启解密、桌面缩放与刷新、断网及401/403/429、清除配置后无旧数据、系统省电和重启后的任务调度。
- 第一阶段不做网页登录、token 自动续期、多账号、云同步和应用内代理；网络走手机当前网络/VPN。
- 接下来只在 WSL 工程工作，不回到 Windows 下载 Android 依赖。需要提交时使用中文提交说明。
