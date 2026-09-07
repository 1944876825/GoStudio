package handler

import (
	"context"
	"encoding/json"
	"io"
	"log/slog"
	"net/http"
	"strings"
	"time"

	"github.com/gin-gonic/gin"

	"github.com/jmwl/gostudio/gostudio-api/internal/config"
	"github.com/jmwl/gostudio/gostudio-api/internal/translation"
)

// TranslationHandler 从旧 translation-api 移植：gopls 文档翻译，
// 语义（缓存、并发闸、超时、错误码）与原服务一致。
type TranslationHandler struct {
	cfg       config.TranslationConfig
	provider  translation.Provider
	cache     *translation.Cache
	logger    *slog.Logger
	semaphore chan struct{}
}

func NewTranslationHandler(cfg config.TranslationConfig, provider translation.Provider, logger *slog.Logger) *TranslationHandler {
	return &TranslationHandler{
		cfg:       cfg,
		provider:  provider,
		cache:     translation.NewCache(cfg.MaxCacheEntries),
		logger:    logger,
		semaphore: make(chan struct{}, cfg.MaxConcurrentJobs),
	}
}

func (h *TranslationHandler) Translate(c *gin.Context) {
	var request translation.Request
	// 与旧服务一致：限制读取体积，防止超大正文拖垮内存。
	body := io.LimitReader(c.Request.Body, int64(h.cfg.MaxTextBytes+16*1024))
	if err := decodeJSONBody(body, &request); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "invalid JSON body"})
		return
	}
	request.Text = strings.TrimSpace(request.Text)
	request.SourceLanguage = strings.ToLower(strings.TrimSpace(request.SourceLanguage))
	request.TargetLanguage = strings.ToLower(strings.TrimSpace(request.TargetLanguage))
	request.Kind = strings.TrimSpace(request.Kind)

	if request.Text == "" {
		c.JSON(http.StatusBadRequest, gin.H{"error": "text is required"})
		return
	}
	if len(request.Text) > h.cfg.MaxTextBytes {
		c.JSON(http.StatusRequestEntityTooLarge, gin.H{"error": "text is too large"})
		return
	}
	if request.TargetLanguage == "" {
		request.TargetLanguage = "zh-cn"
	}
	if request.Kind == "" {
		request.Kind = "documentation"
	}
	if len(request.Kind) > 64 || !validLanguage(request.SourceLanguage) || !validLanguage(request.TargetLanguage) {
		c.JSON(http.StatusBadRequest, gin.H{"error": "invalid source_language, target_language, or kind"})
		return
	}

	key := translation.CacheKey(h.provider.Name(), h.provider.Model(), request.SourceLanguage, request.TargetLanguage, request.Kind, request.Text)
	if translated, ok := h.cache.Get(key); ok {
		c.JSON(http.StatusOK, translation.Response{
			TranslatedText: translated,
			Provider:       h.provider.Name(),
			Model:          h.provider.Model(),
			Cached:         true,
		})
		return
	}

	select {
	case h.semaphore <- struct{}{}:
		defer func() { <-h.semaphore }()
	default:
		c.JSON(http.StatusTooManyRequests, gin.H{"error": "backend is busy"})
		return
	}

	ctx, cancel := contextWithClientTimeout(c.Request.Context(), 50*time.Second)
	defer cancel()
	translated, err := h.provider.Translate(ctx, request)
	if err != nil {
		if ctx.Err() != nil {
			c.JSON(http.StatusGatewayTimeout, gin.H{"error": "translation provider timed out"})
			return
		}
		h.logger.Warn("translation failed", "provider", h.provider.Name(), "kind", request.Kind, "error", errString(err))
		c.JSON(http.StatusBadGateway, gin.H{"error": "translation provider failed"})
		return
	}
	h.cache.Set(key, translated)
	c.JSON(http.StatusOK, translation.Response{
		TranslatedText: translated,
		Provider:       h.provider.Name(),
		Model:          h.provider.Model(),
		Cached:         false,
	})
}

func validLanguage(language string) bool {
	if language == "" || language == "auto" {
		return true
	}
	if len(language) < 2 || len(language) > 20 {
		return false
	}
	for _, char := range language {
		if !(char == '-' || char == '_' || (char >= 'a' && char <= 'z') || (char >= '0' && char <= '9')) {
			return false
		}
	}
	return true
}

func contextWithClientTimeout(parent context.Context, timeout time.Duration) (context.Context, context.CancelFunc) {
	if deadline, ok := parent.Deadline(); ok && time.Until(deadline) < timeout {
		return context.WithDeadline(parent, deadline)
	}
	return context.WithTimeout(parent, timeout)
}

func errString(err error) string {
	if err == nil {
		return ""
	}
	return err.Error()
}

func decodeJSONBody(reader io.Reader, output any) error {
	return json.NewDecoder(reader).Decode(output)
}
