package com.jmwl.gostudio.plugins

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** 插件市场索引条目（对应 gostudio-plugins 仓库 index.json） */
data class marketplace_entry(
    val id: String,
    val name: String,
    val version: String,
    val description: String,
    val author: String,
    val min_app_version: Int,
    val download_url: String,
    val sha256: String,
    val size: Long
)

/**
 * 插件市场：从 GitHub 上的 gostudio-plugins 仓库拉取索引并下载安装。
 *
 * 索引：https://raw.githubusercontent.com/by-yitong/gostudio-plugins/main/index.json
 * 插件包：dist/<id>-<version>.zip（raw 直链下载）
 */
object plugin_marketplace {

    const val INDEX_URL =
        "https://raw.githubusercontent.com/by-yitong/gostudio-plugins/main/index.json"

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    /** 拉取并解析插件索引 */
    suspend fun fetch_index(): Result<List<marketplace_entry>> = withContext(Dispatchers.IO) {
        runCatching {
            val response = client.newCall(Request.Builder().url(INDEX_URL).build()).execute()
            response.use {
                if (!it.isSuccessful) throw IllegalStateException("索引拉取失败：HTTP ${it.code}")
                val json = JSONObject(it.body!!.string())
                val array = json.optJSONArray("plugins")
                    ?: return@runCatching emptyList<marketplace_entry>()
                (0 until array.length()).mapNotNull { i ->
                    val item = array.optJSONObject(i) ?: return@mapNotNull null
                    val id = item.optString("id")
                    val version = item.optString("version")
                    val url = item.optString("download_url")
                    if (id.isBlank() || version.isBlank() || url.isBlank()) return@mapNotNull null
                    marketplace_entry(
                        id = id,
                        name = item.optString("name").ifBlank { id },
                        version = version,
                        description = item.optString("description"),
                        author = item.optString("author"),
                        min_app_version = item.optInt("min_app_version", 0),
                        download_url = url,
                        sha256 = item.optString("sha256"),
                        size = item.optLong("size", 0)
                    )
                }
            }
        }
    }

    /** 下载插件 zip 并安装（返回安装成功的 manifest） */
    suspend fun install(context: android.content.Context, entry: marketplace_entry): Result<plugin_manifest> =
        withContext(Dispatchers.IO) {
            runCatching {
                val response = client.newCall(Request.Builder().url(entry.download_url).build()).execute()
                response.use {
                    if (!it.isSuccessful) throw IllegalStateException("下载失败：HTTP ${it.code}")
                    val bytes = it.body!!.bytes()
                    // 校验值缺失即拒绝：市场插件必须可验证完整性，防止索引被篡改后装进未校验的包
                    if (entry.sha256.isBlank()) {
                        throw IllegalStateException("插件包缺少 sha256 校验值，已拒绝安装")
                    }
                    val digest = java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
                    val actual = digest.joinToString("") { b -> "%02x".format(b) }
                    if (!actual.equals(entry.sha256, ignoreCase = true)) {
                        throw IllegalStateException("校验失败：文件可能被篡改")
                    }
                    bytes.inputStream().use { stream ->
                        plugin_manager.install(context, stream).getOrThrow()
                    }
                }
            }
        }
}
