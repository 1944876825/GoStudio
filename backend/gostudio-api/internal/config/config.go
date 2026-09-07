// Package config 汇总 gostudio-api 各模块配置，全部来自环境变量。
// 变量清单与示例见 configs/config.example.env。
package config

import (
	"fmt"
	"os"
	"strings"
	"time"
)

// Provider 常量与旧 translation-api 保持一致，便于平滑迁移。
const (
	ProviderMock   = "mock"
	ProviderLLM    = "openai"
	ProviderGoogle = "google"
	ProviderDeepL  = "deepl"
)

type Config struct {
	// Addr 监听地址。
	Addr string
	// RoutePrefix 反向代理不剥前缀时的挂载前缀（如 "/api"），默认为空。
	RoutePrefix string
	// DataDir sqlite 数据库与运行数据目录。
	DataDir string

	Admin       AdminConfig
	Crash       CrashConfig
	Translation TranslationConfig
}

type AdminConfig struct {
	Username string
	Password string
	// JWTSecret 管理后台会话签名密钥。
	JWTSecret string
	// SessionTTL 会话有效期。
	SessionTTL time.Duration
}

type CrashConfig struct {
	// APIKeys 允许的客户端 key；为空表示不校验（仅建议内网调试时）。
	APIKeys []string
	// MaxReportBytes 单次上报请求体上限。
	MaxReportBytes int
	// DeviceHourlyLimit 单设备每小时上报次数上限。
	DeviceHourlyLimit int
}

type TranslationConfig struct {
	// Enabled 是否挂载翻译接口（未配置密钥时可关闭）。
	Enabled bool

	Provider          string
	APIKeys           []string
	LLMBaseURL        string
	LLMModel          string
	LLMAPIKey         string
	LLMAPIKeyHeader   string
	GoogleAPIKey      string
	DeepLAPIKey       string
	DeepLEndpoint     string
	MaxTextBytes      int
	MaxCacheEntries   int
	MaxConcurrentJobs int
}

// Load 从环境变量加载配置并做基础校验，问题直接报错退出。
func Load() (Config, error) {
	cfg := Config{
		Addr:        getenv("GOSTUDIO_API_ADDR", ":8080"),
		RoutePrefix: normalizePrefix(getenv("GOSTUDIO_API_ROUTE_PREFIX", "")),
		DataDir:     getenv("GOSTUDIO_API_DATA_DIR", "./data"),
		Admin: AdminConfig{
			Username:    getenv("GOSTUDIO_API_ADMIN_USERNAME", "admin"),
			Password:    os.Getenv("GOSTUDIO_API_ADMIN_PASSWORD"),
			JWTSecret:   os.Getenv("GOSTUDIO_API_ADMIN_JWT_SECRET"),
			SessionTTL:  12 * time.Hour,
		},
		Crash: CrashConfig{
			APIKeys:           splitKeys(os.Getenv("GOSTUDIO_API_KEYS")),
			MaxReportBytes:    256 * 1024,
			DeviceHourlyLimit: 10,
		},
		Translation: TranslationConfig{
			Provider:          strings.ToLower(strings.TrimSpace(getenv("TRANSLATION_PROVIDER", ProviderLLM))),
			LLMBaseURL:        strings.TrimRight(strings.TrimSpace(getenv("LLM_BASE_URL", "https://api.openai.com/v1")), "/"),
			LLMModel:          getenv("LLM_MODEL", "gpt-4o-mini"),
			LLMAPIKeyHeader:   getenv("LLM_API_KEY_HEADER", "Authorization"),
			GoogleAPIKey:      os.Getenv("GOOGLE_TRANSLATE_API_KEY"),
			DeepLAPIKey:       os.Getenv("DEEPL_API_KEY"),
			MaxTextBytes:      64 * 1024,
			MaxCacheEntries:   20_000,
			MaxConcurrentJobs: 8,
		},
	}
	cfg.Translation.LLMAPIKey = os.Getenv("LLM_API_KEY")
	if cfg.Translation.Provider == ProviderDeepL {
		cfg.Translation.DeepLEndpoint = getenv("DEEPL_API_ENDPOINT", "https://api-free.deepl.com/v2/translate")
	}

	// 翻译接口密钥优先读新的 GOSTUDIO_API_KEYS，兼容旧服务的 TRANSLATION_BACKEND_API_KEYS。
	translationKeys := splitKeys(os.Getenv("TRANSLATION_BACKEND_API_KEYS"))
	cfg.Translation.APIKeys = mergeKeys(cfg.Crash.APIKeys, translationKeys)

	if cfg.Admin.Password == "" {
		return cfg, fmt.Errorf("GOSTUDIO_API_ADMIN_PASSWORD is required")
	}
	if len(cfg.Admin.JWTSecret) < 16 {
		return cfg, fmt.Errorf("GOSTUDIO_API_ADMIN_JWT_SECRET must be at least 16 characters")
	}
	if cfg.Crash.MaxReportBytes < 4096 || cfg.Crash.DeviceHourlyLimit < 1 {
		return cfg, fmt.Errorf("invalid crash module capacity configuration")
	}
	if err := validateTranslation(cfg.Translation); err != nil {
		return cfg, err
	}
	cfg.Translation.Enabled = true
	return cfg, nil
}

func validateTranslation(t TranslationConfig) error {
	switch t.Provider {
	case ProviderMock:
		return nil
	case ProviderLLM:
		if t.LLMAPIKey == "" {
			return fmt.Errorf("TRANSLATION_PROVIDER=openai requires LLM_API_KEY")
		}
		if !strings.Contains(t.LLMBaseURL, "://") {
			return fmt.Errorf("LLM_BASE_URL must be an absolute URL")
		}
	case ProviderGoogle:
		if t.GoogleAPIKey == "" {
			return fmt.Errorf("TRANSLATION_PROVIDER=google requires GOOGLE_TRANSLATE_API_KEY")
		}
	case ProviderDeepL:
		if t.DeepLAPIKey == "" {
			return fmt.Errorf("TRANSLATION_PROVIDER=deepl requires DEEPL_API_KEY")
		}
	default:
		return fmt.Errorf("unsupported TRANSLATION_PROVIDER %q (mock, openai, google, deepl)", t.Provider)
	}
	if t.MaxTextBytes < 256 || t.MaxCacheEntries < 16 || t.MaxConcurrentJobs < 1 {
		return fmt.Errorf("invalid translation capacity configuration")
	}
	return nil
}

func getenv(name, fallback string) string {
	if value := strings.TrimSpace(os.Getenv(name)); value != "" {
		return value
	}
	return fallback
}

func splitKeys(raw string) []string {
	var keys []string
	for _, value := range strings.Split(raw, ",") {
		if key := strings.TrimSpace(value); key != "" {
			keys = append(keys, key)
		}
	}
	return keys
}

func mergeKeys(groups ...[]string) []string {
	seen := make(map[string]bool)
	var merged []string
	for _, group := range groups {
		for _, key := range group {
			if !seen[key] {
				seen[key] = true
				merged = append(merged, key)
			}
		}
	}
	return merged
}

func normalizePrefix(prefix string) string {
	prefix = strings.TrimSpace(prefix)
	if prefix == "" || prefix == "/" {
		return ""
	}
	if !strings.HasPrefix(prefix, "/") {
		prefix = "/" + prefix
	}
	return strings.TrimRight(prefix, "/")
}
