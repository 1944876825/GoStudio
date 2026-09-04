package com.jmwl.gostudio.ui.screens.ai

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.sp
import com.jmwl.gostudio.ai.ai_agent_loop
import com.jmwl.gostudio.ui.theme.app_theme_provider

/**
 * AI 聊天全屏页（主界面 "agent" 路由用）。
 * 不绑定具体项目，做通用 Go 编程问答。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ai_chat_screen(
    agent: ai_agent_loop,
    on_back: () -> Unit,
    on_open_settings: () -> Unit,
    current_choice: com.jmwl.gostudio.ai.ai_model_choice,
    instances: List<com.jmwl.gostudio.ai.provider_instance> = emptyList(),
    on_model_choice: (com.jmwl.gostudio.ai.ai_model_choice) -> Unit = {}
) {
    val colors = app_theme_provider.colors

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("AI 助手", color = colors.title_large) },
                navigationIcon = {
                    IconButton(onClick = on_back) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回", tint = colors.top_button_icon)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
            )
        },
        containerColor = colors.gradient_start
    ) { padding ->
        ai_chat_panel(
            agent = agent,
            on_open_settings = on_open_settings,
            current_choice = current_choice,
            instances = instances,
            on_model_choice = on_model_choice,
            modifier = Modifier.fillMaxSize().padding(padding)
        )
    }
}
