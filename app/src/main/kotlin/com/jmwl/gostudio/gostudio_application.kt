package com.jmwl.gostudio

import android.app.Application
import android.content.Intent
import android.os.Build
import com.jmwl.gostudio.activity.crash.crash_activity
import com.jmwl.gostudio.core.logging.logger_manager
import com.jmwl.gostudio.editor.theme.editor_theme_manager
import com.jmwl.gostudio.plugins.plugin_manager
import com.jmwl.gostudio.service.keep_alive_service
import com.jmwl.gostudio.toolchain.toolchain_manager
import com.jmwl.gostudio.toolchain.toolchain_runtime_provider
import com.jmwl.gostudio.ui.theme.theme_manager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import com.jmwl.gostudio.utils.app_lifecycle_observer
import io.github.rosemoe.sora.langs.textmate.TextMateLanguage
import io.github.rosemoe.sora.langs.textmate.registry.FileProviderRegistry
import io.github.rosemoe.sora.langs.textmate.registry.GrammarRegistry
import io.github.rosemoe.sora.langs.textmate.registry.provider.AssetsFileResolver
import org.eclipse.tm4e.core.internal.oniguruma.Oniguruma
import java.io.File
class gostudio_application : Application() {

    companion object {
        lateinit var instance: gostudio_application
    }

    private var textmate_initialized = false

    var keep_alive_service_: keep_alive_service? = null

    override fun onCreate() {
        super.onCreate()
        instance = this

        theme_manager.init(this)
        logger_manager.init(this)
        logger_manager.is_debug = false
        logger_manager.enable_file_log = true
        logger_manager.errors_only = true
        com.jmwl.gostudio.feedback.feedback_settings_store.init(this)

        val create_dirs = listOf(
            File(filesDir, "home"),
            File(filesDir, "home/gostudio/proot-tmps"),
            File(filesDir, "home/gostudio/alpine-rootfs")
        )
        for (dir in create_dirs) {
            if (!dir.exists()) dir.mkdirs()
        }

        toolchain_runtime_provider.init(
            context = this,
            gostudio_dir = File(filesDir, "home/gostudio"),
            home_dir = File(filesDir, "home"),
            rootfs_dir = File(filesDir, "home/gostudio/alpine-rootfs"),
            proot_tmp_dir = File(filesDir, "home/gostudio/proot-tmps")
        )

        // 先于任何 GOPROXY 读取（工具链环境组装/迁移补写都依赖它）
        com.jmwl.gostudio.toolchain.goproxy_store.init(this)

        plugin_manager.init(this)
        // 内置插件随 APK 发布：启动时释放到插件目录（版本升级覆盖，启用状态不动）
        plugin_manager.release_builtin_plugins(this)

        // 旧版本装的 Go 没把 GOPROXY 写进 go env 文件，终端里走默认 proxy.golang.org：
        // 启动时补写一次（文件里已有 GOPROXY 则跳过，不影响用户自定义）。
        CoroutineScope(Dispatchers.IO).launch {
            runCatching {
                if (toolchain_manager.is_go_installed()) {
                    toolchain_manager.ensure_default_go_env()
                }
            }.onFailure { error ->
                logger_manager.e("gostudio_application", "写入默认 GOPROXY 失败: ${error.message}", error)
            }
        }

        init_textmate()
        start_keep_alive_service()
        app_lifecycle_observer.init(this)
        setup_uncaught_exception_handler()
    }

    private fun init_textmate() {
        if (textmate_initialized) return

        try {
            FileProviderRegistry.getInstance().addFileProvider(AssetsFileResolver(assets))
            configure_textmate_regex_engine()

            editor_theme_manager.init(this)

            GrammarRegistry.getInstance().loadGrammars("textmate/languages.json")

            textmate_initialized = true
        } catch (e: Exception) {
            logger_manager.e("gostudio_application", "Failed to init TextMate: ${e.message}", e)
        }
    }

    private fun configure_textmate_regex_engine() {
        runCatching {
            Oniguruma().setUseNativeOniguruma(true)
        }.onFailure { error ->
            logger_manager.e("gostudio_application", "TextMate native Oniguruma unavailable: ${error.message}", error)
        }
    }

    fun get_language_scope_name(file_name: String): String {
        return when {
            file_name.endsWith(".go", ignoreCase = true) -> "source.go"
            file_name.equals("go.mod", ignoreCase = true) -> "source.go"
            file_name.equals("go.sum", ignoreCase = true) -> "source.go"
            file_name.endsWith(".json", ignoreCase = true) -> "source.json"
            file_name.equals(".env", ignoreCase = true) ||
                file_name.startsWith(".env.", ignoreCase = true) -> "source.dotenv"
            else -> "source.go" // GoStudio 默认按 Go 处理
        }
    }

    fun create_textmate_language(file_name: String): TextMateLanguage? {
        if (!textmate_initialized) return null

        val scope_name = get_language_scope_name(file_name)
        return try {
            TextMateLanguage.create(scope_name, false)
        } catch (e: Exception) {
            logger_manager.e("gostudio_application", "Failed to create TextMate language $scope_name: ${e.message}")
            null
        }
    }

    private fun setup_uncaught_exception_handler() {
        Thread.setDefaultUncaughtExceptionHandler { _, throwable ->
            handle_crash(throwable)
        }
    }

    private fun handle_crash(throwable: Throwable) {
        try {
            val log = throwable.javaClass.simpleName + ": " + (throwable.message ?: "Unknown error")
            val stack = get_stack_trace_string(throwable)
            val intent = Intent(this, crash_activity::class.java)
            intent.putExtra("crash_log", log)
            intent.putExtra("crash_stack", stack)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)
            startActivity(intent)
        } catch (e: Exception) {
            logger_manager.e("gostudio_application", "Failed to show crash activity: ${e.message}")
        }
    }

    private fun get_stack_trace_string(throwable: Throwable): String {
        val sw = java.io.StringWriter()
        val pw = java.io.PrintWriter(sw)
        throwable.printStackTrace(pw)
        return sw.toString()
    }

    private fun start_keep_alive_service() {
        try {
            val intent = Intent(this, keep_alive_service::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        } catch (e: Exception) {
            logger_manager.e("gostudio_application", "Failed to start service: ${e.message}")
        }
    }
}
