package com.jmwl.gostudio.ui.screens.main

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jmwl.gostudio.feedback.crash_report_record
import com.jmwl.gostudio.feedback.crash_reporter
import com.jmwl.gostudio.feedback.feedback_settings_store
import com.jmwl.gostudio.ui.theme.app_colors
import com.jmwl.gostudio.ui.theme.app_theme_provider
import java.time.Instant
import java.text.SimpleDateFormat
import java.util.*

/**
 * 问题反馈历史：本设备上报过的崩溃记录（按设备号在服务端查询），
 * 顶部可维护联系方式（上报崩溃时自动携带）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun feedback_history_screen(on_back: () -> Unit) {
    val colors = app_theme_provider.colors
    val context = LocalContext.current

    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var records by remember { mutableStateOf<List<crash_report_record>>(emptyList()) }
    var contact by remember { mutableStateOf(feedback_settings_store.contact) }

    fun reload() {
        loading = true
        error = null
    }

    LaunchedEffect(loading) {
        if (!loading) return@LaunchedEffect
        try {
            records = crash_reporter.my_reports(context)
        } catch (e: Exception) {
            error = e.message ?: "网络错误"
        }
        loading = false
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "问题反馈",
                        color = colors.title_large,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = on_back) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回",
                            tint = colors.top_button_icon
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { reload() }, enabled = !loading) {
                        Icon(
                            Icons.Default.Refresh,
                            contentDescription = "刷新",
                            tint = colors.top_button_icon
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
            )
        },
        containerColor = Color.Transparent
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(colors.gradient_end)
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
        ) {
            Spacer(modifier = Modifier.height(8.dp))

            // ===== 联系方式 =====
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = colors.card_bg
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(
                        text = "联系方式",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.card_text_title
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "崩溃上报时自动携带，便于开发者修复后回复你",
                        fontSize = 12.sp,
                        color = colors.card_text_subtitle
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    OutlinedTextField(
                        value = contact,
                        onValueChange = { if (it.length <= 60) contact = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = {
                            Text("邮箱 / QQ 号（选填）", fontSize = 13.sp, color = colors.input_hint)
                        },
                        singleLine = true,
                        shape = RoundedCornerShape(10.dp),
                        textStyle = androidx.compose.ui.text.TextStyle(
                            color = colors.input_text, fontSize = 14.sp
                        ),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = colors.input_border,
                            unfocusedBorderColor = colors.card_chevron,
                            cursorColor = colors.input_border
                        )
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Button(
                        onClick = {
                            feedback_settings_store.save_contact(context, contact)
                            reload()
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(40.dp),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = colors.dialog_clone_bg,
                            contentColor = colors.dialog_clone_text
                        )
                    ) {
                        Text("保存并刷新", fontSize = 13.sp)
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // ===== 记录列表 =====
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.BugReport,
                    contentDescription = null,
                    modifier = Modifier.size(15.dp),
                    tint = colors.subtitle
                )
                Spacer(modifier = Modifier.width(5.dp))
                Text(
                    text = "我的上报记录",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.subtitle
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            when {
                loading -> Row(
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 40.dp)
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.5.dp,
                        color = colors.title_highlight
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text("加载中…", fontSize = 13.sp, color = colors.subtitle)
                }

                error != null -> Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 32.dp)
                ) {
                    Text(
                        text = "加载失败：${error?.take(80)}",
                        fontSize = 13.sp,
                        color = colors.danger
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    TextButton(onClick = { reload() }) {
                        Text("重试", fontSize = 13.sp, color = colors.title_highlight)
                    }
                }

                records.isEmpty() -> Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = null,
                        modifier = Modifier.size(34.dp),
                        tint = colors.card_chevron
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = "暂无上报记录\nApp 崩溃时点击「上报错误」即可反馈",
                        fontSize = 13.sp,
                        color = colors.subtitle,
                        lineHeight = 19.sp
                    )
                }

                else -> Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    records.forEach { record ->
                        feedback_record_card(colors = colors, record = record)
                    }
                }
            }

            Spacer(modifier = Modifier.height(28.dp))
        }
    }
}

@Composable
private fun feedback_record_card(colors: app_colors, record: crash_report_record) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = colors.card_bg
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                text = record.title,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                color = colors.card_text_title,
                lineHeight = 17.sp
            )
            if (record.comment.isNotBlank()) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "备注：${record.comment}",
                    fontSize = 12.sp,
                    color = colors.card_text_subtitle,
                    lineHeight = 17.sp
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
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
                        fontWeight = FontWeight.SemiBold,
                        color = if (record.status == "resolved") colors.success else colors.danger,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                    )
                }
                Text(
                    text = format_time(record.created_at),
                    fontSize = 11.sp,
                    color = colors.card_text_subtitle
                )
                if (record.app_version.isNotBlank()) {
                    Text(
                        text = "v${record.app_version}",
                        fontSize = 11.sp,
                        color = colors.card_text_subtitle
                    )
                }
            }
        }
    }
}

private fun format_time(raw: String): String {
    return try {
        val instant = Instant.parse(raw)
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date.from(instant))
    } catch (_: Exception) {
        raw.take(19)
    }
}
