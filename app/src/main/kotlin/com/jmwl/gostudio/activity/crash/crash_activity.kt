package com.jmwl.gostudio.activity.crash

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import com.jmwl.gostudio.ui.toast.app_toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jmwl.gostudio.feedback.crash_report_record
import com.jmwl.gostudio.feedback.crash_reporter
import com.jmwl.gostudio.feedback.feedback_settings_store
import com.jmwl.gostudio.ui.theme.app_theme_provider
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.time.Instant
import java.util.*

class crash_activity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val crash_log = intent.getStringExtra("crash_log") ?: return
        val crash_stack = intent.getStringExtra("crash_stack") ?: return

        // 崩溃可能发生在 Application.onCreate 早期，这里兜底初始化一次。
        feedback_settings_store.init(this)

        setContent {
            app_theme_provider {
                crash_screen(
                    crash_log = crash_log,
                    crash_stack = crash_stack,
                    on_restart = {
                        val intent = packageManager.getLaunchIntentForPackage(packageName)
                        intent?.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                        startActivity(intent)
                        finish()
                    },
                    on_exit = { finishAffinity() },
                    on_copy = { log, stack ->
                        copy_to_clipboard(log, stack)
                    },
                    on_save = { log, stack ->
                        save_crash_log_to_file(log, stack)
                    }
                )
            }
        }
    }

    private fun copy_to_clipboard(log: String, stack: String) {
        val text = buildString {
            append("GoStudio Crash Report\n")
            append("Time: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())}\n")
            append("Device: ${Build.MANUFACTURER} ${Build.MODEL}\n")
            append("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n")
            append("\nCrash Log\n")
            append(log)
            append("\n\nStack Trace\n")
            append(stack)
        }

        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("Crash Report", text)
        clipboard.setPrimaryClip(clip)
        app_toast.show(this, "已复制到剪贴板", app_toast.LENGTH_SHORT)
    }

    private fun save_crash_log_to_file(log: String, stack: String) {
        try {
            val crash_dir = File(cacheDir, "crash_logs")
            if (!crash_dir.exists()) {
                crash_dir.mkdirs()
            }
            val timestamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.getDefault()).format(Date())
            val crash_file = File(crash_dir, "crash_$timestamp.txt")

            FileWriter(crash_file).use { writer ->
                writer.write("GoStudio Crash Report\n")
                writer.write("Time: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())}\n")
                writer.write("Device: ${Build.MANUFACTURER} ${Build.MODEL}\n")
                writer.write("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n")
                writer.write("App Version: ${packageManager.getPackageInfo(packageName, 0).versionName}\n")
                writer.write("\nCrash Log\n")
                writer.write(log + "\n")
                writer.write("\nStack Trace\n")
                writer.write(stack + "\n")
            }

            app_toast.show(this, "已保存到: ${crash_file.absolutePath}", app_toast.LENGTH_LONG)
        } catch (e: Exception) {
            app_toast.show(this, "保存失败: ${e.message}", app_toast.LENGTH_SHORT)
        }
    }
}

/** 上报按钮的状态流转。 */
private sealed interface report_step {
    data object idle : report_step
    data object sending : report_step
    data class success(val occurrence_count: Int, val first_report: Boolean) : report_step
    data class failed(val message: String) : report_step
}

@Composable
fun crash_screen(
    crash_log: String,
    crash_stack: String,
    on_restart: () -> Unit,
    on_exit: () -> Unit,
    on_copy: (String, String) -> Unit,
    on_save: (String, String) -> Unit
) {
    val colors = app_theme_provider.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var report_state by remember { mutableStateOf<report_step>(report_step.idle) }
    var contact by remember { mutableStateOf(feedback_settings_store.contact) }
    var comment by remember { mutableStateOf("") }
    var show_history by remember { mutableStateOf(false) }

    val log_lines = remember(crash_log, crash_stack) {
        buildList {
            add("Crash Log")
            addAll(crash_log.split("\n"))
            add("")
            add("Stack Trace")
            addAll(crash_stack.split("\n"))
        }
    }

    fun submit_report() {
        if (report_state == report_step.sending) return
        feedback_settings_store.save_contact(context, contact)
        report_state = report_step.sending
        scope.launch {
            try {
                val result = crash_reporter.report(context, crash_log, crash_stack, comment)
                report_state = report_step.success(result.occurrence_count, result.first_report)
                app_toast.show(context, "上报成功，感谢反馈", app_toast.LENGTH_SHORT)
            } catch (e: Exception) {
                report_state = report_step.failed(e.message ?: "网络错误")
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.gradient_end)
            .statusBarsPadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
    ) {
        Spacer(modifier = Modifier.height(36.dp))

        // ===== 头部 =====
        Box(
            modifier = Modifier
                .size(64.dp)
                .background(colors.danger_bg, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.BugReport,
                contentDescription = null,
                modifier = Modifier.size(30.dp),
                tint = colors.danger
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "程序出现异常",
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            color = colors.title_large
        )

        Spacer(modifier = Modifier.height(4.dp))

        Text(
            text = "很抱歉，GoStudio 遇到了一个意外错误\n你可以把错误上报给开发者，帮助修复问题",
            fontSize = 13.sp,
            color = colors.subtitle,
            lineHeight = 19.sp
        )

        Spacer(modifier = Modifier.height(20.dp))

        // ===== 错误信息 =====
        crash_section_card(colors = colors) {
            crash_section_label(colors = colors, text = "错误信息", danger = true)
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = crash_log.take(300),
                fontSize = 11.sp,
                color = colors.card_text_subtitle,
                fontFamily = FontFamily.Monospace,
                lineHeight = 16.sp
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        // ===== 堆栈 =====
        crash_section_card(colors = colors) {
            crash_section_label(colors = colors, text = "堆栈跟踪")
            Spacer(modifier = Modifier.height(8.dp))
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(220.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                items(log_lines) { line ->
                    val line_color = when {
                        line.contains("Exception") || line.contains("Error") ||
                            line.contains("Caused by") -> colors.danger
                        line.startsWith("\t") -> colors.subtitle
                        line == "Crash Log" || line == "Stack Trace" -> colors.warning
                        else -> colors.card_text_subtitle
                    }
                    Text(
                        text = line.ifBlank { " " },
                        fontSize = 9.sp,
                        color = line_color,
                        fontFamily = FontFamily.Monospace,
                        lineHeight = 12.sp
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // ===== 联系方式 & 补充说明 =====
        crash_section_card(colors = colors) {
            crash_section_label(colors = colors, text = "联系方式（选填）")
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = contact,
                onValueChange = { if (it.length <= 60) contact = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = {
                    Text("邮箱 / QQ 号，便于回复你", fontSize = 13.sp, color = colors.dialog_input_hint)
                },
                singleLine = true,
                shape = RoundedCornerShape(10.dp),
                textStyle = MaterialTheme.typography.bodyMedium.copy(
                    color = colors.dialog_input_text, fontSize = 14.sp
                ),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = colors.dialog_input_border,
                    unfocusedBorderColor = colors.card_chevron,
                    cursorColor = colors.dialog_input_border
                )
            )
            Spacer(modifier = Modifier.height(12.dp))
            OutlinedTextField(
                value = comment,
                onValueChange = { if (it.length <= 500) comment = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = {
                    Text("补充说明：什么操作触发了崩溃？（选填）", fontSize = 13.sp, color = colors.dialog_input_hint)
                },
                shape = RoundedCornerShape(10.dp),
                textStyle = MaterialTheme.typography.bodyMedium.copy(
                    color = colors.dialog_input_text, fontSize = 14.sp
                ),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = colors.dialog_input_border,
                    unfocusedBorderColor = colors.card_chevron,
                    cursorColor = colors.dialog_input_border
                )
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // ===== 主操作：上报 =====
        Button(
            onClick = { submit_report() },
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
            shape = RoundedCornerShape(12.dp),
            enabled = report_state != report_step.sending,
            colors = ButtonDefaults.buttonColors(
                containerColor = when (report_state) {
                    is report_step.failed -> colors.danger
                    else -> colors.dialog_clone_bg
                },
                contentColor = colors.dialog_clone_text
            )
        ) {
            when (val state = report_state) {
                is report_step.idle -> {
                    Icon(
                        imageVector = Icons.Default.BugReport,
                        contentDescription = null,
                        modifier = Modifier.size(17.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("上报错误", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                }
                is report_step.sending -> {
                    CircularProgressIndicator(
                        modifier = Modifier.size(17.dp),
                        strokeWidth = 2.dp,
                        color = colors.dialog_clone_text
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("上报中…", fontSize = 14.sp)
                }
                is report_step.success -> {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = null,
                        modifier = Modifier.size(17.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        if (state.first_report) "已上报（第 1 次出现）"
                        else "已上报（该错误第 ${state.occurrence_count} 次出现）",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                is report_step.failed -> {
                    Text("上报失败：${state.message.take(40)}，点此重试", fontSize = 13.sp)
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        TextButton(
            onClick = { show_history = true },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.textButtonColors(contentColor = colors.dialog_cancel)
        ) {
            Icon(
                imageVector = Icons.Default.History,
                contentDescription = null,
                modifier = Modifier.size(15.dp)
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text("我的反馈记录", fontSize = 13.sp)
        }

        Spacer(modifier = Modifier.height(16.dp))

        // ===== 次操作：复制 / 保存 =====
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedButton(
                onClick = { on_copy(crash_log, crash_stack) },
                modifier = Modifier
                    .weight(1f)
                    .height(44.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = colors.card_text_title
                ),
                border = ButtonDefaults.outlinedButtonBorder.copy(
                    width = 1.dp,
                    brush = androidx.compose.ui.graphics.SolidColor(colors.card_chevron)
                )
            ) {
                Icon(
                    imageVector = Icons.Default.ContentCopy,
                    contentDescription = null,
                    modifier = Modifier.size(15.dp)
                )
                Spacer(modifier = Modifier.width(5.dp))
                Text("复制日志", fontSize = 13.sp)
            }

            OutlinedButton(
                onClick = { on_save(crash_log, crash_stack) },
                modifier = Modifier
                    .weight(1f)
                    .height(44.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = colors.card_text_title
                ),
                border = ButtonDefaults.outlinedButtonBorder.copy(
                    width = 1.dp,
                    brush = androidx.compose.ui.graphics.SolidColor(colors.card_chevron)
                )
            ) {
                Icon(
                    imageVector = Icons.Default.Save,
                    contentDescription = null,
                    modifier = Modifier.size(15.dp)
                )
                Spacer(modifier = Modifier.width(5.dp))
                Text("保存到文件", fontSize = 13.sp)
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // ===== 底部：重启 / 退出 =====
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Button(
                onClick = on_restart,
                modifier = Modifier
                    .weight(1f)
                    .height(44.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = colors.success,
                    contentColor = colors.dialog_clone_text
                )
            ) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = null,
                    modifier = Modifier.size(15.dp)
                )
                Spacer(modifier = Modifier.width(5.dp))
                Text("重启应用", fontSize = 13.sp)
            }

            Button(
                onClick = on_exit,
                modifier = Modifier
                    .weight(1f)
                    .height(44.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = colors.top_button_bg,
                    contentColor = colors.subtitle
                )
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = null,
                    modifier = Modifier.size(15.dp)
                )
                Spacer(modifier = Modifier.width(5.dp))
                Text("退出", fontSize = 13.sp)
            }
        }

        Spacer(modifier = Modifier.height(28.dp))
    }

    if (show_history) {
        crash_feedback_history_dialog(on_dismiss = { show_history = false })
    }
}

/** 统一的分区卡片：surface 底、圆角 14、无阴影（CodeAssist DNA）。 */
@Composable
private fun crash_section_card(
    colors: com.jmwl.gostudio.ui.theme.app_colors,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = colors.card_bg,
        shadowElevation = 0.dp
    ) {
        Column(modifier = Modifier.padding(14.dp), content = content)
    }
}

@Composable
private fun crash_section_label(
    colors: com.jmwl.gostudio.ui.theme.app_colors,
    text: String,
    danger: Boolean = false
) {
    Text(
        text = text,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        color = if (danger) colors.danger else colors.subtitle
    )
}

/** 「我的反馈记录」弹窗：本设备上报过的历史。 */
@Composable
private fun crash_feedback_history_dialog(on_dismiss: () -> Unit) {
    val colors = app_theme_provider.colors
    val context = LocalContext.current

    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var records by remember { mutableStateOf<List<crash_report_record>>(emptyList()) }

    LaunchedEffect(Unit) {
        try {
            records = crash_reporter.my_reports(context)
        } catch (e: Exception) {
            error = e.message ?: "网络错误"
        }
        loading = false
    }

    AlertDialog(
        onDismissRequest = on_dismiss,
        containerColor = colors.dialog_bg,
        title = {
            Text(
                text = "我的反馈记录",
                color = colors.dialog_text,
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(modifier = Modifier.heightIn(max = 380.dp)) {
                when {
                    loading -> Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(26.dp),
                            strokeWidth = 2.5.dp,
                            color = colors.dialog_icon
                        )
                    }
                    error != null -> Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 24.dp)
                    ) {
                        Text(
                            text = "加载失败：${error?.take(60)}",
                            fontSize = 12.sp,
                            color = colors.danger,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        TextButton(onClick = on_dismiss) {
                            Text("关闭", fontSize = 13.sp, color = colors.dialog_cancel)
                        }
                    }
                    records.isEmpty() -> Text(
                        text = "还没有上报记录\n遇到错误时点击「上报错误」，记录会显示在这里",
                        fontSize = 13.sp,
                        color = colors.dialog_hint,
                        lineHeight = 19.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 24.dp)
                    )
                    else -> LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.weight(1f, fill = false)
                    ) {
                        items(records) { record ->
                            crash_feedback_record_row(colors = colors, record = record)
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = on_dismiss) {
                Text("关闭", fontSize = 14.sp, color = colors.dialog_cancel)
            }
        }
    )
}

@Composable
private fun crash_feedback_record_row(
    colors: com.jmwl.gostudio.ui.theme.app_colors,
    record: crash_report_record
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = colors.dialog_card_bg
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = record.title,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                color = colors.dialog_text,
                lineHeight = 16.sp,
                maxLines = 2
            )
            Spacer(modifier = Modifier.height(6.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(999.dp),
                    color = if (record.status == "resolved") colors.success_bg else colors.danger_bg
                ) {
                    Text(
                        text = if (record.status == "resolved") "已修复" else "未处理",
                        fontSize = 10.sp,
                        color = if (record.status == "resolved") colors.success else colors.danger,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                    )
                }
                Text(
                    text = format_report_time(record.created_at),
                    fontSize = 11.sp,
                    color = colors.dialog_hint
                )
                if (record.app_version.isNotBlank()) {
                    Text(
                        text = "v${record.app_version}",
                        fontSize = 11.sp,
                        color = colors.dialog_hint
                    )
                }
            }
        }
    }
}

/** 服务端返回 ISO-8601，尽量转本地时区展示，解析失败原样返回。 */
private fun format_report_time(raw: String): String {
    return try {
        val instant = Instant.parse(raw)
        SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date.from(instant))
    } catch (_: Exception) {
        raw.take(16)
    }
}
