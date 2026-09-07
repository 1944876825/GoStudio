// Package router 组装所有业务路由。路径分版与 App 端 gostudio_backend_config 对应：
// /translation/* /crash/* /admin /healthz，均挂载在可选的 RoutePrefix 之下。
package router

import (
	"log/slog"

	"github.com/gin-gonic/gin"
	"gorm.io/gorm"

	"github.com/jmwl/gostudio/gostudio-api/internal/config"
	"github.com/jmwl/gostudio/gostudio-api/internal/handler"
	"github.com/jmwl/gostudio/gostudio-api/internal/middleware"
	"github.com/jmwl/gostudio/gostudio-api/internal/repository"
	"github.com/jmwl/gostudio/gostudio-api/internal/service"
	"github.com/jmwl/gostudio/gostudio-api/internal/translation"
	"github.com/jmwl/gostudio/gostudio-api/web"
)

// App 聚合路由装配所需的依赖，便于测试时构造。
type App struct {
	Config        config.Config
	DB            *gorm.DB
	Logger        *slog.Logger
	CrashService  *service.CrashService
	AdminService  *service.AdminService
	Translations  *handler.TranslationHandler
}

// NewApp 按配置构造全部服务依赖。
func NewApp(cfg config.Config, db *gorm.DB, logger *slog.Logger) (*App, error) {
	app := &App{Config: cfg, DB: db, Logger: logger}
	app.CrashService = service.NewCrashService(repository.NewCrashRepo(db))
	app.AdminService = service.NewAdminService(cfg.Admin.Username, cfg.Admin.Password, cfg.Admin.JWTSecret, cfg.Admin.SessionTTL)
	if cfg.Translation.Enabled {
		provider, err := translation.NewProvider(
			cfg.Translation.Provider,
			cfg.Translation.LLMBaseURL,
			cfg.Translation.LLMModel,
			cfg.Translation.LLMAPIKey,
			cfg.Translation.LLMAPIKeyHeader,
			cfg.Translation.GoogleAPIKey,
			cfg.Translation.DeepLAPIKey,
			cfg.Translation.DeepLEndpoint,
		)
		if err != nil {
			return nil, err
		}
		app.Translations = handler.NewTranslationHandler(cfg.Translation, provider, logger)
	}
	return app, nil
}

// Engine 构建 gin 引擎并注册全部路由。
func (a *App) Engine() *gin.Engine {
	gin.SetMode(gin.ReleaseMode)
	engine := gin.New()
	engine.Use(gin.Recovery())
	// 不信任任何代理头，gin 的 ClientIP 一律走 RemoteIP；记录用 IP 由
	// middleware.ClientIP 显式读 X-Forwarded-For/X-Real-IP（反代场景）。
	_ = engine.SetTrustedProxies(nil)

	root := engine.Group(a.Config.RoutePrefix)

	root.GET("/healthz", func(c *gin.Context) {
		c.JSON(200, gin.H{"status": "ok"})
	})

	// ===== 翻译（App 编辑器 gopls 文档翻译，契约与旧服务一致） =====
	if a.Translations != nil {
		t := root.Group("/translation")
		t.POST("/v1/translate", middleware.APIKeyAuth("X-GoStudio-Translation-Key", a.Config.Translation.APIKeys), a.Translations.Translate)
		t.POST("/v1/translations", middleware.APIKeyAuth("X-GoStudio-Translation-Key", a.Config.Translation.APIKeys), a.Translations.Translate)
	}

	// ===== 崩溃上报（App 错误反馈） =====
	// 限流在 handler 内按 device_id 做持久化统计（见 crash_handler Submit），
	// 这里只做体积与鉴权拦截。
	crash := handler.NewCrashHandler(a.CrashService)
	cg := root.Group("/crash")
	cg.POST("/v1/reports",
		middleware.APIKeyAuth("X-GoStudio-Backend-Key", a.Config.Crash.APIKeys),
		middleware.MaxBody(int64(a.Config.Crash.MaxReportBytes)),
		crash.Submit,
	)
	cg.GET("/v1/reports",
		middleware.APIKeyAuth("X-GoStudio-Backend-Key", a.Config.Crash.APIKeys),
		crash.MyReports,
	)

	// ===== 管理后台 =====
	admin := handler.NewAdminHandler(a.AdminService, a.CrashService)
	ag := root.Group("/admin")
	api := ag.Group("/api")
	api.POST("/login", admin.Login)
	protected := api.Group("", middleware.AdminAuth(a.AdminService))
	protected.GET("/stats", admin.Stats)
	protected.GET("/error-types", admin.ListTypes)
	protected.GET("/error-types/:id", admin.TypeDetail)
	protected.PATCH("/error-types/:id/status", admin.UpdateStatus)
	handler.RegisterStatic(ag, web.AdminFS)

	return engine
}
