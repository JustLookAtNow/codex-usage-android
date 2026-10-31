package com.codexusage.android

import android.app.Activity
import android.app.AlertDialog
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.Typeface
import android.graphics.drawable.AnimatedVectorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var root: FrameLayout
    private lateinit var scroll: ScrollView
    private lateinit var resetEffect: ResetSuccessEffect
    private var animatedQuota: Usage? = null
    private var resumed = false
    private lateinit var content: LinearLayout
    private lateinit var quota: TextView
    private lateinit var resetSummary: TextView
    private lateinit var status: TextView
    private lateinit var resetDetails: LinearLayout
    private lateinit var resetStatus: TextView
    private lateinit var resetToggle: Button
    private lateinit var source: TextView
    private lateinit var feedback: TextView
    private lateinit var refreshButton: Button
    private lateinit var refreshIcon: AnimatedVectorDrawable
    private val refreshPrefs by lazy { getSharedPreferences("widget_refresh", MODE_PRIVATE) }
    private var refreshing = false
    private val refreshListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        runOnUiThread { if (!isDestroyed && !isFinishing) updateButtons() }
    }
    private lateinit var xposedStatusCard: LinearLayout
    private lateinit var xposedStatusHeading: TextView
    private lateinit var xposedStatus: TextView
    private val actions = mutableListOf<Button>()
    private val resetActions = mutableListOf<Pair<Button, Boolean>>()
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        runOnUiThread { if (!isDestroyed && !isFinishing) render() }
    }
    private val xposedListener: () -> Unit = {
        runOnUiThread { if (!isDestroyed && !isFinishing) refreshXposedStatus() }
    }
    private var busy = false
    private var selectingFile = false
    private var confirmingReset = false
    private var resetsExpanded = false
    private val expiryHandler = Handler(Looper.getMainLooper())
    private val expiryTick = object : Runnable {
        override fun run() {
            renderResetCredits()
            expiryHandler.postDelayed(this, 60000L - System.currentTimeMillis() % 60000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val dark = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = !dark
            isAppearanceLightNavigationBars = !dark
        }
        selectingFile = savedInstanceState?.getBoolean("selecting_file") ?: false
        resetsExpanded = savedInstanceState?.getBoolean("resets_expanded") ?: false
        scroll = ScrollView(this).apply { isFillViewport = true; setBackgroundColor(getColor(R.color.app_background)) }
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(24), dp(24), dp(32))
        }
        scroll.addView(content)
        root = FrameLayout(this).apply { addView(scroll, FrameLayout.LayoutParams(-1, -1)) }
        resetEffect = ResetSuccessEffect(root)
        setContentView(root)
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        text("CODEX / ANDROID", 12, R.color.app_accent, true)
        text("余量，随手可见", 30, bold = true)
        text("5 小时与每周额度 · 桌面小部件", 14, R.color.app_muted)
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(20), dp(18), dp(20))
            background = GradientDrawable().apply { setColor(getColor(R.color.app_surface)); cornerRadius = dp(20).toFloat() }
        }
        content.addView(header, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) })
        quota = text("尚未查询", 22, R.color.app_accent, true, header).apply {
            (layoutParams as LinearLayout.LayoutParams).topMargin = 0
        }
        resetSummary = text("重置点 —", 14, R.color.app_accent, parent = header)
        status = text("", 13, R.color.app_muted)
        refreshButton = button("立即刷新") {
            if (refreshing) return@button
            if (CredentialStore(this).mode == null) feedback.text = "请先配置凭据"
            else {
                feedback.text = "正在刷新，结果会自动更新"
                RefreshWorker.now(this)
                updateButtons()
            }
        }
        refreshIcon = (getDrawable(R.drawable.widget_refresh_animated)!!.mutate() as AnimatedVectorDrawable).apply {
            setTint(getColor(R.color.app_accent))
            setBounds(0, 0, dp(24), dp(24))
        }
        refreshButton.apply {
            compoundDrawablePadding = dp(8)
            setCompoundDrawablesRelative(refreshIcon, null, null, null)
            gravity = Gravity.CENTER
            addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> centerRefreshButtonContent() }
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        resetToggle = button("展开重置点详情 ▾") {
            resetsExpanded = !resetsExpanded
            updateResetExpansion()
        }
        resetDetails = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(resetDetails, LinearLayout.LayoutParams(-1, -2))
        resetStatus = text("", 13, R.color.app_muted)
        button("添加原有小部件") { pinWidget(glass = false) }
        button("添加透明玻璃小部件") { pinWidget(glass = true) }
        button(getString(R.string.notification_settings)) { WeeklyResetNotifications(this).openSettings() }
        xposedStatusCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = GradientDrawable().apply {
                setColor(getColor(R.color.app_surface))
                setStroke(dp(1), getColor(R.color.app_muted))
                cornerRadius = dp(16).toFloat()
            }
        }
        content.addView(xposedStatusCard, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) })
        text("LSPosed · 要求 API 102+", 14, bold = true, parent = xposedStatusCard).apply {
            (layoutParams as LinearLayout.LayoutParams).topMargin = 0
            isAccessibilityHeading = true
        }
        xposedStatusHeading = text("正在检测…", 18, R.color.app_muted, true, xposedStatusCard).apply {
            (layoutParams as LinearLayout.LayoutParams).topMargin = dp(6)
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        xposedStatus = text("LSPosed：正在检测模块服务与系统桌面作用域…", 13, parent = xposedStatusCard).apply {
            (layoutParams as LinearLayout.LayoutParams).topMargin = dp(6)
        }
        text(getString(R.string.glass_widget_compatibility_description) +
            "。请在 LSPosed 中启用本应用模块，勾选系统桌面并重启桌面；未启用时显示半透明背景。", 13, R.color.app_muted)
        text("登录凭据", 21, bold = true)
        source = text("", 13, R.color.app_muted)
        feedback = text("", 13, R.color.app_accent)
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
        text("Root 自动查找默认读取 ChatGPT 安卓应用的当前登录。请先登录 ChatGPT，并在权限管理器中允许本应用使用 Root。也支持终端中的 Codex auth.json 和自定义路径。", 13, R.color.app_muted)
        text("也可以从已登录的电脑或手机选择 auth.json 导入。仅支持 ChatGPT 账号登录，API Key 不支持订阅额度查询。导入内容使用 Android Keystore 加密保存；不会上传给其他服务器。", 13, R.color.app_muted)
        text("后台按 15 分钟调度，省电模式可能延迟。桌面显示本地恢复倒计时；断网时保留旧数据。Root 模式每次刷新重新读取当前凭据；过期时请打开 ChatGPT 更新登录。导入模式需重新导入。应用不会自行续期 token。", 13, R.color.app_muted)
        text("网络使用手机当前连接（含 VPN），暂不提供应用内代理设置。", 13, R.color.app_muted)
        getSharedPreferences("usage", MODE_PRIVATE).registerOnSharedPreferenceChangeListener(listener)
        refreshPrefs.registerOnSharedPreferenceChangeListener(refreshListener)
        render()
        updateButtons()
        RefreshWorker.schedule(this)
        WeeklyResetNotifications(this).requestPermissionOnce(this)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("selecting_file", selectingFile)
        outState.putBoolean("resets_expanded", resetsExpanded)
        super.onSaveInstanceState(outState)
    }

    override fun onStart() {
        super.onStart()
        (application as CodexUsageApplication).xposedStatus.addListener(xposedListener)
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        if (::quota.isInitialized) {
            render()
            refreshXposedStatus()
            expiryHandler.removeCallbacks(expiryTick)
            expiryHandler.postDelayed(expiryTick, 60000L - System.currentTimeMillis() % 60000L)
            if (!busy && UsageRepository(this).resetPending) RefreshWorker.now(this)
        }
    }

    override fun onPause() {
        resumed = false
        resetEffect.stop()
        animatedQuota = null
        renderQuota(UsageRepository(this).displayUsage())
        expiryHandler.removeCallbacks(expiryTick)
        super.onPause()
    }

    override fun onStop() {
        (application as CodexUsageApplication).xposedStatus.removeListener(xposedListener)
        super.onStop()
    }

    override fun onDestroy() {
        getSharedPreferences("usage", MODE_PRIVATE).unregisterOnSharedPreferenceChangeListener(listener)
        refreshPrefs.unregisterOnSharedPreferenceChangeListener(refreshListener)
        refreshIcon.stop()
        resetEffect.stop()
        executor.shutdown()
        super.onDestroy()
    }

    private fun refreshXposedStatus() {
        val tracker = (application as CodexUsageApplication).xposedStatus
        val connection = tracker.connection
        executor.execute {
            val result = readXposedStatus(connection)
            runOnUiThread {
                if (!isDestroyed && !isFinishing && tracker.connection === connection) showXposedStatus(result)
            }
        }
    }

    private fun showXposedStatus(result: XposedStatus) {
        val ready = result is XposedStatus.DesktopScoped
        xposedStatusHeading.text = xposedStatusLabel(result)
        xposedStatusHeading.setTextColor(getColor(if (ready) R.color.app_xposed_ready else R.color.app_xposed_attention))
        xposedStatus.text = xposedStatusText(result)
        xposedStatusCard.background = GradientDrawable().apply {
            setColor(getColor(if (ready) R.color.app_xposed_ready_background else R.color.app_xposed_attention_background))
            setStroke(dp(1), getColor(if (ready) R.color.app_xposed_ready_border else R.color.app_xposed_attention_border))
            cornerRadius = dp(16).toFloat()
        }
    }

    private fun render() {
        val repository = UsageRepository(this)
        val usage = repository.displayUsage()
        if (usage == null) {
            resetEffect.stop()
            animatedQuota = null
        }
        source.text = CredentialStore(this).description()
        renderQuota(animatedQuota ?: usage)
        status.text = listOfNotNull(
            if (repository.updatedAt > 0) "上次成功更新 ${localTime(repository.updatedAt / 1000)}" else "尚无成功查询",
            repository.error?.let { if (usage != null) "当前显示旧数据\n$it" else it }
        ).joinToString("\n")
        renderResetCredits()
        showResetSuccess(repository)
    }

    private fun renderQuota(usage: Usage?) {
        quota.text = usage?.let { data ->
            SpannableStringBuilder().apply {
                data.windows.forEachIndexed { index, window ->
                    if (index > 0) append("\n\n")
                    append("${window.label}   ")
                    val start = length
                    append(percentage(window.remaining))
                    setSpan(ForegroundColorSpan(quotaColor(this@MainActivity, window.remaining,
                        getColor(R.color.app_accent))), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    append("\n重置 ${window.resetAt?.let(::localTime) ?: "未提供"}")
                }
                append("\n\n套餐 ${data.plan ?: "未提供"}")
            }
        } ?: "5h  —\n\nWeekly  —"
    }

    private fun showResetSuccess(repository: UsageRepository) {
        if (!resumed || resetEffect.running) return
        val event = repository.claimResetCelebration() ?: return
        feedback.text = repository.resetMessage
        UsageWidget.updateAll(this)
        scroll.smoothScrollTo(0, 0)
        resetEffect.start(event.before, repository.displayUsage(), frame = {
            animatedQuota = it
            renderQuota(it)
        }, finished = {
            animatedQuota = null
            if (!isDestroyed && !isFinishing) render()
        })
    }

    private fun renderResetCredits() {
        val repository = UsageRepository(this)
        val now = System.currentTimeMillis() / 1000
        val resets = repository.resetCredits()
        val rows = resets?.credits
        resetSummary.setTextColor(resetExpiryColor(this, resets.expiryLevel(now), getColor(R.color.app_accent)))
        resetSummary.text = buildString {
            append("重置点 ${repository.credits?.toString() ?: "—"} 个")
            if ((repository.credits ?: 0) > 0) {
                val available = rows.orEmpty().filter { it.status == "available" }
                val expiries = available.mapNotNull { it.expiresAt }.sorted()
                if (expiries.isEmpty()) append("\n到期时间未提供")
                else {
                    expiries.forEach { append("\n到期 ${resetTime(it)}") }
                    if (available.any { it.expiresAt == null }) append("\n部分重置点到期时间未提供")
                }
            }
        }
        resetDetails.removeAllViews()
        resetActions.clear()
        resets?.totalEarnedCount?.let { text("累计获得 $it 个", 13, parent = resetDetails) }
        if (repository.resetPending) {
            if (repository.pendingResetCreditMissing) text("上次使用的重置点已不在最近的明细中，刷新后将自动核对并确认结果。",
                14, parent = resetDetails)
            resetAction("重试本次重置", resetDetails, true) { confirmReset() }
            resetAction("确认上次已完成", resetDetails, true) { confirmCompletedReset() }
        }
        when {
            rows == null -> text("尚无重置点详情，请刷新查询", 14, parent = resetDetails)
            rows.isEmpty() -> text(if (repository.credits == 0) "当前没有重置点明细" else "接口未返回明细，以上述可用数量为准", 14, parent = resetDetails)
            else -> rows.forEachIndexed { index, credit ->
                val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.TOP }
                resetDetails.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) })
                val description = SpannableStringBuilder().apply {
                    val state = when (credit.status) {
                        "available" -> "可用"
                        "redeemed", "consumed" -> "已使用"
                        "expired" -> "已过期"
                        "revoked" -> "已撤销"
                        else -> credit.status ?: "未提供"
                    }
                    val type = when (credit.resetType) {
                        "codex_rate_limits" -> "Codex 额度重置"
                        else -> credit.resetType ?: "未提供"
                    }
                    append("${index + 1}. ${credit.title ?: "重置点"}\n状态：$state\n类型：$type")
                    append("\n发放：${credit.grantedAt?.let(::resetTime) ?: "未提供"}")
                    val expiryStart = length + 1
                    append("\n到期：${credit.expiresAt?.let(::resetTime) ?: "未提供"}")
                    val level = if ((repository.credits ?: 0) > 0) credit.expiryLevel(now) else ResetExpiryLevel.NORMAL
                    if (level != ResetExpiryLevel.NORMAL) setSpan(
                        ForegroundColorSpan(resetExpiryColor(this@MainActivity, level, getColor(R.color.app_text))),
                        expiryStart, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    credit.description?.let { append("\n说明：$it") }
                    credit.id?.let { append("\n编号：$it") }
                }
                text(description, 14, parent = row).layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
                resetAction("使用", row, !repository.resetPending && (repository.credits ?: 0) > 0 &&
                    credit.status == "available" && !credit.id.isNullOrBlank()) { confirmReset(credit) }
            }
        }
        repository.creditsUpdatedAt.takeIf { it > 0 }?.let {
            text("重置点更新 ${resetTime(it / 1000)}", 13, R.color.app_muted, parent = resetDetails)
        }
        resetStatus.text = listOfNotNull(
            if (repository.error != null || repository.creditsError != null) "查询未成功，当前重置点信息可能已过时" else null,
            repository.creditsError,
            if (repository.pendingResetCreditMissing)
                "上次重置结果待确认，刷新后将自动核对该重置点是否仍在列表中。"
            else repository.resetMessage
        ).joinToString("\n")
        resetStatus.visibility = if (resetStatus.text.isEmpty()) View.GONE else View.VISIBLE
        updateResetExpansion()
        updateButtons()
    }

    private fun updateResetExpansion() {
        resetDetails.visibility = if (resetsExpanded) View.VISIBLE else View.GONE
        resetToggle.text = if (resetsExpanded) "收起重置点详情 ▴" else "展开重置点详情 ▾"
    }

    private fun confirmReset(credit: ResetCredit? = null) {
        if (busy || selectingFile || confirmingReset) return
        val pending = UsageRepository(this).resetPending
        if (!pending && credit?.id.isNullOrBlank()) return
        confirmingReset = true
        updateButtons()
        AlertDialog.Builder(this).setTitle(if (pending) "重试本次重置？" else "使用 1 个重置点？")
            .setMessage(if (pending) "将复用上次请求编号确认结果，避免重复消耗。完成后会重新查询额度和重置点。"
                else "将使用此重置点：${credit?.title ?: "重置点"}\n到期：${credit?.expiresAt?.let(::resetTime) ?: "未提供"}\n\n由服务决定可重置的额度周期。成功后不可撤销；若没有符合条件的额度需要重置，则不消耗重置点。")
            .setNegativeButton("取消", null)
            .setPositiveButton(if (pending) "重试本次请求" else "使用 1 个重置点") { _, _ ->
                task("正在使用重置，请稍候…") {
                    try { UsageRepository(this).consumeReset(credit?.id).message }
                    finally { UsageWidget.updateAll(this) }
                }
            }
            .setOnDismissListener { confirmingReset = false; updateButtons() }.show()
    }

    private fun confirmCompletedReset() {
        if (busy || selectingFile || confirmingReset) return
        val requestId = UsageRepository(this).pendingResetRequestId ?: return
        confirmingReset = true
        updateButtons()
        AlertDialog.Builder(this).setTitle("确认上次重置已完成？")
            .setMessage("仅在你已确认上次重置成功、额度已恢复时使用。确认后结束上次的待确认状态，保留当前额度和其他重置点，不会再次使用重置点。")
            .setNegativeButton("取消", null)
            .setPositiveButton("确认已完成") { _, _ ->
                task("正在更新上次重置状态…") {
                    UsageRepository(this).acknowledgeCompletedReset(requestId).also { UsageWidget.updateAll(this) }
                }
            }
            .setOnDismissListener { confirmingReset = false; updateButtons() }.show()
    }

    private fun discover() {
        var paths = emptyList<String>()
        task("正在请求 Root 并查找，请在权限管理器中授权…", after = {
            when {
                paths.isEmpty() -> feedback.setText(R.string.root_not_found)
                CHATGPT_AUTH_PATH in paths -> selectRoot(CHATGPT_AUTH_PATH)
                paths.size == 1 -> selectRoot(paths.first())
                else -> AlertDialog.Builder(this).setTitle("选择 Codex 凭据")
                    .setItems(paths.toTypedArray()) { _, which -> selectRoot(paths[which]) }
                    .setNegativeButton("取消", null).show()
            }
        }) {
            paths = RootAccess.discover()
            null
        }
    }

    private fun askPath() {
        val input = EditText(this).apply {
            hint = CHATGPT_AUTH_PATH
            setText(CredentialStore(this@MainActivity).rootPath ?: CHATGPT_AUTH_PATH)
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

    private fun pinWidget(glass: Boolean) {
        val manager = getSystemService(AppWidgetManager::class.java)
        if (manager.isRequestPinAppWidgetSupported) {
            val provider = if (glass) GlassUsageWidget::class.java else UsageWidget::class.java
            manager.requestPinAppWidget(ComponentName(this, provider), null, null)
            feedback.text = "请在桌面确认添加小部件"
        } else feedback.setText(if (glass) R.string.glass_widget_add_manually else R.string.widget_add_manually)
    }

    private fun updateButtons() {
        val available = !busy && !selectingFile && !confirmingReset
        actions.forEach { it.isEnabled = available }
        val active = WidgetRefreshState.active(this)
        refreshButton.isEnabled = available && !active
        refreshButton.text = if (active) "正在刷新…" else "立即刷新"
        centerRefreshButtonContent()
        if (active) {
            if (!refreshIcon.isRunning) refreshIcon.start()
        } else {
            refreshIcon.stop()
            refreshIcon.reset()
            if (refreshing) {
                val repository = UsageRepository(this)
                feedback.text = repository.error ?: repository.creditsError?.let { "额度已更新，重置点查询失败：$it" }
                    ?: if (CredentialStore(this).mode == null) "请先配置凭据" else "刷新完成"
            }
        }
        refreshing = active
        resetActions.forEach { (button, eligible) -> button.isEnabled = available && eligible && CredentialStore(this).mode != null }
    }
    private fun resetAction(label: String, parent: LinearLayout, eligible: Boolean, action: () -> Unit) {
        val button = Button(this).apply {
            text = label; isAllCaps = false; minimumHeight = dp(48)
            setOnClickListener { action() }
        }
        parent.addView(button, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(8) })
        resetActions += button to eligible
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun centerRefreshButtonContent() {
        refreshButton.apply {
            if (width == 0) return
            val contentWidth = paint.measureText(text.toString()) + refreshIcon.bounds.width() + compoundDrawablePadding
            // TextView anchors its start drawable to the padding, so center the whole group.
            val horizontalPadding = ((width - contentWidth) / 2).toInt().coerceAtLeast(dp(16))
            if (paddingStart != horizontalPadding || paddingEnd != horizontalPadding) {
                setPaddingRelative(horizontalPadding, paddingTop, horizontalPadding, paddingBottom)
            }
        }
    }
    private fun resetTime(seconds: Long) = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        .withZone(ZoneId.systemDefault()).format(Instant.ofEpochSecond(seconds))
    private fun text(value: CharSequence, size: Int, color: Int = R.color.app_text, bold: Boolean = false, parent: LinearLayout = content): TextView {
        return TextView(this).apply {
            text = value; textSize = size.toFloat(); setTextColor(getColor(color))
            if (bold) setTypeface(typeface, Typeface.BOLD)
            setTextIsSelectable(true)
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) }
            parent.addView(this)
        }
    }
    private fun button(label: String, action: () -> Unit): Button {
        return Button(this).apply {
            text = label; isAllCaps = false; minimumHeight = dp(48)
            setOnClickListener { action() }
            content.addView(this, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
            actions += this
        }
    }

    companion object { private const val IMPORT_REQUEST = 100 }
}
