package main

import (
	"context"
	"fmt"
	"log/slog"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"

	"github.com/example/orders-service/internal/application"
	"github.com/example/orders-service/internal/infrastructure/config"
	"github.com/example/orders-service/internal/infrastructure/postgres"
	httpInterface "github.com/example/orders-service/internal/interfaces/http"
)

func main() {
	log := slog.New(slog.NewJSONHandler(os.Stdout, &slog.HandlerOptions{
		Level: slog.LevelInfo,
	}))

	cfg, err := config.Load()
	if err != nil {
		log.Error("invalid configuration", "error", err)
		os.Exit(1)
	}

	log.Info("starting orders service",
		"port", cfg.Server.Port,
		"database", cfg.Database.Name,
	)

	db, err := postgres.NewConnection(cfg.Database, log)
	if err != nil {
		log.Error("failed to connect to database", "error", err)
		os.Exit(1)
	}
	defer db.Close()

	migrationsPath := os.Getenv("MIGRATIONS_PATH")
	if migrationsPath == "" {
		migrationsPath = "./db/migrations"
	}
	if migrationsPath != "skip" {
		if err := postgres.RunMigrations(cfg.Database.URL(), migrationsPath, log); err != nil {
			log.Error("failed to run migrations", "error", err)
			os.Exit(1)
		}
	}

	orderRepo := postgres.NewOrderRepository(db)

	createOrder := application.NewCreateOrderUseCase(orderRepo, log)
	getOrder := application.NewGetOrderUseCase(orderRepo, log)
	listOrders := application.NewListOrdersUseCase(orderRepo, log)
	updateOrderStatus := application.NewUpdateOrderStatusUseCase(orderRepo, log)

	router := httpInterface.NewRouter(
		createOrder,
		getOrder,
		listOrders,
		updateOrderStatus,
		log,
		db,
	)

	srv := &http.Server{
		Addr:         fmt.Sprintf(":%d", cfg.Server.Port),
		Handler:      router,
		ReadTimeout:  time.Duration(cfg.Server.ReadTimeout) * time.Second,
		WriteTimeout: time.Duration(cfg.Server.WriteTimeout) * time.Second,
	}

	errCh := make(chan error, 1)
	go func() {
		log.Info("server listening", "addr", srv.Addr)
		errCh <- srv.ListenAndServe()
	}()

	sigCh := make(chan os.Signal, 1)
	signal.Notify(sigCh, syscall.SIGINT, syscall.SIGTERM)

	select {
	case sig := <-sigCh:
		log.Info("received shutdown signal", "signal", sig)
	case err := <-errCh:
		if err != nil && err != http.ErrServerClosed {
			log.Error("server error", "error", err)
		}
	}

	log.Info("shutting down server...")
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()

	if err := srv.Shutdown(ctx); err != nil {
		log.Error("server forced to shutdown", "error", err)
		os.Exit(1)
	}

	log.Info("server stopped gracefully")
}
