// gostudio-api：GoStudio App 的统一后端（崩溃上报 + 管理后台 + gopls 翻译）。
package main

import (
	"context"
	"errors"
	"log/slog"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"

	"github.com/jmwl/gostudio/gostudio-api/internal/config"
	"github.com/jmwl/gostudio/gostudio-api/internal/database"
	"github.com/jmwl/gostudio/gostudio-api/internal/router"
)

func main() {
	logger := slog.New(slog.NewJSONHandler(os.Stdout, nil))

	cfg, err := config.Load()
	if err != nil {
		logger.Error("invalid gostudio-api configuration", "error", err)
		os.Exit(1)
	}

	db, err := database.Open(cfg.DataDir, logger)
	if err != nil {
		logger.Error("init database failed", "error", err)
		os.Exit(1)
	}

	app, err := router.NewApp(cfg, db, logger)
	if err != nil {
		logger.Error("init services failed", "error", err)
		os.Exit(1)
	}
	server := &http.Server{
		Addr:              cfg.Addr,
		Handler:           app.Engine(),
		ReadHeaderTimeout: 5 * time.Second,
		ReadTimeout:       30 * time.Second,
		WriteTimeout:      60 * time.Second,
		IdleTimeout:       120 * time.Second,
	}

	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()

	go func() {
		logger.Info("gostudio-api listening",
			"addr", cfg.Addr,
			"route_prefix", cfg.RoutePrefix,
			"data_dir", cfg.DataDir,
			"translation", cfg.Translation.Provider,
		)
		if err := server.ListenAndServe(); err != nil && !errors.Is(err, http.ErrServerClosed) {
			logger.Error("http server failed", "error", err)
			stop()
		}
	}()

	<-ctx.Done()
	shutdownContext, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	if err := server.Shutdown(shutdownContext); err != nil {
		logger.Error("graceful shutdown failed", "error", err)
	}
}
