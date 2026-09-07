package translation

import (
	"crypto/sha256"
	"encoding/hex"
	"sync"
)

// Cache 翻译结果的进程内 LRU（近似实现：满了随机淘汰到上限以下），移植自旧服务。
type Cache struct {
	mu      sync.RWMutex
	entries map[string]string
	max     int
}

func NewCache(max int) *Cache {
	return &Cache{entries: make(map[string]string), max: max}
}

func (c *Cache) Get(key string) (string, bool) {
	c.mu.RLock()
	value, ok := c.entries[key]
	c.mu.RUnlock()
	return value, ok
}

func (c *Cache) Set(key, value string) {
	c.mu.Lock()
	defer c.mu.Unlock()
	if len(c.entries) >= c.max {
		for oldKey := range c.entries {
			delete(c.entries, oldKey)
			if len(c.entries) < c.max {
				break
			}
		}
	}
	c.entries[key] = value
}

func CacheKey(provider, model, sourceLanguage, targetLanguage, kind, text string) string {
	sum := sha256.Sum256([]byte(provider + "\x00" + model + "\x00" + sourceLanguage + "\x00" + targetLanguage + "\x00" + kind + "\x00" + text))
	return hex.EncodeToString(sum[:])
}
