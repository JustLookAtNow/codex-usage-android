# Codex 余量 · Android（第一阶段）

原生 Kotlin 应用和 4×2 桌面小部件，支持 Android 8.0（API 26）及以上。Android 工程独立于 Windows/Python 程序。

## 已实现

- 显示 5h、Weekly 和接口提供的附加额度、套餐、重置时间、重置点数量。
- Root 默认读取 ChatGPT 安卓应用的当前登录，也支持终端的 Codex 凭据和绝对路径；每次查询重新读取源文件。
- 通过系统文件选择器导入 `auth.json`，支持从电脑复制的文件。只保存查询所需的 access token 和 account ID，使用 Android Keystore / AES-GCM 加密。
- 桌面小部件显示主要额度、重置时间和更新时间；点击刷新，点击主体打开应用。
- WorkManager 每 15 分钟安排后台查询；支持手动刷新，重启后由 WorkManager 恢复调度。系统省电策略可能延迟执行。
- 查询失败保留旧数据并显示错误；遵守接口 `Retry-After`，手动刷新不绕过限流。
- 清除凭据时停止后台任务并删除额度缓存；禁用备份和设备迁移，不记录凭据或原始响应。

## 配置凭据

### Root 自动获取

1. 在手机上的官方 ChatGPT 应用（`com.openai.chatgpt`）中登录账号。
2. 打开应用，点击“Root 自动查找”，在 Magisk / KernelSU 等权限管理器中允许本应用使用 Root。
3. 默认自动选择 ChatGPT 认证文件。若未安装 ChatGPT，则继续查找终端中的 Codex `auth.json`；多个终端文件需要选择。也可通过“Root 自定义路径”指定来源。

自动查找仅检查：

```text
/data/user/0/com.openai.chatgpt/shared_prefs/com.auth0.authentication.storage.xml
/data/data/*/files/home/.codex/auth.json
/data/user/0/*/files/home/.codex/auth.json
/data/data/*/files/.codex/auth.json
/data/local/tmp/.codex/auth.json
```

ChatGPT 来源还会只读检查同目录应用数据下的 `files/datastore/global_session.json`，按当前登录会话选择 `sessions.*.com.auth0.access_token`，从 JWT 的 `https://api.openai.com/auth` 对象中的 `chatgpt_account_id` 字段取得额度账号。不会选择旧的 `users.*` 缓存或其他登录会话。读取前后确认会话索引一致，并检查 XML 和 JWT 中的到期时间。Root 配置只保存路径，不复制 refresh token、ID token 或密钥。

`/data/data/com.openai.chatgpt/` 与 `/data/user/0/com.openai.chatgpt/` 在已验证的手机上是同一数据目录，两种路径均支持。终端来源覆盖 Termux 常见位置 `/data/data/com.termux/files/home/.codex/auth.json`。不递归扫描手机。不同终端和自定义 `CODEX_HOME` 使用自定义路径。

Root 源文件只读，不修改原始登录，也不主动续期。ChatGPT 凭据失效时请打开 ChatGPT 更新登录，之后本应用刷新会重读；终端来源则在相应终端更新登录。自动后台读取需要权限管理器持久允许 Root；授权拒绝或超时会显示错误。认证文件和会话索引属于 ChatGPT 内部格式，后续版本可能变化，解析失败时可使用原有 `auth.json` 方式。

Root 使用 Magisk/KernelSU 的 `su -M -c` 在全局挂载命名空间读取，支持跨应用数据访问；已在 KernelSU 手机验证默认来源和应用内额度刷新。

### 手工导入

点击“手工导入 auth.json”，通过系统文件管理器选择手机上的文件。Windows 上通常来自 `%USERPROFILE%\.codex\auth.json`。不需要 Root 或全盘存储权限，API Key 登录不支持订阅额度查询。

导入模式不会追踪原文件更新，凭据失效后重新导入即可。请妥善处理复制到手机公共存储的原文件；应用只在自己的私有存储中保存加密的必要字段。

## 小部件与网络

点击“添加桌面小部件”，也可长按桌面，从小部件列表选择“Codex 余量”。所有实例共用一个账号和缓存。时间使用手机本地时区，显示重置时刻而非秒级倒计时。

联网采用手机当前网络，包含 VPN。第一阶段不提供应用内 HTTP/SOCKS 代理、网页登录、自动刷新 token、多账号或云端同步。应用只连接 `https://chatgpt.com/backend-api/wham/`，保持 TLS 校验并拒绝重定向。该客户端后端接口可能变化。

## 构建与测试

2026-10-24 已连接 Root 手机验证 ChatGPT 当前会话 token：沿用应用的 Bearer / ChatGPT-Account-Id 请求头，`usage` 和 `rate-limit-reset-credits` 均返回 HTTP 200。文件记录的 token 类型为 DPoP，此次验证的是这两个额度接口接受现有请求方式，不代表其他接口也支持。

新版已通过 29 项单元测试/Robolectric 测试、Lint 和 APK 构建，并通过 APK v2 签名及 zipalign 检查。已安装到连接的手机；用户人工确认默认读取 ChatGPT 凭据并刷新额度成功。其他厂商设备、小部件和后台省电调度仍需分别验证。

Lint 无错误，保留 4 条版本升级提示（target SDK 35、Core 1.15.0、WorkManager runtime/testing 2.9.1）。窗口边距使用 AndroidX WindowInsetsCompat，应用不再调用已弃用的窗口边距 API。完整报告位于 `app/build/reports/lint-results-debug.html` 和 `app/build/reports/tests/testDebugUnitTest/index.html`。

需要 JDK 17、Android SDK Platform 35、Build Tools 34.0.0。用 Android Studio 打开本目录，或配置 `ANDROID_HOME` / `local.properties` 后执行：

本项目在 WSL 开发，目录内 `mise.toml` 固定使用 Java 17：

```sh
mise trust
mise install
mise exec -- java -version
mise exec -- ./gradlew testDebugUnitTest lintDebug assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Gradle Wrapper 固定为 8.9，Android Gradle Plugin 为 8.7.3。首次构建需要在 WSL 下载缺少的依赖，无需在 Windows 安装或下载 Android SDK。调试 APK 位于 `app/build/outputs/apk/debug/app-debug.apk`。

仓库保留 GitHub Actions 的 `Android` 工作流配置，目前按要求停用，不会自动运行。后续启用后可执行单元测试、Lint 和 APK 构建，产物位于 `CodexUsage-Android-debug` 附件。Debug APK 用于自用验证，正式分发前需配置独立签名。

单元测试覆盖凭据格式、大小限制、敏感字段隐藏、Root 路径 shell 转义、Root 拒绝/超时及管道清理、额度容错解析、附加额度隔离和限流等待。ChatGPT 解析测试使用虚构 XML、会话索引及 JWT，验证当前会话选择、旧凭据隔离、源 token 更新、退出登录、过期、缺失字段和恶意 XML。Robolectric 测试使用虚构凭据和本地假响应，检查每次刷新重新加载源凭据、401/403/429、失败保留缓存、文件选择器取消、无凭据不调度、周期任务唯一性及取消、小部件失败时显示更新时间和清除旧数据。加密测试只替换密钥获取来源，实际执行 AES-GCM 加解密、存储、随机 IV 和篡改检测；真实 Keystore 重启后解密仍需真机测试。它们不连接真实账号，也不替代真机验证。

真机验收：

1. 不授予 Root 时显示可理解的错误，界面保持可操作；无凭据时小部件引导打开应用。
2. Root 单文件自动选择、多文件选择、自定义路径、拒绝授权、读取超时；更新源文件后刷新使用新凭据。
3. 通过文件选择器取消、导入合法文件、无效 JSON、API Key、缺字段及超过 256 KiB 的文件；重启应用后仍可解密查询。
4. 添加、缩放、删除和重新添加小部件；点击刷新和主体分别触发查询及打开应用。
5. 断网、401、403、429 和重定向时保留旧额度并显示状态；恢复联网后可重新查询。
6. 清除配置后重启，确认凭据和缓存均不再显示且后台不继续查询；锁屏、省电、重启后检查调度与更新时间。

本仓库的单元测试不使用真实账号。Root 管理器、文件选择器、Keystore、后台调度和各厂商桌面需要真机验证。
