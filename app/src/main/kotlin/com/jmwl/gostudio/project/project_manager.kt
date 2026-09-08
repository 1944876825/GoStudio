package com.jmwl.gostudio.project

import com.jmwl.gostudio.gostudio_application
import com.jmwl.gostudio.toolchain.toolchain_runtime_provider
import com.jmwl.gostudio.toolchain.toolchain_manager

import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object project_manager {
    private const val max_recent_projects = 20
    private const val project_config_dir_name = ".gostudio"
    private const val project_config_file_name = ".gostudio-project.json"
    // 旧版（还叫 XCode 时）的项目配置目录名，仅用于一次性迁移（重命名为 .gostudio/），不可删除
    private const val legacy_project_config_dir_name = ".xcode"
    private const val legacy_project_config_file_name = ".xcode-project.json"
    private const val go_mod_file_name = "go.mod"
    private val json = GsonBuilder().setPrettyPrinting().create()
    private val valid_project_name = Regex("^[a-zA-Z_][a-zA-Z0-9_]*$")
    private val supported_goos = setOf("linux", "darwin", "android", "windows", "freebsd")
    private val supported_goarch = setOf("amd64", "arm64", "386", "arm")
    private val supported_build_types = setOf("Debug", "Release")

    /**
     * 项目统一存放于 app 内部存储（proot 已绑定到 guest 内 /home/gostudio）。
     * 放内部存储是为了让 proot（native 进程）能稳定访问项目文件——外部存储的
     * FUSE 路径 proot 无法可靠 bind，会导致 go build / gopls 失败。
     */
    fun default_projects_dir(): File {
        val dir = File(toolchain_runtime_provider.paths().gostudio_dir, "projects")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /**
     * 列出内部存储项目目录下的所有 Go 项目（含 go.mod 的子目录）。
     * 用于最近项目记录的初始化发现。
     */
    fun list_local_projects(): List<File> {
        val root = default_projects_dir()
        return root.listFiles()
            ?.filter { it.isDirectory && File(it, go_mod_file_name).isFile }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()
    }

    suspend fun create_project(
        name: String,
        template_id: String,
        app_name: String = "",
        package_name: String = ""
    ): Result<File> = withContext(Dispatchers.IO) {
        try {
            val project_name = name.trim()

            if (!valid_project_name.matches(project_name)) {
                return@withContext Result.failure(IllegalArgumentException("项目名称只能包含字母、数字和下划线，且不能以数字开头"))
            }

            val project_dir = File(default_projects_dir(), project_name)
            if (project_dir.exists()) {
                return@withContext Result.failure(IllegalStateException("项目已存在"))
            }

            if (!project_dir.mkdirs()) {
                return@withContext Result.failure(IllegalStateException("无法创建项目目录"))
            }

            // Go 项目结构：go.mod + main.go（按模板选不同内容）
            create_go_project(project_dir, project_name, template_id)

            // IDE 配置：读取 go.mod 中的 go 版本，记录到项目配置
            val go_version = read_go_version_from_mod(project_dir)
            val app_config = if (template_id == "app-ui") {
                project_app_config(
                    app_name = app_name.trim().ifBlank { project_name },
                    package_name = package_name.trim().ifBlank { "com.gs.$project_name" }
                )
            } else project_app_config()
            write_project_config(
                dir = project_dir,
                name = project_name,
                go_version = go_version,
                template_id = template_id,
                build = project_build_config(),
                app = app_config
            )
            Result.success(project_dir)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun create_project_entry(
        project_path: String,
        parent_path: String,
        name: String,
        directory: Boolean,
        template_id: String = "PLAIN"
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val entry_name = normalize_project_entry_name(name)
            require(entry_name.isNotBlank()) { "名称不能为空" }

            val root = File(project_path).canonicalFile
            val root_path = root.toPath()
            val parent_dir = File(parent_path).canonicalFile
            val parent_dir_path = parent_dir.toPath()
            require(parent_dir.exists() && parent_dir.isDirectory && parent_dir_path.startsWith(root_path)) {
                "目标文件夹不存在或不在项目中"
            }

            val target = File(parent_dir, entry_name)
            val target_path = target.canonicalFile.toPath()
            require(target_path.startsWith(root_path)) { "路径不能超出项目目录" }
            require(!target.exists()) { if (directory) "文件夹已存在" else "文件已存在" }

            if (directory) {
                if (!target.mkdirs()) {
                    throw IllegalStateException("无法创建文件夹")
                }
            } else {
                target.parentFile?.mkdirs()
                if (!target.createNewFile()) {
                    throw IllegalStateException("无法创建文件")
                }
                create_project_entry_content(
                    template_id = template_id,
                    target = target,
                    parent_dir = parent_dir,
                    project_root = root
                )?.let { target.writeText(it) }
            }
            target
        }
    }

    suspend fun rename_project_entry(
        project_path: String,
        path: String,
        new_name: String
    ): Result<Pair<String, String>> = withContext(Dispatchers.IO) {
        runCatching {
            val entry_name = normalize_project_entry_name(new_name)
            require(entry_name.isNotBlank()) { "名称不能为空" }
            require(!entry_name.contains('/') && !entry_name.contains('\\')) { "名称不能包含路径分隔符" }

            val root = File(project_path).canonicalFile
            val source = File(path).canonicalFile
            val source_path = source.toPath()
            require(source.exists() && source_path.startsWith(root.toPath())) { "节点不存在或不在项目中" }
            require(source_path != root.toPath()) { "不能重命名项目根目录" }

            val parent = source.parentFile ?: throw IllegalStateException("无法读取父目录")
            val target = File(parent, entry_name).canonicalFile
            require(target.toPath().startsWith(root.toPath())) { "路径不能超出项目目录" }
            require(!target.exists()) { "同名文件或文件夹已存在" }
            if (!source.renameTo(target)) {
                throw IllegalStateException("重命名失败")
            }
            source.absolutePath to target.absolutePath
        }
    }

    suspend fun resolve_project_entry_for_delete(project_path: String, path: String): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val root = File(project_path).canonicalFile
            val source = File(path).canonicalFile
            val source_path = source.toPath()
            require(source.exists() && source_path.startsWith(root.toPath())) { "节点不存在或不在项目中" }
            require(source_path != root.toPath()) { "不能删除项目根目录" }
            source
        }
    }

    suspend fun delete_project_entry(project_path: String, path: String): Result<Pair<String, String>> = withContext(Dispatchers.IO) {
        runCatching {
            val source = resolve_project_entry_for_delete(project_path, path).getOrThrow()
            val source_path = source.absolutePath
            val parent_path = source.parentFile?.absolutePath ?: File(project_path).absolutePath
            val deleted = if (source.isDirectory) source.deleteRecursively() else source.delete()
            require(deleted) { "删除失败" }
            source_path to parent_path
        }
    }

    private fun create_project_entry_content(
        template_id: String,
        target: File,
        parent_dir: File,
        project_root: File
    ): String? {
        return when (template_id) {
            "GO_SOURCE" -> "package ${go_package_name(parent_dir, project_root)}\n"
            "GO_TEST" -> go_test_file_content(target, parent_dir, project_root)
            "GO_MOD" -> go_mod_file_content(parent_dir, project_root)
            "GO_WORK" -> "go ${go_version(project_root)}\n\nuse .\n"
            else -> null
        }
    }

    private fun go_test_file_content(target: File, parent_dir: File, project_root: File): String {
        val test_name = target.name
            .removeSuffix("_test.go")
            .removeSuffix(".go")
            .split(Regex("[^A-Za-z0-9]+"))
            .filter { it.isNotBlank() }
            .joinToString("") { part ->
                part.replaceFirstChar { it.uppercaseChar() }
            }
            .ifBlank { "Example" }
        return """package ${go_package_name(parent_dir, project_root)}

import "testing"

func Test$test_name(t *testing.T) {
    t.Log("write your test here")
}
"""
    }

    private fun go_mod_file_content(parent_dir: File, project_root: File): String {
        val root_module = File(project_root, "go.mod")
            .takeIf { it.isFile }
            ?.readText()
            ?.let { Regex("(?m)^\\s*module\\s+(\\S+)").find(it)?.groupValues?.getOrNull(1) }
        val module_base = root_module ?: go_identifier(project_root.name.ifBlank { "example" })
        val relative_dir = project_root.toPath().relativize(parent_dir.toPath())
        val module_path = if (relative_dir.nameCount == 0) {
            module_base
        } else {
            val relative_module = relative_dir.joinToString("/") { path -> go_identifier(path.toString()) }
            "$module_base/$relative_module"
        }
        return "module $module_path\ngo ${go_version(project_root)}\n"
    }

    private fun go_version(project_root: File): String {
        return File(project_root, "go.mod")
            .takeIf { it.isFile }
            ?.readText()
            ?.let { Regex("(?m)^\\s*go\\s+([0-9]+(?:\\.[0-9]+)*(?:[A-Za-z0-9.]+)?)").find(it)?.groupValues?.getOrNull(1) }
            ?: "1.22"
    }

    private fun go_package_name(directory: File, project_root: File): String {
        val sibling_package = directory
            .listFiles { file -> file.isFile && file.extension.equals("go", ignoreCase = true) }
            ?.firstOrNull()
            ?.readText()
            ?.let { Regex("(?m)^\\s*package\\s+([A-Za-z_][A-Za-z0-9_]*)").find(it)?.groupValues?.getOrNull(1) }
        if (sibling_package != null) return sibling_package
        if (directory.canonicalPath == project_root.canonicalPath) return "main"
        return go_identifier(directory.name.ifBlank { "main" })
    }

    private fun go_identifier(value: String): String {
        val identifier = value.replace(Regex("[^A-Za-z0-9_]"), "_").lowercase().ifBlank { "main" }
        return if (identifier.first().isDigit()) "_$identifier" else identifier
    }

    private fun normalize_project_entry_name(name: String): String {
        return name.trim().trim('/', '\\')
    }

    /**
     * 创建 Go 项目文件（go.mod + main.go）。
     *
     * @param template_id 模板：hello(默认)/http-request/http/cli/webapi/database/gin/gorm
     * Go 项目仅 go.mod + main.go 即可 go run。
     */
    private fun create_go_project(dir: File, name: String, template_id: String) {
        val mainGo = when (template_id) {
            "app-ui" -> go_app_ui_template()
            "http-request" -> go_http_request_template()
            "http" -> go_http_template(name)
            "cli" -> go_cli_template(name)
            "webapi" -> go_webapi_template(name)
            "database" -> go_database_template(name)
            "gin" -> go_gin_template(name)
            "gorm" -> go_gorm_template(name)
            else -> go_hello_template(name)
        }
        File(dir, "main.go").writeText(mainGo)
        File(dir, "go.mod").writeText(go_mod_content(name, template_id))
        if (template_id == "app-ui") {
            File(dir, "layout.xml").writeText(app_ui_layout_template())
            File(File(dir, "floats").apply { mkdirs() }, "note.xml").writeText(app_ui_float_layout_template())
            create_app_ui_runtime(dir)
        }
    }

    /**
     * App 界面模板：复制内置的 gostudio SDK（纯 Go，无第三方依赖）到项目内，
     * go.mod 通过 replace 本地引用，离线可用。
     */
    private fun create_app_ui_runtime(dir: File) {
        val sdk_dir = File(dir, "gostudio")
        sdk_dir.mkdirs()
        val assets = gostudio_application.instance.assets
        val asset_dir = "templates/app-ui/gostudio"
        assets.list(asset_dir)?.forEach { file_name ->
            assets.open("$asset_dir/$file_name").use { input ->
                File(sdk_dir, file_name).outputStream().use { output -> input.copyTo(output) }
            }
        }
    }

    /** 默认悬浮窗布局示例。 */
    private fun app_ui_float_layout_template(): String = """
<LinearLayout orientation="vertical" background="#F21B1C1F" padding="14dp">

    <TextView id="float_text" text="悬浮窗内容" textColor="#E6E6E6" textSize="14sp"/>
    <Button id="float_btn" text="操作" layout_marginTop="10dp"/>

</LinearLayout>
""".trimIndent() + "\n"

    /** App 界面模板：AndLua 式 XML 布局 + Go 逻辑，宿主内直接运行 */
    private fun go_app_ui_template(): String = """
package main

import "gostudio/appsdk"

func main() {
	app := appsdk.Start()
	tv := app.Text("tv")

	app.Button("btn").OnClick(func() {
		name, _ := app.Text("input").GetText()
		if name == "" {
			name = "GoStudio"
		}
		tv.SetText("你好，" + name + "！")
	})

	app.Run()
}
""".trimIndent() + "\n"

    /** App 界面模板的布局文件（AndLua 语法） */
    private fun app_ui_layout_template(): String = """
<LinearLayout orientation="vertical" gravity="center" padding="24dp">

    <TextView
        id="tv"
        text="你好，GoStudio！"
        textSize="24sp"
        layout_marginBottom="24dp"/>

    <EditText
        id="input"
        hint="输入你的名字"
        layout_width="match_parent"/>

    <Button
        id="btn"
        text="打招呼"
        layout_marginTop="16dp"/>

</LinearLayout>
""".trimIndent() + "\n"

    /** Hello World 模板 */
    private fun go_hello_template(name: String): String = """
package main

import "fmt"

func main() {
	fmt.Println("Hello from $name!")
}
""".trimIndent() + "\n"

    /** 数据库模板（database/sql + 纯 Go SQLite 驱动） */
    private fun go_database_template(name: String): String = """
package main

import (
	"database/sql"
	"fmt"
	_ "modernc.org/sqlite"
)

func main() {
	db, err := sql.Open("sqlite", "./$name.db")
	if err != nil {
		fmt.Println("打开数据库失败:", err)
		return
	}
	defer db.Close()

	_, err = db.Exec(`
		CREATE TABLE IF NOT EXISTS users (
			id INTEGER PRIMARY KEY AUTOINCREMENT,
			name TEXT NOT NULL,
			age INTEGER NOT NULL
		)
	`)
	if err != nil {
		fmt.Println("建表失败:", err)
		return
	}

	result, err := db.Exec("INSERT INTO users (name, age) VALUES (?, ?)", "Alice", 20)
	if err != nil {
		fmt.Println("插入失败:", err)
		return
	}
	id, _ := result.LastInsertId()
	fmt.Println("新建用户 ID:", id)

	rows, err := db.Query("SELECT id, name, age FROM users")
	if err != nil {
		fmt.Println("查询失败:", err)
		return
	}
	defer rows.Close()

	for rows.Next() {
		var user struct {
			ID   int64
			Name string
			Age  int64
		}
		if err := rows.Scan(&user.ID, &user.Name, &user.Age); err != nil {
			fmt.Println("读取失败:", err)
			return
		}
		fmt.Printf("%d %s %d\n", user.ID, user.Name, user.Age)
	}
}
""".trimIndent() + "\n"

    /** Gin 框架模板 */
    private fun go_gin_template(name: String): String = """
package main

import (
	"fmt"
	"net/http"

	"github.com/gin-gonic/gin"
)

type createUserRequest struct {
	Name string `json:"name" binding:"required"`
	Age  int    `json:"age" binding:"gte=0"`
}

func main() {
	r := gin.Default()

	r.GET("/", func(c *gin.Context) {
		c.JSON(http.StatusOK, gin.H{
			"message": "Hello from $name!",
		})
	})

	r.GET("/users/:id", func(c *gin.Context) {
		c.JSON(http.StatusOK, gin.H{
			"id": c.Param("id"),
		})
	})

	r.POST("/users", func(c *gin.Context) {
		var req createUserRequest
		if err := c.ShouldBindJSON(&req); err != nil {
			c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
			return
		}
		c.JSON(http.StatusCreated, req)
	})

	fmt.Println("Gin server starting on :8080...")
	if err := r.Run(":8080"); err != nil {
		fmt.Println("启动失败:", err)
	}
}
""".trimIndent() + "\n"

    /** GORM 框架模板（纯 Go SQLite 驱动） */
    private fun go_gorm_template(name: String): String = """
package main

import (
	"fmt"

	"github.com/glebarez/sqlite"
	"gorm.io/gorm"
)

type User struct {
	ID   uint   `gorm:"primaryKey"`
	Name string `gorm:"size:64;not null"`
	Age  int
}

func main() {
	db, err := gorm.Open(sqlite.Open("$name.db"), &gorm.Config{})
	if err != nil {
		fmt.Println("连接数据库失败:", err)
		return
	}

	if err := db.AutoMigrate(&User{}); err != nil {
		fmt.Println("迁移失败:", err)
		return
	}

	user := User{Name: "Alice", Age: 20}
	if err := db.Create(&user).Error; err != nil {
		fmt.Println("创建失败:", err)
		return
	}

	if err := db.Model(&user).Updates(map[string]any{"age": 21}).Error; err != nil {
		fmt.Println("更新失败:", err)
		return
	}

	var users []User
	if err := db.Limit(10).Find(&users).Error; err != nil {
		fmt.Println("查询失败:", err)
		return
	}

	for _, item := range users {
		fmt.Printf("%d %s %d\n", item.ID, item.Name, item.Age)
	}
}
""".trimIndent() + "\n"

    /** 依赖型模板在 go.mod 中声明直接依赖，首次运行前需 go mod tidy 生成 go.sum。 */
    private fun go_mod_content(name: String, template_id: String): String {
        val base = "module $name\n\ngo 1.21\n"
        if (template_id == "app-ui") {
            return base + "\nrequire gostudio/appsdk v0.0.0\n\nreplace gostudio/appsdk => ./gostudio\n"
        }
        val dependencies = when (template_id) {
            "database" -> listOf("modernc.org/sqlite v1.33.1")
            "gin" -> listOf("github.com/gin-gonic/gin v1.10.0")
            "gorm" -> listOf(
                "github.com/glebarez/sqlite v1.11.0",
                "gorm.io/gorm v1.25.12"
            )
            else -> return base
        }
        val requires = dependencies.joinToString("\n") { "\t$it" }
        return "$base\nrequire (\n$requires\n)\n"
    }

    /** HTTP 请求模板：请求天气 API 并解析 JSON。 */
    private fun go_http_request_template(): String = """
package main

import (
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/url"
)

type weatherResponse struct {
	City  string    `json:"city"`
	Today weatherDay `json:"1"`
}

type weatherDay struct {
	Date    string `json:"date"`
	Weather string `json:"weather"`
	High    string `json:"high"`
	Low     string `json:"low"`
}

func main() {
	query := url.Values{}
	query.Set("dz", "北京")
	query.Set("return", "json")

	api := "https://api.tangdouz.com/tq.php?" + query.Encode()
	resp, err := http.Get(api)
	if err != nil {
		fmt.Println("请求失败:", err)
		return
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusOK {
		fmt.Println("接口返回状态:", resp.StatusCode)
		return
	}

	body, err := io.ReadAll(resp.Body)
	if err != nil {
		fmt.Println("读取响应失败:", err)
		return
	}

	var weather weatherResponse
	if err := json.Unmarshal(body, &weather); err != nil {
		fmt.Println("解析 JSON 失败:", err)
		return
	}

	fmt.Printf("%s %s：%s，%s ~ %s\n", weather.City, weather.Today.Date, weather.Today.Weather, weather.Today.Low, weather.Today.High)
}
""".trimIndent() + "\n"

    /** HTTP 服务器模板（net/http） */
    private fun go_http_template(name: String): String = """
package main

import (
	"fmt"
	"net/http"
)

func main() {
	http.HandleFunc("/", func(w http.ResponseWriter, r *http.Request) {
		fmt.Fprintf(w, "Hello from $name!")
	})
	fmt.Println("Server starting on :8080...")
	http.ListenAndServe(":8080", nil)
}
""".trimIndent() + "\n"

    /** CLI 工具模板（os.Args） */
    private fun go_cli_template(name: String): String = """
package main

import (
	"fmt"
	"os"
)

func main() {
	if len(os.Args) < 2 {
		fmt.Println("Usage: $name <name>")
		return
	}
	fmt.Printf("Hello, %s! from $name\n", os.Args[1])
}
""".trimIndent() + "\n"

    /** Web API 模板（标准库 JSON API） */
    private fun go_webapi_template(name: String): String = """
package main

import (
	"encoding/json"
	"fmt"
	"net/http"
)

type Response struct {
	Message string `json:"message"`
}

func main() {
	http.HandleFunc("/api", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		json.NewEncoder(w).Encode(Response{Message: "Hello from $name API"})
	})
	fmt.Println("API server on :8080, try /api")
	http.ListenAndServe(":8080", nil)
}
""".trimIndent() + "\n"

    private fun project_config_file(project_dir: File): File {
        return File(File(project_dir, project_config_dir_name), project_config_file_name)
    }

    /**
     * Go 项目不需要 .clang-format（用 gofmt/goimports）。
     * 此函数保留为空操作，仅为兼容外部调用点，不再写入任何文件。
     */
    fun ensure_project_clang_format(path: String): Result<Unit> = Result.success(Unit)

    fun ensure_project_config(path: String): Result<Unit> {
        return runCatching {
            val project_dir = File(path)
            require(project_dir.exists() && project_dir.isDirectory) { "项目目录不存在" }
            require(
                File(project_dir, go_mod_file_name).isFile
            ) { "不是 Go 项目（缺少 go.mod）" }

            migrate_legacy_project_config(project_dir)

            val config_file = project_config_file(project_dir)
            if (config_file.isFile) return@runCatching

            write_project_config(
                dir = project_dir,
                name = project_dir.name.ifBlank { "GoProject" },
                go_version = read_go_version_from_mod(project_dir),
                template_id = "imported",
                build = project_build_config()
            )
        }
    }

    /**
     * 一次性迁移：若存在旧的 .xcode/.xcode-project.json 而新 .gostudio/ 不存在，则重命名旧目录。
     * 失败不阻断（按默认 Go 配置重建）。
     */
    private fun migrate_legacy_project_config(project_dir: File) {
        val new_dir = File(project_dir, project_config_dir_name)
        if (new_dir.exists()) return
        val legacy_dir = File(project_dir, legacy_project_config_dir_name)
        if (!legacy_dir.exists()) return
        runCatching { legacy_dir.renameTo(new_dir) }
    }

    /**
     * 从 go.mod 读取 Go 版本（如 `go 1.21` → "1.21"）。无法解析时返回空串。
     */
    private fun read_go_version_from_mod(project_dir: File): String {
        val go_mod = File(project_dir, go_mod_file_name)
        if (!go_mod.isFile) return ""
        return Regex("^\\s*go\\s+(\\d+\\.\\d+)\\b", RegexOption.MULTILINE)
            .find(go_mod.readText())
            ?.groupValues
            ?.getOrNull(1)
            .orEmpty()
    }

    private fun write_project_config(
        dir: File,
        name: String,
        go_version: String,
        template_id: String,
        build: project_build_config = project_build_config(),
        app: project_app_config = project_app_config()
    ) {
        val normalized_build = normalize_project_build_config(build)
        val config = project_config(
            name = name,
            go_version = go_version,
            template = template_id,
            created = System.currentTimeMillis(),
            build = normalized_build,
            app = app
        )
        project_config_file(dir).apply {
            parentFile?.mkdirs()
            writeText(json.toJson(config) + "\n")
        }
    }

    fun read_project_build_config(path: String): project_build_config {
        return read_project_ide_config(path).build
    }

    fun read_project_ide_config(path: String): project_ide_config {
        return try {
            val config_file = project_config_file(File(path))
            val config = json.fromJson(config_file.readText(), project_config::class.java)
            project_ide_config(
                go_version = config?.go_version.orEmpty(),
                build = normalize_project_build_config(config?.build ?: project_build_config()),
                // 旧配置文件没有 version_code，Gson 反序列化会得到 0，这里归一到合法值
                app = (config?.app ?: project_app_config()).let { app ->
                    app.copy(version_code = app.version_code.coerceIn(1, 2_000_000_000))
                }
            )
        } catch (_: Exception) {
            project_ide_config()
        }
    }

    fun save_project_ide_config(path: String, ide_config: project_ide_config): Result<Unit> {
        return runCatching {
            val project_dir = File(path)
            require(
                File(project_dir, go_mod_file_name).isFile
            ) { "不是 Go 项目，无法保存配置（缺少 go.mod）" }
            require(toolchain_manager.is_go_installed()) { "Go 工具链未安装" }

            val config_file = project_config_file(project_dir)
            val config = json.fromJson(config_file.readText(), project_config::class.java)
                ?: throw IllegalStateException("项目配置文件损坏")
            val normalized_build = normalize_project_build_config(ide_config.build)
            config_file.writeText(
                json.toJson(
                    config.copy(
                        go_version = ide_config.go_version,
                        build = normalized_build,
                        app = ide_config.app
                    )
                ) + "\n"
            )
        }
    }

    private fun normalize_project_build_config(build: project_build_config): project_build_config {
        val goos = build.goos.takeIf { it in supported_goos } ?: "linux"
        val goarch = build.goarch.takeIf { it in supported_goarch } ?: "arm64"
        val build_type = build.build_type.takeIf { it in supported_build_types } ?: "Debug"
        val parallel_jobs = build.parallel_jobs.coerceIn(0, 8)
        val build_tags = build.build_tags.orEmpty().trim()
        val ldflags = build.ldflags.orEmpty().trim()
        val run_entry = normalize_go_run_entry(build.run_entry)
        return project_build_config(
            goos = goos,
            goarch = goarch,
            build_type = build_type,
            build_tags = build_tags,
            ldflags = ldflags,
            trimpath = build.trimpath,
            parallel_jobs = parallel_jobs,
            run_entry = run_entry
        )
    }

    private fun normalize_go_run_entry(value: String): String {
        val trimmed = value.trim().replace(File.separatorChar, '/')
        if (trimmed.isBlank() || trimmed == ".") return "."
        if (trimmed.startsWith("/") || trimmed.startsWith("../") || trimmed.contains("/../") || trimmed == "..") return "."
        return "./" + trimmed.removePrefix("./")
    }

    fun get_project_last_opened(path: String): String {
        return try {
            val last_modified = File(path).takeIf { it.exists() }?.lastModified() ?: System.currentTimeMillis()
            SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(last_modified))
        } catch (e: Exception) {
            SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        }
    }

    fun update_project_opened_time(path: String) {
        try {
            val project_dir = File(path)
            if (project_dir.exists()) {
                project_dir.setLastModified(System.currentTimeMillis())
            }
        } catch (_: Exception) {
        }
    }

    fun get_project_info(path: String): project_info? {
        return try {
            require_gostudio_project_info(path)
        } catch (_: Exception) {
            null
        }
    }

    private fun require_gostudio_project_info(path: String): project_info {
        val project_dir = File(path)
        require(project_dir.exists() && project_dir.isDirectory) { "项目目录不存在" }
        require(
            File(project_dir, go_mod_file_name).isFile
        ) { "不是 Go 项目（缺少 go.mod）" }

        migrate_legacy_project_config(project_dir)
        val config_file = project_config_file(project_dir)
        require(config_file.isFile) { "不是 GoStudio 项目，缺少 $project_config_dir_name/$project_config_file_name" }

        val config = try {
            json.fromJson(config_file.readText(), project_config::class.java)
        } catch (e: Exception) {
            throw IllegalStateException("项目配置文件损坏")
        } ?: throw IllegalStateException("项目配置文件损坏")

        require(config.name.isNotBlank()) { "项目配置缺少名称" }
        require(config.template.isNotBlank()) { "项目配置缺少模板类型" }

        return project_info(
            name = config.name,
            path = project_dir.absolutePath,
            go_version = config.go_version,
            template = config.template,
            app_icon_path = resolve_app_icon_path(project_dir, config)
        )
    }

    /** App 界面项目的图标文件（配置的 icon_path 落到项目目录），不存在则空串。 */
    private fun resolve_app_icon_path(project_dir: File, config: project_config): String {
        if (config.template != "app-ui") return ""
        return config.app.icon_path
            .takeIf { it.isNotBlank() }
            ?.let { File(project_dir, it) }
            ?.takeIf { it.isFile }
            ?.absolutePath
            .orEmpty()
    }

    suspend fun get_recent_projects(): List<recent_project_info> = withContext(Dispatchers.IO) {
        val records = load_recent_project_records()
            .filter { runCatching { require_gostudio_project_info(it.path) }.isSuccess }
            .sortedByDescending { it.opened_at }
            .take(max_recent_projects)
        write_recent_project_records(records)
        records.map { create_recent_project_info(it) }
    }

    suspend fun check_project_toolchain(path: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val info = require_gostudio_project_info(path)
            val project_dir = File(info.path)
            val environment = toolchain_manager.project_environment(project_dir.absolutePath)
            require(environment.missing.isEmpty()) { environment.missing.joinToString("；") }
        }
    }

    suspend fun add_recent_project(path: String): Result<recent_project_info> = withContext(Dispatchers.IO) {
        try {
            val project_path = normalize_project_path(path)
            if (project_path.isBlank()) {
                return@withContext Result.failure(IllegalArgumentException("项目路径不能为空"))
            }

            val project_dir = File(project_path)
            if (!project_dir.exists()) {
                return@withContext Result.failure(IllegalArgumentException("项目目录不存在"))
            }
            if (!project_dir.isDirectory) {
                return@withContext Result.failure(IllegalArgumentException("项目路径不是目录"))
            }

            val now = System.currentTimeMillis()
            val info = require_gostudio_project_info(project_path)
            val record = recent_project_record(
                name = info.name,
                path = project_path,
                go_version = info.go_version,
                template = info.template,
                opened_at = now
            )

            val records = read_recent_project_records()
                .filterNot { normalize_project_path(it.path) == project_path }
                .toMutableList()
            records.add(0, record)
            write_recent_project_records(records.take(max_recent_projects))
            runCatching { project_dir.setLastModified(now) }

            Result.success(create_recent_project_info(record))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun remove_recent_project(path: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val project_path = normalize_project_path(path)
            val records = load_recent_project_records()
                .filterNot { normalize_project_path(it.path) == project_path }
            write_recent_project_records(records)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun recent_projects_file(): File {
        return File(toolchain_runtime_provider.paths().home_dir.parentFile, "recent_projects.json")
    }

    private fun load_recent_project_records(): MutableList<recent_project_record> {
        val file = recent_projects_file()
        if (file.exists()) {
            return read_recent_project_records()
        }

        val discovered_records = discover_project_records()
            .sortedByDescending { it.opened_at }
            .take(max_recent_projects)
        write_recent_project_records(discovered_records)
        return discovered_records.toMutableList()
    }

    private fun read_recent_project_records(): MutableList<recent_project_record> {
        return try {
            val file = recent_projects_file()
            if (!file.exists()) return mutableListOf()

            val type = object : TypeToken<List<recent_project_record>>() {}.type
            val records = json.fromJson<List<recent_project_record>>(file.readText(), type) ?: emptyList()
            records
                .filter { it.path.isNotBlank() }
                .distinctBy { normalize_project_path(it.path) }
                .toMutableList()
        } catch (_: Exception) {
            mutableListOf()
        }
    }

    private fun write_recent_project_records(records: List<recent_project_record>) {
        val file = recent_projects_file()
        file.parentFile?.mkdirs()
        file.writeText(json.toJson(records.take(max_recent_projects)))
    }

    private fun discover_project_records(): List<recent_project_record> {
        return try {
            val projects = list_local_projects()
                .sortedByDescending { it.lastModified() }
                .take(max_recent_projects)

            projects.mapNotNull { project_dir ->
                val info = runCatching { require_gostudio_project_info(project_dir.absolutePath) }.getOrNull()
                    ?: return@mapNotNull null
                recent_project_record(
                    name = info.name,
                    path = normalize_project_path(project_dir.absolutePath),
                    go_version = info.go_version,
                    template = info.template,
                    opened_at = project_dir.lastModified().takeIf { it > 0L } ?: System.currentTimeMillis()
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun normalize_project_path(path: String): String {
        val trimmed_path = path.trim()
        if (trimmed_path.isBlank()) return ""

        return try {
            File(trimmed_path).canonicalPath
        } catch (_: Exception) {
            File(trimmed_path).absolutePath
        }
    }

    private fun create_recent_project_info(record: recent_project_record): recent_project_info {
        val project_info = get_project_info(record.path)
        val project_dir = File(record.path)

        return recent_project_info(
            name = project_info?.name ?: record.name.ifBlank { project_dir.name },
            path = record.path,
            go_version = project_info?.go_version ?: record.go_version,
            template = project_info?.template ?: record.template.ifBlank { "hello" },
            app_icon_path = project_info?.app_icon_path.orEmpty(),
            last_opened = format_last_opened(record.opened_at),
            opened_at = record.opened_at
        )
    }

    private fun format_last_opened(opened_at: Long): String {
        val diff = (System.currentTimeMillis() - opened_at).coerceAtLeast(0L)
        val minute = 60_000L
        val hour = 60 * minute
        val day = 24 * hour

        return when {
            diff < minute -> "刚刚"
            diff < hour -> "${diff / minute}分钟前"
            diff < day -> "${diff / hour}小时前"
            diff < 2 * day -> "昨天"
            diff < 7 * day -> "${diff / day}天前"
            else -> SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(opened_at))
        }
    }

}

data class project_info(
    val name: String,
    val path: String,
    val go_version: String,
    val template: String,
    val app_icon_path: String = ""
)

data class project_build_config(
    val goos: String = "linux",
    val goarch: String = "arm64",
    val build_type: String = "Debug",
    val build_tags: String = "",
    val ldflags: String = "",
    val trimpath: Boolean = false,
    val parallel_jobs: Int = 0,
    val run_entry: String = "."
)

data class project_app_config(
    val app_name: String = "",
    val package_name: String = "",
    val version_name: String = "1.0",
    val version_code: Int = 1,
    val icon_path: String = "icon.png"
)

data class project_ide_config(
    val go_version: String = "",
    val build: project_build_config = project_build_config(),
    val app: project_app_config = project_app_config()
)

private data class project_config(
    val name: String = "",
    val go_version: String = "",
    val template: String = "",
    val created: Long = 0L,
    val build: project_build_config = project_build_config(),
    val app: project_app_config = project_app_config()
)

data class recent_project_info(
    val name: String,
    val path: String,
    val go_version: String,
    val template: String,
    val app_icon_path: String = "",
    val last_opened: String,
    val opened_at: Long
)

private data class recent_project_record(
    val name: String = "",
    val path: String = "",
    val go_version: String = "",
    val template: String = "",
    val opened_at: Long = 0L
)
