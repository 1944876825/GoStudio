// Package middleware 提供 gin 中间件：客户端 key 鉴权、后台 JWT 鉴权、限流、体积限制。
package middleware

import (
	"crypto/subtle"
	"net/http"
	"strings"

	"github.com/gin-gonic/gin"
)

// APIKeyAuth 校验指定请求头里的客户端 key（与配置的 key 列表比对）。
// keys 为空表示未启用鉴权，直接放行（仅建议内网调试）。
func APIKeyAuth(header string, keys []string) gin.HandlerFunc {
	return func(c *gin.Context) {
		if len(keys) == 0 {
			c.Next()
			return
		}
		present := strings.TrimSpace(c.GetHeader(header))
		for _, expected := range keys {
			if present != "" && subtle.ConstantTimeCompare([]byte(present), []byte(expected)) == 1 {
				c.Next()
				return
			}
		}
		c.AbortWithStatusJSON(http.StatusUnauthorized, gin.H{"error": "invalid or missing API key"})
	}
}
