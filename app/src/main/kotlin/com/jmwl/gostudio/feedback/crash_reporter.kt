package com.jmwl.gostudio.feedback

import android.content.Context
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** 上报成功的返回：错误是否首次出现、该类型累计次数。 */
data class crash_report_result(
    val report_id: Long,
    val error_type_id: Long,
    val occurrence_count: Int,
    val first_report: Boolean
)

/** 「我的反馈」里的一条历史记录。 */
data class crash_report_record(
    val id: Long,
    val error_type_id: Long,
    val title: String,
    /** open = 未处理，resolved = 已修复 */
    val status: String,
    val app_version: String,
    val comment: String,
    val created_at: String
)

/**
 * 崩溃上报客户端（OkHttp，coroutine 封装）。
 * 地址与鉴权 key 统一来自 [gostudio_backend_config]。
 */
object crash_reporter {

    private const val max_payload_chars = 32 * 1024

    private val json_media_type = "application/json; charset=utf-8".toMediaType()

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()

    /**
     * 上报一次崩溃。崩溃页在独立进程外的普通 Activity 里调用，
     * 网络在 IO 线程执行；失败抛异常，由调用方决定提示与重试。
     */
    suspend fun report(
        context: Context,
        crash_log: String,
        crash_stack: String,
        comment: String
    ): crash_report_result = withContext(Dispatchers.IO) {
        val payload = JSONObject().apply {
            put("device_id", feedback_settings_store.device_id(context))
            put("device_brand", Build.BRAND ?: "")
            put("device_manufacturer", Build.MANUFACTURER ?: "")
            put("device_model", Build.MODEL ?: "")
            put("android_version", Build.VERSION.RELEASE ?: "")
            put("app_version", app_version(context))
            put("contact", feedback_settings_store.contact)
            put("comment", comment.take(500))
            put("crash_log", crash_log.take(max_payload_chars))
            put("crash_stack", crash_stack.take(max_payload_chars))
        }
        val request = Request.Builder()
            .url(base_url() + "/v1/reports")
            .header("X-GoStudio-Backend-Key", backend_key())
            .post(payload.toString().toRequestBody(json_media_type))
            .build()
        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IllegalStateException(error_message(body, response.code))
            }
            val decoded = JSONObject(body)
            crash_report_result(
                report_id = decoded.getLong("report_id"),
                error_type_id = decoded.getLong("error_type_id"),
                occurrence_count = decoded.getInt("occurrence_count"),
                first_report = decoded.getBoolean("first_report")
            )
        }
    }

    /** 本设备的上报历史（我的反馈）。 */
    suspend fun my_reports(context: Context): List<crash_report_record> =
        withContext(Dispatchers.IO) {
            val url = base_url() + "/v1/reports?device_id=" +
                android.net.Uri.encode(feedback_settings_store.device_id(context)) +
                "&page=1&page_size=50"
            val request = Request.Builder()
                .url(url)
                .header("X-GoStudio-Backend-Key", backend_key())
                .get()
                .build()
            client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    throw IllegalStateException(error_message(body, response.code))
                }
                val records = JSONObject(body).getJSONArray("reports")
                buildList {
                    for (index in 0 until records.length()) {
                        val item = records.getJSONObject(index)
                        add(
                            crash_report_record(
                                id = item.getLong("id"),
                                error_type_id = item.getLong("error_type_id"),
                                title = item.optString("title"),
                                status = item.optString("status", "open"),
                                app_version = item.optString("app_version"),
                                comment = item.optString("comment"),
                                created_at = item.optString("created_at")
                            )
                        )
                    }
                }
            }
        }

    private fun base_url(): String =
        com.jmwl.gostudio.editor.config.gostudio_backend_config.CRASH_BASE_URL.trimEnd('/')

    private fun backend_key(): String =
        com.jmwl.gostudio.editor.config.gostudio_backend_config.CRASH_BACKEND_KEY

    private fun app_version(context: Context): String = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: ""
    } catch (_: Exception) {
        ""
    }

    private fun error_message(body: String, code: Int): String = try {
        JSONObject(body).optString("error").ifBlank { "HTTP $code" }
    } catch (_: Exception) {
        "HTTP $code"
    }
}
