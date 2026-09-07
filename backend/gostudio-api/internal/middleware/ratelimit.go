package middleware

import (
	"net/http"
	"sync"
	"time"

	"github.com/gin-gonic/gin"
)

type fixedWindowLimiter struct {
	mu      sync.Mutex
	hits    map[string][]time.Time
	limit   int
	window  time.Duration
	lastSweep time.Time
}

func (l *fixedWindowLimiter) allow(key string, now time.Time) bool {
	l.mu.Lock()
	defer l.mu.Unlock()
	// 顺手清理过期 key，防止 map 无限增长。
	if now.Sub(l.lastSweep) > l.window {
		for k, hits := range l.hits {
			if len(hits) == 0 || now.Sub(hits[len(hits)-1]) > l.window {
				delete(l.hits, k)
			}
		}
		l.lastSweep = now
	}
	hits := l.hits[key]
	kept := hits[:0]
	for _, t := range hits {
		if now.Sub(t) < l.window {
			kept = append(kept, t)
		}
	}
	if len(kept) >= l.limit {
		l.hits[key] = kept
		return false
	}
	l.hits[key] = append(kept, now)
	return true
}

// RateLimit 按 key（取自 keyFunc，通常是 device_id 或客户端 IP）限制时间窗内的请求次数。
func RateLimit(limit int, window time.Duration, keyFunc func(*gin.Context) string) gin.HandlerFunc {
	limiter := &fixedWindowLimiter{
		hits:    make(map[string][]time.Time),
		limit:   limit,
		window:  window,
	}
	return func(c *gin.Context) {
		key := keyFunc(c)
		if key != "" && !limiter.allow(key, time.Now()) {
			c.AbortWithStatusJSON(http.StatusTooManyRequests, gin.H{
				"error": "rate limit exceeded, retry later",
			})
			return
		}
		c.Next()
	}
}

// MaxBody 限制请求体大小，超限直接 413。
func MaxBody(maxBytes int64) gin.HandlerFunc {
	return func(c *gin.Context) {
		if c.Request.ContentLength > maxBytes {
			c.AbortWithStatusJSON(http.StatusRequestEntityTooLarge, gin.H{"error": "request body too large"})
			return
		}
		c.Request.Body = http.MaxBytesReader(c.Writer, c.Request.Body, maxBytes)
		c.Next()
	}
}

// ClientIP 取客户端 IP：优先 X-Forwarded-For 首个地址（反代场景），否则 RemoteAddr。
func ClientIP(c *gin.Context) string {
	if forwarded := c.GetHeader("X-Forwarded-For"); forwarded != "" {
		first := forwarded
		if idx := indexByte(forwarded, ','); idx >= 0 {
			first = forwarded[:idx]
		}
		return trimSpaces(first)
	}
	if realIP := c.GetHeader("X-Real-IP"); realIP != "" {
		return trimSpaces(realIP)
	}
	return c.RemoteIP()
}

func indexByte(s string, b byte) int {
	for i := 0; i < len(s); i++ {
		if s[i] == b {
			return i
		}
	}
	return -1
}

func trimSpaces(s string) string {
	start := 0
	for start < len(s) && (s[start] == ' ' || s[start] == '\t') {
		start++
	}
	end := len(s)
	for end > start && (s[end-1] == ' ' || s[end-1] == '\t') {
		end--
	}
	return s[start:end]
}
