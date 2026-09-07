// Package database 负责 sqlite 连接的建立与表结构迁移。
// 使用 glebarez/sqlite（modernc 纯 Go 实现），构建产物不依赖 CGO。
package database

import (
	"fmt"
	"log/slog"
	"os"
	"path/filepath"

	"github.com/glebarez/sqlite"
	"gorm.io/gorm"
	gormlogger "gorm.io/gorm/logger"

	"github.com/jmwl/gostudio/gostudio-api/internal/model"
)

// Open 打开（必要时创建）data_dir 下的 gostudio.db，并执行 AutoMigrate。
func Open(dataDir string, logger *slog.Logger) (*gorm.DB, error) {
	if err := os.MkdirAll(dataDir, 0o755); err != nil {
		return nil, fmt.Errorf("create data dir: %w", err)
	}
	db, err := gorm.Open(sqlite.Open(filepath.Join(dataDir, "gostudio.db")), &gorm.Config{
		Logger: gormlogger.Discard,
	})
	if err != nil {
		return nil, fmt.Errorf("open sqlite: %w", err)
	}
	sqlDB, err := db.DB()
	if err != nil {
		return nil, err
	}
	// sqlite 单写者：串行化连接池，避免 database is locked。
	sqlDB.SetMaxOpenConns(1)

	if err := db.AutoMigrate(&model.ErrorType{}, &model.ErrorReport{}); err != nil {
		return nil, fmt.Errorf("migrate schema: %w", err)
	}
	logger.Info("sqlite ready", "path", filepath.Join(dataDir, "gostudio.db"))
	return db, nil
}

// OpenMemory 打开内存库，测试用。
func OpenMemory() (*gorm.DB, error) {
	db, err := gorm.Open(sqlite.Open(":memory:"), &gorm.Config{
		Logger: gormlogger.Discard,
	})
	if err != nil {
		return nil, err
	}
	if err := db.AutoMigrate(&model.ErrorType{}, &model.ErrorReport{}); err != nil {
		return nil, err
	}
	return db, nil
}
