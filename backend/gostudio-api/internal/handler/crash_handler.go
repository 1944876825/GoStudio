// Package handler 实现各业务分组的 HTTP 接口。
package handler

import (
	"net/http"
	"strings"
	"time"

	"github.com/gin-gonic/gin"

	"github.com/jmwl/gostudio/gostudio-api/internal/middleware"
	"github.com/jmwl/gostudio/gostudio-api/internal/service"
)

const (
	maxDeviceIDLen   = 64
	maxStringLen     = 1024
	defaultPageSize  = 20
	maxPageSize      = 100
	hourlyReportCap  = 10 // 保险丝：单设备每小时硬上限（正常流程已被限流拦住）
)

type CrashHandler struct {
	crash *service.CrashService
}

func NewCrashHandler(crash *service.CrashService) *CrashHandler {
	return &CrashHandler{crash: crash}
}

type submitReportRequest struct {
	DeviceID           string `json:"device_id" binding:"required"`
	DeviceBrand        string `json:"device_brand"`
	DeviceManufacturer string `json:"device_manufacturer"`
	DeviceModel        string `json:"device_model"`
	AndroidVersion     string `json:"android_version"`
	AppVersion         string `json:"app_version"`
	Contact            string `json:"contact"`
	Comment            string `json:"comment"`
	CrashLog           string `json:"crash_log" binding:"required"`
	CrashStack         string `json:"crash_stack" binding:"required"`
}

// Submit 处理 App 的崩溃上报：同指纹错误类型不重复创建。
func (h *CrashHandler) Submit(c *gin.Context) {
	var request submitReportRequest
	if err := c.ShouldBindJSON(&request); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "invalid JSON body"})
		return
	}
	request.DeviceID = truncate(strings.TrimSpace(request.DeviceID), maxDeviceIDLen)
	request.Contact = truncate(strings.TrimSpace(request.Contact), 128)
	request.Comment = truncate(strings.TrimSpace(request.Comment), maxStringLen)
	request.CrashLog = truncate(strings.TrimSpace(request.CrashLog), maxStringLen)
	request.CrashStack = truncate(strings.TrimSpace(request.CrashStack), 128*1024)

	// 双保险限流：中间件按小时窗拦截后，这里再挡一次极端突发。
	if used, err := h.recentReportCount(request.DeviceID); err == nil && used >= hourlyReportCap {
		c.JSON(http.StatusTooManyRequests, gin.H{"error": "too many reports from this device, retry later"})
		return
	}

	result, err := h.crash.Submit(service.SubmitInput{
		DeviceID:           request.DeviceID,
		DeviceBrand:        truncate(strings.TrimSpace(request.DeviceBrand), 64),
		DeviceManufacturer: truncate(strings.TrimSpace(request.DeviceManufacturer), 64),
		DeviceModel:        truncate(strings.TrimSpace(request.DeviceModel), 128),
		AndroidVersion:     truncate(strings.TrimSpace(request.AndroidVersion), 32),
		AppVersion:         truncate(strings.TrimSpace(request.AppVersion), 32),
		IP:                 truncate(middleware.ClientIP(c), 64),
		Contact:            request.Contact,
		Comment:            request.Comment,
		CrashLog:           request.CrashLog,
		CrashStack:         request.CrashStack,
	})
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "failed to store crash report"})
		return
	}
	c.JSON(http.StatusOK, gin.H{
		"report_id":         result.Report.ID,
		"error_type_id":     result.Type.ID,
		"fingerprint":       result.Type.Fingerprint,
		"occurrence_count":  result.Type.OccurrenceCount,
		"first_report":      result.FirstReport,
	})
}

func (h *CrashHandler) recentReportCount(deviceID string) (int64, error) {
	reports, _, err := h.crash.DeviceReports(deviceID, 1, hourlyReportCap)
	if err != nil {
		return 0, err
	}
	var count int64
	cutoff := time.Now().UTC().Add(-time.Hour)
	for _, report := range reports {
		if !report.CreatedAt.Before(cutoff) {
			count++
		}
	}
	return count, nil
}

// MyReports 返回指定设备的上报历史（App「我的反馈」）。
func (h *CrashHandler) MyReports(c *gin.Context) {
	deviceID := truncate(strings.TrimSpace(c.Query("device_id")), maxDeviceIDLen)
	if deviceID == "" {
		c.JSON(http.StatusBadRequest, gin.H{"error": "device_id is required"})
		return
	}
	page, pageSize := pageParams(c)
	reports, total, err := h.crash.DeviceReports(deviceID, page, pageSize)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "failed to load reports"})
		return
	}
	items := make([]gin.H, 0, len(reports))
	for _, report := range reports {
		items = append(items, gin.H{
			"id":            report.ID,
			"error_type_id": report.ErrorTypeID,
			"title":         service.BuildTitle(report.CrashLog),
			"status":        report.TypeStatus,
			"app_version":   report.AppVersion,
			"comment":       report.Comment,
			"created_at":    report.CreatedAt,
		})
	}
	c.JSON(http.StatusOK, gin.H{
		"total":     total,
		"page":      page,
		"page_size": pageSize,
		"reports":   items,
	})
}

func pageParams(c *gin.Context) (page, pageSize int) {
	page = atoiDefault(c.Query("page"), 1)
	if page < 1 {
		page = 1
	}
	pageSize = atoiDefault(c.Query("page_size"), defaultPageSize)
	if pageSize < 1 {
		pageSize = defaultPageSize
	}
	if pageSize > maxPageSize {
		pageSize = maxPageSize
	}
	return page, pageSize
}

func atoiDefault(raw string, fallback int) int {
	value := 0
	for _, ch := range raw {
		if ch < '0' || ch > '9' {
			return fallback
		}
		value = value*10 + int(ch-'0')
		if value > 1_000_000 {
			return fallback
		}
	}
	if raw == "" {
		return fallback
	}
	return value
}

func truncate(text string, limit int) string {
	if len(text) <= limit {
		return text
	}
	return text[:limit]
}
