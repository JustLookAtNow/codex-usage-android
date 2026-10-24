package com.codexusage.android

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var content: LinearLayout
    private lateinit var quota: TextView
    private lateinit var status: TextView
    private lateinit var source: TextView
    private lateinit var feedback: TextView
    private val actions = mutableListOf<Button>()
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> runOnUiThread { render() } }
    private var busy = false
    private var selectingFile = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        selectingFile = savedInstanceState?.getBoolean("selecting_file") ?: false
        // Credential import is intentionally excluded from screenshots and recent-app previews.
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        val scroll = ScrollView(this).apply { isFillViewport = true }
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(24), dp(24), dp(32))
        }
        scroll.addView(content)
        setContentView(scroll)
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        text("CODEX / ANDROID", 12, "#087F6D", true)
        text("余量，随手可见", 30, bold = true)
        text("5 小时与每周额度 · 桌面小部件", 14, "#65766F")
        quota = text("尚未查询", 22, "#087F6D", true).apply {
            setPadding(dp(18), dp(20), dp(18), dp(20))
            background = GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = dp(20).toFloat() }
        }
        status = text("", 13, "#65766F")
        button("立即刷新") {
            if (CredentialStore(this).mode == null) feedback.text = "请先配置凭据"
            else {
                feedback.text = "已安排刷新，结果会自动更新"
                RefreshWorker.now(this)
            }
        }
        button("添加桌面小部件") { pinWidget() }
        text("登录凭据", 21, bold = true)
        source = text("", 13, "#65766F")
        feedback = text("", 13, "#087F6D")
        button("Root 自动查找") { discover() }
        button("Root 自定义路径") { askPath() }
        button("手工导入 auth.json") {
            selectingFile = true
            updateButtons()
            try {
                startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                }, IMPORT_REQUEST)
            } catch (_: Exception) {
                selectingFile = false
                updateButtons()
                feedback.text = "无法打开文件选择器，请安装或启用系统文件管理器"
            }
        }
        button("清除凭据与缓存") {
            AlertDialog.Builder(this).setTitle("清除本机配置？")
                .setMessage("删除应用保存的凭据、Root 路径和额度缓存，并停止刷新。")
                .setNegativeButton("取消", null).setPositiveButton("清除") { _, _ ->
                    task("正在清除…") {
                        synchronized(RepositoryLock) {
                            synchronized(ConfigurationLock) {
                                RefreshWorker.cancel(this)
                                CredentialStore(this).clear()
                                UsageRepository(this).clear()
                                UsageWidget.updateAll(this)
                            }
                        }
                        "已清除"
                    }
                }.show()
        }
        text("使用说明", 21, bold = true)
        text("Root 查找只检查终端应用常见的 .codex/auth.json 位置。请先在手机终端中登录 Codex，并在权限管理器中允许本应用使用 Root。找不到时可填写绝对路径。", 13, "#65766F")
        text("也可以从已登录的电脑或手机选择 auth.json 导入。仅支持 ChatGPT 账号登录，API Key 不支持订阅额度查询。导入内容使用 Android Keystore 加密保存；不会上传给其他服务器。", 13, "#65766F")
        text("后台按 15 分钟调度，省电模式可能延迟。桌面显示重置的本地时间；断网时保留旧数据。Root 模式每次刷新重新读取源文件；导入模式凭据失效后需要重新导入。第一阶段不包含网页登录和自动续期。", 13, "#65766F")
        text("网络使用手机当前连接（含 VPN），暂不提供应用内代理设置。", 13, "#65766F")
        getSharedPreferences("usage", MODE_PRIVATE).registerOnSharedPreferenceChangeListener(listener)
        render()
        updateButtons()
        RefreshWorker.schedule(this)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("selecting_file", selectingFile)
        super.onSaveInstanceState(outState)
    }

    override fun onResume() { super.onResume(); if (::quota.isInitialized) render() }
    override fun onDestroy() {
        getSharedPreferences("usage", MODE_PRIVATE).unregisterOnSharedPreferenceChangeListener(listener)
        executor.shutdown()
        super.onDestroy()
    }

    private fun render() {
        val repository = UsageRepository(this)
        val usage = repository.usage()
        source.text = CredentialStore(this).description()
        quota.text = usage?.let {
            it.windows.joinToString("\n\n") { window ->
                "${window.label}   ${percentage(window.remaining)}\n重置 ${window.resetAt?.let(::localTime) ?: "未提供"}"
            } + "\n\n套餐 ${it.plan ?: "未提供"} · 重置点 ${repository.credits?.toString() ?: "—"}"
        } ?: "5h  —\n\nWeekly  —"
        status.text = listOfNotNull(
            if (repository.updatedAt > 0) "上次成功更新 ${localTime(repository.updatedAt / 1000)}" else "尚无成功查询",
            repository.error?.let { if (usage != null) "当前显示旧数据\n$it" else it },
            repository.creditsError?.let { "重置点：$it" }
        ).joinToString("\n")
    }

    private fun discover() {
        var paths = emptyList<String>()
        task("正在请求 Root 并查找，请在权限管理器中授权…", after = {
            when (paths.size) {
                0 -> feedback.setText(R.string.root_not_found)
                1 -> selectRoot(paths.first())
                else -> AlertDialog.Builder(this).setTitle("选择 Codex 凭据")
                    .setItems(paths.toTypedArray()) { _, which -> selectRoot(paths[which]) }
                    .setNegativeButton("取消", null).show()
            }
        }) {
            paths = RootAccess.discover()
            null
        }
    }

    @SuppressLint("SdCardPath") // The example intentionally refers to Termux's Codex home.
    private fun askPath() {
        val input = EditText(this).apply {
            hint = "/data/data/com.termux/files/home/.codex/auth.json"
            setText(CredentialStore(this@MainActivity).rootPath.orEmpty())
            setSingleLine(true)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        AlertDialog.Builder(this).setTitle("Root 凭据绝对路径").setView(input)
            .setNegativeButton("取消", null).setPositiveButton("读取") { _, _ -> selectRoot(input.text.toString().trim()) }.show()
    }

    private fun selectRoot(path: String) {
        task("正在验证 Root 凭据…") {
            synchronized(RepositoryLock) {
                CredentialStore(this).selectRoot(path)
                UsageRepository(this).clear()
                configured()
            }
            "已选择 Root 凭据，每次刷新会重新读取"
        }
    }

    @Deprecated("Platform activity-result API is sufficient for the single file picker")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != IMPORT_REQUEST) return
        selectingFile = false
        updateButtons()
        if (resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        task("正在验证并加密导入…") {
            val bytes = contentResolver.openInputStream(uri)?.use { readBounded(it, AUTH_MAX_BYTES) }
                ?: throw UserError("无法读取所选文件")
            synchronized(RepositoryLock) {
                CredentialStore(this).import(bytes)
                UsageRepository(this).clear()
                configured()
            }
            "导入成功，正在查询额度"
        }
    }

    private fun configured() {
        UsageWidget.updateAll(this)
        RefreshWorker.schedule(this)
        RefreshWorker.now(this)
    }

    private fun task(message: String, after: (() -> Unit)? = null, block: () -> String?) {
        busy = true
        feedback.text = message
        updateButtons()
        executor.execute {
            var succeeded = false
            val outcome = try { block().also { succeeded = true } } catch (error: UserError) { error.message }
            catch (_: Exception) { "操作失败，请重试；必要时重新导入凭据" }
            runOnUiThread {
                if (!isDestroyed && !isFinishing) {
                    busy = false
                    updateButtons()
                    if (outcome != null) feedback.text = outcome
                    render()
                    if (succeeded) after?.invoke()
                }
            }
        }
    }

    private fun pinWidget() {
        val manager = getSystemService(AppWidgetManager::class.java)
        if (manager.isRequestPinAppWidgetSupported) {
            manager.requestPinAppWidget(ComponentName(this, UsageWidget::class.java), null, null)
            feedback.text = "请在桌面确认添加小部件"
        } else feedback.setText(R.string.widget_add_manually)
    }

    private fun updateButtons() { actions.forEach { it.isEnabled = !busy && !selectingFile } }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun text(value: String, size: Int, color: String = "#183D30", bold: Boolean = false): TextView {
        return TextView(this).apply {
            text = value; textSize = size.toFloat(); setTextColor(Color.parseColor(color))
            if (bold) setTypeface(typeface, Typeface.BOLD)
            setTextIsSelectable(true)
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) }
            content.addView(this)
        }
    }
    private fun button(label: String, action: () -> Unit) {
        Button(this).apply {
            text = label; isAllCaps = false; minimumHeight = dp(48)
            setOnClickListener { action() }
            content.addView(this, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
            actions += this
        }
    }

    companion object { private const val IMPORT_REQUEST = 100 }
}
