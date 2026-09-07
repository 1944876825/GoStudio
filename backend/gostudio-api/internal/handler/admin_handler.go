package handler

import (
	"embed"
	"io/fs"
	"net/http"
	"strings"

	"github.com/gin-gonic/gin"

	"github.com/jmwl/gostudio/gostudio-api/internal/model"
	"github.com/jmwl/gostudio/gostudio-api/internal/service"
)

// AdminHandler 管理后台：登录、错误类型列表/详情、状态流转、概览统计。
type AdminHandler struct {
	admins *service.AdminService
	crash  *service.CrashService
}

func NewAdminHandler(admins *service.AdminService, crash *service.CrashService) *AdminHandler {
	return &AdminHandler{admins: admins, crash: crash}
}

type loginRequest struct {
	Username string `json:"username" binding:"required"`
	Password string `json:"password" binding:"required"`
}

// Login 账号密码登录，返回 Bearer token。
func (h *AdminHandler) Login(c *gin.Context) {
	var request loginRequest
	if err := c.ShouldBindJSON(&request); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "username and password are required"})
		return
	}
	token, expiresAt, err := h.admins.Login(strings.TrimSpace(request.Username), request.Password)
	if err != nil {
		c.JSON(http.StatusUnauthorized, gin.H{"error": "用户名或密码错误"})
		return
	}
	c.JSON(http.StatusOK, gin.H{"token": token, "expires_at": expiresAt})
}

// Stats 概览统计。
func (h *AdminHandler) Stats(c *gin.Context) {
	stats, err := h.crash.Stats()
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "failed to load stats"})
		return
	}
	c.JSON(http.StatusOK, stats)
}

// ListTypes 错误类型列表（分页/搜索/状态过滤）。
func (h *AdminHandler) ListTypes(c *gin.Context) {
	status := c.Query("status")
	if status != "" && status != model.StatusOpen && status != model.StatusResolved {
		c.JSON(http.StatusBadRequest, gin.H{"error": "invalid status filter"})
		return
	}
	page, pageSize := pageParams(c)
	items, total, err := h.crash.ListTypes(status, c.Query("q"), page, pageSize)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "failed to load error types"})
		return
	}
	c.JSON(http.StatusOK, gin.H{
		"total":     total,
		"page":      page,
		"page_size": pageSize,
		"items":     items,
	})
}

// TypeDetail 单个错误类型 + 上报明细（设备、IP、联系方式）。
func (h *AdminHandler) TypeDetail(c *gin.Context) {
	id := atoiDefault(c.Param("id"), 0)
	if id <= 0 {
		c.JSON(http.StatusBadRequest, gin.H{"error": "invalid id"})
		return
	}
	errorType, err := h.crash.TypeDetail(uint(id))
	if err != nil {
		c.JSON(http.StatusNotFound, gin.H{"error": "error type not found"})
		return
	}
	page, pageSize := pageParams(c)
	reports, total, err := h.crash.TypeReports(errorType.ID, page, pageSize)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "failed to load reports"})
		return
	}
	c.JSON(http.StatusOK, gin.H{
		"error_type": errorType,
		"total":      total,
		"page":       page,
		"page_size":  pageSize,
		"reports":    reports,
	})
}

type updateStatusRequest struct {
	Status string `json:"status" binding:"required"`
}

// UpdateStatus 标记错误类型已修复/重新打开。
func (h *AdminHandler) UpdateStatus(c *gin.Context) {
	id := atoiDefault(c.Param("id"), 0)
	if id <= 0 {
		c.JSON(http.StatusBadRequest, gin.H{"error": "invalid id"})
		return
	}
	var request updateStatusRequest
	if err := c.ShouldBindJSON(&request); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "status is required"})
		return
	}
	if request.Status != model.StatusOpen && request.Status != model.StatusResolved {
		c.JSON(http.StatusBadRequest, gin.H{"error": "status must be open or resolved"})
		return
	}
	if err := h.crash.UpdateTypeStatus(uint(id), request.Status); err != nil {
		c.JSON(http.StatusNotFound, gin.H{"error": "error type not found"})
		return
	}
	c.JSON(http.StatusOK, gin.H{"ok": true, "status": request.Status})
}

// RegisterStatic 挂载内嵌的管理后台静态页面（web/web.go 提供的 AdminFS）。
func RegisterStatic(group *gin.RouterGroup, content embed.FS) {
	sub, err := fs.Sub(content, "admin")
	if err != nil {
		return
	}
	fileServer := http.StripPrefix(group.BasePath(), http.FileServer(http.FS(sub)))
	group.GET("", func(c *gin.Context) { c.Redirect(http.StatusFound, group.BasePath()+"/") })
	group.GET("/", gin.WrapF(fileServer.ServeHTTP))
	group.GET("/index.html", gin.WrapF(fileServer.ServeHTTP))
	group.GET("/app.js", gin.WrapF(fileServer.ServeHTTP))
	group.GET("/style.css", gin.WrapF(fileServer.ServeHTTP))
}
