# WSL 开发接续说明

- 项目位置：`/home/lee/projectSpace/codex-usage-android`，已从 Windows 项目的 android 子目录迁移成独立工程，使用独立 Git 仓库及 `main` 主分支。
- Android 8.0+；Kotlin 2.0.21、AGP 8.7.3、Gradle Wrapper 8.9、JDK 17、compile/target SDK 35、Build Tools 默认 34.0.0。
- 2026-10-24 已在 WSL 完成编译、29 项单元测试/Robolectric 测试、Lint 和调试 APK 构建。最新版已覆盖安装到 Root 手机；应用内已显示 ChatGPT 认证文件来源及成功查询结果，用户也已人工确认能够取得凭据并刷新额度。
- `mise.toml` 指定 JDK 17；本轮构建通过临时 `JAVA_HOME` 使用本机 `/home/lee/.local/share/mise/installs/java/zulu-17.60.17.0`。后续可使用已配置的 JDK 17 执行 `./gradlew testDebugUnitTest lintDebug assembleDebug`；最终结果 `BUILD SUCCESSFUL`，29 项测试零失败。
- Lint 为 0 错误、4 条版本提示：target SDK 35、Core 1.15.0、WorkManager runtime/testing 2.9.1 存在更新版本。未为消除版本提示擅自升级现有 SDK/构建工具链。报告：`app/build/reports/lint-results-debug.html`；测试报告：`app/build/reports/tests/testDebugUnitTest/index.html`。
- APK：`app/build/outputs/apk/debug/app-debug.apk`，包名 `com.codexusage.android`，版本 0.1.0，最低 Android 8.0/API 26；已通过 APK v2 签名验证和 zipalign 检查。
- 当前 APK：2,836,955 字节，SHA-256 为 `cb6b657932ff8288ab9851230217c2d9fe44dbf47eb87660e011c38221bf3ced`。
- 历史上传：此前已按用户要求参考 Flutter 项目的 `upload_apks.sh`，上传到同一个 WebDAV 的 `资料/apks/codex-usage-android-0.1.0-debug-20261024-0a084e32.apk`，HTTP 201；回读远端文件 4,948,474 字节，SHA-256 与当时的本地文件一致。此次新版未重新上传。未复制 WebDAV 真实凭据到本项目。
- 既有修复：窗口边距改用 AndroidX WindowInsetsCompat；Root 超时清理避免被继承的输出管道阻塞，自定义路径只允许普通文件；失败保留重置点数量；HTTP 日期限流等待设上限；小部件额度分行、刷新触控区域 48dp、失败仍显示更新时间；清除配置与调度/小部件更新同步；文件选择器状态跨界面重建保存；凭据清除时尝试删除 Keystore 密钥；缓存及限流同步写入检查磁盘错误；修正字符串资源和 API 31 小部件元数据兼容性。
- 自动化测试仅使用虚构凭据、假 HTTP 响应和测试 AES 密钥，不读取真实认证文件。加密测试验证 AES-GCM 和私有存储的加解密、随机 IV 和篡改检测；真实 Android Keystore 的密钥持久性仍需真机验收。保留使用 Gradle Wrapper 的 CI 配置，目前按用户要求停用 GitHub Actions；本轮按用户要求提交本地源码和文档，不推送，不创建 GitHub Release 或上传 APK。
- 用户已明确授权读取手机 ChatGPT 认证文件及测试额度请求。Root 默认来源改为 `/data/user/0/com.openai.chatgpt/shared_prefs/com.auth0.authentication.storage.xml`，并结合 `files/datastore/global_session.json` 选取当前 `sessions.*` 凭据，禁止回退旧 `users.*` 缓存；保留终端 Codex auth.json、自定义路径和手工导入。每次重读文件，检查会话一致性、源到期时间和 JWT 到期时间；不保存或刷新 refresh token、不修改 ChatGPT 数据。
- Root 命令使用 Magisk/KernelSU 的 `su -M -c` 在全局挂载命名空间执行，支持访问其他应用数据；在本机 KernelSU 中已验证。新增 7 项 ChatGPT 解析测试覆盖当前会话选择、旧凭据隔离、源 token 更新、退出登录、到期校验、错误 JWT、恶意 XML 及默认路径。
- 2026-10-24 真机只读测试：ChatGPT 当前会话 token 的存储类型为 DPoP，但现有 Bearer + ChatGPT-Account-Id 方式在 `usage` 与 `rate-limit-reset-credits` 均得到 HTTP 200，成功返回额度和重置点。真实凭据只经内存和 stdin 传递，未写入项目、日志或测试数据。
- 两个请求端点：`https://chatgpt.com/backend-api/wham/usage` 和 `https://chatgpt.com/backend-api/wham/rate-limit-reset-credits`；Bearer access_token 与 ChatGPT-Account-Id 请求头。禁止日志输出真实凭据或原始接口响应。
- 真机待补充验证：su 拒绝/超时、多路径选择、源文件更新、SAF 文件导入及取消、Keystore 重启解密、桌面缩放与刷新、断网及401/403/429、清除配置后无旧数据、系统省电和重启后的任务调度。
- 第一阶段不做网页登录、token 自动续期、多账号、云同步和应用内代理；网络走手机当前网络/VPN。
- 接下来只在 WSL 工程工作，不回到 Windows 下载 Android 依赖。需要提交时使用中文提交说明。
